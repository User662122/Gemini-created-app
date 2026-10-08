package com.example.ui.engine.gecko

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Everything a page can ask the browser, on its way to a dialog.
 *
 * Android WebView draws its own UI for `alert()`/`confirm()`/`prompt()`, HTTP authentication and
 * file pickers, so an app that never implements a handler still gets working dialogs. GeckoView
 * does not: a prompt with no delegate is dismissed, which would look like the page hanging. This
 * host is how the app keeps those behaviours — the engine asks, the host publishes a request, the
 * Compose layer renders it, and the answer is routed back to the exact engine callback.
 */
sealed interface GeckoPromptRequest {
    val id: Long
    /** Host shown to the user, e.g. `example.com`. */
    val origin: String

    data class JsAlert(
        override val id: Long,
        override val origin: String,
        val message: String,
    ) : GeckoPromptRequest

    data class JsConfirm(
        override val id: Long,
        override val origin: String,
        val message: String,
    ) : GeckoPromptRequest

    data class JsText(
        override val id: Long,
        override val origin: String,
        val message: String,
        val defaultValue: String,
    ) : GeckoPromptRequest

    data class HttpAuth(
        override val id: Long,
        override val origin: String,
        val host: String,
        val realm: String,
        val isProxy: Boolean,
    ) : GeckoPromptRequest

    /** Geolocation, site notifications, storage access and friends. */
    data class ContentPermission(
        override val id: Long,
        override val origin: String,
        val kind: PermissionKind,
    ) : GeckoPromptRequest

    data class MediaPermission(
        override val id: Long,
        override val origin: String,
        val allowsAudio: Boolean,
        val allowsVideo: Boolean,
    ) : GeckoPromptRequest

    /**
     * The page wants an Android runtime permission (camera, microphone, location). No dialog is
     * drawn by this host for these: the request is passed to the system permission prompt.
     */
    data class AndroidPermissions(
        override val id: Long,
        override val origin: String,
        val permissions: List<String>,
    ) : GeckoPromptRequest

    data class FileChooser(
        override val id: Long,
        override val origin: String,
        val allowsMultiple: Boolean,
        val mimeTypes: List<String>,
        val allowsFolder: Boolean,
    ) : GeckoPromptRequest

    /**
     * A `<select>` element, or a datalist/autocomplete menu. Browsers draw this themselves; without
     * it the control simply would not open, so it is part of keeping forms working.
     */
    data class Choice(
        override val id: Long,
        override val origin: String,
        val title: String,
        val multiple: Boolean,
        val choices: List<PromptChoice>,
    ) : GeckoPromptRequest

    /** `<input type="date">`, `type="time">`, `type="month">` and friends. */
    data class DateTime(
        override val id: Long,
        override val origin: String,
        val type: Int,
        val value: String,
        val minValue: String?,
        val maxValue: String?,
    ) : GeckoPromptRequest
}

/** One entry of a [GeckoPromptRequest.Choice]. */
data class PromptChoice(
    val id: String,
    val label: String,
    val disabled: Boolean,
    val selected: Boolean,
)

/** The permission kinds the app shows UI for, named the way a person would say them. */
enum class PermissionKind(val label: String, val description: String) {
    GEOLOCATION("your location", "share your location with this site"),
    NOTIFICATIONS("notifications", "send you notifications"),
    STORAGE_ACCESS("storage access", "read its cookies in this context"),
}

/** How the user answered a [GeckoPromptRequest]. */
sealed interface PromptAnswer {
    object Dismiss : PromptAnswer
    object Allow : PromptAnswer
    object Deny : PromptAnswer
    data class Text(val value: String) : PromptAnswer
    data class Credentials(val username: String, val password: String) : PromptAnswer
    data class Files(val uris: List<Uri>) : PromptAnswer
    data class ChoiceOne(val id: String) : PromptAnswer
    data class ChoiceMany(val ids: List<String>) : PromptAnswer
}

/**
 * Holds the prompts that are waiting for the user, and routes answers back to the engine callback
 * that asked for them.
 *
 * Only one prompt of each kind is published at a time per tab in practice, but nothing here assumes
 * that: requests are keyed by id, so a page that fires three `alert()`s in a row queues three
 * requests rather than losing two.
 */
class GeckoPromptHost {

    private val _requests = MutableStateFlow<List<GeckoPromptRequest>>(emptyList())
    val requests: StateFlow<List<GeckoPromptRequest>> = _requests.asStateFlow()

    private val resolvers = HashMap<Long, (PromptAnswer) -> Unit>()
    private var nextId = 0L

    /** Publishes a request and returns its id. [resolver] is called exactly once. */
    fun publish(request: GeckoPromptRequest, resolver: (PromptAnswer) -> Unit): Long {
        resolvers[request.id] = resolver
        _requests.update { it + request }
        return request.id
    }

    fun nextRequestId(): Long = ++nextId

    fun resolve(id: Long, answer: PromptAnswer) {
        val resolver = resolvers.remove(id) ?: return
        _requests.update { list -> list.filterNot { it.id == id } }
        resolver(answer)
    }

    /**
     * Drops every pending prompt without answering it. Called when the surface goes away; the engine
     * treats an unanswered prompt as dismissed when the session closes.
     */
    fun clear() {
        resolvers.clear()
        _requests.value = emptyList()
    }

    /** Cancels the prompts belonging to tabs that no longer exist. */
    fun cancelPrompt(id: Long) {
        resolve(id, PromptAnswer.Dismiss)
    }
}

/** `https://www.example.com/a/b?c` -> `example.com`, for dialog titles. */
fun originLabel(uri: String?): String {
    if (uri.isNullOrBlank()) return "this page"
    val host = Uri.parse(uri).host
    if (!host.isNullOrBlank()) return host.removePrefix("www.")
    return uri.take(80)
}

/**
 * Schemes Gecko loads itself.
 *
 * `about:`, `data:` and `blob:` are produced by the engine during normal browsing (a `data:` link, a
 * blob URL in a single-page app) so they must stay internal. Everything else — `mailto:`, `tel:`,
 * `intent:`, a custom app scheme — is handed to the OS, which is exactly what the WebView engine did
 * in `shouldOverrideUrlLoading`.
 */
private val ENGINE_SCHEMES = setOf("http", "https", "about", "data", "blob", "file", "content")

fun schemeOf(uri: String?): String? = uri?.let { runCatching { Uri.parse(it).scheme?.lowercase() }.getOrNull() }

fun isEngineLoadable(uri: String?): Boolean = schemeOf(uri) in ENGINE_SCHEMES

/**
 * `javascript:` navigations are neither loaded nor handed to another app: a top-level `javascript:`
 * URL is script execution disguised as a link, and no browser opens it as a page.
 */
fun isScriptUrl(uri: String?): Boolean = schemeOf(uri) == "javascript"

