# Automation API (control the browser from Python)

The app can run a small control server on the phone's loopback interface
(`127.0.0.1:8765`). Python scripts can open tabs, navigate, read pages, run
JavaScript, and click or type into elements.

- Off by default. Turn it on from the **Automation** icon in the toolbar.
- The server listens only on `127.0.0.1`. It is not reachable from other devices on your network.
- Every request must send the access token in the `X-Automation-Token` header.
  Generate a new token in the same dialog at any time.

## Connecting

| Where Python runs | Address to use | Setup |
|---|---|---|
| A PC, connected by USB | `http://127.0.0.1:8765` | `adb forward tcp:8765 tcp:8765` |
| Termux (or another app) on the same phone | `http://127.0.0.1:8765` | none |

The client reads `GECKO_BROWSER_TOKEN` and `GECKO_BROWSER_URL` from the
environment. You can also pass `token=` and `base_url=` directly.

```sh
export GECKO_BROWSER_TOKEN=<token from the app>
adb forward tcp:8765 tcp:8765          # PC only
cd tools/gecko_automation
python3 example.py
```

## Python API

The client is in [`tools/gecko_automation/gecko_browser.py`](../tools/gecko_automation/gecko_browser.py).
It uses only the standard library.

```python
from gecko_browser import Browser

browser = Browser()                              # token from GECKO_BROWSER_TOKEN
tab = browser.new_tab("https://example.com")     # or browser.tabs(), browser.active_tab()
tab.wait_until_loaded()

print(tab.title, tab.url)
print(tab.eval("document.querySelectorAll('a').length"))
print(tab.text("h1"))
print(tab.html("main", inner=True))
print(tab.attr("a", "href"))

tab.wait_for("input[name=q]", timeout=20)        # raises TimeoutError
tab.type("input[name=q]", "hello")
tab.click("button[type=submit]")
tab.navigate("https://example.org")              # waits for load by default
tab.back(); tab.forward(); tab.reload(); tab.stop()
tab.select()                                     # make it the active tab
tab.close()
```

| Method | Purpose |
|---|---|
| `Browser.status()` | Server version, tab count, whether the page bridge is ready |
| `Browser.tabs()`, `active_tab()`, `tab(id)` | Find tabs |
| `Browser.new_tab(url=None)` | Open a tab |
| `Tab.info()`, `.url`, `.title`, `.loading` | Tab state |
| `Tab.navigate(url, wait=True)` | Load a URL |
| `Tab.wait_until_loaded(timeout)` | Wait until the page finishes loading |
| `Tab.reload()`, `back()`, `forward()`, `stop()`, `select()`, `close()` | Tab control |
| `Tab.eval(code)` | Run JavaScript in the page and return a JSON value |
| `Tab.text(sel)`, `html(sel=None, inner=False)`, `attr(sel, name)`, `value(sel)` | Read the page |
| `Tab.exists(sel)`, `wait_for(sel, timeout)` | Check or wait for an element |
| `Tab.click(sel)`, `Tab.type(sel, text)` | Interact with the page |

Errors:

- `BrowserError` has `.status` and `.message`. It is raised for HTTP errors, such as a missing element (422) or a tab with no page (409).
- `ConnectionError` means the server is not reachable. The message says what to check.
- `TimeoutError` is raised by `wait_for` and `wait_until_loaded`.

## HTTP API

All paths are under `/api/v1`. Bodies and responses are JSON. Send
`X-Automation-Token: <token>` with every request.

| Method | Path | Body | Response |
|---|---|---|---|
| GET | `/status` | | `{ok, apiVersion, tabs, activeTabId, extensionReady}` |
| GET | `/tabs` | | `[tab, ...]` |
| POST | `/tabs` | `{url?}` | `201` + tab |
| GET | `/tabs/{id}` | | tab |
| DELETE | `/tabs/{id}` | | `{closed: true}` |
| POST | `/tabs/{id}/select` | | tab |
| POST | `/tabs/{id}/navigate` | `{url}` | tab (loading may still be true) |
| POST | `/tabs/{id}/reload`, `back`, `forward`, `stop` | | tab |
| POST | `/tabs/{id}/eval` | `{code}` | `{result}` |
| POST | `/tabs/{id}/dom` | `{op, selector?, name?, text?, inner?}` | `{result}` |

A tab is `{id, url, title, loading, progress, canGoBack, canGoForward, active}`.

`op` for `/dom` is one of `text`, `html`, `attr`, `value`, `exists`, `click`, `type`.

Status codes: `400` bad input, `401` bad or missing token, `403` request
looks like it came from a web page (has an `Origin` header) or has a non-local
`Host`, `404` unknown tab or path, `409` the tab has no page script (for example
`about:blank`, or the page is still starting), `422` the page operation failed
(for example, no element matches), `504` the page did not answer in 15 seconds.

Example with curl:

```sh
curl -s -H "X-Automation-Token: $GECKO_BROWSER_TOKEN" http://127.0.0.1:8765/api/v1/status
```

## How it works

1. The app installs a small built-in WebExtension from `app/src/main/assets/automation/`.
2. Its content script runs in each top-level http(s) page and opens a native port to the app.
3. An API request is turned into an operation message and sent to the tab's port.
   The content script runs it in the page's own JavaScript world and sends back the result.
4. Tab changes (new, close, navigate) are made on the Android main thread, where GeckoView requires them.

## Limits

- Only top-level http(s) pages. Iframes, `about:` pages, and internal browser
  pages are not scriptable.
- Page operations, including the DOM helpers, run through the page's `eval`. A
  page whose Content Security Policy blocks `eval` returns an error for them.
- No screenshots. GeckoView's public API does not expose a page capture method
  that this bridge uses.
- A page operation that gets no reply within 15 seconds fails with `504`.
- The token is stored in the app's private preferences. Anyone with the token and
  adb access to the phone can control the browser, so keep it private.

## Verification status

What was checked before this was committed:

- **Python client:** `python3 -m unittest` in `tools/gecko_automation` (14 tests). These run against an in-process fake server that follows the HTTP contract above.
- **Page script (`content.js`):** run in jsdom with a mocked `browser` API. Checked eval, text/html/attr/value/exists, click, type (with input and change events), contenteditable, missing elements, and unknown operations. Real GeckoView was not used.
- **Kotlin, `com.example.automation` package:** type-checked with the Kotlin 2.2.10 compiler. Android, GeckoView, and Compose APIs were replaced with stubs that follow the real signatures. The stubs don't cover the rest of the app.
- **HTTP server:** run on the JVM against real sockets. Covered POST bodies, UTF-8, malformed requests (400), oversized bodies (413), oversized headers (431), 40 concurrent requests, and an idle client that doesn't block others.
- **Auth and routing:** `AutomationApi.handle` was run with the stubs. Covered no or wrong token (401), browser `Origin` header (403), non-local `Host` (403), status and tab routes, and unknown routes (404).

Not verified, and needs a device test:

- The full app has not been built into an APK here. There's no Android SDK, and Gradle and Maven aren't reachable from this environment. Run `gradle :app:assembleDebug` to confirm the build.
- GeckoView behavior at runtime:
  - Whether `connectNative` from a content script works for this built-in extension without extra flags. The code does not set `WebExtension.Flags.ALLOW_CONTENT_MESSAGING`, because I could not confirm where that flag is set for built-in extensions.
  - Whether `window.wrappedJSObject.eval` runs page code and returns values on this GeckoView build (157).
- The settings dialog and toolbar icon on a real screen.
