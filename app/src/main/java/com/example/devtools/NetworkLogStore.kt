package com.example.devtools

/**
 * The inspector's ring buffer.
 *
 * Bounded on purpose: at most [InspectorLimits.MAX_ENTRIES] request/response pairs and
 * [InspectorLimits.MAX_CONSOLE_ENTRIES] console lines are kept. Old rows are dropped from the head
 * and counted, so long debug sessions cannot grow the app's memory without limit.
 *
 * Threading: every method may be called from the WebView thread, a JavaScript bridge thread, the UI
 * thread or the app's OkHttp dispatcher. All mutation goes through one short `synchronized` block on
 * a private lock — no I/O, no allocation-heavy work and no callbacks while holding the lock.
 */
class NetworkLogStore {

    private val lock = Any()
    private val entries = ArrayList<NetworkEntry>(128)
    private val consoleEntries = ArrayList<ConsoleEntry>(128)
    private val observedCookies = ArrayList<CookieRecord>(32)
    private var nextEntryId = 1L
    private var nextConsoleId = 1L
    private var droppedEntryCount = 0
    private var droppedConsoleCount = 0
    private var revisionCounter = 0L

    /**
     * When this capture session began, on the app clock.
     *
     * The buffers are memory-only, so they hold nothing from before the process started — and at the
     * exact moment a site is busiest, Android is most likely to have killed the process and restored
     * it. Without this stamp an export of a fresh, nearly empty buffer is indistinguishable from an
     * export of a session in which nothing went wrong; with it, the reader can see that the file
     * covers the last 40 seconds rather than the whole attempt.
     */
    private var sessionStartedAtMillis = System.currentTimeMillis()

    /** Counts of the failures the app itself witnessed, in [IncidentKind] order. */
    private val incidents = LinkedHashMap<IncidentKind, Int>()

    /** Increases on every stored change; the UI compares this to decide whether to rebuild. */
    val revision: Long
        get() = synchronized(lock) { revisionCounter }

    fun allocateEntryId(): Long = synchronized(lock) { nextEntryId++ }

    fun allocateConsoleId(): Long = synchronized(lock) { nextConsoleId++ }

    fun addEntry(entry: NetworkEntry) {
        synchronized(lock) {
            if (entries.size >= InspectorLimits.MAX_ENTRIES) {
                entries.removeAt(0)
                droppedEntryCount++
            }
            entries.add(entry)
            revisionCounter++
        }
    }

    /** Replaces one entry in place; returns the stored value or null when it has already been dropped. */
    fun updateEntry(id: Long, transform: (NetworkEntry) -> NetworkEntry): NetworkEntry? {
        synchronized(lock) {
            // Entries are appended in id order, so a backwards scan finds the entry immediately and
            // costs nothing for the common case (the tail).
            for (index in entries.indices.reversed()) {
                val current = entries[index]
                if (current.id == id) {
                    val updated = transform(current)
                    entries[index] = updated
                    revisionCounter++
                    return updated
                }
                if (current.id < id) break
            }
            return null
        }
    }

    /** Applies [transform] to every entry of one tab; returns how many entries changed. */
    fun updateEntriesForTab(tabId: String, transform: (NetworkEntry) -> NetworkEntry): Int {
        synchronized(lock) {
            var changed = 0
            for (index in entries.indices) {
                val current = entries[index]
                if (current.tabId != tabId) continue
                val updated = transform(current)
                if (updated !== current) {
                    entries[index] = updated
                    changed++
                }
            }
            if (changed > 0) revisionCounter++
            return changed
        }
    }

    /**
     * Finds the newest entry of [tabId] whose URL/method correlation key equals [key].
     *
     * [skipPageObserved] is used when attaching page-JS details: an entry that already carries them
     * must not receive them twice. The scan is bounded by [maxScan] so a misbehaving page cannot turn
     * correlation into a hot loop.
     */
    fun findEntry(
        tabId: String,
        key: String,
        skipPageObserved: Boolean,
        maxScan: Int = 40,
    ): NetworkEntry? {
        synchronized(lock) {
            var scanned = 0
            for (index in entries.indices.reversed()) {
                val candidate = entries[index]
                if (candidate.tabId != tabId) continue
                if (scanned >= maxScan) return null
                scanned++
                if (skipPageObserved && candidate.pageObserved) continue
                if (UrlParts.correlationKey(candidate.url, candidate.method) == key) return candidate
            }
            return null
        }
    }

