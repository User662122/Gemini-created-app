# Replacing the WebView browsing engine with an embedded Chromium — inspection, mapping, plan, and feasibility verdict

Status: **plan only — no code has been changed.** The brief says to stop and explain if full
Chromium embedding is not feasible with this project's build environment, so that is what this
document does, with the evidence and the API mapping attached so the decision can be made on facts.

---

## 1. What was inspected

Whole project: 70 Kotlin files (61 in `app/src/main`), 16,226 lines, plus `app/build.gradle.kts`, `AndroidManifest.xml`,
`libs.versions.toml`, the Gradle wrapper properties and `.github/workflows/android-apk.yml`.

Build facts that matter:

| Fact | Value |
| --- | --- |
| `minSdk` / `targetSdk` / `compileSdk` | 24 / 36 / 36.1 |
| Build tool | AGP 9.1.1, Kotlin 2.2.10, Gradle 9.3.1, Compose BOM 2024.09.00 |
| Native build (NDK / CMake) | **not configured at all** — no `externalNativeBuild`, no `.so` in `jniLibs` |
| Verification | GitHub Actions `ubuntu-latest`, `gradle :app:assembleDebug` + `:app:testDebugUnitTest`, `timeout-minutes: 40` |
| Local verification in this sandbox | **impossible** — no JDK, no Android SDK, and the sandbox network allowlist covers only github.com / codeload / api.github.com / npm / PyPI, so `dl.google.com`, `maven.google.com`, `chromium.googlesource.com` and `cef-builds.spotifycdn.com` are unreachable here |

---

## 2. The current engine: complete WebView inventory

The engine is `android.webkit.WebView`, created in exactly one place.

**Core**

| File | Lines | Role |
| --- | --- | --- |
| `ui/components/WebViewContainer.kt` | 817 | The engine itself: creates the `WebView`, `WebSettings` block, `WebViewClient`, `WebChromeClient`, two JavaScript bridges, find-in-page, automation playback, load watchdog, lifecycle/destroy |
| `ui/BrowserScreen.kt` | 393 | Hosts `WebViewContainer` inside the Compose scaffold (one `WebView` per tab; other tabs hold state, not views) |
| `ui/BrowserViewModel.kt` | 770 | Tab model, navigation triggers, history/bookmarks; `android.webkit.URLUtil` for URL detection; `cookieScopeProvider` |
| `data/model/BrowserTab.kt` | — | Tab state: `url`, `canGoBack`, `canGoForward`, `isLoading`, `isDesktopSite`, `navigationTrigger` |

**WebView-specific devtools layer (debug builds only)**

| File | Lines | WebView API used |
| --- | --- | --- |
| `devtools/NetworkInspectorWebViewClient.kt` | 279 | `WebViewClient.shouldInterceptRequest`, `onReceivedHttpError`, `onReceivedSslError`, `onRenderProcessGone`, `HttpAuthHandler` |
| `devtools/NetworkInspectorWebChromeClient.kt` | 90 | `WebChromeClient.onConsoleMessage`, `onReceivedTitle`, `ConsoleMessage.MessageLevel` |
| `devtools/CookieInspector.kt` | 235 | `android.webkit.CookieManager.getCookie(url)` |
| `devtools/InspectorJsBridge.kt` + `InspectorScripts.kt` | 567 | `addJavascriptInterface` bridge + injected page hooks |
| `devtools/WebViewThreads.kt` | 40 | WebView threading assumptions |
| `docs/NETWORK_INSPECTOR.md` | — | Documented the WebView observability model |

**Exact WebView API surface in use** (from a symbol census over `app/src/main`):

