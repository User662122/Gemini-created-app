"""Tests for gecko_browser against an in-process fake of the automation API.

The fake implements the contract in docs/AUTOMATION.md (paths, token header,
status codes), so these tests check the Python side without needing a phone.

Run with:  python3 -m unittest -v
"""

import json
import re
import threading
import unittest
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from gecko_browser import Browser, BrowserError

TOKEN = "test-token-123"


class FakeBrowser:
    """Keeps tab state the way the app would, and fakes page operations."""

    def __init__(self):
        self.tabs = {}
        self.active = None
        self.counter = 0
        self.lock = threading.Lock()

    def create(self, url=""):
        self.counter += 1
        tab_id = f"tab-{self.counter}"
        self.tabs[tab_id] = {"id": tab_id, "url": url, "title": url or "New tab",
                             "loading": False, "progress": 100,
                             "canGoBack": False, "canGoForward": False}
        self.active = tab_id
        return self.tabs[tab_id]

    def info(self, tab_id):
        tab = self.tabs[tab_id]
        if tab["loading"]:
            # Pages "finish loading" after a couple of polls, like a fast real page.
            tab["polls"] = tab.get("polls", 0) + 1
            if tab["polls"] >= 2:
                tab["loading"] = False
                tab["polls"] = 0
        result = dict(tab)
        result["active"] = tab_id == self.active
        return result


class Handler(BaseHTTPRequestHandler):
    server_version = "FakeGecko/1"

    @property
    def fake(self):
        return self.server.fake

    def log_message(self, *args):  # keep test output quiet
        pass

    def _send(self, status, payload):
        body = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _body(self):
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length) if length else b""
        return json.loads(raw) if raw else {}

    def _check_auth(self):
        if self.headers.get("X-Automation-Token") != TOKEN:
            self._send(401, {"error": "Missing or invalid X-Automation-Token header"})
            return False
        if self.headers.get("Origin") is not None:
            self._send(403, {"error": "Requests from web pages are not allowed"})
            return False
        return True

    def _dispatch(self, method):
        if not self._check_auth():
            return
        path = urllib.parse.urlsplit(self.path).path
        with self.fake.lock:
            self._route(method, path)

    def do_GET(self):
        self._dispatch("GET")

    def do_POST(self):
        self._dispatch("POST")

    def do_DELETE(self):
        self._dispatch("DELETE")

    def _route(self, method, path):
        fake = self.fake
        if path == "/api/v1/status" and method == "GET":
            return self._send(200, {"ok": True, "apiVersion": 1, "tabs": len(fake.tabs),
                                    "activeTabId": fake.active, "extensionReady": True})
        if path == "/api/v1/tabs" and method == "GET":
            return self._send(200, [fake.info(t) for t in fake.tabs])
        if path == "/api/v1/tabs" and method == "POST":
            url = self._body().get("url", "")
            return self._send(201, fake.info(fake.create(url)["id"]))

        match = re.fullmatch(r"/api/v1/tabs/([^/]+)(/[a-z]+)?", path)
        if not match:
            return self._send(404, {"error": f"Unknown endpoint: {path}"})
        tab_id, action = match.group(1), match.group(2) or ""
        if tab_id not in fake.tabs:
            return self._send(404, {"error": f"No tab with id {tab_id}"})
        tab = fake.tabs[tab_id]

        if action == "" and method == "GET":
            return self._send(200, fake.info(tab_id))
        if action == "" and method == "DELETE":
            del fake.tabs[tab_id]
            return self._send(200, {"closed": True})
        if action == "/navigate" and method == "POST":
            url = self._body().get("url", "")
            tab.update(url=url, title=url, loading=True, polls=0)
            return self._send(200, {**tab, "active": tab_id == fake.active})
        if action == "/select" and method == "POST":
            fake.active = tab_id
            return self._send(200, fake.info(tab_id))
        if action in ("/reload", "/back", "/forward", "/stop") and method == "POST":
            return self._send(200, fake.info(tab_id))
        if action == "/eval" and method == "POST":
            code = self._body().get("code", "")
            if not tab["url"]:
                return self._send(409, {"error": "This tab has no page script attached."})
            if code == "1 + 2":
                return self._send(200, {"result": 3})
            if code == "document.title":
                return self._send(200, {"result": tab["title"]})
            return self._send(422, {"error": "ReferenceError: nope is not defined"})
        if action == "/dom" and method == "POST":
            body = self._body()
            op = body.get("op")
            if op not in ("text", "html", "attr", "value", "exists", "click", "type"):
                return self._send(400, {"error": '"op" must be one of: text, html, ...'})
            selector = body.get("selector")
            if op == "exists":
                return self._send(200, {"result": selector == "h1"})
            if selector != "h1":
                return self._send(422, {"error": f"No element matches selector: {selector}"})
            if op == "text":
                return self._send(200, {"result": "Heading"})
            if op == "click":
                return self._send(200, {"result": True})
            return self._send(200, {"result": None})
        return self._send(405, {"error": "Method not allowed"})


