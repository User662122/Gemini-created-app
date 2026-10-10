"""Python client for the Gecko Browser localhost automation API.

Standard library only (Python 3.8+). Enable "Automation" in the app, copy the
access token, then:

    from gecko_browser import Browser

    browser = Browser(token="...")          # or set GECKO_BROWSER_TOKEN
    tab = browser.new_tab("https://example.com")
    tab.wait_until_loaded()
    print(tab.title, tab.eval("document.title"))
    tab.wait_for("h1")
    print(tab.text("h1"))

From a PC the phone's loopback port is reached over USB with:

    adb forward tcp:8765 tcp:8765

See docs/AUTOMATION.md for the full API.
"""

from __future__ import annotations

import json
import os
import time
import urllib.error
import urllib.request
from typing import Any, Dict, List, Optional

DEFAULT_URL = "http://127.0.0.1:8765"
TOKEN_ENV_VAR = "GECKO_BROWSER_TOKEN"
URL_ENV_VAR = "GECKO_BROWSER_URL"
TOKEN_HEADER = "X-Automation-Token"


class BrowserError(Exception):
    """The browser answered with an error. ``status`` is the HTTP status code."""

    def __init__(self, status: int, message: str):
        super().__init__(f"HTTP {status}: {message}")
        self.status = status
        self.message = message


class Browser:
    """Connection to the browser's automation server."""

    def __init__(
        self,
        token: Optional[str] = None,
        base_url: Optional[str] = None,
        timeout: float = 30.0,
    ):
        self.base_url = (base_url or os.environ.get(URL_ENV_VAR) or DEFAULT_URL).rstrip("/")
        self.token = token or os.environ.get(TOKEN_ENV_VAR)
        if not self.token:
            raise ValueError(f"No access token. Pass token=... or set {TOKEN_ENV_VAR}.")
        self.timeout = timeout

    # --- low level -------------------------------------------------------

    def request(self, method: str, path: str, payload: Optional[Dict[str, Any]] = None) -> Any:
        """Send one request and return the decoded JSON response."""
        headers = {TOKEN_HEADER: self.token, "Accept": "application/json"}
        data = None
        if payload is not None:
            data = json.dumps(payload).encode("utf-8")
            headers["Content-Type"] = "application/json"
        req = urllib.request.Request(
            self.base_url + path, data=data, method=method, headers=headers
        )
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as resp:
                raw = resp.read()
        except urllib.error.HTTPError as err:
            message: Any = err.reason
            try:
                message = json.loads(err.read().decode("utf-8")).get("error", message)
            except (ValueError, AttributeError):
                pass
            raise BrowserError(err.code, str(message)) from None
        except urllib.error.URLError as err:
            raise ConnectionError(
                f"Cannot reach the browser at {self.base_url} ({err.reason}). "
                "Turn on Automation in the app. On a PC over USB, run: "
                "adb forward tcp:8765 tcp:8765"
            ) from err
        return json.loads(raw.decode("utf-8")) if raw else None

    # --- browser level ---------------------------------------------------

    def status(self) -> Dict[str, Any]:
        return self.request("GET", "/api/v1/status")

    def tabs(self) -> List["Tab"]:
        return [Tab(self, info["id"]) for info in self.request("GET", "/api/v1/tabs")]

    def active_tab(self) -> "Tab":
        for info in self.request("GET", "/api/v1/tabs"):
            if info.get("active"):
                return Tab(self, info["id"])
        raise LookupError("No active tab")

    def new_tab(self, url: Optional[str] = None) -> "Tab":
        payload = {"url": url} if url else {}
        info = self.request("POST", "/api/v1/tabs", payload)
        return Tab(self, info["id"])

    def tab(self, tab_id: str) -> "Tab":
        return Tab(self, tab_id)