| API | Uses |
| --- | --- |
| `WebView` | 134 |
| `CookieManager` | 30 |
| `WebViewClient` | 10 |
| `WebView.evaluateJavascript` | 5 |
| `WebChromeClient` | 5 |
| `SslErrorHandler` | 5 |
| `WebSettings` | 4 |
| `@JavascriptInterface` | 4 |
| `addJavascriptInterface` | 2 |
| `setFindListener` | 1 |
| settings touched | `javaScriptEnabled`, `domStorageEnabled`, `databaseEnabled`, `cacheMode`, `mixedContentMode`, `userAgentString`, `useWideViewPort`, `loadWithOverviewMode`, `builtInZoomControls`, `displayZoomControls`, `setSupportZoom` |

**Not present anywhere** (important — the brief lists these as "existing functionality"): there is no
`DownloadListener`/`setDownloadListener`, no `WebChromeClient.onShowFileChooser`, no
`onPermissionRequest`, no `onCreateWindow`/`setSupportMultipleWindows`, no `onJsAlert/Confirm/Prompt`,
and no browser-level download UI. The overflow menu is: New tab, New incognito tab, History,
Bookmarks, Share, Find in page, Desktop site, Network Inspector (debug), Automations, Settings. The
only download code in the project is `devtools/InspectorDownload.kt`, which saves inspector exports
to public Downloads via `MediaStore`.

---

## 3. The brief's "functionality to preserve" — what actually exists today

| Feature | Today | Reality |
| --- | --- | --- |
| JavaScript, cookies, DOM storage, cache, HTTPS, redirects | yes | all via WebView defaults |
| Popups / new tabs | **no** | no `onCreateWindow`; `target=_blank` does nothing |
| Permissions | **no** | no `onPermissionRequest`; geolocation/camera/mic prompts never reach a handler |
| File uploads | **no** | no `onShowFileChooser`; `<input type=file>` cannot open a picker |
| Downloads | **no** | no `DownloadListener`; tapping a download link does nothing |
| Clear browsing data (Settings) | partial | `clearBrowsingData()` only wipes the Room history table — it never clears cookies, cache or storage |
| Desktop site | partial | sets a Chrome UA string on a WebView (see §7, note 3) |
| Back/Forward/Reload/Stop/Find/Tabs/Bookmarks/History/Omnibox/Settings UI | yes | app-owned Compose UI, engine-independent |

So the migration's *preservation* surface is genuinely engine-coupled in only two places:
`WebViewContainer.kt` and the two devtools clients. Everything else is app state and Compose UI that
does not care which engine renders.

---

## 4. WebView API → Chromium equivalent (the mapping the brief asks for)

Two possible Chromium hosts are named below: **`//content`** = an in-tree embedder built from the
Chromium source tree (what `content_shell` is), and **CEF** = the out-of-tree Chromium embedder
(desktop platforms only, see §7). Where neither exposes an equivalent, that is stated plainly.

