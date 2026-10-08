package com.example.ui.engine.gecko

import android.content.Context
import android.net.Uri
import com.example.data.model.BrowserTab
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.Autocomplete
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.WebRequestError
import org.mozilla.geckoview.WebResponse

/**
 * What the Gecko engine layer reports back to the app.
 *
 * The list mirrors the callbacks the WebView container raised — so the browser UI does not need to
 * know which engine renders — plus the events Gecko exposes and WebView could not: redirect-aware
 * location changes, renderer replacement, downloads, and text selections.
 */
class GeckoEngineCallbacks(
    val onPageStarted: (String, String) -> Unit,
    /** A URL change for the tab that is not necessarily a new document (redirect, hash, pushState). */
    val onUrlChanged: (String, String) -> Unit,
    val onPageFinished: (String, String, String?) -> Unit,
    val onProgressChanged: (String, Int) -> Unit,
    val onPageEvent: (String, String) -> Unit,
    val onNavigationStateChanged: (String, Boolean, Boolean) -> Unit,
    /** A non-web scheme the OS should handle: `mailto:`, `tel:`, an app link. */
    val onExternalApp: (String) -> Unit,
    /** `target="_blank"` / `window.open` — the app opens a tab and returns its id. */
    val onNewTab: (String) -> String,
    /** `window.close()` from the page. */
    val onCloseTab: (String) -> Unit,
    /** The content process died. `killedForMemory` distinguishes an out-of-memory kill from a crash. */
    val onRendererGone: (String, Boolean) -> Unit,
    /** A text selection appeared or disappeared; the container draws the actions bar. */
    val onSelectionChanged: (String, GeckoSession.SelectionActionDelegate.Selection?) -> Unit,
    /** `navigator.share`. Returns true when Android's share sheet was shown. */
    val onShareRequested: (String?, String?) -> Boolean,
    /**
     * A request failed at the network level: (tabId, url, description, error code). Gecko reports a
     * code and a category where WebView reported a description and an opaque code, so the developer
     * inspector gets a better record for a worse-defined feature.
     */
    val onLoadFailure: (String, String?, String, Int) -> Unit = { _, _, _, _ -> },
)

/**
 * Owns one [GeckoSession] per browser tab.
 *
 * In GeckoView a session *is* a tab: it holds the document, its back/forward history, its own
 * settings (private mode, JavaScript, desktop mode) and its page's storage context. Unlike the
 * WebView engine — which destroyed and re-created a WebView on every tab switch and therefore lost
 * each tab's history — sessions here outlive being hidden, so going back after switching tabs still
 * works, the way a real browser behaves.
 *
 * Every callback in this file runs on the UI thread, which is where GeckoView delivers them.
 */