class Tab:
    """One browser tab. Methods raise BrowserError for browser-side failures."""

    def __init__(self, browser: Browser, tab_id: str):
        self.browser = browser
        self.id = tab_id

    def __repr__(self) -> str:
        return f"Tab(id={self.id!r})"

    def _path(self, suffix: str = "") -> str:
        return f"/api/v1/tabs/{self.id}{suffix}"

    def _dom(self, op: str, **args: Any) -> Any:
        payload = {"op": op, **args}
        return self.browser.request("POST", self._path("/dom"), payload)["result"]

    # --- state -----------------------------------------------------------

    def info(self) -> Dict[str, Any]:
        """The tab's id, url, title, loading, progress, canGoBack, canGoForward, active."""
        return self.browser.request("GET", self._path())

    @property
    def url(self) -> str:
        return self.info()["url"]

    @property
    def title(self) -> str:
        return self.info()["title"]

    @property
    def loading(self) -> bool:
        return self.info()["loading"]

    def wait_until_loaded(self, timeout: float = 30.0, interval: float = 0.2) -> Dict[str, Any]:
        """Block until the page is no longer loading. Returns the tab info."""
        deadline = time.monotonic() + timeout
        while True:
            info = self.info()
            if not info["loading"] and info["url"]:
                return info
            if time.monotonic() >= deadline:
                raise TimeoutError(f"Page still loading after {timeout}s: {info['url']}")
            time.sleep(interval)

    # --- navigation and tab management -------------------------------------

    def select(self) -> Dict[str, Any]:
        return self.browser.request("POST", self._path("/select"))

    def navigate(self, url: str, wait: bool = True, timeout: float = 30.0) -> Dict[str, Any]:
        """Load ``url``. With ``wait=True``, return after the page finishes loading."""
        before = self.info()["url"]
        info = self.browser.request("POST", self._path("/navigate"), {"url": url})
        if wait:
            # The loading state is updated asynchronously; wait for it to start first.
            deadline = time.monotonic() + 5.0
            while time.monotonic() < deadline:
                current = self.info()
                if current["loading"] or current["url"] != before:
                    break
                time.sleep(0.1)
            return self.wait_until_loaded(timeout=timeout)
        return info

    def reload(self) -> Dict[str, Any]:
        return self.browser.request("POST", self._path("/reload"))

    def back(self) -> Dict[str, Any]:
        return self.browser.request("POST", self._path("/back"))

    def forward(self) -> Dict[str, Any]:
        return self.browser.request("POST", self._path("/forward"))

    def stop(self) -> Dict[str, Any]:
        return self.browser.request("POST", self._path("/stop"))

    def close(self) -> None:
        self.browser.request("DELETE", self._path())

    # --- page scripting ----------------------------------------------------

    def eval(self, code: str) -> Any:
        """Run JavaScript in the page and return its JSON-serializable result."""
        return self.browser.request("POST", self._path("/eval"), {"code": code})["result"]

    def text(self, selector: str) -> str:
        """The visible text of the first element matching ``selector``."""
        return self._dom("text", selector=selector)

    def html(self, selector: Optional[str] = None, inner: bool = False) -> str:
        """Outer HTML of the page or of ``selector`` (inner HTML if ``inner=True``)."""
        args: Dict[str, Any] = {"inner": inner}
        if selector:
            args["selector"] = selector
        return self._dom("html", **args)

    def attr(self, selector: str, name: str) -> Optional[str]:
        return self._dom("attr", selector=selector, name=name)

    def value(self, selector: str) -> str:
        """The current value of an input, textarea or select."""
        return self._dom("value", selector=selector)

    def exists(self, selector: str) -> bool:
        return self._dom("exists", selector=selector)

    def click(self, selector: str) -> None:
        self._dom("click", selector=selector)

    def type(self, selector: str, text: str) -> None:
        """Replace the value of an input (or the text of a contenteditable element)."""
        self._dom("type", selector=selector, text=text)

    def wait_for(self, selector: str, timeout: float = 15.0, interval: float = 0.25) -> None:
        """Block until ``selector`` matches an element. Raises TimeoutError otherwise."""
        deadline = time.monotonic() + timeout
        while True:
            if self.exists(selector):
                return
            if time.monotonic() >= deadline:
                raise TimeoutError(f"No element matched {selector!r} within {timeout}s")
            time.sleep(interval)


__all__ = ["Browser", "BrowserError", "Tab"]
