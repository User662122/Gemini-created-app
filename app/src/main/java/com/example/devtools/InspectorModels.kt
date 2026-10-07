package com.example.devtools

/**
 * Network Inspector data model.
 *
 * Design rule for this whole feature: **never invent data**. Every value is wrapped in an
 * [InspectorValue] that records where the value came from, or why it is not available. The UI
 * renders the "why" text instead of an empty cell, so a missing field can never be mistaken for an
 * empty/failed request.
 *
 * Nothing in this file touches the Android framework, so it is covered by plain JVM unit tests
 * (see `app/src/test/java/com/example/devtools/`).
 */

/** Where a fact in the inspector actually came from. */
enum class EvidenceSource(val label: String) {
    /** Delivered by a WebView callback (`WebViewClient` / `WebChromeClient`). */
    WEBVIEW_CALLBACK("WebView callback"),

    /** Read from a header on the request itself (for example `Sec-Fetch-Dest`). */
    REQUEST_HEADER("request header"),

    /** Reported by the JavaScript hooks this inspector injects into the page. */
    PAGE_JAVASCRIPT("injected JS hook"),

    /** Read back from `android.webkit.CookieManager`. */
    COOKIE_MANAGER("CookieManager"),

    /** Parsed from a `Set-Cookie` response header this app really observed. */
    SET_COOKIE_HEADER("Set-Cookie header"),

    /** Captured by an HTTP client owned by the app (optional hook, see [CookieInspector]). */
    APP_HTTP_CLIENT("app HTTP client"),

    /** Timestamp taken by this app's own clock (`System.currentTimeMillis`). */
    APP_CLOCK("app clock"),

    /** Computed by the inspector from other known facts. Shown as "inferred", never as fact. */
    DERIVED("inferred by the inspector"),

    /** Not observable through the public APIs this app is allowed to use. */
    UNAVAILABLE("not available"),
}

/**
 * A value plus the evidence that produced it.
 *
 * `value == null` means *unknown*; [note] then explains why it is unknown. `value != null` means the
 * value was really observed (or soundly derived, when [source] is [EvidenceSource.DERIVED]).
 */
data class InspectorValue<T>(
    val value: T?,
    val source: EvidenceSource,
    val note: String? = null,
) {
    val isKnown: Boolean get() = value != null

    val isInferred: Boolean get() = source == EvidenceSource.DERIVED

    companion object {
        /** A value this app really observed. */
        fun <T> known(value: T, source: EvidenceSource, note: String? = null): InspectorValue<T> =
            InspectorValue(value, source, note)

        /** Not observable, with the technical reason why. */
        fun <T> unknown(reason: String): InspectorValue<T> =
            InspectorValue(value = null, source = EvidenceSource.UNAVAILABLE, note = reason)

        /** Soundly derived from other observed facts. */
        fun <T> derived(value: T, reason: String): InspectorValue<T> =
            InspectorValue(value, EvidenceSource.DERIVED, reason)
    }
}

/** `true`/`false`/unknown rendered for the UI. */
fun InspectorValue<Boolean>.asFlag(): String = when (value) {
    true -> "Yes"
    false -> "No"
    null -> "Unknown"
}

/** Any value rendered for the UI; unknown values render as an em dash. */
fun InspectorValue<*>.asText(): String = value?.toString() ?: "—"

/** How a field was protected before it was stored. */
enum class RedactionKind {
    /** Shown exactly as observed. */
    NONE,

    /** Masked because the name looks sensitive; the original value was not stored. */
    MASKED,

    /** Masked always: this value is never stored or revealed. */
    NEVER_STORED,

    /**
     * A value the inspector would normally mask, stored and shown in the clear because **full
     * capture** is on. Flagged rather than silently treated as ordinary, so the UI can mark it as a
     * secret that is currently visible (and about to be written into an export).
     */
    REVEALED,

    /** Value was cut down to the inspector's size budget. */
    TRUNCATED,
}

/**
 * One HTTP header as stored by the inspector.
 *
 * [display] is what the UI and the reports print, and it is the real value whenever full capture is
 * on. [raw] is non-null in that mode too, so the per-field "Reveal" action stays available.
 */
data class HttpField(
    val name: String,
    val display: String,
    val raw: String? = null,
    val redaction: RedactionKind = RedactionKind.NONE,
    val note: String? = null,
) {
    /** True when something was hidden or cut, so the row can be flagged as "not the full value". */
    val isRedacted: Boolean get() =
        redaction == RedactionKind.MASKED ||
            redaction == RedactionKind.NEVER_STORED ||
            redaction == RedactionKind.TRUNCATED

    /** True when this is a normally-sensitive value that full capture is showing in the clear. */
    val isRevealedSensitive: Boolean get() = redaction == RedactionKind.REVEALED

    val canReveal: Boolean get() = raw != null
}

