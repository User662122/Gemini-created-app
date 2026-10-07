package com.example.devtools

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Status buckets offered by the filter sheet, in the order they appear. */
enum class StatusFilter(val label: String) {
    ANY("Any"),
    SUCCESS("2xx"),
    REDIRECT("3xx"),
    CLIENT_ERROR("4xx"),
    SERVER_ERROR("5xx"),
    FAILED("Failed"),
    PENDING("Pending"),
    NOT_OBSERVED("Unobserved");

    fun matches(entry: NetworkEntry): Boolean = when (this) {
        ANY -> true
        SUCCESS -> entry.statusCode in 200..299
        REDIRECT -> entry.statusCode in 300..399
        CLIENT_ERROR -> entry.statusCode in 400..499
        SERVER_ERROR -> entry.statusCode in 500..599
        FAILED -> entry.state == EntryState.FAILED
        PENDING -> entry.state == EntryState.PENDING
        NOT_OBSERVED -> entry.state == EntryState.UNOBSERVED
    }
}

/** Everything the filter bar holds. Immutable so it can live in the inspector's UI state. */
data class InspectorFilterState(
    val category: ResourceCategory = ResourceCategory.ALL,
    val status: StatusFilter = StatusFilter.ANY,
    /** Free-text search across URL, method, status code, resource type and header names. */
    val query: String = "",
    /** Method filter such as "GET" or "POST"; null means any. */
    val method: String? = null,
    /**
     * When true (the default) only the traffic of the visible tab is listed, which is what a developer
     * normally wants. Turning it off shows every tab, newest first.
     */
    val onlyCurrentTab: Boolean = true,
    /** Only rows where the inspector captured a request body. */
    val onlyWithRequestBody: Boolean = false,
    /** Only rows whose captured data was masked or partially dropped by a limit. */
    val onlyWithRedactions: Boolean = false,
) {
    val isDefault: Boolean
        get() = category == ResourceCategory.ALL && status == StatusFilter.ANY && query.isBlank() &&
            method == null && !onlyWithRequestBody && !onlyWithRedactions
}

/**
 * Search and filter logic, kept out of the UI so it can be unit tested and so the list composable
 * only ever receives rows that are already filtered.
 */
object InspectorFilters {

    /**
     * Free-text matching. Every whitespace-separated term must match at least one field:
     * URL, method, status code, resource type, content type, or a header name. That is what makes
     * searches like `POST 404 api` behave the way a developer expects.
     */
    fun matchesQuery(entry: NetworkEntry, query: String): Boolean {
        val terms = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (terms.isEmpty()) return true

        val haystack = StringBuilder(256)
        haystack.append(entry.url.lowercase()).append(' ')
        haystack.append(entry.method.lowercase()).append(' ')
        haystack.append(entry.request.category.label.lowercase()).append(' ')
        entry.statusCode?.let { haystack.append(it).append(' ') }
        entry.request.categoryDetail?.let { haystack.append(it.lowercase()).append(' ') }
        entry.response?.contentType?.value?.let { haystack.append(it.lowercase()).append(' ') }
        entry.response?.reasonPhrase?.value?.let { haystack.append(it.lowercase()).append(' ') }
        entry.request.headers.forEach { haystack.append(it.name.lowercase()).append(' ') }
        entry.response?.headers?.forEach { haystack.append(it.name.lowercase()).append(' ') }
        if (entry.state == EntryState.FAILED) haystack.append("failed ")
        if (entry.state == EntryState.PENDING) haystack.append("pending ")
        if (entry.state == EntryState.UNOBSERVED) haystack.append("unobserved ")
        if (entry.request.body != null) haystack.append("body ")

        val text = haystack.toString()
        return terms.all { text.contains(it) }
    }

    fun matches(entry: NetworkEntry, filter: InspectorFilterState): Boolean {
        if (filter.category != ResourceCategory.ALL && entry.request.category != filter.category) return false
        if (!filter.status.matches(entry)) return false
        if (filter.method != null && !entry.method.equals(filter.method, ignoreCase = true)) return false
        if (filter.onlyWithRequestBody && entry.request.body == null) return false
        if (filter.onlyWithRedactions && !hasRedactions(entry)) return false
        return matchesQuery(entry, filter.query)
    }

