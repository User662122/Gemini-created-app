# Gecko Browser

A minimal Android browser: it loads web pages with Mozilla GeckoView (Gecko rendering and SpiderMonkey JavaScript), with an address/search bar, back/forward, reload/stop, and basic tab creation and switching.

The browser does not include bookmarks, browsing history, downloads UI, settings, automation, or an in-app inspector.

## Build

Open the project in Android Studio or run:

```sh
gradle :app:assembleDebug :app:testDebugUnitTest
```

The app requires Android 8.0 (API 26) or newer. GeckoView is bundled in the APK, so the first build downloads a large engine dependency.
