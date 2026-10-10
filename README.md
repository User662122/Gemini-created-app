# Gecko Browser

A minimal Android browser: it loads web pages with Mozilla GeckoView (Gecko rendering and SpiderMonkey JavaScript), with an address/search bar, back/forward, reload/stop, and basic tab creation and switching.

The browser does not include bookmarks, browsing history, downloads UI, settings, or an in-app inspector.

## Remote control from Termux

Scripts on the same phone, such as Python in [Termux](https://termux.dev), can control the browser over `http://127.0.0.1:9876`. They can open and switch tabs, navigate, click, fill forms, read text and HTML, run JavaScript in pages and take screenshots. To turn it on, tap the terminal icon under the address bar and switch on **Allow localhost control**. Then copy the setup command into Termux and use [`termux/browser_control.py`](termux/browser_control.py):

```python
from browser_control import Browser
b = Browser()
b.goto("https://example.com")
print(b.eval("document.title"))
```

The server only listens on the loopback interface and needs a per-device access token. A foreground notification keeps it running while the browser is in the background. Page commands go through a built-in GeckoView WebExtension (`app/src/main/assets/remote_control`). See [termux/README.md](termux/README.md) for setup and the full HTTP API.

## Build

Open the project in Android Studio or run:

```sh
gradle :app:assembleDebug :app:testDebugUnitTest
```

The app requires Android 8.0 (API 26) or newer. Only the 32-bit `armeabi-v7a` ABI is packaged; there are no 64-bit APKs. The phone and ARM-compatible emulator releases contain the same APK under separate release entries. GeckoView is bundled in the APK, so the first build downloads a large engine dependency.
