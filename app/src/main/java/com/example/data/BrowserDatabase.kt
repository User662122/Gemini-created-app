package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.dao.BookmarkDao
import com.example.data.dao.HistoryDao
import com.example.data.model.Bookmark
import com.example.data.model.HistoryItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Database(
    entities = [Bookmark::class, HistoryItem::class],
    version = 1,
    exportSchema = false
)
abstract class BrowserDatabase : RoomDatabase() {
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun historyDao(): HistoryDao

    companion object {
        @Volatile
        private var INSTANCE: BrowserDatabase? = null

        fun getDatabase(context: Context): BrowserDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    BrowserDatabase::class.java,
                    "chrome_browser_database"
                )
                    .addCallback(object : Callback() {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            super.onCreate(db)
                            CoroutineScope(Dispatchers.IO).launch {
                                populateDefaultBookmarks(getDatabase(context).bookmarkDao())
                            }
                        }
                    })
                    .build()
                INSTANCE = instance
                instance
            }
        }

        private suspend fun populateDefaultBookmarks(dao: BookmarkDao) {
            val defaults = listOf(
                Bookmark(title = "Google", url = "https://www.google.com"),
                Bookmark(title = "Wikipedia", url = "https://www.wikipedia.org"),
                Bookmark(title = "GitHub", url = "https://www.github.com"),
                Bookmark(title = "Android Developers", url = "https://developer.android.com"),
                Bookmark(title = "Reddit", url = "https://www.reddit.com"),
                Bookmark(title = "BBC News", url = "https://www.bbc.com/news")
            )
            defaults.forEach { dao.insertBookmark(it) }
        }
    }
}
