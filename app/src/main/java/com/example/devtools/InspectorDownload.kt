package com.example.devtools

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore

/**
 * Writes an inspector export to a file the user can actually get at.
 *
 * Two paths, both without a single new permission:
 *
 *  * **Android 10 (API 29) and newer** — inserted straight into the public Downloads collection via
 *    `MediaStore`. One tap in the inspector, and the file appears in the Downloads app, visible to any
 *    file manager and to USB/adb pull. `IS_PENDING` is used so a half-written file is never published.
 *  * **Android 9 and older** — writing to the public Downloads directory needs `WRITE_EXTERNAL_STORAGE`,
 *    which this app deliberately does not request. The inspector instead hands the text to the Storage
 *    Access Framework ([android.content.Intent.ACTION_CREATE_DOCUMENT]); the user picks the location
 *    (Downloads is the default) and the system gives the app a writable URI.
 *
 * Nothing here is reachable outside a debug build: the whole inspector is gated by [DevToolsGate], and
 * the export entry points live behind `InspectorRuntime.buildExport`.
 */
object InspectorDownload {

    /** Sub-folder created inside Downloads, so exports do not litter the root. */
    const val FOLDER_NAME = "NetworkInspector"

    /**
     * Saves to the public Downloads folder. Returns the created document, or null when this device is
     * older than API 29 (the caller then uses the Storage Access Framework) or the write failed.
     *
     * `MediaStore.Downloads` itself exists only from API 29, which is exactly why the version check is
     * the first statement here.
     */
    fun saveToPublicDownloads(
        context: Context,
        fileName: String,
        mimeType: String,
        content: String,
    ): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val resolver = context.applicationContext.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val pending = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER_NAME)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val uri = runCatching { resolver.insert(collection, pending) }.getOrNull() ?: return null
        val written = runCatching {
            resolver.openOutputStream(uri, "w")?.use { stream ->
                stream.write(content.toByteArray(Charsets.UTF_8))
            } != null
        }.getOrDefault(false)

        if (!written) {
            runCatching { resolver.delete(uri, null, null) }
            return null
        }

        val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        runCatching { resolver.update(uri, done, null, null) }
        return uri
    }

    /** Writes to a URI the user chose through `ACTION_CREATE_DOCUMENT`. */
    fun writeToUri(context: Context, uri: Uri, content: String): Boolean = runCatching {
        context.applicationContext.contentResolver.openOutputStream(uri, "w")?.use { stream ->
            stream.write(content.toByteArray(Charsets.UTF_8))
        } != null
    }.getOrDefault(false)

    /** Opens the exported file in whatever app can display it (text editor, viewer, …). */
    fun openUri(context: Context, uri: Uri, mimeType: String): Boolean = runCatching {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.applicationContext.startActivity(intent)
        true
    }.getOrDefault(false)

    /** Shares the exported file (mail, chat, cloud drive …). */
    fun shareUri(context: Context, uri: Uri, mimeType: String, subject: String): Boolean = runCatching {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, subject)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(intent, subject).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.applicationContext.startActivity(chooser)
        true
    }.getOrDefault(false)

    /** Human-readable destination shown in the confirmation message. */
    fun publicDownloadsLocation(): String = "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER_NAME"
}