    /**
     * Stores a console line, collapsing a burst of identical lines into one row with a repeat count.
     * Returns the stored entry (which may be an existing row).
     */
    fun addConsole(entry: ConsoleEntry): ConsoleEntry {
        synchronized(lock) {
            val last = consoleEntries.lastOrNull()
            if (last != null &&
                last.tabId == entry.tabId &&
                last.level == entry.level &&
                last.message == entry.message &&
                entry.timestampMillis - last.timestampMillis <= CONSOLE_MERGE_WINDOW_MS
            ) {
                val merged = last.copy(
                    timestampMillis = entry.timestampMillis,
                    repeatCount = last.repeatCount + 1,
                    stackTrace = last.stackTrace ?: entry.stackTrace,
                    masked = last.masked || entry.masked,
                )
                consoleEntries[consoleEntries.size - 1] = merged
                revisionCounter++
                return merged
            }

            if (consoleEntries.size >= InspectorLimits.MAX_CONSOLE_ENTRIES) {
                consoleEntries.removeAt(0)
                droppedConsoleCount++
            }
            consoleEntries.add(entry)
            revisionCounter++
            return entry
        }
    }

    /**
     * Attaches a stack trace from the page's `window.onerror` hook to the console row WebView already
     * reported for the same error, instead of adding a duplicate row.
     */
    fun attachConsoleStack(message: String, stack: String, nowMillis: Long): Boolean {
        synchronized(lock) {
            for (index in consoleEntries.indices.reversed()) {
                val candidate = consoleEntries[index]
                if (nowMillis - candidate.timestampMillis > STACK_ATTACH_WINDOW_MS) return false
                if (candidate.message == message && candidate.stackTrace == null) {
                    consoleEntries[index] = candidate.copy(stackTrace = stack)
                    revisionCounter++
                    return true
                }
            }
            return false
        }
    }

    fun clearEntries() {
        synchronized(lock) {
            entries.clear()
            droppedEntryCount = 0
            revisionCounter++
        }
    }

    fun clearConsole() {
        synchronized(lock) {
            consoleEntries.clear()
            droppedConsoleCount = 0
            revisionCounter++
        }
    }

    fun clearAll() {
        synchronized(lock) {
            entries.clear()
            consoleEntries.clear()
            droppedEntryCount = 0
            droppedConsoleCount = 0
            // A cleared buffer is a new session, so its scope starts again here too; keeping the old
            // start time would make the next export claim to cover time it holds nothing for.
            incidents.clear()
            sessionStartedAtMillis = System.currentTimeMillis()
            revisionCounter++
        }
    }

    fun entryCount(): Int = synchronized(lock) { entries.size }

    fun consoleCount(): Int = synchronized(lock) { consoleEntries.size }

    fun droppedCounts(): Pair<Int, Int> = synchronized(lock) { droppedEntryCount to droppedConsoleCount }

    /** Notes one failure the app witnessed. Counted rather than stored, so it survives eviction. */
    fun recordIncident(kind: IncidentKind) {
        synchronized(lock) { incidents[kind] = (incidents[kind] ?: 0) + 1 }
    }

    /** Incidents this session, in a stable order, excluding kinds that never happened. */
    fun incidents(): List<Pair<IncidentKind, Int>> = synchronized(lock) {
        incidents.entries.sortedBy { it.key.ordinal }.map { it.key to it.value }
    }

    /** When this capture session began; reset by [clearAll], not by [clearEntries]. */
    fun sessionStartedAt(): Long = synchronized(lock) { sessionStartedAtMillis }