/** How complete the captured header set is. WebView documents no completeness guarantee. */
enum class HeaderCompleteness(val label: String, val explanation: String) {
    SUBSET_REPORTED_BY_WEBVIEW(
        "subset (WebView)",
        "WebView hands the app the headers it chooses to expose; it does not promise the full set " +
            "sent on the wire. Headers added by the network stack (for example cookies, " +
            "User-Agent details, HTTP/2 pseudo headers) are frequently missing.",
    ),
    FROM_PAGE_JAVASCRIPT(
        "headers passed by page JavaScript",
        "These are the headers the page's own fetch/XHR call passed. The browser may add more " +
            "headers (Cookie, User-Agent, Referer, Sec-*) after this point.",
    ),
    FROM_APP_HTTP_CLIENT(
        "complete (app HTTP client)",
        "These headers come from an HTTP client this app controls, so the set is complete as sent " +
            "by that client. This request did not come from the WebView.",
    ),
    UNKNOWN(
        "unknown",
        "No header source reported anything for this request.",
    ),
}

/** Body encoding reported by the injected hooks. */
enum class BodyKind { TEXT, FORM_URLENCODED, MULTIPART_FORM_DATA, JSON, BINARY, STREAM, UNKNOWN }

/**
 * A request body preview.
 *
 * [BodyRecord] only ever exists when page JavaScript really handed a body to `fetch`/`XHR`/
 * `sendBeacon`. WebView has no API that exposes the body of a native request, and the inspector
 * does not attempt to reconstruct it.
 */
data class BodyRecord(
    val preview: InspectorValue<String>,
    val kind: BodyKind,
    /**
     * Size as reported by the source. For text bodies this is a character count, for binary bodies a
     * byte count — it is labelled "reported size" in the UI rather than pretending to be exact bytes.
     */
    val reportedLength: Int? = null,
    val truncated: Boolean = false,
)

/** The filter buckets requested by the product spec, plus nothing else. */
enum class ResourceCategory(val label: String) {
    ALL("All"),
    DOCUMENT("Docs"),
    XHR_FETCH("XHR/Fetch"),
    JAVASCRIPT("JS"),
    CSS("CSS"),
    IMAGE("Images"),
    OTHER("Other"),
}

/** How the request was started, as far as this app can tell. */
enum class Initiator(val label: String) {
    DOCUMENT_NAVIGATION("document navigation"),
    RESOURCE_LOAD("resource load"),
    XMLHTTP_REQUEST("XMLHttpRequest"),
    FETCH("fetch()"),
    SEND_BEACON("navigator.sendBeacon()"),
    SERVICE_WORKER("service worker"),
}

/** State of the request/response pair. */
enum class EntryState {
    /** The request was observed and no response callback has arrived yet. */
    PENDING,

    /** A response (or an HTTP error) was observed. */
    RECEIVED,

    /** The load failed: transport error, JavaScript rejection, or a dead renderer. */
    FAILED,

    /**
     * The request was observed but no response callback ever arrived, and the correlation window has
     * passed. This is *not* a failure — the platform simply has no callback for some successful
     * loads (cache hits, resources finished inside the browser process).
     */
    UNOBSERVED,
}

data class RequestRecord(
    /** Display URL. Sensitive query *values* are masked here in safe mode (the default). */
    val url: String,
    val method: String,
    val startedAtMillis: Long,
    /** Which clock produced [startedAtMillis]: the app clock, or the page's `Date.now()`. */
    val startedAtSource: EvidenceSource,
    val isForMainFrame: Boolean?,
    val isRedirect: Boolean?,
    val hasGesture: Boolean?,
    val headers: List<HttpField>,
    val headersCompleteness: HeaderCompleteness,
    /** Non-null only when page JavaScript really supplied a body. */
    val body: BodyRecord?,
    val category: ResourceCategory,
    val categorySource: EvidenceSource,
    /** Extra detail such as `font`, `media`, `worker`, `main frame`. */
    val categoryDetail: String?,
    val initiator: Initiator,
)

data class ResponseRecord(
    val statusCode: InspectorValue<Int>,
    val reasonPhrase: InspectorValue<String>,
    val headers: List<HttpField>,
    val contentType: InspectorValue<String>,
    val receivedAtMillis: InspectorValue<Long>,
    val finalUrl: InspectorValue<String>,
    /** Wall-clock duration when a source could measure it (page hooks usually can). */
    val durationMillis: Long?,
    /** Network-level failure text (`WebResourceError` / fetch rejection), or null when there was none. */
    val errorDescription: InspectorValue<String>? = null,
    /**
     * Preview of the response body. Only ever present when the *page* read a text body through the
     * injected `fetch`/XHR hooks and response-body capture is switched on; bodies of resources WebView
     * fetched for itself are not available to the app and are reported as such.
     */
    val body: BodyRecord? = null,
)

