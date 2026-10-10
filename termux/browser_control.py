#!/usr/bin/env python3
"""Control the Gecko Browser Android app from Termux (or any script on the same phone).

Turn on "Remote control" in the browser (the terminal icon under the address bar),
copy the setup command it shows and paste it into Termux, e.g.:

    export BROWSER_PORT=9876 BROWSER_TOKEN=0123abcd...

Then, from Python:

    from browser_control import Browser

    b = Browser()
    b.goto("https://example.com")
    print(b.title())
    b.click("text=More information")

or from the shell:

    python browser_control.py goto https://example.com
    python browser_control.py eval "document.title"

Only the Python standard library is used, so no `pip install` is needed.
"""

import argparse
import json
import os
import sys
import urllib.error
import urllib.request

__all__ = ["Browser", "BrowserError"]

DEFAULT_PORT = 9876
TOKEN_FILES = ("~/.config/gecko-browser/token", "~/.browser_token")


class BrowserError(Exception):
    """Raised when the browser rejects or fails a command."""

    def __init__(self, message, status=None, code=None):
        super().__init__(message)
        self.status = status
        self.code = code


def _find_token():
    token = os.environ.get("BROWSER_TOKEN")
    if token:
        return token.strip()
    for path in TOKEN_FILES:
        path = os.path.expanduser(path)
        if os.path.isfile(path):
            with open(path, encoding="utf-8") as handle:
                token = handle.read().strip()
            if token:
                return token
    return None


