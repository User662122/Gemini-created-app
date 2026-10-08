package com.example.ui.engine.gecko

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.example.data.DownloadRegistry
import com.example.data.model.DownloadEntry
import com.example.data.model.DownloadStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.mozilla.geckoview.WebResponse
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Writes downloads to disk.
 *
 * GeckoView hands a download to the app as a [WebResponse] with an open body, precisely so the app
 * can keep the bytes the engine already fetched — cookies, session, redirects and all. Nothing here
 * re-fetches the URL (no `DownloadManager.enqueue`, which would issue a second, unauthenticated
 * request) and nothing reaches around the engine, it just copies the stream Gecko produced.
 *
 * Destinations:
 *
 *  * **Android 10+** — the public Downloads collection through `MediaStore`. No storage permission
 *    is needed, the file shows up in the system Downloads app, and the entry keeps a `content://` URI
 *    so it can be opened again from this browser.
 *  * **Android 9 and older** — the public Downloads directory when the app holds
 *    `WRITE_EXTERNAL_STORAGE`, and otherwise this app's own external files directory, with the
 *    location said out loud rather than a download that silently goes nowhere.
 */
class GeckoDownloader(
    context: Context,
    private val registry: DownloadRegistry,
    private val onStatus: (String) -> Unit,
    /** Hand a URL to another app (a store link that Gecko decided is not a download). */
    private val onExternalApp: (String) -> Unit,
) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun handle(tabId: String, pageUrl: String, response: WebResponse) {
        if (response.requestExternalApp == true) {
            // Gecko decided this response belongs to another app rather than to Downloads.
            onExternalApp(response.uri)
            return
        }

        val body = response.body
        if (body == null) {
            onStatus("Nothing was downloaded: the server sent no file content.")
            return
        }
        // A slow server must not be mistaken for a dead one: the engine's default read timeout is
        // short for a large file on a weak connection.
        response.setReadTimeoutMillis(READ_TIMEOUT_MS)

        val displayName = suggestedFileName(response)
        val mimeType = headerValue(response, "content-type")?.substringBefore(';')?.trim()
        val totalBytes = headerValue(response, "content-length")?.trim()?.toLongOrNull() ?: -1L

        val entry = DownloadEntry(
            id = UUID.randomUUID().toString(),
            url = response.uri,
            fileName = displayName,
            mimeType = mimeType,
            totalBytes = totalBytes,
            bytesWritten = 0L,
            status = DownloadStatus.RUNNING,
            location = destinationLabel(),
            contentUri = null,
            startedAtMillis = System.currentTimeMillis(),
            finishedAtMillis = null,
        )
        registry.upsert(entry)
        onStatus("Downloading ${entry.fileName}…")

        scope.launch {
            runDownload(entry, body)
        }
    }

    private fun runDownload(entry: DownloadEntry, body: InputStream) {
        var lastReportedBytes = 0L
        try {
            val target = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> writeToMediaStore(entry, body, registry) { written ->
                    if (written - lastReportedBytes >= PROGRESS_STEP_BYTES) {
                        lastReportedBytes = written
                        registry.upsert(entry.copy(bytesWritten = written))
                    }
                }

                else -> writeToLegacyFile(entry, body) { written ->
                    if (written - lastReportedBytes >= PROGRESS_STEP_BYTES) {
                        lastReportedBytes = written
                        registry.upsert(
                            entry.copy(bytesWritten = written, location = legacyDestinationLabel())
                        )
                    }
                }
            }

            registry.upsert(
                entry.copy(
                    bytesWritten = target.bytesWritten,
                    totalBytes = if (entry.totalBytes > 0L) entry.totalBytes else target.bytesWritten,
                    status = DownloadStatus.COMPLETE,
                    location = target.location,
                    contentUri = target.contentUri,
                    finishedAtMillis = System.currentTimeMillis(),
                )
            )
            onStatus("Downloaded ${entry.fileName} to ${target.location}.")
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            registry.upsert(
                entry.copy(
                    status = DownloadStatus.FAILED,
                    finishedAtMillis = System.currentTimeMillis(),
                    error = error.message ?: error.javaClass.simpleName,
                )
            )
            onStatus("Download failed: ${error.message ?: "the file could not be written"}.")
        } finally {
            runCatching { body.close() }
        }
    }

    private class Written(
        val bytesWritten: Long,
        val location: String,
        val contentUri: String?,
    )

    /** Android 10+: the public Downloads collection, with no storage permission required. */
    private fun writeToMediaStore(
        entry: DownloadEntry,
        body: InputStream,
        registry: DownloadRegistry,
        onProgress: (Long) -> Unit,
    ): Written {
        val resolver = appContext.contentResolver
        val displayName = uniqueMediaStoreName(resolver, entry.fileName)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, entry.mimeType ?: DEFAULT_MIME_TYPE)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val uri: Uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("the Downloads collection refused the file")

        try {
            val written = resolver.openOutputStream(uri)?.use { output ->
                copyWithProgress(body, output, onProgress)
            } ?: throw IOException("the Downloads collection gave no writable stream")

            val publish = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, publish, null, null)
            registry.upsert(entry.copy(fileName = displayName))
            return Written(written, "Downloads", uri.toString())
        } catch (error: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw error
        }
    }

    /** Android 9 and older: a plain file in Downloads, or in this app's own files directory. */
    private fun writeToLegacyFile(
        entry: DownloadEntry,
        body: InputStream,
        onProgress: (Long) -> Unit,
    ): Written {
        val publicDirectory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val canWritePublicly = publicDirectory != null &&
            (publicDirectory.exists() || publicDirectory.mkdirs()) &&
            publicDirectory.canWrite() &&
            hasLegacyWritePermission()

        val directory = if (canWritePublicly) {
            publicDirectory!!
        } else {
            appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: appContext.filesDir
        }

        val file = uniqueFile(directory, entry.fileName)
        file.outputStream().use { output -> copyWithProgress(body, output, onProgress) }
        return Written(
            bytesWritten = file.length(),
            location = if (canWritePublicly) "Downloads" else file.parent ?: directory.absolutePath,
            contentUri = null,
        )
    }

    private fun hasLegacyWritePermission(): Boolean =
        appContext.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun destinationLabel(): String = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        "Downloads"
    } else {
        legacyDestinationLabel()
    }

    private fun legacyDestinationLabel(): String {
        val publicDirectory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val canWritePublicly = publicDirectory != null && publicDirectory.canWrite() && hasLegacyWritePermission()
        return if (canWritePublicly) "Downloads" else "this app's files"
    }

    private fun copyWithProgress(
        input: InputStream,
        output: OutputStream,
        onProgress: (Long) -> Unit,
    ): Long {
        val buffer = ByteArray(BUFFER_SIZE_BYTES)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            output.write(buffer, 0, read)
            total += read
            onProgress(total)
        }
        output.flush()
        return total
    }

    /** Keeps MediaStore from being handed a display name that already exists in Downloads. */
    private fun uniqueMediaStoreName(resolver: android.content.ContentResolver, name: String): String {
        val existing = runCatching {
            val projection = arrayOf(MediaStore.MediaColumns.DISPLAY_NAME)
            resolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                projection,
                "${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                arrayOf("${Environment.DIRECTORY_DOWNLOADS}/"),
                null
            )?.use { cursor ->
                buildSet {
                    val column = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                    while (cursor.moveToNext()) {
                        if (column >= 0) add(cursor.getString(column))
                    }
                }
            }
        }.getOrNull().orEmpty()

        if (name !in existing) return name
        val base = name.substringBeforeLast('.', name)
        val extension = name.substringAfterLast('.', "")
        var index = 1
        while (true) {
            val candidate = if (extension.isEmpty()) "$base ($index)" else "$base ($index).$extension"
            if (candidate !in existing) return candidate
            index += 1
        }
    }

    private fun uniqueFile(directory: File, name: String): File {
        val candidate = File(directory, name)
        if (!candidate.exists()) return candidate
        val base = name.substringBeforeLast('.', name)
        val extension = name.substringAfterLast('.', "")
        var index = 1
        while (true) {
            val next = if (extension.isEmpty()) "$base ($index)" else "$base ($index).$extension"
            val file = File(directory, next)
            if (!file.exists()) return file
            index += 1
        }
    }

    private fun headerValue(response: WebResponse, name: String): String? =
        response.headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    private companion object {
        const val BUFFER_SIZE_BYTES = 64 * 1024
        const val PROGRESS_STEP_BYTES = 256 * 1024L
        const val DEFAULT_MIME_TYPE = "application/octet-stream"
        const val READ_TIMEOUT_MS = 120_000L
    }
}

