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
2. [How to use it](#2-how-to-use-it) — including [revealing values](#2b-revealing-values) and
   [downloading everything](#2c-downloading-everything)
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
| Failures the page cannot report | yes | recorded by the app itself: `onRenderProcessGone` (and whether the renderer *crashed* or was *killed for memory*), `onReceivedSslError`, main-frame `onReceivedError`/`onReceivedHttpError`, and a load watchdog for a document that never finishes. Each becomes a FAILED row plus a console line tagged *inferred by the inspector*, because in these cases the page never ran far enough to say anything |
| Service-worker traffic | no | requests issued by a service worker never reach `WebViewClient`. Would need `androidx.webkit`'s `ServiceWorkerControllerCompat` (see §11) |
| WebSocket (`ws:`/`wss:`) frames | no | WebView exposes no API for them at all |
| Cache hits / preflight requests | no | not reported; a request that never produces a callback is marked *unobserved*, not *failed* |
| Anything about encrypted payloads | no | the inspector never touches TLS; it only sees what the platform already decrypted and handed to the app |

Both the in-app **Info** tab and the shared report repeat this list, so a reader of a report knows
exactly which fields were observed and which were unavailable.

## 2. How to use it

1. Build and install a debug build (`./gradlew :app:installDebug`).
2. Open the browser, tap the three-dot menu → **Network Inspector (debug)**.
3. Tabs: **Network**, **Cookies**, **Console**, **Info**. The **Download** arrow in the header
   (and the two buttons on the **Info** tab) writes the whole session to a file — see
   [Downloading everything](#2b-downloading-everything).
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

### 2b. Revealing values

Debug builds start with **"Reveal sensitive values" ON**: Authorization/Cookie headers, tokens in
URLs, cookie values, request and response body previews and console text are stored exactly as
observed. Rows that hold a normally-sensitive value are tagged **unmasked** so you can see at a glance
what you are looking at, and a red banner sits above the tabs for as long as the switch is on.

Switch it off on the **Info** tab to go back to masking: sensitive values are then replaced *before*
they are stored, so they cannot be recovered from the buffer, the UI or an export. Rows captured in
masking mode keep the masked form and say so — re-capture (reload the page) to see them in the clear.

### 2c. Downloading everything

One tap on either button writes the entire session to a real file:

| Where | What happens |
| --- | --- |
| Android 10 (API 29) and newer | Written straight into `Downloads/NetworkInspector/` through `MediaStore`. Visible to any file manager, `adb pull`, or the Downloads app. A snackbar offers **Open**. |
| Android 9 and older | The system save dialog (`ACTION_CREATE_DOCUMENT`, defaulting to Downloads) asks where to put it, because writing to public Downloads there would need `WRITE_EXTERNAL_STORAGE` — which this app does not request. |

Two formats, same content: **Save report (.txt)** is organised for reading (per-entry sections with
every field and its provenance, then console and cookies); **Save data (.json)** is the same data as
structured JSON for offline analysis, including each value's `source` (observed / inferred / not
available).

Both include every entry in the ring buffer, the console, the cookies, all settings, and the drop
counters — and the file starts with a warning plus the capture mode, so a file that contains real
tokens cannot be mistaken for a masked one. No permission is added for any of this.

The header also carries two lines that only matter when something went wrong, and then matter a lot:

| Line | Why it is there |
| --- | --- |
| `Session start:` / `Covers:` | The buffers are memory-only, so they hold nothing from before the process started. At the moment a site is busiest, Android is most likely to have killed and restored the process — and a file covering only the last 40 seconds otherwise looks identical to a session where nothing went wrong. These lines say how much time the file really covers |
| `Incidents:` | Counts the failures the **app** witnessed that the page could not report: renderer killed, TLS certificate refused, main-frame HTTP error, main-frame load failure, load watchdog timeout. Each has a row in the network list and a line in the console. A failure that reaches the app through two channels is counted once |

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

Downloads need no permission either: on API 29+ the app inserts into `MediaStore.Downloads` (scoped
storage), and below that it uses the Storage Access Framework, where the *user* grants access to one
chosen document. The app never receives broad storage access at any point.

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
| `InspectorReport.kt` | Text form of a single entry (detail screen Copy/Share, export sections) |
| `InspectorExport.kt` | Renders the whole session as a `.txt` report or a `.json` document |
| `InspectorDownload.kt` | Writes an export to `Downloads/NetworkInspector/` (API 29+) or through the system save dialog |
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
| `app/src/test/java/com/example/devtools/CapturePipelineTest.kt` | End-to-end correlation tests against the real `LiveNetworkObserver` |
| `app/src/test/java/com/example/devtools/InspectorExportTest.kt` | Export formats, JSON round-trip, and the no-leak guarantee |
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
   * renders `NetworkInspectorScreen` above the browser when open, passing
     `onBuildExport = { format -> inspector.buildExport(format) }`.

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

Two modes, one switch (`Reveal sensitive values`, `InspectorSetting.FULL_CAPTURE`):

| | Full capture ON (debug default) | Full capture OFF |
| --- | --- | --- |
| Sensitive headers | stored and shown verbatim, tagged `unmasked` | replaced with `•••••• (n chars)` before storage |
| Sensitive query parameters | shown verbatim | value replaced with `••••••` in the stored URL |
| Cookie values | stored as sent, for `Set-Cookie` rows and `CookieManager` rows | `<n> chars` summary only |
| Body previews | stored verbatim (size-capped) | scrubbed with `Redaction.scrubText` |
| Console text | stored verbatim (size-capped) | scrubbed |
| Exports | contain real values, with a warning header | cannot contain what was never stored |

Neither mode is reachable in a release build, and turning the switch off only affects what is captured
**from then on**: rows already in the buffer keep the form they were captured in, and the detail screen
says so rather than implying the value was never observed.

* **Masking (when enabled) happens at capture time, not at render time.** `Redaction.headerField`
  decides the stored `display` string and whether the raw value is kept *at all*; in masking mode there
  is no code path where an unmasked value is stored first and hidden later.
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
* **In masking mode, cookie values are never stored** — only a `<n> chars` summary. `CookieManager`
  remains the source of truth if the app itself needs a value.
* **Exports are honest about what they contain.** They are built from what is in the buffer, state the
  capture mode at the top, and carry the full-capture warning when relevant. A masked row cannot leak
  through an export because the value was never stored (covered by a test that greps both formats for
  the secrets it fed in).
* **Captured data is dropped when you leave the browser.** `MainActivity.onDestroy` (on a real finish,
  not on rotation) calls `endSession()`, which clears the buffers so tokens are not left sitting in a
  process nobody is watching. Export first if you want to keep something.
* **Nothing is ever sent anywhere.** There is no telemetry, no upload and no background service: the
  inspector only reads what the WebView in this process hands it, and writes files only where the user
  asks.
* **Debug-only by construction**: with `NETWORK_INSPECTOR_ENABLED=false` (release) the capture code is
  unreachable and R8 can strip it. Because full capture can put real credentials on screen and into a
  file, that gate is the feature's safety boundary — not the masking switch.

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
* `CapturePipelineTest` — drives the real `LiveNetworkObserver`: pairing, orphan responses, both
  capture modes (verbatim vs masked, per field), error channels, `HTTP ERROR nnn` inference, page-JS
  merge, console merging and rate limits, ring-buffer eviction, ambiguity notes, `Set-Cookie` attribute
  capture, unobserved expiry, and the report formatter.
* `InspectorExportTest` — the export formats: full-session coverage, JSON structure, an empty buffer,
  file naming, and the guarantee that a masked capture cannot leak through either format.

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
| Console empty | `console.debug`/`console.info` need the "verbose" switch; also check the console filter. If the page never ran — a blocked navigation, a refused certificate, a killed renderer — there is no page console to capture, and the app's own diagnosis rows (tagged *inferred by the inspector*) are what remains |
| The page went blank and reloaded itself | The WebView renderer died. The inspector now records one FAILED row saying whether it **crashed** (a fault in the page) or was **killed by the system to reclaim memory** (a heavy page on a low-RAM device), plus how many requests were still in flight. The browser also shows this as a message instead of failing silently |
| Blank page, nothing in the console, no HTTP status | Look for a `TLS/SSL error` row. WebView cancels the load when it rejects a certificate, so no response is ever delivered. This app never calls `SslErrorHandler.proceed()` — it reports the cause instead of bypassing it |
| Spinner runs forever, nothing recorded | The document stalled and WebView has no navigation timeout. After 45 s the load watchdog reports it in the console and as a browser message. It never cancels the load: a slow page that is still working is allowed to finish |
| `HTTP 403`/`503` on the main document | The server rejected the navigation itself, so the page's JavaScript never started — which is why an otherwise busy session can export with **zero** console lines and no page-hook rows. The console now carries one `HTTP nnn on the main document …` line explaining exactly that |
| Automation stops with "element was not found" on a slow site | One step now waits up to 15 s for its element instead of ~2.4 s, and a click waits up to 2.5 s for a navigation to start instead of sampling once at 350 ms. Both were tuned for an idle page and misread a loaded one as broken |
| `Access Denied`, `errors.edgesuite.net`, or a `Reference #…` on the page | The site's CDN/access-control layer refused this client — see §12c. The console carries the same reference, and the message offers **Open externally** to hand the URL to a real browser |
| Nothing at all is captured | Check the `ENABLED` switch on the Info tab, then the four gate conditions in §3 |
| Everything shows `•••••• (n chars)` | "Reveal sensitive values" was off when those rows were captured. Turn it on and reload the page |
| The export file is empty / nothing happens | The inspector is switched off or nothing has been captured yet; the toast says which |
| I cannot find the exported file | Android 10+: `Downloads/NetworkInspector/`. Older: wherever you chose in the save dialog |
| Release APK: is any of this still there? | The UI code is present but unreachable (`NullInspectorRuntime`), no bridge is added to pages, and the capture engine is only reachable through the debug `Application` subclass, which is not compiled into release builds |

### 12b. Why a site can work off-peak and fail at peak

A very common report is "the browser is fine all day, then it dies completely at the moment the site
gets busy". Comparing an off-peak export with a peak one makes the cause readable in a few lines:

| In the export | Off-peak | Peak | What it means |
| --- | --- | --- | --- |
| `Entries` | hundreds | few dozen | the page never got far enough to make its usual calls |
| `Console lines` | many | **0** | the page's JavaScript never started, so there was nothing to log |
| Rows tagged `injected JS hook` | present | **absent** | the `fetch`/XHR hooks were never reached — the document itself failed |
| Main-document status | 200 | 403 / 503 | the server or its CDN rejected the navigation outright |
| `TLS/SSL error` row | — | sometimes | the handshake was refused and the load cancelled |
| `renderer process was killed … to reclaim memory` | — | sometimes | the device ran out of RAM on a heavy page |

Read together, "few entries + zero console lines + no page-hook rows" is not a broken inspector. It is
the signature of a navigation that never produced a working document: the server refused it, the TLS
handshake was rejected, the renderer was killed, or the response never arrived at all. Each of those
now leaves its own row and console line, so the distinction is written down instead of having to be
guessed.

That comparison no longer has to be made by hand: the export header prints `Covers:` (how much time
the file really holds, which exposes a session truncated by a process restart) and `Incidents:` (the
count of each failure the app witnessed). On the 08:12 export above, those two lines are the whole
diagnosis.

What this feature deliberately does **not** do is anything about it. A 403 from a site's anti-bot or
rate-limiting layer is that site's decision, and this inspector neither evades nor works around it —
no fingerprint or user-agent spoofing, no challenge solving, no retry storm. It reports the status,
the headers the platform exposed, and any reference or tracking identifier the server returned, so
the failure can be understood and raised with the site's owner. See §1 and the top of this document
for the scope this tool keeps itself to.

### 12c. "Access Denied" from the site's CDN, and why only this browser

The page itself, as a user sees it:

```
Access Denied
You don't have permission to access "http://www.irctc.co.in/" on this server.
Reference #18.b60e0317.1791437529.de1b38a3
https://errors.edgesuite.net/18.b60e0317.1791437529.de1b38a3
```

`errors.edgesuite.net` and the `Reference #` are an access-control layer's own rejection page
(Akamai, in this case); the reference is the id of the rule and edge node that refused the request.
The same id travels on the 403 response as `x-reference-error` / `akamai-grn`, and that is what the
inspector matches on: when it sees those marks on a main-frame 403/405/429 it records an
*access-control rejection by the site's CDN* incident and a console line quoting the reference, so
the file says what happened instead of showing a bare status.

**Why this app and not Chrome, from the same device at the same minute.** Rules of this kind key on
client properties, and an embedded WebView announces itself differently from any standalone browser:

* its User-Agent carries `; wv` and `Version/4.0`, which only a WebView sends;
* its client-hint brand is literally `"Android WebView"` in `sec-ch-ua`, never a browser brand;
* its TLS handshake fingerprint is the WebView stack's, not a browser's.

**Why only at peak.** These rules are commonly armed, or their thresholds lowered, exactly while the
site is under load. Off-peak the same client sits inside the tolerated band; at 08:00 or 11:00 it does
not. That is a server-side policy decision about a *class of client* — not a fault in the app, in the
network, or in the page, which is why "it works in Chrome" and "it worked at midnight" are both true
at once.

**What this app will not do about it.** Presenting another browser's User-Agent or client hints would
be an attempt to defeat that decision, and §1 rules it out. It would also not work: the client-hint
brand and the TLS fingerprint are generated by the engine and the network stack, not by the
User-Agent string, so the layer would still see a WebView. The two productive paths are the ones the
app offers — quote the reference to the site's owner, or use the **Open externally** action on the
failure message to hand the very same URL to a real browser, which the site accepts on its own terms.

---

## 13. Status under the embedded Gecko engine

`docs/ENGINE_MIGRATION.md` §12 has the full list; this is the inspector-specific part. The app can
render with either engine, and the two engines expose different things to an embedder — so the
inspector has **two capture layers**: the WebView client's (`shouldInterceptRequest` and friends) and,
for Gecko, the bridge extension's background script, which uses the WebExtension APIs Gecko grants
its extensions (`webRequest`, `webRequestBlocking`, `cookies`). The panels, the export, the masking and
the settings are identical either way; only the source of the observations differs.

| Panel / data | WebView engine | Gecko engine |
| --- | --- | --- |
| Document loads (`DocumentStarted` / `DocumentFinished` / `DocumentLoadTimeout`) | yes | **yes** — fed from `onPageStart` / `onPageStop` / `onLocationChange` and the same load watchdog |
| Load failures, with the engine's own code and category | yes (`WebResourceError`) | **yes** — `WebRequestError.code` and `.category` are more specific than WebView's description; recorded as `FailureObservation` |
| Renderer death | `onRenderProcessGone` | **yes** — `onCrash` / `onKill` (reported as `isRendererProcessDeath`) |
| Uncaught page errors | console capture | **yes** — the bridge content script reports `error` / `unhandledrejection` |
| Other console output (`console.log`, warnings) | yes | **no** — a content script runs in an isolated world and cannot read the page's console |
| Per-request records (URL, method, timing) | yes (`shouldInterceptRequest`) | **yes** — `webRequest` sees every request on the wire |
| Complete request/response header sets | subset only | **yes, complete** — `webRequest` reports the wire headers, including the ones the platform adds (Cookie, User-Agent, Sec-*) |
| HTTP status for every response | 4xx/5xx and intercepted responses only | **yes, every response** — including 2xx/3xx |
| Redirect chains | no | **yes** — every hop is recorded with its status and target |
| Request bodies (fetch/XHR/sendBeacon) | yes (page JS hooks) | **no** — `webRequest` reports requests, not bodies, and the page hooks cannot run in a Gecko content script's isolated world |
| Response body previews | responses the app fetches itself | **yes, for text-like resources** (documents, scripts, stylesheets, XHR) — streamed via `webRequest.filterResponseData`, capped at 8 KB, gated on "Capture response body previews"; binary resources are never previewed as text |
| Network-level errors per request | yes (`onReceivedError`) | **yes** — `webRequest.onErrorOccurred` (the `NS_ERROR_*` text) |
| HTTP error statuses, SSL incidents, CDN access-control incidents | yes | **yes** — statuses and headers come from the same capture, so the `x-reference-error` / `akamai-grn` detection in §12c fires under Gecko too |
| Cookie jar listing (`CookieInspector`) | yes (`CookieManager.getCookie`, values without attributes) | **yes, with full attributes** — the extension's `cookies` API reports domain, path, Secure, HttpOnly, expiry and SameSite for every cookie in the engine's store, pushed at startup and on every `cookies.onChanged` |
| Traffic from private (incognito) tabs | yes | **no** — the extension is not allowed in private browsing, so private tabs produce no capture (and the automation recorder does not run there either) |
| Anything at all in a release build | no | **no** — the inspector is `NullInspectorRuntime`, so the extension attaches no listeners at all |

**How the Gecko capture reaches the inspector.** `assets/browserbridge/background.js` assembles one
record per request (headers, status, redirect hops, timing, body preview, errors) and pushes it over
native messaging on a dedicated channel (`browserbridge-net`, separate from the per-tab content-script
ports). `GeckoNetworkCapture` maps each record to the app's tab (by the initiating document's URL),
hands it to the same `LiveNetworkObserver` the WebView path feeds, and answers the extension's
settings pull — the extension attaches its listeners only while capture is on, so an idle debug build
pays nothing, and a release build attaches none. **Values cross the boundary raw and are masked at
capture time in the observer**, exactly as the WebView path is, so "Reveal sensitive values" behaves
identically under both engines. Masking in two places would be a bug; there is one place.

**For the full Network/Console/Storage panels under Gecko, the engine's own tools still apply.** Debug
builds enable Gecko remote debugging (`GeckoRuntimeSettings.remoteDebuggingEnabled(BuildConfig.DEBUG)`),
so desktop Firefox DevTools can attach to the running engine:

```
adb forward tcp:6000 localfilesystem:/data/data/com.aistudio.chromebrowser.vktpnx/firefox-debugger-socket
```

then *about:debugging → This Firefox → Connect* with host `localhost:6000`. That is the engine's real
Network, Console, Storage and DOM panels — live, with WebSocket frames and service-worker fetches,
which the in-app inspector does not show under either engine. The in-app inspector remains the tool for
exporting a session to a file with the app's own redaction and provenance model; the remote debugging
socket is the tool for looking *while* the page is in front of you.

**The app's own incident narration works under Gecko.** The CDN access-control incident in §12c is
detected from `x-reference-error` / `akamai-grn` marks on responses, which the `webRequest` capture
now provides under Gecko as well. Nothing about the "why this app and not Chrome" reasoning changes: a
Gecko client is no more the standalone Chrome that a client-keyed rule expects, and the app still
refuses to impersonate one — the **Open externally** action remains the honest escape hatch.

**What the Gecko engine still cannot show the in-app inspector** (the export's capability table says
the same, under "WHAT THE GECKO ENGINE LETS THIS APP OBSERVE"): request bodies, `console.log` output,
WebSocket frames, service-worker fetches, and private-tab traffic. Each of those is a Gecko/extension
boundary, not a missing feature of the app: Gecko's `webRequest` does not expose request bodies,
content scripts live in an isolated world, WebSocket frames and service-worker fetches never reach
`webRequest`, and the extension is not allowed in private browsing.

### 13a. If the Gecko capture is empty: read CAPTURE SOURCE first

An empty NETWORK tab under Gecko means the capture pipeline stopped somewhere, and the export now
says where. Every export carries a **CAPTURE SOURCE** section (text and JSON) with one line per
pipeline stage:

| Line | Meaning when it is wrong |
| --- | --- |
| `Installed extension version` vs `Bundled extension version` | The profile still runs an older copy of the bridge extension — an in-place APK upgrade keeps the profile, so the new background script is not installed. The app detects this and forces one reinstall; if both lines still differ, clear the app's data once or reinstall. |
| `Background-message delegate registered: no` | The app never registered the delegate that receives the extension's messages. |
| `Capture settings pulls answered: 0` | The extension's settings pull never reached the app (or the app never started the Gecko engine). |
| `Network records received: 0` (with pulls > 0) | The extension is not attaching listeners — check the extension's own report below the counters. |
| `permission webRequest: false` (in the extension's report) | Gecko did not grant the built-in extension the `webRequest` permission — the capture cannot work at all. |
| `listener error: …` (in the extension's report) | Attaching the listeners threw; the message says why. |
| `manifest version running` | What the extension believes it is; compared with the bundled version above. |

The same events are logged under the `GeckoCapture` tag, so `adb logcat -s GeckoCapture` shows the
pipeline live. The counters are process-wide: they survive an activity recreation, so an export
after a rotation still describes the whole session.
