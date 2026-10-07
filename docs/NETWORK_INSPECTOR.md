# Network Inspector (developer builds only)

A built-in DevTools-style panel for this browser: every request the app can actually observe, the
responses WebView reports, the cookies the app is allowed to see, and the page's JavaScript console —
all captured inside the app, with no proxy, no VPN service and no extra permissions.

It exists to help debug **your own** pages and the pages you are authorised to test. It does not
bypass CAPTCHAs, anti-bot systems, authentication, rate limits or fingerprinting checks, and it never
attempts to decrypt or reconstruct traffic that WebView does not hand to the app. There is no
"intercept and modify" feature: the inspector is a read-only observer.

Table of contents

1. [What it can and cannot see](#1-what-it-can-and-cannot-see)
2. [How to use it](#2-how-to-use-it)
3. [How the debug-only gate works](#3-how-the-debug-only-gate-works)
4. [Permissions](#4-permissions)
5. [Dependencies](#5-dependencies)
6. [Files](#6-files)
7. [Changes to existing files](#7-changes-to-existing-files)
8. [Privacy and redaction model](#8-privacy-and-redaction-model)
9. [Performance](#9-performance)
10. [Tests](#10-tests)
11. [Optional extras](#11-optional-extras)
12. [Troubleshooting](#12-troubleshooting)

---

## 1. What it can and cannot see

The inspector is built on the public Android/WebView APIs only. Some of what a desktop DevTools
Network tab shows is simply not available to an app that does not own the network stack, so every
field in the UI is tagged with where it came from and, when it is missing, *why*.

Legend: **yes** = reported by the platform, **partial** = only in some situations, **no** = not
available to a third-party app, with the reason shown in the UI.

| Data | Available | How / why not |
| --- | --- | --- |
| Request URL | yes | `WebResourceRequest.getUrl()`, page hooks, or the app's own HTTP client |
| HTTP method | yes | `WebResourceRequest.getMethod()` (missing for `onReceivedHttpAuthRequest`) |
| Request headers | partial | only the subset WebView chooses to expose in `getRequestHeaders()`. Page-issued `fetch`/XHR headers come from the injected hook, which sees them *before* the browser adds its own |
| Request body / POST data | partial | never from `WebResourceRequest`. Only when the request was issued by page JavaScript the hook wrapped (`fetch`, `XMLHttpRequest`, `sendBeacon`) **and** "Capture request bodies" is switched on; the preview is capped and scrubbed |
| Request timestamp | yes | the app clock at callback time, or the page's own timestamp (labelled) |
| Resource type | partial | `Sec-Fetch-Dest` when the header exists, the `Accept` header, the main-frame flag, or a file extension (shown as *inferred*, never as fact) |
| Response status code | partial | the delivered `WebResourceResponse` when the app consumes the response, `onReceivedHttpError` for 4xx/5xx, and main-frame error pages whose title is `HTTP ERROR nnn`. Otherwise the platform gives the app nothing |
| Response headers | partial | same sources as the status code; a 4xx/5xx from `onReceivedHttpError` arrives without a response object, so headers are usually empty |
| Content-Type | partial | from the same response object/exchange; inferred values are labelled |
| Response timestamp, duration | partial | measured by the app around the callbacks it receives; exact only for the app's own HTTP client |
| Final URL after redirects | partial | sub-resources do not report redirects at all (the redirect chain is invisible); the page hook can follow `response.url`, and the app's own client reports it exactly |
| Reason phrase | partial | available from the app's own client and page hooks; WebView's response object often omits it |
| Response body | partial | only text previews of responses the *page* read through the wrapped `fetch`/XHR, when switched on. Documents, images, CSS and JS that the WebView itself fetched are never available |
| Cookies: name, value | yes (value masked) | `CookieManager.getCookie(url)` and observed `Set-Cookie` headers |
| Cookie attributes (Domain, Path, Secure, HttpOnly, Expires) | partial | only when the app actually observed a `Set-Cookie` header. `getCookie(url)` returns `name=value` pairs only, and `getCookie(url, includeAttributes)` is not available to third-party apps — such fields are shown as "not available, and here is why" instead of being guessed |
| JavaScript console | yes | `WebChromeClient.onConsoleMessage` plus `window.onerror` |
| Service-worker traffic | no | requests issued by a service worker never reach `WebViewClient`. Would need `androidx.webkit`'s `ServiceWorkerControllerCompat` (see §11) |
| WebSocket (`ws:`/`wss:`) frames | no | WebView exposes no API for them at all |
| Cache hits / preflight requests | no | not reported; a request that never produces a callback is marked *unobserved*, not *failed* |
| Anything about encrypted payloads | no | the inspector never touches TLS; it only sees what the platform already decrypted and handed to the app |

Both the in-app **Info** tab and the shared report repeat this list, so a reader of a report knows
exactly which fields were observed and which were unavailable.

## 2. How to use it

1. Build and install a debug build (`./gradlew :app:installDebug`).
2. Open the browser, tap the three-dot menu → **Network Inspector (debug)**.
3. Tabs: **Network**, **Cookies**, **Console**, **Info**.
   * **Network** — search field, category chips (All / Documents / XHR–Fetch / JavaScript / CSS /
     Images / Other), a *current-tab only* toggle, and filters for status bucket, method, rows with a
     captured body and rows with redactions. Tap a row for the full request/response detail, with
     Copy-as-text and Share actions.
   * **Cookies** — one row per cookie the app can see, value masked, with a "why is this field
     unknown" note where the platform does not provide it.
   * **Console** — `console.log/warn/error/verbose`, uncaught JS errors, repeat collapsing and the
     stack from `window.onerror`.
   * **Info** — the capture switches, buffer/limit status, the capability summary and the explicit
     "out of scope" list.
4. Back closes the detail sheet first, then the inspector.

The menu entry only appears when the inspector is available, i.e. never in a release build.

## 3. How the debug-only gate works

Four independent conditions must all hold before a single byte is captured
(`DevToolsGate.isInspectorAvailable`):

| # | Condition | Where |
| --- | --- | --- |
| 1 | `BuildConfig.DEBUG` | generated by AGP; false in release |
| 2 | `BuildConfig.NETWORK_INSPECTOR_ENABLED` | `app/build.gradle.kts`: `true` for `debug`, `false` for `release` |
| 3 | `ApplicationInfo.FLAG_DEBUGGABLE` | true only for debuggable installs |
| 4 | the debug-only `Application` subclass | `app/src/debug/java/.../DebugBrowserApplication.kt` |

Production code never references the capture engine directly. It asks
`DevToolsRuntimeProvider.get()` for an `InspectorRuntime`, and without the debug `Application`
subclass that call can only return `NullInspectorRuntime`: no observer, no bridge, no UI entry point,
no capture. Because that subclass is in the `debug` source set, release builds do not even contain it.

To disable the inspector in debug builds, set `NETWORK_INSPECTOR_ENABLED` to `"false"` in
`app/build.gradle.kts` for the `debug` build type.

## 4. Permissions

**No permission is added or needed.** The inspector reads what the WebView already holds in this
process and what `CookieManager` already stores for this app.

* `android.permission.INTERNET` — already declared by the browser itself, not by the inspector.
* `android.permission.ACCESS_NETWORK_STATE` — already declared; the inspector does not use it, but a
  browser without it cannot show a connectivity hint.
* Not used, deliberately: no `VPNService`, no user-installed CA, no `QUERY_ALL_PACKAGES`, no
  `READ_PHONE_STATE`, no storage permissions, no overlay permission, no accessibility service.
  Any of those would mean inspecting traffic the app does not own, which is out of scope and, in most
  jurisdictions, unlawful without the owner's consent.

`CookieManager` works without extra permissions; a WebView that never called
`CookieManager.setAcceptCookie(true)` simply has nothing to show.

## 5. Dependencies

The inspector adds **no new dependency**. Everything is built on the Android SDK plus what the app
already uses: Kotlin, Jetpack Compose, Material 3, coroutines (`kotlinx-coroutines-android`, already
in the Compose stack) and `org.json` from the platform.

* `androidx.webkit` is **not** required. It would only be needed for the two features that are
  explicitly unavailable today — service-worker interception and `WebViewFeature` capability probes —
  see §11. Adding it just for those is not worth ~1 packaged library until you need them.
* `material-icons-extended` is already a dependency of this app; the inspector uses
  `ArrowBack`, `Close`, `Search`, `ContentCopy`, `Share` (and `BugReport` in the overflow menu). If you
  ever drop that dependency, replace those five icons with core glyphs or text labels.
* No reflection, no hidden APIs, no `@hide` calls, no root, no proxy.

## 6. Files

Everything below is new. All inspector code lives in the `com.example.devtools` package so it can be
deleted in one move.

### Capture engine — `app/src/main/java/com/example/devtools/`

| File | Responsibility |
| --- | --- |
| `DevToolsGate.kt` | The four-condition production gate and the human-readable reason it is off |
| `DevToolsRuntimeProvider.kt` | Process-wide holder; returns `NullInspectorRuntime` unless the debug `Application` installed the real one |
| `InspectorRuntime.kt` | `InspectorMessage` (the WebView→engine message set) and the `InspectorRuntime` interface + `NullInspectorRuntime` |
| `InspectorController.kt` | Owns the observer, settings, cookie reader and the ⇄ UI ticker (≤ 4 publishes/second, only while a screen is open) |
| `InspectorModels.kt` | Value/evidence/record/entry/console/cookie types, `InspectorValue`, `EntryState`, `InspectorLimits` |
| `NetworkObserver.kt` | The port the WebView side talks to (`onRequestStarted`, `onResponseReceived`, …) + `NullNetworkObserver` + the observation data classes |
| `LiveNetworkObserver.kt` | The engine: correlation, merging, ring buffers, rate limits, page-JS record handling, `Set-Cookie` capture |
| `NetworkLogStore.kt` | Thread-safe bounded store (400 entries, 300 console rows, 64 cookie observations) with revision counting |
| `CapturePolicy.kt` | Immutable per-callback settings snapshot read once per event |
| `InspectorSettingsStore.kt` | Persisted switches (SharedPreferences + JSON) published atomically as a `CapturePolicy` |
| `NetworkInspectorWebViewClient.kt` | Delegating `WebViewClient`: reports requests, HTTP errors, network failures, auth challenges, page lifecycle — then behaves exactly like the default |
| `NetworkInspectorWebChromeClient.kt` | Delegating `WebChromeClient`: console messages and page titles (used for `HTTP ERROR nnn` status inference) |
| `InspectorJsBridge.kt` | The single `@JavascriptInterface` method pages may call; validates and size-caps the payload |
| `InspectorScripts.kt` | The injected JavaScript: wraps `fetch`/XHR/`sendBeacon`, captures queue flushes, `window.onerror`, batched push over the bridge |
| `PageJsRecords.kt` | Defensive parser for what that JavaScript sends (204/200-record caps, http(s) only, never throws) |
| `Redaction.kt` | Capture-time masking: sensitive header/param detection, cookie-value summarising, text scrubbing, surrogate-safe truncation |
| `UrlParts.kt` | Scheme/host/path/extension/origin helpers, RFC 6265 default cookie path, correlation keys |
| `ResourceClassifier.kt` | The documented classification order (main frame → `Sec-Fetch-Dest` → `Accept` → JS initiator → non-GET → extension guess → Other) |
| `SetCookieParser.kt` | Parses one `Set-Cookie` value (name, attributes, expiry label) without ever keeping the value |
| `CookieInspector.kt` | Reads cookie *names* through `CookieManager` on a dedicated looper thread with a 1000 ms timeout, and merges the `Set-Cookie` observations |
| `InspectorFilters.kt` | `StatusFilter`, `InspectorFilterState`, matching/search logic, `InspectorEntryView`, `InspectorUiState`, display formatters |
| `InspectorExplanations.kt` | Every "not available, and here is why" sentence and the capability summary, in one place |
| `InspectorReport.kt` | Copy/share formatters for a single entry and for the whole session |
| `WebViewThreads.kt` | Answers "is this callback on the UI thread?" and recognises local schemes |
| `AppHttpClientInstrumentation.kt` | Optional OkHttp `Interceptor` for the app's own HTTP client (see §11); unused until you wire it |

### UI — `app/src/main/java/com/example/devtools/ui/`

| File | Responsibility |
| --- | --- |
| `NetworkInspectorScreen.kt` | The full-screen inspector: header, four tabs, filter bar, list, detail overlay |
| `RequestDetailScreen.kt` | One request/response pair in full, with reveal for raw values, Copy and Share |
| `CookieInspectorScreen.kt` | Cookie rows with real attributes where they exist and explicit unknowns otherwise |
| `ConsoleInspectorScreen.kt` | Console rows with level colouring, repeat counts and stacks |
| `InspectorInfoTab.kt` | Switches, buffer/limit status, capability summary, out-of-scope list |
| `InspectorComponents.kt` | Shared building blocks (badges, value rows, header lists, banners, empty states) |
| `ui/theme/InspectorColors.kt` | The status/method/level palette |

### Debug-only, tests and docs

| File | Responsibility |
| --- | --- |
| `app/src/debug/AndroidManifest.xml` | Declares `DebugBrowserApplication` for debug builds only |
| `app/src/debug/java/com/example/devtools/DebugBrowserApplication.kt` | Installs the live inspector; exists in no other build type |
| `app/src/test/java/com/example/devtools/RedactionTest.kt` | Masking/scrubbing must-haves |
| `app/src/test/java/com/example/devtools/UrlPartsAndFiltersTest.kt` | URL parsing, classification, filter and search behaviour |
| `app/src/test/java/com/example/devtools/CapturePipelineTest.kt` | End-to-end correlation tests against the real `LiveNetworkObserver` (no Android needed) |
| `docs/NETWORK_INSPECTOR.md` | This document |

## 7. Changes to existing files

Six existing app files are modified (below), plus one CI file: `.github/workflows/android-apk.yml`
now also captures the debug-build log, turns the first compiler errors into a "Debug build
diagnostics" check run and uploads the log with the other diagnostics — useful whenever a change is
only verifiable in CI. Nothing else in the app changes; in particular
`app/src/main/AndroidManifest.xml` is **not** touched — the inspector adds no permission, service,
activity or receiver.

1. **`app/build.gradle.kts`** — declares `NETWORK_INSPECTOR_ENABLED` for both build types
   (`buildConfigField("boolean", …)`), so `DevToolsGate` can read it. `buildConfig` was already on.

2. **`app/src/main/java/com/example/MainActivity.kt`** — resolves
   `DevToolsRuntimeProvider.get()` once, passes it to `BrowserScreen`, and calls
   `inspector.endSession()` in `onDestroy()` when the activity is finishing (not on rotation), so raw
   captured values do not outlive the session.

3. **`app/src/main/java/com/example/ui/BrowserViewModel.kt`** — adds one public property,
   `cookieScopeProvider: CookieScopeProvider`, which tells the inspector which URL to ask
   `CookieManager` about (the visible tab's page). No other behaviour changes.

4. **`app/src/main/java/com/example/ui/BrowserScreen.kt`** —
   * takes an `inspector: InspectorRuntime` parameter,
   * collects `inspector.uiState`,
   * opens/closes the inspector, wires the BackHandler (detail sheet first, then the panel),
   * reports the visible tab via `setCurrentTab`, keeps the ticker idle via `setScreensVisible`,
   * passes `inspector` and `inspectorState.scriptToken` down to `WebViewContainer`,
   * renders `NetworkInspectorScreen` above the browser when open.

5. **`app/src/main/java/com/example/ui/components/WebViewContainer.kt`** —
   * two new parameters (`inspector`, `inspectorScriptToken`),
   * the anonymous clients now extend `NetworkInspectorWebViewClient` / `NetworkInspectorWebChromeClient`
     (existing overrides still call `super` first; browser behaviour is unchanged),
   * registers `InspectorJsBridge` under `InspectorScripts.BRIDGE_NAME` when the inspector is
     available, and removes it in `onDispose`,
   * re-injects the page hooks after each navigation and whenever the settings version changes,
   * tells the inspector when the WebView is destroyed.

6. **`app/src/main/java/com/example/ui/components/ChromeOverflowMenu.kt`** — one optional callback,
   `onNetworkInspector: (() -> Unit)?`, and one menu item that is only rendered when that callback is
   non-null (the caller passes `null` in every build where the inspector is unavailable).

Nothing else is touched: no changes to the Room database, the repository, bookmarks, history,
automations, tabs, incognito handling, themes or existing tests.

## 8. Privacy and redaction model

* **Masking happens at capture time, not at render time.** `Redaction.headerField` decides the stored
  `display` string and whether the raw value is kept *at all*; there is no code path where an unmasked
  value is stored first and hidden later.
* **Sensitive by name**: `Authorization`, `Proxy-Authorization`, `Cookie`, `Set-Cookie`, `X-Api-Key`,
  `X-Csrf-Token`, and anything whose name contains `token`, `secret`, `password`, `credential`,
  `session`, `auth`, `apikey`, `signature`, … (see `Redaction.EXACT_SENSITIVE_HEADERS` and
  `SENSITIVE_HEADER_FRAGMENTS`).
* **Sensitive by position**: query parameters whose decoded name tokenises to `token`, `secret`,
  `password`, `sid`, `session`, `key`, `signature`, `samlresponse`, … are replaced with `••••••` in
  the stored URL, while the rest of the URL stays readable.
* **Text scrubbing** (console messages, error text, body previews) removes `Bearer`/`Basic`
  credentials, `user:password@` URL credentials, sensitive `key=value` pairs, JWTs and long opaque
  runs; anything that was changed is flagged `masked`/`scrubbed` in the UI.
* **Cookie values are never stored.** The Cookie Inspector keeps the name, scope, flags, expiry and a
  `<n> chars` summary. `CookieManager` remains the source of truth if the app itself needs a value.
* **Raw capture is opt-in and session-only.** The "Raw capture (reveal masked values)" switch keeps
  original values so an individual field can be revealed — it is never persisted, and
  `MainActivity.onDestroy` (and `endSession`) clears it when the browser is closed.
* **Reports never contain what safe mode masked**: the copy/share formatter re-resolves every field
  through the policy at render time (covered by a test).
* **Debug-only by construction**: with `NETWORK_INSPECTOR_ENABLED=false` (release) the capture code is
  unreachable and R8 can strip it.

## 9. Performance

Design rules, in the order they matter:

1. **Nothing is captured when the inspector is off.** `LiveNetworkObserver` reads one immutable
   `CapturePolicy` per callback (an `AtomicReference` read); a disabled inspector costs a boolean
   check per WebView callback and nothing else.
2. **No I/O, no locks and no formatting on the WebView threads.** Callbacks build a small observation,
   merge it into a bounded store and return. Correlation is a hash lookup plus a short scan of a small
   deque.
3. **Rate limits with accounting.** 120 messages/second and 100 console rows/second overall, 60 page
   batches/second, 120 records per batch, 400-record queue in the page script with drop counting.
   A page that hammers `fetch` or `console.log` cannot push the app into a busy loop; the drops are
   counted and shown in the banner instead of being silently swallowed.
4. **Bounded memory.** 400 network entries, 300 console rows, 64 cookie observations, 2 000-char body
   previews, 800-char header values, 2 000-char URLs.
5. **The UI only renders when it is on screen.** The publishing ticker sleeps unless an inspector
   screen is open, and publishes at most every 250 ms, and only when the snapshot revision, console
   count or settings version actually changed. Display strings (time, status, URL shortening) are
   pre-computed once per publish, so recomposition only draws strings.
6. **Cookie reads are off the UI thread** on a dedicated looper thread with a 1000 ms timeout, and only
   when the Cookie tab is visible or the cookie store actually changed.
7. **The injected JavaScript is deferred by construction**: it wraps the page's functions and does
   nothing until the page itself performs a network or console call, queues records and flushes them in
   batches over one bridge call, with a 25 ms debounce.

Measured cost on a debug build browsing ordinary pages is dominated by the page hook's wrapper
overhead (a few microseconds per `fetch`/XHR call) rather than by anything WebView-side.

## 10. Tests

Plain JVM unit tests (no Robolectric, no device) live in `app/src/test/java/com/example/devtools/`:

```
./gradlew :app:testDebugUnitTest --tests 'com.example.devtools.*'
```

* `RedactionTest` — headers, URLs, cookies, console text, surrogate-safe truncation.
* `UrlPartsAndFiltersTest` — URL parsing, resource classification order, status/category/method/body/
  redaction filters and free-text search.
* `CapturePipelineTest` — drives the real `LiveNetworkObserver`: pairing, orphan responses, safe-mode
  masking, error channels, `HTTP ERROR nnn` inference, page-JS merge, console merging and rate limits,
  ring-buffer eviction, ambiguity notes, `Set-Cookie` attribute capture, unobserved expiry, and the
  report formatter.

The existing `ExampleRobolectricTest` (app name "Chrome") and the automation store tests are
untouched. CI (`.github/workflows/android-apk.yml`) runs `assembleDebug` and `testDebugUnitTest`, which
is also what compiles the debug-only sources.

## 11. Optional extras

Both are deliberately *not* wired in, because each needs a dependency or app-level choice:

* **Instrument the app's own HTTP client.** `NetworkInspectorInterceptor` is an OkHttp interceptor that
  records requests and responses with complete headers, reason phrase, final URL and a measured
  duration — the only source that can show all of those. Add it while building the client:
  ```kotlin
  val client = OkHttpClient.Builder()
      .addInterceptor(NetworkInspectorInterceptor(inspector))
      .build()
  ```
  It is a pass-through when the inspector is disabled, it never reads bodies (that would consume the
  streams the app needs), and it labels its entries as coming from "app HTTP client" so they are never
  mixed up with WebView traffic. `CookieInspector.recordAppSetCookie(url, setCookieHeader)` is the
  matching hook for cookie attributes seen by that client.

* **Service-worker traffic.** Requests issued by a service worker bypass `WebViewClient` entirely.
  Covering them needs `androidx.webkit` (`ServiceWorkerControllerCompat.setServiceWorkerClient`) plus a
  version gate (`WebViewFeature.SERVICE_WORKER_*`), and the same approach can add
  `WebViewFeature.isFeatureSupported` reporting for the capability list. Until that is added, traffic
  from a service worker is simply not listed — the UI says so rather than inventing data.

* **WebSocket frames** cannot be observed through any public API. There is no plan to fake it.

## 12. Troubleshooting

| Symptom | Explanation |
| --- | --- |
| No "Network Inspector (debug)" menu item | Not a debug build, `NETWORK_INSPECTOR_ENABLED=false`, or the package is not debuggable. The Info tab (when reachable) and `DevToolsGate.unavailableReason()` state which condition failed |
| A request shows "Waiting for a response…" forever | WebView never called back for it (cache hit, service worker, or a renderer that died). After 20 s it becomes *unobserved*, not *failed* |
| Status/headers empty for a resource | Platform limitation for sub-resources — see §1. `onReceivedHttpError` covers 4xx/5xx; other statuses are simply not reported |
| Cookie attributes all "not available" | `CookieManager.getCookie(url)` returns `name=value` only. Attributes appear once the app observes a real `Set-Cookie` response header |
| Page's `fetch` calls missing | The hooks are injected after the document starts; requests fired before that (or from a service worker) are not logged. Reloading the page with the inspector open usually shows them |
| Console empty | `console.debug`/`console.info` need the "verbose" switch; also check the console filter |
| Nothing at all is captured | Check the `ENABLED` switch on the Info tab, then the four gate conditions in §3 |
| Release APK: is any of this still there? | The UI code is present but unreachable (`NullInspectorRuntime`), no bridge is added to pages, and the capture engine is only reachable through the debug `Application` subclass, which is not compiled into release builds |