| WebView API (current use) | `//content` equivalent | CEF equivalent |
| --- | --- | --- |
| `WebView` view | `content::WebContents` + `org.chromium.content_public.browser.ContentView` / `RenderWidgetHostViewAndroid` on a `SurfaceView` | `CefBrowser` (Alloy runtime) on a `SurfaceView` |
| `shouldOverrideUrlLoading` (non-http schemes → external app) | `NavigationThrottle` / `WebContentsDelegate::OpenURLFromTab` | `CefRequestHandler::OnBeforeBrowse` |
| `onPageStarted` / `onPageFinished` | `WebContentsObserver::DidStartLoading` / `DidFinishNavigation` / `DidStopLoading` | `CefLoadHandler::OnLoadingStateChange`, `OnLoadEnd` |
| `onProgressChanged` | `WebContentsObserver::DidChangeLoadProgress` | `CefDisplayHandler::OnLoadingProgressChange` |
| `onReceivedError` | `DidFailLoad` + `net::Error`, `net::ErrorToString` | `CefLoadHandler::OnLoadError` |
| `onReceivedHttpError` | `DidReceiveResponse` + `net::HttpResponseHeaders` | `CefRequestHandler::OnResourceResponse` |
| `onReceivedSslError` (cancel, explain) | `DidFailLoad` with `ERR_CERT_*` | `CefRequestHandler::OnCertificateError` |
| `onRenderProcessGone` (crash vs. OOM kill) | `RenderProcessHostObserver::RenderProcessExited` | `CefRequestHandler::OnRenderProcessTerminated` |
| `onConsoleMessage` | `WebContentsObserver::OnDidAddMessageToConsole` | `CefDisplayHandler::OnConsoleMessage` |
| `onReceivedTitle` | `WebContentsObserver::TitleWasSet` | `CefDisplayHandler::OnTitleChange` |
| `onJsAlert/Confirm/Prompt` (unused) | `JavaScriptDialogManager` | `CefJSDialogHandler` |
| `WebSettings.javaScriptEnabled` | Blink `WebPreferences` via `WebContents::GetOrCreateWebPreferences` | `CefBrowserSettings.javascript` |
| `domStorageEnabled`, `databaseEnabled` | not a per-view toggle — Blink storage + `StoragePartition` in the browser process | `web_storage` settings |
| `cacheMode` / incognito | off-the-record `StoragePartition` | `CefRequestContext` (OTR) |
| `mixedContentMode` | `WebPreferences.allow_mixed_content` | `CefBrowserSettings` mixed-content flags |
| `userAgentString`, `useWideViewPort`, `loadWithOverviewMode`, zoom | `WebPreferences` + `blink::WebSettings` | `CefBrowserSettings`, `CefRequest` headers |
| `addJavascriptInterface` (automation bridge, inspector bridge) | **no Java-object injection exists.** The supported paths are Mojo JS bindings or `postMessage` to a native message handler | `CefMessageRouter` (origin-checked, async) or `CefV8Handler` / `CefRegisterExtension` |
| `evaluateJavascript` | `RenderFrameHost::ExecuteJavaScript` / isolated-world variant with result callback | `CefFrame::ExecuteJavaScript` |
| `findAllAsync` / `findNext` / `clearMatches` / `setFindListener` | `blink::mojom::FindInPage` over a `RenderFrameHost` | **no API** — needs an in-page `window.find()` fallback |
| `CookieManager.getCookie` | `network::mojom::CookieManager` from the tab's `StoragePartition` (**full attributes**: Domain, Path, Secure, HttpOnly, Expiry) | `CefCookieManager` (Chromium's SQLite cookie store) |
| Clear site data / cache | `StoragePartition::ClearData` / `BrowsingDataRemover` | `CefCookieManager::DeleteCookies`, `CefRequestContext::ClearHttpCache` |
| `setDownloadListener` (absent today) | `download::DownloadManager` + `DownloadManagerDelegate` | `CefDownloadHandler` |
| `onShowFileChooser` (absent today) | `WebContentsDelegate::RunFileChooser` + `FileSelectHelper` | `CefDialogHandler::OnFileDialog` |
| `onPermissionRequest` (absent today) | `PermissionControllerDelegate` / `PermissionRequestManager` | `CefPermissionHandler` |
| `onCreateWindow` (absent today) | `WebContentsDelegate::AddNewContents` + a new `WebContents` in the same `BrowserContext` | `CefLifeSpanHandler::OnBeforePopup` |
| Back/forward list, `saveState`/`restoreState` | one `WebContents` + `NavigationController` per tab (no public serializable entry list) | `CefBrowser::GoBack/GoForward` (no serializable list either) |
| `WebView.destroy()` | `WebContents::Close` → `WebContentsDestroyed` | `CefBrowserHost::CloseBrowser` |
| Tab management | one `WebContents` (+ hidden `ContentView`) per tab, swapped on screen | one `CefBrowser` per tab |
| Networking | `StoragePartition` → Chromium network service (HTTP/2, HTTP/3/QUIC, BoringSSL, Chromium DNS/cache/cookies) | same, through `CefRequestContext` |
| History / Bookmarks / Downloads / Settings / Omnilbox UI | **no Chromium equivalent is embeddable** — `HistoryService`, `BookmarkModel` and the omnibox live in `//chrome`, above the content layer. Keep the existing Room + Compose code. | same |

