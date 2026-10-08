package com.example.data

import android.content.Context
import com.example.data.model.DownloadEntry
import com.example.data.model.DownloadStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * The app's record of what it downloaded.
 *
 * Downloads themselves are written by the engine layer (`ui/engine/gecko/GeckoDownloader.kt`); this
 * only remembers them so the Downloads screen still has something to show after a restart. A small
 * `SharedPreferences`-backed JSON list is deliberately used instead of a Room table: it needs no
 * schema migration for an append-only list of at most [MAX_ENTRIES] records, and the browser's
 * database stays about the user's own bookmarks and history.
 *
 * Corrupt or partial JSON is dropped rather than allowed to crash the list — a download record is
 * never worth losing the browser to.
 */
class DownloadRegistry(context: Context) {

    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private val _downloads = MutableStateFlow(read())

    val downloads: StateFlow<List<DownloadEntry>> = _downloads.asStateFlow()

    /** Adds a new entry, or replaces the existing one with the same id. */
    fun upsert(entry: DownloadEntry) {
        _downloads.value = (listOf(entry) + _downloads.value.filterNot { it.id == entry.id })
            .take(MAX_ENTRIES)
        persist()
    }

    fun remove(id: String) {
        _downloads.value = _downloads.value.filterNot { it.id == id }
        persist()
    }

    /** Clears the list. Files already written to shared storage are left where the user can find them. */
    fun clear() {
        _downloads.value = emptyList()
        persist()
    }

    private fun persist() {
        val array = JSONArray()
        _downloads.value.forEach { entry -> array.put(entry.toJson()) }
        preferences.edit().putString(KEY_ENTRIES, array.toString()).apply()
    }

    private fun read(): List<DownloadEntry> {
        val raw = preferences.getString(KEY_ENTRIES, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.toEntry()
            }
        }.getOrDefault(emptyList())
    }

    private fun DownloadEntry.toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("url", url)
        put("fileName", fileName)
        put("mimeType", mimeType ?: JSONObject.NULL)
        put("totalBytes", totalBytes)
        put("bytesWritten", bytesWritten)
        put("status", status.name)
        put("location", location)
        put("contentUri", contentUri ?: JSONObject.NULL)
        put("startedAtMillis", startedAtMillis)
        put("finishedAtMillis", finishedAtMillis ?: JSONObject.NULL)
        put("error", error ?: JSONObject.NULL)
    }

    private fun JSONObject.toEntry(): DownloadEntry? {
        val id = optString("id").takeIf { it.isNotBlank() } ?: return null
        val status = runCatching { DownloadStatus.valueOf(optString("status")) }
            .getOrDefault(DownloadStatus.FAILED)
        return DownloadEntry(
            id = id,
            url = optString("url"),
            fileName = optString("fileName"),
            mimeType = optString("mimeType").takeIf { it.isNotBlank() && it != "null" },
            totalBytes = optLong("totalBytes", -1L),
            bytesWritten = optLong("bytesWritten", 0L),
            status = status,
            location = optString("location"),
            contentUri = optString("contentUri").takeIf { it.isNotBlank() && it != "null" },
            startedAtMillis = optLong("startedAtMillis"),
            finishedAtMillis = optLong("finishedAtMillis").takeIf { has("finishedAtMillis") && !isNull("finishedAtMillis") },
            error = optString("error").takeIf { it.isNotBlank() && it != "null" },
        )
    }

    private companion object {
        const val PREFERENCES_NAME = "browser_downloads"
        const val KEY_ENTRIES = "entries"
        const val MAX_ENTRIES = 100
    }
}