    /** Applies the status/category/method/search/redaction filters. Tab scoping is the caller's job. */
    fun apply(entries: List<NetworkEntry>, filter: InspectorFilterState): List<NetworkEntry> {
        if (filter.isDefault) return entries
        return entries.filter { matches(it, filter) }
    }

    /** True when anything in this row was masked or truncated by the inspector's own limits. */
    fun hasRedactions(entry: NetworkEntry): Boolean {
        if (entry.request.headers.any { it.isRedacted }) return true
        if (entry.response?.headers?.any { it.isRedacted } == true) return true
        if (entry.request.body?.truncated == true) return true
        if (entry.response?.body?.truncated == true) return true
        return entry.request.url.contains(Redaction.MASK)
    }
}

/**
 * A row of the network list, formatted once when the snapshot is published.
 *
 * Composables then only draw strings; no date formatting, no URL parsing and no list scanning happens
 * during recomposition.
 */
data class InspectorEntryView(
    val id: Long,
    val url: String,
    val displayUrl: String,
    val host: String,
    val method: String,
    val statusLabel: String,
    val statusCode: Int?,
    val state: EntryState,
    val categoryLabel: String,
    val categorySourceLabel: String?,
    val timeLabel: String,
    val durationLabel: String?,
    val redacted: Boolean,
    val noteCount: Int,
    val pageObserved: Boolean,
)

/** Everything the inspector screens render. */
data class InspectorUiState(
    /** True when this build allows the inspector at all (debug + debuggable + enabled by flag). */
    val available: Boolean = false,
    val enabled: Boolean = false,
    val settings: Map<InspectorSetting, Boolean> = emptyMap(),
    val unavailableReason: String = "",
    val filter: InspectorFilterState = InspectorFilterState(),
    val entries: List<InspectorEntryView> = emptyList(),
    val totalEntries: Int = 0,
    val console: List<ConsoleEntry> = emptyList(),
    val consoleTotal: Int = 0,
    val cookies: List<CookieRecord> = emptyList(),
    val cookieRefreshPending: Boolean = false,
    val openEntry: NetworkEntry? = null,
    val droppedEntries: Int = 0,
    val droppedConsole: Int = 0,
    val droppedByRateLimit: Long = 0L,
    val droppedByPageScript: Long = 0L,
    val currentTabId: String = "",
    /**
     * Changes whenever the injected script must be re-evaluated. The WebView containers watch this
     * value, which is how a settings change reaches every open tab.
     */
    val scriptToken: Long = 0L,
) {
    val activeFilterCount: Int
        get() {
            var count = 0
            if (filter.category != ResourceCategory.ALL) count++
            if (filter.status != StatusFilter.ANY) count++
            if (filter.query.isNotBlank()) count++
            if (filter.method != null) count++
            if (filter.onlyWithRequestBody) count++
            if (filter.onlyWithRedactions) count++
            if (!filter.onlyCurrentTab) count++
            return count
        }
}

/** Formats the inspector's display strings. Main-thread only (see [InspectorController.publish]). */
object InspectorFormatting {

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun time(millis: Long): String = timeFormat.format(Date(millis))

    fun statusLabel(entry: NetworkEntry): String {
        val status = entry.response?.statusCode
        if (status?.isKnown == true) {
            val code = status.value.toString()
            return if (status.isInferred) "$code (inferred)" else code
        }
        return when (entry.state) {
            EntryState.FAILED -> "failed"
            EntryState.PENDING -> "…"
            EntryState.UNOBSERVED -> "n/a"
            EntryState.RECEIVED -> "—"
        }
    }

    fun duration(millis: Long?): String? = millis?.let { if (it < 1_000) "$it ms" else "%.2f s".format(it / 1_000.0) }

    /** Short URL for the list: host + path + query, without the scheme. */
    fun shortUrl(url: String, maxChars: Int = 96): String {
        val withoutScheme = url.substringAfter("://", url)
        return Redaction.truncate(withoutScheme, maxChars)
    }
}
