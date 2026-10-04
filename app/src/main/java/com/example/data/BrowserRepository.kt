package com.example.data

import com.example.data.dao.BookmarkDao
import com.example.data.dao.HistoryDao
import com.example.data.model.Bookmark
import com.example.data.model.HistoryItem
import kotlinx.coroutines.flow.Flow

class BrowserRepository(
    private val bookmarkDao: BookmarkDao,
    private val historyDao: HistoryDao
) {
    val allBookmarks: Flow<List<Bookmark>> = bookmarkDao.getAllBookmarks()
    val allHistory: Flow<List<HistoryItem>> = historyDao.getAllHistory()

    fun isBookmarked(url: String): Flow<Boolean> = bookmarkDao.isBookmarked(url)

    fun searchHistory(query: String): Flow<List<HistoryItem>> = historyDao.searchHistory(query)

    suspend fun addBookmark(title: String, url: String, folder: String = "Mobile Bookmarks"): Long {
        return bookmarkDao.insertBookmark(
            Bookmark(
                title = title.ifBlank { url },
                url = url,
                folder = folder
            )
        )
    }

    suspend fun deleteBookmark(bookmark: Bookmark) {
        bookmarkDao.deleteBookmark(bookmark)
    }

    suspend fun deleteBookmarkById(id: Long) {
        bookmarkDao.deleteById(id)
    }

    suspend fun deleteBookmarkByUrl(url: String) {
        bookmarkDao.deleteByUrl(url)
    }

    suspend fun addHistory(title: String, url: String, isIncognito: Boolean) {
        if (isIncognito || url.isBlank() || url == "chrome://newtab" || url == "about:blank") return
        historyDao.insertHistory(
            HistoryItem(
                title = title.ifBlank { url },
                url = url,
                visitedAt = System.currentTimeMillis(),
                isIncognito = false
            )
        )
    }

    suspend fun deleteHistoryItem(item: HistoryItem) {
        historyDao.deleteHistory(item)
    }

    suspend fun deleteHistoryById(id: Long) {
        historyDao.deleteById(id)
    }

    suspend fun clearAllHistory() {
        historyDao.clearAllHistory()
    }
}