class Browser:
    """Client for the browser's localhost control API.

    Most methods take an optional ``tab``: a tab id (from :meth:`tabs`) or a zero-based
    index. Without it the command goes to the active tab.

    Element selectors are CSS selectors, or ``"text=Sign in"`` (element containing the
    text, case-insensitive), or ``"xpath=//button[2]"``.
    """

    def __init__(self, host="127.0.0.1", port=None, token=None, timeout=60):
        self.host = host
        self.port = int(port or os.environ.get("BROWSER_PORT") or DEFAULT_PORT)
        self.token = token or _find_token()
        self.timeout = timeout
        # Never route localhost traffic through an HTTP proxy configured in the environment.
        self._opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        if not self.token:
            raise BrowserError(
                "No token. In the browser open Remote control, tap 'Copy command' and paste it "
                "into Termux (export BROWSER_TOKEN=...), or pass Browser(token=...)."
            )

    # ------------------------------------------------------------------ transport

    @property
    def base_url(self):
        return "http://%s:%d" % (self.host, self.port)

    def request(self, path, params=None, raw=False):
        """POST ``params`` as JSON to ``path`` and return the decoded JSON (or bytes)."""
        params = {k: v for k, v in (params or {}).items() if v is not None}
        body = json.dumps(params).encode("utf-8")
        req = urllib.request.Request(
            self.base_url + path,
            data=body,
            method="POST",
            headers={
                "Content-Type": "application/json",
                "Authorization": "Bearer " + self.token,
            },
        )
        wait = float(params.get("timeout") or 0)
        try:
            with self._opener.open(req, timeout=max(self.timeout, wait + 15)) as response:
                data = response.read()
                if raw:
                    return data
                return json.loads(data.decode("utf-8"))
        except urllib.error.HTTPError as error:
            payload = error.read()
            try:
                info = json.loads(payload.decode("utf-8"))
                message, code = info.get("error", str(error)), info.get("code")
            except ValueError:
                message, code = payload.decode("utf-8", "replace") or str(error), None
            raise BrowserError(message, status=error.code, code=code) from None
        except urllib.error.URLError as error:
            raise BrowserError(
                "Cannot reach the browser at %s (%s). Is the app open with Remote control "
                "turned on?" % (self.base_url, error.reason)
            ) from None

    # ------------------------------------------------------------------ browser & tabs

    def status(self):
        return self.request("/status")

    def tabs(self):
        """List of tabs: dicts with id, index, active, url, title, loading, ..."""
        return self.request("/tabs")["tabs"]

    def tab(self, tab=None):
        return self.request("/tab", {"tab": tab})["tab"]

    def new_tab(self, url=None, activate=True, wait=True, timeout=30):
        """Open a tab and return its info dict (use ``["id"]`` to target it later)."""
        return self.request(
            "/tabs/new",
            {"url": url, "activate": activate, "wait": wait and url is not None, "timeout": timeout},
        )["tab"]

    def activate(self, tab):
        return self.request("/tabs/activate", {"tab": tab})["tab"]

    def close_tab(self, tab=None):
        return self.request("/tabs/close", {"tab": tab})["closed"]

    # ------------------------------------------------------------------ navigation

    def goto(self, url, tab=None, wait=True, timeout=30):
        """Load a URL (or search terms) and, by default, wait until it has loaded."""
        return self.request("/navigate", {"url": url, "tab": tab, "wait": wait, "timeout": timeout})["tab"]

    def back(self, tab=None, wait=True, timeout=30):
        return self.request("/back", {"tab": tab, "wait": wait, "timeout": timeout})["tab"]

    def forward(self, tab=None, wait=True, timeout=30):
        return self.request("/forward", {"tab": tab, "wait": wait, "timeout": timeout})["tab"]

    def reload(self, tab=None, wait=True, timeout=30):
        return self.request("/reload", {"tab": tab, "wait": wait, "timeout": timeout})["tab"]

    def stop(self, tab=None):
        return self.request("/stop", {"tab": tab})["tab"]

    def wait_load(self, tab=None, timeout=30):
        return self.request("/wait_load", {"tab": tab, "timeout": timeout})["tab"]

    def url(self, tab=None):
        return self.tab(tab)["url"]

    def title(self, tab=None):
        return self.tab(tab)["title"]

    # ------------------------------------------------------------------ page content

    def eval(self, script, tab=None, world="content", timeout=30):
        """Run JavaScript in the page and return its (JSON-converted) result.

        A single expression returns its value (``await`` is allowed). For several
        statements use ``return``::

            b.eval("document.title")
            b.eval("const links = document.links; return links.length")
            b.eval("await fetch('/api').then(r => r.json())")

        ``world="content"`` (default) sees the page's DOM; ``world="page"`` runs with
        the page's own JavaScript globals (subject to the site's Content-Security-Policy).
        """
        return self.request(
            "/eval", {"script": script, "tab": tab, "world": world, "timeout": timeout}
        ).get("result")

    def click(self, selector, index=0, tab=None, timeout=30):
        """Click the matching element; returns a description of it."""
        return self.request(
            "/click", {"selector": selector, "index": index, "tab": tab, "timeout": timeout}
        ).get("result")

    def fill(self, selector, value, submit=False, index=0, tab=None, timeout=30):
        """Type ``value`` into an input/textarea/select (or tick a checkbox with True/False)."""
        if isinstance(value, bool):
            value = "true" if value else "false"
        return self.request(
            "/fill",
            {"selector": selector, "value": value, "submit": submit, "index": index,
             "tab": tab, "timeout": timeout},
        ).get("result")

    def html(self, selector=None, index=0, tab=None):
        """Outer HTML of the element, or of the whole document."""
        return self.request("/html", {"selector": selector, "index": index, "tab": tab})["result"]

    def text(self, selector=None, index=0, tab=None):
        """Visible text of the element, or of the whole page."""
        return self.request("/text", {"selector": selector, "index": index, "tab": tab})["result"]

    def query(self, selector, limit=50, tab=None):
        """Describe all matching elements (tag, text, href, value, visible, rect, ...)."""
        return self.request("/query", {"selector": selector, "limit": limit, "tab": tab})["result"]

    def exists(self, selector, tab=None):
        return bool(self.query(selector, limit=1, tab=tab))

    def wait_for(self, selector, timeout=10, visible=False, gone=False, tab=None):
        """Wait until an element appears (or disappears with ``gone=True``)."""
        return self.request(
            "/wait_for",
            {"selector": selector, "timeout": timeout, "visible": visible, "gone": gone, "tab": tab},
        )["result"]

    def scroll(self, x=0, y=0, selector=None, to=None, tab=None):
        """Scroll by (x, y) pixels, to an element, or ``to="top"`` / ``to="bottom"``."""
        return self.request(
            "/scroll", {"x": x, "y": y, "selector": selector, "to": to, "tab": tab}
        )["result"]

    def screenshot(self, path=None, tab=None):
        """PNG of the visible tab (the browser must be on screen). Saves to ``path`` if given."""
        data = self.request("/screenshot", {"tab": tab}, raw=True)
        if path:
            with open(path, "wb") as handle:
                handle.write(data)
        return data


