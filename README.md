# Gecko Browser

A minimal Android browser backed by Mozilla GeckoView (Gecko rendering and SpiderMonkey JavaScript), with an address/search bar, back/forward, reload/stop, basic tabs, and an opt-in in-app network inspector.

## Network inspector

Open **Traffic** in the browser toolbar, then explicitly start capture. Capture is off by default and applies only to traffic handled by this app's GeckoView browser. The inspector stores a bounded log in app memory, displays request and response metadata, headers, and available bodies, and can export the log as one HAR 1.2 file using Android's system save-file picker.

The capture is deliberately local: the built-in GeckoView extension sends records only to this app's native code. There is no logging server or VPN. Headers are not redacted, so cookies, authorization values, URL query parameters, form values, and page content may be visible. Exported HAR files are also unredacted; save them only to a location you trust. Clear the in-memory log from the inspector, or close the app to discard it.

This is an application-level inspector, not a packet sniffer or a promise of literally every Chrome DevTools field. GeckoView exposes HTTP(S) request events and WebSocket handshake metadata, plus many headers/bodies, but some browser-generated/internal traffic, streaming or file-upload bodies, cached/script-optimized content, and WebSocket frames may be unavailable. Bodies are limited to 1 MiB per upload, 2 MiB per response, and a 16 MiB capture budget; the interface and HAR mark truncated or unavailable data. Clear the log to discard retained bodies and reset the body-capture budget. The browser does not capture traffic from other apps.

## Build

Open the project in Android Studio or run:

```sh
gradle :app:assembleDebug :app:testDebugUnitTest
```

The app requires Android 8.0 (API 26) or newer. Only the 32-bit `armeabi-v7a` ABI is packaged; there are no 64-bit APKs. The phone and ARM-compatible emulator releases contain the same APK under separate release entries. GeckoView is bundled in the APK, so the first build downloads a large engine dependency.
