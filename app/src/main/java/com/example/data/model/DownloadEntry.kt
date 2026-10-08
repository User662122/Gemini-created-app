package com.example.data.model

/** How a download is going. */
enum class DownloadStatus { RUNNING, COMPLETE, FAILED }

/**
 * One entry in the browser's download list.
 *
 * The WebView engine had no download handling at all — no `DownloadListener`, no downloads screen —
 * so this is new behaviour rather than a port: the same "Downloads" idea the menu and the existing
 * settings text promised, backed by a real file.
 */
data class DownloadEntry(
    val id: String,
    val url: String,
    val fileName: String,
    val mimeType: String?,
    /** Total size when the server declared one, otherwise -1. */
    val totalBytes: Long,
    val bytesWritten: Long,
    val status: DownloadStatus,
    /** Where the file went, in words: "Downloads" or a path the user can find. */
    val location: String,
    /**
     * Set when the file lives in shared storage (MediaStore on Android 10+), which is what makes it
     * openable again from this list. Null when the file was written somewhere only this app can
     * reach, in which case the list says so instead of offering a broken "Open".
     */
    val contentUri: String?,
    val startedAtMillis: Long,
    val finishedAtMillis: Long?,
    val error: String? = null,
) {
    val isRunning: Boolean get() = status == DownloadStatus.RUNNING

    /** 0f..1f, or null when the server never said how big the file is. */
    val progress: Float?
        get() = if (totalBytes > 0L) (bytesWritten.toFloat() / totalBytes).coerceIn(0f, 1f) else null
}