# ---------------------------------------------------------------------- command line


def _main(argv=None):
    parser = argparse.ArgumentParser(
        description="Control Gecko Browser from Termux. Needs BROWSER_TOKEN (see the app's Remote control dialog)."
    )
    parser.add_argument("--port", type=int, help="server port (default $BROWSER_PORT or %d)" % DEFAULT_PORT)
    parser.add_argument("--token", help="access token (default $BROWSER_TOKEN)")
    parser.add_argument("--tab", help="tab id or index (default: active tab)")
    sub = parser.add_subparsers(dest="command", metavar="command")
    sub.required = True

    sub.add_parser("status", help="browser status")
    sub.add_parser("tabs", help="list tabs")
    p = sub.add_parser("new-tab", help="open a tab")
    p.add_argument("url", nargs="?")
    p = sub.add_parser("activate", help="switch to a tab")
    p.add_argument("target", help="tab id or index")
    sub.add_parser("close-tab", help="close the tab (default: active)")
    p = sub.add_parser("goto", help="load a URL and wait for it")
    p.add_argument("url")
    for name in ("back", "forward", "reload", "stop"):
        sub.add_parser(name)
    p = sub.add_parser("eval", help="run JavaScript and print the result")
    p.add_argument("script", help="JavaScript; use '-' to read from stdin")
    p.add_argument("--page-world", action="store_true", help="run with the page's own globals")
    p = sub.add_parser("click", help="click an element")
    p.add_argument("selector")
    p = sub.add_parser("fill", help="type into a form field")
    p.add_argument("selector")
    p.add_argument("value")
    p.add_argument("--submit", action="store_true", help="press Enter / submit the form afterwards")
    p = sub.add_parser("text", help="print page or element text")
    p.add_argument("selector", nargs="?")
    p = sub.add_parser("html", help="print page or element HTML")
    p.add_argument("selector", nargs="?")
    p = sub.add_parser("query", help="describe matching elements")
    p.add_argument("selector")
    p = sub.add_parser("wait-for", help="wait for an element")
    p.add_argument("selector")
    p.add_argument("--timeout", type=float, default=10)
    p = sub.add_parser("screenshot", help="save a PNG of the visible tab")
    p.add_argument("path", nargs="?", default="screenshot.png")

    args = parser.parse_args(argv)
    try:
        b = Browser(port=args.port, token=args.token)
        tab = args.tab
        cmd = args.command
        if cmd == "status":
            out = b.status()
        elif cmd == "tabs":
            out = b.tabs()
        elif cmd == "new-tab":
            out = b.new_tab(args.url)
        elif cmd == "activate":
            out = b.activate(args.target)
        elif cmd == "close-tab":
            out = b.close_tab(tab)
        elif cmd == "goto":
            out = b.goto(args.url, tab=tab)
        elif cmd in ("back", "forward", "reload"):
            out = getattr(b, cmd)(tab=tab)
        elif cmd == "stop":
            out = b.stop(tab=tab)
        elif cmd == "eval":
            script = sys.stdin.read() if args.script == "-" else args.script
            out = b.eval(script, tab=tab, world="page" if args.page_world else "content")
        elif cmd == "click":
            out = b.click(args.selector, tab=tab)
        elif cmd == "fill":
            out = b.fill(args.selector, args.value, submit=args.submit, tab=tab)
        elif cmd == "text":
            out = b.text(args.selector, tab=tab)
        elif cmd == "html":
            out = b.html(args.selector, tab=tab)
        elif cmd == "query":
            out = b.query(args.selector, tab=tab)
        elif cmd == "wait-for":
            out = b.wait_for(args.selector, timeout=args.timeout, tab=tab)
        elif cmd == "screenshot":
            b.screenshot(args.path, tab=tab)
            out = "Saved " + args.path
        else:  # pragma: no cover - argparse rejects unknown commands
            parser.error("unknown command")
            return 2
    except BrowserError as error:
        print("Error: %s" % error, file=sys.stderr)
        return 1

    if isinstance(out, str):
        print(out)
    else:
        print(json.dumps(out, indent=2, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(_main())
