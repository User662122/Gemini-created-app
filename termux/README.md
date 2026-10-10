# Controlling the browser from Termux

The browser has a built-in **localhost control server**. Python scripts (or `curl`) running in
[Termux](https://termux.dev) on the same phone can use it to open tabs, load pages, click, fill in
forms, read text/HTML, run JavaScript inside pages and take screenshots.

## 1. Turn it on in the browser

1. Tap the **terminal icon** at the right of the back/forward row.
2. Switch on **Allow localhost control**. A notification appears while it is on. Android may ask
   for permission to show notifications; the feature works either way.
3. Tap **Copy command** and paste the command into Termux:

   ```sh
   export BROWSER_PORT=9876 BROWSER_TOKEN=3f9c...
   ```

   To keep it for new Termux sessions, add the line to `~/.bashrc`. Instead of the environment
   variable you can also save the token to `~/.config/gecko-browser/token`.

The server only listens on `127.0.0.1`, so other devices on your network can't reach it. Every
request must include the token. **New token** cancels the old one. Turning the switch off (or
tapping *Turn off* in the notification) stops the server.

## 2. Get the client

`browser_control.py` only needs Python's standard library:

```sh
pkg install python
curl -O https://raw.githubusercontent.com/User662122/Gemini-created-app/arena/ea1f687f-gemini-created-app/termux/browser_control.py
# (or copy termux/browser_control.py from this repository into your Termux home)
```

## 3. Use it

```python
from browser_control import Browser

b = Browser()                                   # reads BROWSER_PORT / BROWSER_TOKEN
b.goto("https://duckduckgo.com")                # waits for the page to load
b.fill("input[name=q]", "GeckoView", submit=True)
b.wait_load()
b.wait_for("text=GeckoView")
print(b.title(), b.url())

for link in b.query("a[data-testid=result-title-a]", limit=5):
    print(link["text"], link["href"])

print(b.eval("document.querySelectorAll('a').length"))   # run JavaScript in the page
b.screenshot("page.png")                                  # the browser must be on screen
```

Also from the shell:

```sh
python browser_control.py goto https://example.com
python browser_control.py eval "document.title"
python browser_control.py click "text=More information"
python browser_control.py --tab 1 text h1
python browser_control.py tabs
```

See `example.py` for a longer script.

### Selectors

Element commands accept:

| Form | Meaning |
| --- | --- |
| `#id`, `.class`, `form input[name=q]` | Any CSS selector |
| `text=Sign in` | Innermost element whose text contains "sign in" (ignores case; exact matches come first) |
| `xpath=//button[2]` | An XPath expression |

If several elements match, pass `index=` (0-based; negative counts from the end).

### Tabs

Every method takes an optional `tab=`: either a tab `id` from `b.tabs()` / `b.new_tab()`, or a
0-based index. Without it, the command goes to the active tab. Background tabs work too, but
screenshots only work for the visible tab.

### Running JavaScript

```python
b.eval("document.title")                          # one expression: its value is returned
b.eval("const n = document.links.length; return n")  # several statements: use return
b.eval("await fetch('/api/me').then(r => r.json())")  # await is allowed
b.eval("window.someAppVariable", world="page")    # run with the page's own JS globals
```

Results come back as JSON. Elements are returned as descriptions (tag, text, href, value,
visible, position, ...). By default code runs in an isolated content-script world. It can see and
change the DOM, but not variables that the page's scripts created. Use `world="page"` for those.
That mode is subject to the site's Content-Security-Policy.

## HTTP API

Every endpoint accepts `GET` with query parameters or `POST` with a JSON object body. Send the
token as `Authorization: Bearer <token>`, as an `X-Browser-Token` header, or as `?token=`.
Responses are JSON objects with `"ok": true` or `"ok": false, "error": "..."`.

```sh
curl -s -H "Authorization: Bearer $BROWSER_TOKEN" http://127.0.0.1:9876/tabs
curl -s -H "Authorization: Bearer $BROWSER_TOKEN" -d '{"url":"example.com","wait":true}' http://127.0.0.1:9876/navigate
curl -s -H "Authorization: Bearer $BROWSER_TOKEN" -d '{"script":"document.title"}' http://127.0.0.1:9876/eval
```

| Endpoint | Parameters | Result |
| --- | --- | --- |
| `/` | none (no token needed) | Server name and list of endpoints |
| `/status` | | Tab count, active tab, page bridge state |
| `/tabs` | | `tabs`: list of tab objects |
| `/tab` | `tab` | `tab` |
| `/tabs/new` | `url`, `activate=true`, `wait=false`, `timeout` | `tab` |
| `/tabs/activate` | `tab` (required) | `tab` |
| `/tabs/close` | `tab` | `closed`: tab id |
| `/navigate` | `url` (URL or search terms), `tab`, `wait=false`, `timeout=30` | `tab` |
| `/back`, `/forward`, `/reload` | `tab`, `wait=false`, `timeout` | `tab` |
| `/stop` | `tab` | `tab` |
| `/wait_load` | `tab`, `timeout=30` | `tab` once loading has finished |
| `/eval` | `script`, `world=content\|page`, `tab`, `timeout=30` | `result` |
| `/click` | `selector`, `index`, `tab` | `result`: element description |
| `/fill` | `selector`, `value`, `submit=false`, `index`, `tab` | `result`: element description |
| `/html` | `selector` (optional), `index`, `tab` | `result`: outer HTML |
| `/text` | `selector` (optional), `index`, `tab` | `result`: visible text |
| `/query` | `selector`, `limit=50`, `tab` | `result`: list of element descriptions |
| `/wait_for` | `selector`, `timeout=10`, `visible=false`, `gone=false`, `tab` | `result` |
| `/scroll` | `x`, `y` \| `selector` \| `to=top\|bottom`, `tab` | `result`: scroll position |
| `/screenshot` | `tab` (must be the visible tab) | PNG image |

A tab object contains `id`, `index`, `active`, `url`, `title`, `loading`, `progress`,
`can_go_back`, `can_go_forward` and `scriptable` (page commands are available).

If a `click`/`fill`/`eval` makes the page navigate before it can answer, the response is
`{"ok": true, "result": null, "navigated": true}`. Call `wait_load` afterwards.

Error status codes: `401` wrong/missing token, `404` unknown tab or endpoint, `409` page not
ready (blank/internal page, still loading) or tab not visible for a screenshot, `400` JavaScript
error or element not found (`"code": "page_error"`), `504` timeout, `503` browser window not open.

## Notes and limits

- Keep the browser app open (it can be in the background). The notification keeps Android from
  freezing it while Termux is in front. Screenshots need the browser to be visible, e.g. in split
  screen.
- Page commands work on normal web pages (`http`, `https`, `file`). They don't work on `about:`
  pages or the blank new-tab page.
- Clicks and typing are DOM events created by script. Sites that only accept real touch input may
  ignore them.
- Anyone who has the token can control every page, including sites you are logged in to. Treat it
  like a password.