class GeckoTabSessions(
    private val context: Context,
    private val promptHost: GeckoPromptHost,
    private val downloads: GeckoDownloader,
    private val bridge: GeckoBridge,
    private val callbacks: GeckoEngineCallbacks,
) {

    private val sessions = LinkedHashMap<String, GeckoSession>()
    private val delegates = HashMap<String, TabDelegates>()
    private val currentUrl = HashMap<String, String>()
    private val currentTitle = HashMap<String, String?>()

    /**
     * GeckoSession has no `canGoBack()`/`canGoForward()` accessors; the navigation delegate reports
     * each change separately, so the pair is tracked here.
     */
    private val canGoBack = HashMap<String, Boolean>()
    private val canGoForward = HashMap<String, Boolean>()

    /**
     * The last (url, title) handed to `onPageFinished`.
     *
     * Gecko reports a title both while a document loads and when it settles, and the app turns every
     * page-finished report into a history row, so only changes are reported.
     */
    private val reportedFinish = HashMap<String, Pair<String, String?>>()

    fun session(tabId: String): GeckoSession? = sessions[tabId]

    /** The URL the engine last reported for this tab, or null if the tab has not loaded yet. */
    fun currentUrl(tabId: String): String? = currentUrl[tabId]

    /** Returns this tab's session, creating and opening it on first use. */
    fun obtain(tab: BrowserTab, javaScriptEnabled: Boolean): GeckoSession {
        sessions[tab.id]?.let { return it }

        val settings = GeckoSessionSettings.Builder()
            // Private mode can only be chosen before the session is opened.
            .usePrivateMode(tab.isIncognito)
            .allowJavascript(javaScriptEnabled)
            // Gecko's Enhanced Tracking Protection, Standard — what the Settings screen has always
            // claimed to offer, and what the WebView engine never actually did.
            .useTrackingProtection(true)
            .suspendMediaWhenInactive(true)
            .displayMode(GeckoSessionSettings.DISPLAY_MODE_BROWSER)
            .userAgentMode(userAgentModeFor(tab.isDesktopSite))
            .viewportMode(viewportModeFor(tab.isDesktopSite))
            .build()

        val session = GeckoSession(settings)
        session.open(GeckoRuntimeManager.get(context))

        val tabDelegates = TabDelegates(tab.id)
        delegates[tab.id] = tabDelegates
        session.navigationDelegate = tabDelegates
        session.progressDelegate = tabDelegates
        session.contentDelegate = tabDelegates
        session.permissionDelegate = tabDelegates
        session.promptDelegate = tabDelegates
        session.selectionActionDelegate = tabDelegates

        sessions[tab.id] = session
        bridge.attach(tab.id, session)
        return session
    }

    fun close(tabId: String) {
        val session = sessions.remove(tabId) ?: return
        session.setActive(false)
        session.close()
        delegates.remove(tabId)
        bridge.detach(tabId)
        currentUrl.remove(tabId)
        currentTitle.remove(tabId)
        canGoBack.remove(tabId)
        canGoForward.remove(tabId)
        reportedFinish.remove(tabId)
    }

    /** The ids of every tab that currently owns a session. */
    fun liveTabIds(): Set<String> = sessions.keys.toSet()

    /** Closes sessions whose tab no longer exists. Called with the app's live tab ids. */
    fun keepOnly(liveTabIds: Set<String>) {
        sessions.keys.filterNot { it in liveTabIds }.forEach(::close)
    }

    /** Applies the global JavaScript switch to every open tab. */
    fun setJavaScriptEnabled(enabled: Boolean) {
        sessions.values.forEach { session -> session.settings.allowJavascript = enabled }
    }

    /** Desktop site is per tab and can change while the page is open, exactly like a browser. */
    fun setDesktopSite(tabId: String, desktop: Boolean) {
        val session = sessions[tabId] ?: return
        session.settings.userAgentMode = userAgentModeFor(desktop)
        session.settings.viewportMode = viewportModeFor(desktop)
    }

    /** Replaces a session whose content process died; the previous session is unusable. */
    fun recreate(tab: BrowserTab, javaScriptEnabled: Boolean): GeckoSession {
        close(tab.id)
        return obtain(tab, javaScriptEnabled)
    }

    private fun userAgentModeFor(desktop: Boolean): Int = if (desktop) {
        GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
    } else {
        GeckoSessionSettings.USER_AGENT_MODE_MOBILE
    }

    private fun viewportModeFor(desktop: Boolean): Int = if (desktop) {
        GeckoSessionSettings.VIEWPORT_MODE_DESKTOP
    } else {
        GeckoSessionSettings.VIEWPORT_MODE_MOBILE
    }

    /**
     * Everything GeckoSession can ask the embedder, for one tab.
     *
     * One instance per tab, so every callback already knows which tab it belongs to instead of
     * having to work it out from the session object.
     */
    private inner class TabDelegates(private val tabId: String) :
        GeckoSession.NavigationDelegate,
        GeckoSession.ProgressDelegate,
        GeckoSession.ContentDelegate,
        GeckoSession.PermissionDelegate,
        GeckoSession.PromptDelegate,
        GeckoSession.SelectionActionDelegate {

        private fun origin(): String = originLabel(currentUrl[tabId])

        private fun reportNavigationState() {
            callbacks.onNavigationStateChanged(
                tabId,
                canGoBack[tabId] == true,
                canGoForward[tabId] == true
            )
        }

        // ------------------------------------------------------------------ navigation

        override fun onLoadRequest(
            session: GeckoSession,
            request: GeckoSession.NavigationDelegate.LoadRequest
        ): GeckoResult<AllowOrDeny>? {
            val uri = request.uri
            return when {
                // A top-level `javascript:` URL is script execution dressed as a link. No browser
                // follows one, and it is not something to hand to another app either.
                isScriptUrl(uri) -> GeckoResult.deny()

                isEngineLoadable(uri) -> GeckoResult.allow()

                else -> {
                    // Hand the URL to whichever app claims it (mail, dialer, a store link). The
                    // engine must not also try to load it, so the navigation is denied for Gecko and
                    // completed by the external app instead.
                    callbacks.onExternalApp(uri)
                    GeckoResult.deny()
                }
            }
        }

        override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? {
            // A page asked for a new window. This browser shows new windows as new tabs, which is
            // what Chrome does and what the tab switcher is for. The engine-level window is denied
            // and the app's own tab takes the URL: that avoids handing Gecko an unopened session
            // whose lifetime the framework itself manages.
            if (isEngineLoadable(uri)) callbacks.onNewTab(uri)
            return GeckoResult.fromValue(null)
        }

        override fun onLoadError(
            session: GeckoSession,
            uri: String?,
            error: WebRequestError
        ): GeckoResult<String>? {
            val description = describeLoadError(uri, error)
            callbacks.onPageEvent(tabId, description)
            // Navigating a URL is not a request with a method, but naming one keeps the inspector's
            // record shaped like every other failure it holds.
            callbacks.onLoadFailure(tabId, uri, description, error.code)
            return null // Let Gecko render its own error page for the failure.
        }

        override fun onLocationChange(
            session: GeckoSession,
            url: String?,
            permissions: List<GeckoSession.PermissionDelegate.ContentPermission>,
            hasUserGesture: Boolean
        ) {
            val target = url ?: return
            if (currentUrl[tabId] == target) return
            currentUrl[tabId] = target
            callbacks.onUrlChanged(tabId, target)
        }

        override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
            this@GeckoTabSessions.canGoBack[tabId] = canGoBack
            reportNavigationState()
        }

        override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
            this@GeckoTabSessions.canGoForward[tabId] = canGoForward
            reportNavigationState()
        }

        // ------------------------------------------------------------------ progress

        override fun onPageStart(session: GeckoSession, url: String) {
            currentUrl[tabId] = url
            callbacks.onPageStarted(tabId, url)
        }

        override fun onPageStop(session: GeckoSession, success: Boolean) {
            if (!success) return // onLoadError has already explained the failure.
            reportFinished()
        }

        override fun onProgressChange(session: GeckoSession, progress: Int) {
            callbacks.onProgressChanged(tabId, progress)
        }

        override fun onSecurityChange(
            session: GeckoSession,
            securityInfo: GeckoSession.ProgressDelegate.SecurityInformation
        ) {
            // A failed handshake is reported through onLoadError. This covers the other case: a page
            // that loaded but is not the site it claims to be.
            if (securityInfo.isSecure) return
            val uri = securityInfo.origin ?: currentUrl[tabId]
            if (schemeOf(uri) != "https") return
            callbacks.onPageEvent(
                tabId,
                "This page's connection is not secure, so its traffic is not protected."
            )
        }

        // ------------------------------------------------------------------ content

        override fun onTitleChange(session: GeckoSession, title: String?) {
            if (title.isNullOrBlank()) return
            currentTitle[tabId] = title
            reportFinished()
        }

        override fun onExternalResponse(session: GeckoSession, response: WebResponse) {
            downloads.handle(tabId, currentUrl[tabId] ?: response.uri, response)
        }

        override fun onCloseRequest(session: GeckoSession) {
            callbacks.onCloseTab(tabId)
        }

        override fun onCrash(session: GeckoSession) {
            handleContentProcessGone(killedForMemory = false)
        }

        override fun onKill(session: GeckoSession) {
            handleContentProcessGone(killedForMemory = true)
        }

        private fun handleContentProcessGone(killedForMemory: Boolean) {
            callbacks.onLoadFailure(
                tabId,
                currentUrl[tabId],
                if (killedForMemory) {
                    "The content process was killed by Android to reclaim memory."
                } else {
                    "The content process crashed."
                },
                WebRequestError.ERROR_CONTENT_CRASHED
            )
            callbacks.onPageEvent(
                tabId,
                if (killedForMemory) {
                    "The page was closed by Android to free memory, and is reloading. A heavy site " +
                        "can do this repeatedly on a low-memory device — close other tabs to help it."
                } else {
                    "The page stopped unexpectedly and is reloading."
                }
            )
            callbacks.onRendererGone(tabId, killedForMemory)
        }

        /**
         * Reports a settled document to the app, at most once per (url, title) pair.
         *
         * The app turns this into the tab's title and one history visit, so a repeat would show up as
         * a duplicate history row.
         */
        private fun reportFinished() {
            val url = currentUrl[tabId] ?: return
            val title = currentTitle[tabId]
            if (reportedFinish[tabId] == url to title) return
            reportedFinish[tabId] = url to title
            callbacks.onPageFinished(tabId, url, title)
        }

        // ------------------------------------------------------------------ permissions

        override fun onContentPermissionRequest(
            session: GeckoSession,
            perm: GeckoSession.PermissionDelegate.ContentPermission
        ): GeckoResult<Int>? {
            val kind = when (perm.permission) {
                GeckoSession.PermissionDelegate.PERMISSION_GEOLOCATION -> PermissionKind.GEOLOCATION
                GeckoSession.PermissionDelegate.PERMISSION_DESKTOP_NOTIFICATION -> PermissionKind.NOTIFICATIONS
                GeckoSession.PermissionDelegate.PERMISSION_STORAGE_ACCESS -> PermissionKind.STORAGE_ACCESS

                // Storage the page owns anyway, and autoplay that makes no sound: browsers grant
                // these without asking.
                GeckoSession.PermissionDelegate.PERMISSION_PERSISTENT_STORAGE,
                GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE ->
                    return GeckoResult.fromValue(GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW)

                // Audible autoplay, tracking-protection overrides, DRM key systems, XR and local
                // device/network access are refused rather than silently granted: this app has no
                // content-decryption module, no XR support and no device-pairing feature, and a page
                // must not be able to switch off its own tracking protection.
                else -> return GeckoResult.fromValue(
                    GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY
                )
            }

            val result = GeckoResult<Int>()
            perm.notifyShown()
            promptHost.publish(GeckoPromptRequest.ContentPermission(promptHost.nextRequestId(), originLabel(perm.uri), kind)) { answer ->
                result.complete(
                    if (answer is PromptAnswer.Allow) {
                        GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW
                    } else {
                        GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY
                    }
                )
            }
            return result
        }

        override fun onAndroidPermissionsRequest(
            session: GeckoSession,
            permissions: Array<out String>?,
            callback: GeckoSession.PermissionDelegate.Callback
        ) {
            val requested = permissions?.toList().orEmpty()
            if (requested.isEmpty()) {
                callback.reject()
                return
            }
            promptHost.publish(
                GeckoPromptRequest.AndroidPermissions(promptHost.nextRequestId(), origin(), requested)
            ) { answer ->
                if (answer is PromptAnswer.Allow) callback.grant() else callback.reject()
            }
        }

        override fun onMediaPermissionRequest(
            session: GeckoSession,
            uri: String,
            videoSources: Array<out GeckoSession.PermissionDelegate.MediaSource>?,
            audioSources: Array<out GeckoSession.PermissionDelegate.MediaSource>?,
            callback: GeckoSession.PermissionDelegate.MediaCallback
        ) {
            promptHost.publish(
                GeckoPromptRequest.MediaPermission(
                    id = promptHost.nextRequestId(),
                    origin = originLabel(uri),
                    allowsAudio = !audioSources.isNullOrEmpty(),
                    allowsVideo = !videoSources.isNullOrEmpty(),
                )
            ) { answer ->
                if (answer is PromptAnswer.Allow) {
                    // grant() takes video first, then audio — the opposite of onMediaPermissionRequest.
                    callback.grant(videoSources?.firstOrNull(), audioSources?.firstOrNull())
                } else {
                    callback.reject()
                }
            }
        }

        // ------------------------------------------------------------------ prompts

        override fun onAlertPrompt(
            session: GeckoSession,
            prompt: GeckoSession.PromptDelegate.AlertPrompt
        ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? =
            publishPrompt(
                GeckoPromptRequest.JsAlert(promptHost.nextRequestId(), origin(), prompt.message.orEmpty())
            ) { prompt.dismiss() }

        override fun onButtonPrompt(
            session: GeckoSession,
            prompt: GeckoSession.PromptDelegate.ButtonPrompt
        ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? =
            publishPrompt(
                GeckoPromptRequest.JsConfirm(promptHost.nextRequestId(), origin(), prompt.message.orEmpty())
            ) { answer ->
                prompt.confirm(
                    if (answer is PromptAnswer.Allow) {
                        GeckoSession.PromptDelegate.ButtonPrompt.Type.POSITIVE
                    } else {
                        GeckoSession.PromptDelegate.ButtonPrompt.Type.NEGATIVE
                    }
                )
            }

        override fun onTextPrompt(
            session: GeckoSession,
            prompt: GeckoSession.PromptDelegate.TextPrompt
        ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? =
            publishPrompt(
                GeckoPromptRequest.JsText(
                    promptHost.nextRequestId(),
                    origin(),
                    prompt.message.orEmpty(),
                    prompt.defaultValue.orEmpty(),
                )
            ) { answer ->
                if (answer is PromptAnswer.Text) prompt.confirm(answer.value) else prompt.dismiss()
            }

        override fun onAuthPrompt(
            session: GeckoSession,
            prompt: GeckoSession.PromptDelegate.AuthPrompt
        ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
            val options = prompt.authOptions
            val isProxy = (options.flags and GeckoSession.PromptDelegate.AuthPrompt.AuthOptions.Flags.PROXY) != 0
            return publishPrompt(
                GeckoPromptRequest.HttpAuth(
                    id = promptHost.nextRequestId(),
                    origin = origin(),
                    host = options.uri?.let { Uri.parse(it).host } ?: origin(),
                    realm = prompt.message.orEmpty(),
                    isProxy = isProxy,
                )
            ) { answer ->
                if (answer is PromptAnswer.Credentials) {
                    prompt.confirm(answer.username, answer.password)
                } else {
                    prompt.dismiss()
                }
            }
        }

        override fun onFilePrompt(
            session: GeckoSession,
            prompt: GeckoSession.PromptDelegate.FilePrompt
        ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? =
            publishPrompt(
                GeckoPromptRequest.FileChooser(
                    id = promptHost.nextRequestId(),
                    origin = origin(),
                    allowsMultiple = prompt.type == GeckoSession.PromptDelegate.FilePrompt.Type.MULTIPLE,
                    allowsFolder = prompt.type == GeckoSession.PromptDelegate.FilePrompt.Type.FOLDER,
                    mimeTypes = prompt.mimeTypes?.toList().orEmpty(),
                )
            ) { answer ->
                when (answer) {
                    is PromptAnswer.Files -> when (answer.uris.size) {
                        0 -> prompt.dismiss()
                        1 -> prompt.confirm(context, answer.uris.first())
                        else -> prompt.confirm(context, answer.uris.toTypedArray())
                    }

                    else -> prompt.dismiss()
                }
            }

        override fun onChoicePrompt(
            session: GeckoSession,
            prompt: GeckoSession.PromptDelegate.ChoicePrompt
        ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? =
            publishPrompt(
                GeckoPromptRequest.Choice(
                    id = promptHost.nextRequestId(),
                    origin = origin(),
                    title = prompt.title.orEmpty(),
                    multiple = prompt.type == GeckoSession.PromptDelegate.ChoicePrompt.Type.MULTIPLE,
                    choices = prompt.choices.map { choice ->
                        PromptChoice(
                            id = choice.id,
                            label = choice.label,
                            disabled = choice.disabled,
                            selected = choice.selected,
                        )
                    },
                )
            ) { answer ->
                when (answer) {
                    is PromptAnswer.ChoiceOne -> prompt.confirm(answer.id)
                    is PromptAnswer.ChoiceMany -> prompt.confirm(answer.ids.toTypedArray())
                    else -> prompt.dismiss()
                }
            }

        override fun onDateTimePrompt(
            session: GeckoSession,
            prompt: GeckoSession.PromptDelegate.DateTimePrompt
        ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? =
            publishPrompt(
                GeckoPromptRequest.DateTime(
                    id = promptHost.nextRequestId(),
                    origin = origin(),
                    type = prompt.type,
                    value = prompt.defaultValue.orEmpty(),
                    minValue = prompt.minValue,
                    maxValue = prompt.maxValue,
                )
            ) { answer ->
                if (answer is PromptAnswer.Text) prompt.confirm(answer.value) else prompt.dismiss()
            }

        override fun onBeforeUnloadPrompt(
            session: GeckoSession,
            prompt: GeckoSession.PromptDelegate.BeforeUnloadPrompt
        ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? =
            publishPrompt(
                GeckoPromptRequest.JsConfirm(
                    promptHost.nextRequestId(),
                    origin(),
                    "Leave this page? Changes you made may not be saved."
                )
            ) { answer ->
                prompt.confirm(if (answer is PromptAnswer.Allow) AllowOrDeny.ALLOW else AllowOrDeny.DENY)
            }

        override fun onRepostConfirmPrompt(
            session: GeckoSession,
            prompt: GeckoSession.PromptDelegate.RepostConfirmPrompt
        ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? =
            publishPrompt(
                GeckoPromptRequest.JsConfirm(
                    promptHost.nextRequestId(),
                    origin(),
                    "This page was built from data you already sent. Sending it again repeats " +
                        "whatever that action did."
                )
            ) { answer ->
                prompt.confirm(if (answer is PromptAnswer.Allow) AllowOrDeny.ALLOW else AllowOrDeny.DENY)
            }

        override fun onPopupPrompt(
            session: GeckoSession,
            prompt: GeckoSession.PromptDelegate.PopupPrompt
        ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
            // Gecko reaches this prompt only when a page tries to open a window *without* a user
            // gesture, i.e. an unsolicited popup. Every browser blocks those; user-initiated windows
            // arrive through onNewSession and become tabs.
            return GeckoResult.fromValue(prompt.confirm(AllowOrDeny.DENY))
        }

        override fun onSharePrompt(
            session: GeckoSession,
            prompt: GeckoSession.PromptDelegate.SharePrompt
        ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
            // navigator.share(): hand the payload to Android's share sheet, then tell the page what
            // happened. The app cannot know which target the user picked, so a chooser that opened is
            // reported as a success and one that could not open as an abort — never as silence.
            val shared = callbacks.onShareRequested(prompt.text, prompt.uri)
            return GeckoResult.fromValue(
                prompt.confirm(
                    if (shared) {
                        GeckoSession.PromptDelegate.SharePrompt.Result.SUCCESS
                    } else {
                        GeckoSession.PromptDelegate.SharePrompt.Result.ABORT
                    }
                )
            )
        }

        override fun onLoginSave(
            session: GeckoSession,
            request: GeckoSession.PromptDelegate.AutocompleteRequest<Autocomplete.LoginSaveOption>
        ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? =
            GeckoResult.fromValue(request.dismiss())

        override fun onLoginSelect(
            session: GeckoSession,
            request: GeckoSession.PromptDelegate.AutocompleteRequest<Autocomplete.LoginSelectOption>
        ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? =
            GeckoResult.fromValue(request.dismiss())

        override fun onAddressSave(
            session: GeckoSession,
            request: GeckoSession.PromptDelegate.AutocompleteRequest<Autocomplete.AddressSaveOption>
        ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? =
            GeckoResult.fromValue(request.dismiss())

        override fun onAddressSelect(
            session: GeckoSession,
            request: GeckoSession.PromptDelegate.AutocompleteRequest<Autocomplete.AddressSelectOption>
        ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? =
            GeckoResult.fromValue(request.dismiss())

        override fun onCreditCardSave(
            session: GeckoSession,
            request: GeckoSession.PromptDelegate.AutocompleteRequest<Autocomplete.CreditCardSaveOption>
        ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? =
            GeckoResult.fromValue(request.dismiss())

        override fun onCreditCardSelect(
            session: GeckoSession,
            request: GeckoSession.PromptDelegate.AutocompleteRequest<Autocomplete.CreditCardSelectOption>
        ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? =
            GeckoResult.fromValue(request.dismiss())

        override fun onColorPrompt(
            session: GeckoSession,
            prompt: GeckoSession.PromptDelegate.ColorPrompt
        ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
            // <input type="color"> has no dialog here yet. Dismissing leaves the field's existing
            // value in place, which the page can see, rather than writing a wrong colour silently.
            return GeckoResult.fromValue(prompt.dismiss())
        }

        /** Publishes one prompt and completes the engine result with whatever the user answered. */
        private fun publishPrompt(
            request: GeckoPromptRequest,
            resolve: (PromptAnswer) -> GeckoSession.PromptDelegate.PromptResponse
        ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
            val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
            promptHost.publish(request) { answer ->
                result.complete(resolve(answer))
            }
            return result
        }

        // ------------------------------------------------------------------ selection

        override fun onShowActionRequest(
            session: GeckoSession,
            selection: GeckoSession.SelectionActionDelegate.Selection
        ) {
            callbacks.onSelectionChanged(tabId, selection)
        }

        override fun onHideAction(session: GeckoSession, reason: Int) {
            callbacks.onSelectionChanged(tabId, null)
        }

        override fun onShowClipboardPermissionRequest(
            session: GeckoSession,
            permission: GeckoSession.SelectionActionDelegate.ClipboardPermission
        ): GeckoResult<AllowOrDeny>? {
            // Web content reading the clipboard without the user asking for it is never granted:
            // Android WebView had no equivalent API, so nothing that worked before stops working.
            return GeckoResult.deny()
        }
    }
}

/**
 * Turns a Gecko load failure into something a person can act on.
 *
 * Gecko reports a code and a category, which is strictly more than WebView's opaque description; the
 * mapping below names the failures that change what the user should do next.
 */
internal fun describeLoadError(uri: String?, error: WebRequestError): String {
    val target = uri?.let { originLabel(it) } ?: "this page"
    return when (error.code) {
        WebRequestError.ERROR_UNKNOWN_HOST ->
            "$target could not be found. Check the address, or whether the name resolves on this network."

        WebRequestError.ERROR_NET_TIMEOUT ->
            "$target took too long to answer. The site is slow or unreachable from this network."

        WebRequestError.ERROR_CONNECTION_REFUSED ->
            "$target refused the connection. The server may be down."

        WebRequestError.ERROR_NET_INTERRUPT ->
            "The connection to $target was interrupted before the page arrived."

        WebRequestError.ERROR_NET_RESET ->
            "The connection to $target was reset by the server."

        WebRequestError.ERROR_OFFLINE ->
            "There is no network connection, so $target could not be loaded."

        WebRequestError.ERROR_SECURITY_BAD_CERT,
        WebRequestError.ERROR_SECURITY_SSL,
        WebRequestError.ERROR_BAD_HSTS_CERT ->
            "The secure connection to $target was rejected, and Gecko is showing its own error page. " +
                "This browser does not bypass certificate errors."

        WebRequestError.ERROR_HTTPS_ONLY ->
            "$target requires a secure connection and the site would not provide one."

        WebRequestError.ERROR_REDIRECT_LOOP ->
            "$target redirected in a loop and was stopped."

        WebRequestError.ERROR_PORT_BLOCKED ->
            "The port requested for $target is blocked."

        WebRequestError.ERROR_SAFEBROWSING_PHISHING_URI,
        WebRequestError.ERROR_SAFEBROWSING_MALWARE_URI,
        WebRequestError.ERROR_SAFEBROWSING_UNWANTED_URI,
        WebRequestError.ERROR_SAFEBROWSING_HARMFUL_URI ->
            "Safe Browsing blocked $target: it is on a list of sites that deceive people or " +
                "distribute harmful software."

        WebRequestError.ERROR_LOCAL_NETWORK_ACCESS_DENIED ->
            "$target tried to reach a device on your local network, which was refused."

        WebRequestError.ERROR_CONTENT_CRASHED ->
            "The page's content stopped unexpectedly."

        WebRequestError.ERROR_FILE_NOT_FOUND ->
            "The file requested by $target does not exist."

        WebRequestError.ERROR_FILE_ACCESS_DENIED ->
            "Access to that file was refused."

        else -> {
            val category = when (error.category) {
                WebRequestError.ERROR_CATEGORY_NETWORK -> "a network error"
                WebRequestError.ERROR_CATEGORY_SECURITY -> "a security error"
                WebRequestError.ERROR_CATEGORY_CONTENT -> "a content error"
                WebRequestError.ERROR_CATEGORY_URI -> "an address error"
                WebRequestError.ERROR_CATEGORY_PROXY -> "a proxy error"
                WebRequestError.ERROR_CATEGORY_SAFEBROWSING -> "a Safe Browsing block"
                else -> "an error"
            }
            "$target could not be loaded: $category (code ${error.code})."
        }
    }
}