class ApiTests(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        cls.server.fake = FakeBrowser()
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()
        cls.base_url = f"http://127.0.0.1:{cls.server.server_address[1]}"

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()

    def setUp(self):
        self.server.fake.tabs.clear()
        self.server.fake.active = None
        self.browser = Browser(token=TOKEN, base_url=self.base_url, timeout=5)

    def test_token_is_required(self):
        with self.assertRaises(BrowserError) as ctx:
            Browser(token="wrong", base_url=self.base_url).status()
        self.assertEqual(ctx.exception.status, 401)

    def test_token_from_environment(self):
        import os
        from unittest import mock
        with mock.patch.dict(os.environ, {"GECKO_BROWSER_TOKEN": TOKEN}):
            browser = Browser(base_url=self.base_url)
            self.assertEqual(browser.status()["apiVersion"], 1)

    def test_missing_token_is_an_error_before_any_request(self):
        import os
        from unittest import mock
        with mock.patch.dict(os.environ, {}, clear=True):
            with self.assertRaises(ValueError):
                Browser(base_url=self.base_url)

    def test_status_and_tab_lifecycle(self):
        self.assertTrue(self.browser.status()["extensionReady"])

        tab = self.browser.new_tab("https://example.com")
        self.assertEqual(tab.url, "https://example.com")
        self.assertEqual([t.id for t in self.browser.tabs()], [tab.id])
        self.assertEqual(self.browser.active_tab().id, tab.id)

        tab.close()
        self.assertEqual(self.browser.tabs(), [])

    def test_navigate_without_wait_returns_immediately(self):
        tab = self.browser.new_tab()
        info = tab.navigate("https://example.org", wait=False)
        self.assertEqual(info["url"], "https://example.org")
        self.assertTrue(info["loading"])

    def test_navigate_waits_for_page_load(self):
        tab = self.browser.new_tab()
        info = tab.navigate("https://example.net", wait=True, timeout=5)
        self.assertEqual(info["url"], "https://example.net")
        self.assertFalse(info["loading"])

    def test_wait_until_loaded_times_out_while_loading(self):
        tab = self.browser.new_tab()
        self.server.fake.tabs[tab.id].update(url="https://slow.example", loading=True, polls=-10**9)
        with self.assertRaises(TimeoutError):
            tab.wait_until_loaded(timeout=0.2, interval=0.02)

    def test_eval_returns_result(self):
        tab = self.browser.new_tab("https://example.com")
        self.assertEqual(tab.eval("1 + 2"), 3)
        self.assertEqual(tab.eval("document.title"), "https://example.com")

    def test_eval_errors_are_raised_with_status(self):
        tab = self.browser.new_tab("https://example.com")
        with self.assertRaises(BrowserError) as ctx:
            tab.eval("nope")
        self.assertEqual(ctx.exception.status, 422)
        self.assertIn("ReferenceError", ctx.exception.message)

    def test_tab_without_page_reports_conflict(self):
        tab = self.browser.new_tab()  # about:blank has no page script
        with self.assertRaises(BrowserError) as ctx:
            tab.eval("1 + 2")
        self.assertEqual(ctx.exception.status, 409)

    def test_dom_helpers(self):
        tab = self.browser.new_tab("https://example.com")
        self.assertTrue(tab.exists("h1"))
        self.assertFalse(tab.exists("#nothing"))
        self.assertEqual(tab.text("h1"), "Heading")
        tab.click("h1")
        tab.type("h1", "hello")  # fake returns null for type; must not raise
        tab.wait_for("h1", timeout=1, interval=0.01)

    def test_wait_for_times_out(self):
        tab = self.browser.new_tab("https://example.com")
        with self.assertRaises(TimeoutError):
            tab.wait_for("#never", timeout=0.2, interval=0.05)

    def test_missing_element_is_a_browser_error(self):
        tab = self.browser.new_tab("https://example.com")
        with self.assertRaises(BrowserError) as ctx:
            tab.text("#gone")
        self.assertEqual(ctx.exception.status, 422)
        self.assertIn("No element matches selector: #gone", ctx.exception.message)

    def test_unreachable_server_gives_helpful_error(self):
        browser = Browser(token=TOKEN, base_url="http://127.0.0.1:9", timeout=2)
        with self.assertRaises(ConnectionError) as ctx:
            browser.status()
        self.assertIn("adb forward", str(ctx.exception))


if __name__ == "__main__":
    unittest.main()