data class NetworkEntry(
    val id: Long,
    val tabId: String,
    val request: RequestRecord,
    val response: ResponseRecord? = null,
    val state: EntryState = EntryState.PENDING,
    /**
     * When this app stored the entry, always on the app clock. Used for expiry/cleanup, so it is
     * never mixed with the page clock timestamps a JS hook may have supplied.
     */
    val recordedAtAppMillis: Long = 0L,
    /**
     * True when the page's own `fetch`/`XHR` hooks contributed details (headers, body preview,
     * initiator) to this entry. Keeps the inspector from attaching the same page report twice.
     */
    val pageObserved: Boolean = false,
    /** Entry-level caveats: exactly what could not be observed, and why. */
    val notes: List<String> = emptyList(),
) {
    val url: String get() = request.url
    val method: String get() = request.method
    val statusCode: Int? get() = response?.statusCode?.value
    val category: ResourceCategory get() = request.category
    val startedAtMillis: Long get() = request.startedAtMillis
}

enum class ConsoleLevel(val label: String) {
    VERBOSE("verbose"),
    LOG("log"),
    WARN("warn"),
    ERROR("error"),
}

data class ConsoleEntry(
    val id: Long,
    val tabId: String,
    val level: ConsoleLevel,
    val message: String,
    /** True when the secret scrubbing pass changed the message. */
    val masked: Boolean,
    /** Page that produced the message, as reported by WebView. */
    val source: String?,
    val lineNumber: Int?,
    val timestampMillis: Long,
    val stackTrace: String?,
    val evidence: EvidenceSource,
    /** Number of identical consecutive messages collapsed into this one. */
    val repeatCount: Int = 1,
)

data class CookieRecord(
    val id: String,
    val name: String,
    val value: InspectorValue<String>,
    val domain: InspectorValue<String>,
    val path: InspectorValue<String>,
    val secure: InspectorValue<Boolean>,
    val httpOnly: InspectorValue<Boolean>,
    val expiration: InspectorValue<String>,
    /** The URL whose cookie header (or response) produced this record, so the claim stays checkable. */
    val observedForUrl: String,
    val sources: Set<EvidenceSource>,
    /** Caveats for this row: merged headers, masked value, unreadable attributes, and so on. */
    val notes: List<String> = emptyList(),
)

/**
 * Immutable view of the inspector session for the UI.
 *
 * Deliberately *not* a data class: equality is identity, so the state flow emits as soon as a new
 * snapshot exists instead of deep-comparing hundreds of entries on every publish.
 */
class InspectorSnapshot(
    val revision: Long,
    val entries: List<NetworkEntry>,
    val console: List<ConsoleEntry>,
    val cookies: List<CookieRecord>,
    val droppedEntryCount: Int,
    val capturedAtMillis: Long,
)

/** Size and rate budgets. These are what keep the inspector cheap enough to leave switched on. */
object InspectorLimits {
    /** Ring buffer size for request/response pairs. */
    const val MAX_ENTRIES = 400

    /** Ring buffer size for console messages. */
    const val MAX_CONSOLE_ENTRIES = 300

    /** Upper bound on cookies shown at once (hosts are capped separately). */
    const val MAX_COOKIE_RECORDS = 300

    /** Distinct hosts queried for cookies in one refresh. */
    const val MAX_COOKIE_HOSTS = 30

    /** Header values longer than this are truncated. */
    const val MAX_HEADER_VALUE_CHARS = 800

    /** Headers kept per message; the rest are counted and reported as dropped. */
    const val MAX_HEADERS_PER_MESSAGE = 60

    /** Request body preview size. */
    const val MAX_BODY_PREVIEW_CHARS = 2_000

    /** Console message size. */
    const val MAX_CONSOLE_MESSAGE_CHARS = 4_000

    /** Stack traces attached to JS errors. */
    const val MAX_STACK_TRACE_CHARS = 4_000

    /** URL length stored/rendered. */
    const val MAX_URL_CHARS = 2_000

    /** Unmatched in-flight requests kept for correlating native + page-JS observations. */
    const val MAX_PENDING_CORRELATIONS = 300

    /** Hard cap on captured messages per second per tab; excess is dropped and counted, never queued. */
    const val MAX_MESSAGES_PER_SECOND = 120

    /** Separate budget for console lines, so a chatty logger cannot hide network rows. */
    const val MAX_CONSOLE_MESSAGES_PER_SECOND = 100

    /** Distinct page documents whose request ids are tracked for correlation. */
    const val MAX_TRACKED_DOCUMENTS = 6

    /** Request ids remembered per document. */
    const val MAX_TRACKED_REQUEST_IDS = 600

    /** Hard cap on batches the page may push through the JS bridge per second. */
    const val MAX_BRIDGE_BATCHES_PER_SECOND = 60

    /** Largest JSON batch accepted from the JS bridge, in characters. */
    const val MAX_BRIDGE_BATCH_CHARS = 256_000

    /** UI refresh interval; captures are coalesced into at most this many publications per second. */
    const val PUBLISH_INTERVAL_MS = 250L

    /** Correlation window for matching a page-JS request with a WebView request. */
    const val CORRELATION_WINDOW_MS = 5_000L
}