    /** Distinct origins seen in this tab, newest first. Used to query cookies for the sites in play. */
    fun originsForTab(tabId: String, limit: Int): List<String> {
        synchronized(lock) {
            val result = LinkedHashSet<String>()
            for (index in entries.indices.reversed()) {
                val entry = entries[index]
                if (entry.tabId != tabId) continue
                val origin = UrlParts.origin(entry.url) ?: continue
                if (result.add(origin) && result.size >= limit) break
            }
            return result.toList()
        }
    }

    /** Newest main-frame document URL for a tab, or null. */
    fun latestDocumentUrl(tabId: String): String? {
        synchronized(lock) {
            for (index in entries.indices.reversed()) {
                val entry = entries[index]
                if (entry.tabId != tabId) continue
                if (entry.request.isForMainFrame == true) return entry.url
            }
            return null
        }
    }

    /** Immutable newest-first view of everything the inspector holds. */
    fun snapshot(nowMillis: Long): InspectorSnapshot {
        val entryCopy: List<NetworkEntry>
        val consoleCopy: List<ConsoleEntry>
        val droppedEntries: Int
        val revision: Long
        synchronized(lock) {
            entryCopy = ArrayList(entries).asReversed()
            consoleCopy = ArrayList(consoleEntries).asReversed()
            droppedEntries = droppedEntryCount
            revision = revisionCounter
        }
        return InspectorSnapshot(
            revision = revision,
            entries = entryCopy,
            console = consoleCopy,
            cookies = emptyList(),
            droppedEntryCount = droppedEntries,
            capturedAtMillis = nowMillis,
        )
    }

    /** Console rows dropped from the ring buffer, shown in the "buffer full" banner. */
    fun droppedConsoleRows(): Int = synchronized(lock) { droppedConsoleCount }

    /**
     * Remembers a cookie scope learned from a `Set-Cookie` header (or from a cookie the app set
     * itself). These are the only rows that can show real Domain/Path/Secure/HttpOnly/Expiry values,
     * so they are kept even when the traffic that produced them has already scrolled out of the ring
     * buffer.
     */
    fun addCookieObservation(record: CookieRecord) {
        synchronized(lock) {
            val existingIndex = observedCookies.indexOfFirst { it.id == record.id }
            if (existingIndex >= 0) {
                observedCookies[existingIndex] = record
            } else {
                if (observedCookies.size >= MAX_OBSERVED_COOKIES) observedCookies.removeAt(0)
                observedCookies.add(record)
            }
            revisionCounter++
        }
    }

    /** Cookie scopes learned from responses, newest last. */
    fun cookieObservations(): List<CookieRecord> = synchronized(lock) { ArrayList(observedCookies) }

    /** Drops one remembered cookie scope (the engine's cookie store reported it removed). */
    fun removeCookieObservation(id: String) {
        synchronized(lock) {
            if (observedCookies.removeIf { it.id == id }) revisionCounter++
        }
    }

    /**
     * Marks requests older than [cutoffAppMillis] that still wait for a response as
     * [EntryState.UNOBSERVED], attaching the explanation built by [note].
     */
    fun expirePendingEntries(cutoffAppMillis: Long, note: String): Int {
        synchronized(lock) {
            var changed = 0
            for (index in entries.indices) {
                val current = entries[index]
                if (current.state != EntryState.PENDING) continue
                if (current.recordedAtAppMillis == 0L || current.recordedAtAppMillis > cutoffAppMillis) continue
                entries[index] = current.copy(
                    state = EntryState.UNOBSERVED,
                    notes = if (current.notes.contains(note)) current.notes else current.notes + note,
                )
                changed++
            }
            if (changed > 0) revisionCounter++
            return changed
        }
    }

    companion object {
        /** Identical consecutive console lines are merged into one row within this window. */
        const val CONSOLE_MERGE_WINDOW_MS = 2_500L

        /** How long after an error WebView's own report may arrive and receive the JS stack trace. */
        const val STACK_ATTACH_WINDOW_MS = 1_500L

        /** Distinct cookie scopes remembered from responses. */
        const val MAX_OBSERVED_COOKIES = 64
    }
}