/**
 * The file name a browser would use for a response.
 *
 * `Content-Disposition` wins because it is the server's own suggestion; otherwise the last path
 * segment of the URL. The result is stripped of path separators and control characters so a hostile
 * header cannot escape the Downloads directory.
 */
internal fun suggestedFileName(response: WebResponse): String {
    val disposition = response.headers.entries
        .firstOrNull { it.key.equals("content-disposition", ignoreCase = true) }
        ?.value
    val fromHeader = disposition?.let { parseContentDispositionFileName(it) }
    val candidate = fromHeader
        ?: Uri.parse(response.uri).lastPathSegment
        ?: "download"
    return sanitizeFileName(candidate)
}

internal fun parseContentDispositionFileName(disposition: String): String? {
    // RFC 6266: filename*=UTF-8''name with percent-escapes wins over the plain filename= form.
    val extended = Regex("filename\\*\\s*=\\s*([^;]+)", RegexOption.IGNORE_CASE)
        .find(disposition)?.groupValues?.get(1)?.trim()
    if (extended != null) {
        val value = extended.substringAfter("''", extended).trim('"', '\'')
        val decoded = runCatching { Uri.decode(value) }.getOrDefault(value)
        if (decoded.isNotBlank()) return decoded
    }
    val plain = Regex("filename\\s*=\\s*(\"[^\"]*\"|[^;]+)", RegexOption.IGNORE_CASE)
        .find(disposition)?.groupValues?.get(1)?.trim()?.trim('"')
    return plain?.takeIf { it.isNotBlank() }
}

internal fun sanitizeFileName(name: String): String {
    val stripped = name
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .map { character -> if (character.code < 0x20 || character == '\u007f') '_' else character }
        .joinToString("")
        .trim()
        .trimStart('.')
        .ifBlank { "download" }
    return stripped.take(MAX_FILE_NAME_LENGTH)
}

private const val MAX_FILE_NAME_LENGTH = 120