Two consequences worth stating explicitly, because they are *wins* and they are what a real embedder
buys you: the inspector stops guessing about cookie attributes (it gets Domain/Path/Secure/HttpOnly/
Expiry from Chromium's own cookie store — today `docs/NETWORK_INSPECTOR.md` has to say "not available
to third-party apps"), and downloads, file chooser, permissions and popups become implementable at
all (they are not implementable through WebView without the same hooks; today they simply don't exist).

---

## 5. Target architecture

```
Compose browser UI  (address bar, tabs, back/forward, reload/stop, history,
                     bookmarks, downloads, settings, find-in-page)
        |  engine-neutral interface: BrowserSurface + EngineCallbacks
        v
Chromium integration layer   (one per engine implementation)
   - ChromiumEngine : WebContents/ContentView  |  CefEngine : CefBrowser
   - navigation, load state, errors  ->  app callbacks
   - bridges via Mojo / CefMessageRouter instead of addJavascriptInterface
   - downloads, file chooser, permissions, popups, find-in-page
        v
Chromium  ->  Blink (rendering/layout)  +  V8 (JS)  +  Chromium network service
              + StoragePartition (cookies, DOM storage, HTTP cache)
```

The UI layer already satisfies this shape: it depends on `BrowserTab` state + callbacks, not on
WebView types — **with one exception**: `WebViewContainer.kt` itself. That file is the seam.

---

## 6. Phased plan (gated exactly as the brief requires)

**Phase 0 — this document.** No code changes.

**Phase 1 — introduce the seam (safe, verifiable on CI today).**
Add `ui/engine/BrowserSurface.kt`: a platform-agnostic interface (load/reload/stop/goBack/goForward/find/
evaluate/navigate + a callback set matching today's `onPageStarted`/`onPageFinished`/`onProgressChanged`/
`onPageEvent`/`onNavigationStateChanged`/`onFindMatchesChanged`). Move `WebViewContainer.kt`'s body into
`ui/engine/webview/WebViewEngine.kt` implementing it, keeping behaviour byte-for-byte. `BrowserScreen`
talks to the interface only. Nothing is deleted; the WebView path stays the only implementation and the
app behaves identically. This is the step that makes Phase 3 possible without rewriting the app.

**Phase 2 — stand up the Chromium embedder itself (out of tree; cannot be done in this repo's CI).**
Either clone `//chromium/src` with `depot_tools` and add an embedder target derived from
`content_shell_apk`, or (desktop-only CEF) build a `libcef` client. Publish the result as an AAR +
per-ABI `.so` + `.pak`/ICU resources to a Maven repo or as a GitHub Release asset **fetched by a Gradle
task** — never committed to Git (the session's artifact cap is ~128 MB; one ABI of Chromium resources
plus `libchrome.so` alone exceeds it, and four ABIs are several times that).

**Phase 3 — second engine implementation behind the Phase 1 interface.** `ChromiumEngine` backed by
`ContentView`/`CefBrowser`, wiring every row of §4. Feature-flagged; the WebView engine remains the
default and is still fully present and working.

**Phase 4 — parity run.** Both engines side by side, one tab each, against the brief's checklist:
JavaScript, cookies, DOM storage, cache, HTTPS, redirects, file uploads, downloads, popups/new tabs,
permissions, plus the existing inspector and automation features.

**Phase 5 — cut over, then delete.** Only after the Chromium engine builds, installs and passes Phase 4
on a real device/emulator: flip the default, keep the WebView engine for one release as a fallback, then
delete `ui/engine/webview/**`, the two `NetworkInspector*Client` classes, the `addJavascriptInterface`
bridges and the `android.webkit` imports, and rewrite §7 of `docs/NETWORK_INSPECTOR.md`.

Work in Phases 1 and 5 is ordinary Kotlin and is verifiable by this project's CI. **Phase 2 is the
gate, and it is not executable here** — see §7.

---

## 7. Feasibility verdict: the blocker

**Full Chromium embedding is not feasible with the current Android project/build environment.** Not
"hard" — structurally impossible in this repository as configured, for four independent reasons.

1. **Chromium publishes no embeddable Android library, by design.** A Chromium maintainer's answer to
   exactly this question (chromium-dev, Jan 2026) is: *"No, there's no support for this… Android WebView
   is the only project built from Chromium which is used as a library, but it's designed to be used
   specifically as a library by the Android OS/framework itself… Whatever you do, there is no way to
   continue using existing app code which is written to use the Android WebView APIs."* The recommended
   route is to fork Chromium and add your own embedder inside its own build system. There is no AAR, no
   Maven artifact and no supported `//content` consumption path for third-party apps.

2. **Building Chromium does not fit this build environment.** The official Android build instructions
   require Linux, ≥8 GB RAM (16 GB+ recommended) and **≥100 GB free disk space**, a `depot_tools`
   checkout of ~30 GB, and a multi-hour `autoninja` build. This project's CI is a GitHub-hosted
   `ubuntu-latest` runner — 4 vCPU, 16 GB RAM, ~14 GB free disk space — with a **40-minute** job timeout,
   and this sandbox has no JDK, no Android SDK and cannot even reach `chromium.googlesource.com`. Building
   `content_shell_apk` or `chrome_public_apk` is off the table by two orders of magnitude on disk alone.

3. **CEF does not support Android.** The maintainer's answer on the CEF forum is "CEF is not supported on
   Android"; CEF's own binary distributions cover Windows, macOS and Linux. Community Android ports exist
   but are experimental and unpublished. There is also no `externalNativeBuild`/NDK configuration in this
   project at all, so even a CEF-shaped integration would require adding a native toolchain, JNI glue and
   ~100–400 MB of per-ABI native payload that cannot live in this repository.

4. **Crosswalk and ChromeView — the historical answers to this exact question — are dead.** Crosswalk was
   discontinued in 2018; `pwnall/chromeview` was unmaintained years earlier; both are archived.

**What is technically reachable from here, ranked by how much of the brief each satisfies:**

| Option | Real Chromium/Blink+V8? | Embedded in the app (own UI/tabs)? | Feasible in this environment? |
| --- | --- | --- | --- |
| Fork Chromium + custom embedder (`//content`, e.g. `content_shell`-derived) — **the only true answer to the brief** | ✅ genuine Blink + V8 + Chromium networking/storage, version under your control | ✅ | ❌ needs a ≥100 GB Chromium checkout, depot_tools, hours of build, a self-hosted runner, and a Gradle/native integration for a multi-hundred-MB payload |
| CEF | ✅ (Blink + V8) | ✅ | ❌ no Android support |
| Chromium source AAR / Maven artifact | — | — | ❌ does not exist (see 1) |
| **GeckoView** (Mozilla, Maven-published) | ❌ — Gecko + SpiderMonkey, not Blink/V8 | ✅ self-contained engine, its own networking/storage, browser-grade APIs (tabs, downloads, permissions, file upload, popups, prompts), drop-in `AndroidView` — and it *does* satisfy "a genuine non-WebView browser engine" | ✅ technically | This is the only way to get a *real, app-owned browser engine* into this project with its current build setup. It is a different engine from the one the brief names, so it needs your explicit decision. |
| **Cronet** (`org.chromium.net:cronet-embedded`) | networking only — ✅ real Chromium net stack (QUIC/HTTP3, BoringSSL, Chromium DNS/cache/cookies), ~6.2 MB per ABI | n/a | ✅ | Renders nothing. Cannot be a browsing engine; it *is* the "use Chromium's networking" part of the brief, in isolation. |
| **Chrome Custom Tabs / Trusted Web Activity** | ✅ — delegates to the device's real Chrome: Blink, V8, Chrome networking, real releases | ❌ — Chrome's UI replaces yours; your address bar, tabs, find-in-page and menus are unused; needs Chrome installed | ✅ | Fails "preserve the existing UI/UX". |
| Android System WebView (today) | ✅ — it *is* Chromium, same source tree, Blink + V8; on Android 10+ it uses Chromium's network stack | ❌ — the OS owns it | ✅ | What the app already does. Explicitly excluded by the brief. |

**Notes that change how the request should be read** (stated so the decision is made on facts, not on the
premise that WebView is not Chromium):

1. **Android System WebView on API 24+ is Chromium.** It is built from the Chromium source tree
   (`//android_webview`), renders with Blink, executes with V8, and since Android 10 serves traffic through
   Chromium's network stack. The app is therefore already rendering with Blink + V8 today. What it does
   *not* have is ownership: no control over the engine version, and no access to the browser-grade APIs in
   §4. If "genuine Chromium" is the literal goal, it is already met; if the goal is *owning* the engine,
   only option 1 above achieves it, and it needs a different build environment.
2. The literal anti-requirement in the brief is honoured: I will not implement a User-Agent string swap
   dressed up as an engine change, and §4 explains why a UA string alone is not even self-consistent — a
   WebView sending Chrome's UA still sends its own `Sec-CH-UA` client hints, so the two disagree.
3. Related: the existing `DESKTOP_USER_AGENT` constant already *is* such an override, for the Desktop-site
   toggle. It is a deliberate, user-visible feature, but it is exactly the pattern the brief rules out as
   the *solution* to this task. I have left it untouched and am flagging it rather than quietly building
   on it.
4. If the underlying motivation is the CDN 403 mentioned in the previous commit (`c2376ba`), the honest
   framing is that a self-owned Chromium would give the app a genuine Chromium client identity rather than
   a simulated one — but whether a given CDN finds that acceptable, and whether the goal is
   access-control circumvention at all, is a policy question I am not going to answer by implementing it.
   The brief already excludes anti-bot / CAPTCHA / access-control bypass, and I will hold to that line
   whichever option you pick.

---

## 8. What I need from you

Nothing gets deleted, and no UI gets rewritten, until a replacement engine builds *and* runs — that gate
is Phase 4/5 above and it stays in place. To move at all I need one decision:

- **A — target a real self-hosted Chromium build.** You provide a machine/runner with ≥100 GB disk (or a
  Chromium/`//content` embedder AAR + resources you build elsewhere), and I write Phases 1, 3, 4 and 5 —
  the engine seam, the `ContentView`/`CefBrowser` integration, the callbacks in §4, and the eventual
  WebView removal. The engine stays Chromium/Blink/V8 exactly as the brief specifies.
- **B — accept GeckoView** as the app-owned engine instead of Chromium. Real engine, real networking, real
  tabs/downloads/permissions/file-upload, works with this Gradle-only setup and is verifiable on the
  existing CI, and it is not WebView. It is *not* Blink/V8, so it does not satisfy the brief's engine
  requirement literally.
- **C — stay on WebView** and spend the effort on the real gaps instead: downloads, file chooser,
  permissions, popups, a working "Clear browsing data", and a correct Client-Hints-consistent desktop
  mode. Cheapest, honest, and no engine change is claimed.
- **D — Phase 1 now, decide the engine later.** I do the engine-neutral seam described in §6 (plus the
  measurable wins it enables: the inspector's cookie attributes via the engine's cookie store, and the
  missing downloads/file-chooser/permission/popup plumbing), so the app is ready for whichever engine is
  chosen, without pretending to have replaced the engine.

My recommendation: **D now**, because it is real, verifiable progress on the parts of the brief that are
achievable in this environment, and it is the mandatory precondition for A. If the engine requirement is
non-negotiable and A is possible for you, say so with the hardware and I will build Phase 2's integration
against it.
