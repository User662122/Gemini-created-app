package com.example.devtools.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.devtools.ExportFormat
import com.example.devtools.InspectorDownload
import com.example.devtools.InspectorEntryView
import com.example.devtools.InspectorExport
import com.example.devtools.InspectorFilterState
import com.example.devtools.InspectorSetting
import com.example.devtools.InspectorUiState
import com.example.devtools.ResourceCategory
import com.example.devtools.StatusFilter
import com.example.ui.engine.BrowserEngineKind
import com.example.devtools.ui.theme.InspectorAmber
import com.example.devtools.ui.theme.InspectorGreen
import com.example.devtools.ui.theme.InspectorGrey
import com.example.devtools.ui.theme.InspectorIndigo
import com.example.devtools.ui.theme.InspectorRed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Tabs of the inspector shell. */
enum class InspectorTab(val label: String) {
    NETWORK("Network"),
    COOKIES("Cookies"),
    CONSOLE("Console"),
    SETTINGS("Info"),
}

/**
 * The Network Inspector shell: header, tabs and the four screens.
 *
 * It is rendered as a full-screen overlay by `BrowserScreen`, exactly like the existing bookmarks and
 * history screens, so it inherits the app's back handling and theming.
 */
@Composable
fun NetworkInspectorScreen(
    state: InspectorUiState,
    onClose: () -> Unit,
    onFilterChange: (InspectorFilterState) -> Unit,
    onOpenEntry: (Long) -> Unit,
    onCloseEntry: () -> Unit,
    onClearEntries: () -> Unit,
    onClearConsole: () -> Unit,
    onClearAll: () -> Unit,
    onRefreshCookies: () -> Unit,
    onSettingChange: (InspectorSetting, Boolean) -> Unit,
    onEndSession: () -> Unit,
    /** Renders the whole session as text; the screen writes it to a file itself. */
    onBuildExport: (ExportFormat) -> String,
    modifier: Modifier = Modifier,
) {
    var tab by remember { mutableStateOf(InspectorTab.NETWORK) }
    val revealSensitiveValues = state.settings[InspectorSetting.FULL_CAPTURE] == true

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var pendingDocumentContent by remember { mutableStateOf<String?>(null) }

    /** Confirms an export in-place, with a one-tap Open when there is a document to open. */
    fun confirm(message: String, uri: Uri? = null, mimeType: String? = null) {
        scope.launch {
            val action = if (uri != null && mimeType != null) "Open" else null
            val result = snackbarHostState.showSnackbar(
                message = message,
                actionLabel = action,
                duration = SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed && uri != null && mimeType != null) {
                InspectorDownload.openUri(context, uri, mimeType)
            }
        }
    }

    /** Second half of the pre-Android-10 path, once the user has chosen where to save. */
    fun writeChosenDocument(uri: Uri?, format: ExportFormat) {
        val content = pendingDocumentContent
        pendingDocumentContent = null
        if (uri == null || content == null) return
        scope.launch {
            val written = withContext(Dispatchers.IO) { InspectorDownload.writeToUri(context, uri, content) }
            confirm(
                message = if (written) {
                    "Saved ${uri.lastPathSegment ?: "the export"}"
                } else {
                    "Could not write the export to that location"
                },
                uri = uri.takeIf { written },
                mimeType = format.mimeType,
            )
        }
    }

    val textDocumentSaver = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(ExportFormat.TEXT.mimeType)
    ) { uri -> writeChosenDocument(uri, ExportFormat.TEXT) }

    val jsonDocumentSaver = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(ExportFormat.JSON.mimeType)
    ) { uri -> writeChosenDocument(uri, ExportFormat.JSON) }

    /**
     * One tap: render everything, then either drop it in Downloads (Android 10+) or ask the system
     * where to put it (older versions, which would otherwise need a storage permission this app does
     * not ask for).
     */
    fun saveExport(format: ExportFormat) {
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                val content = runCatching { onBuildExport(format) }.getOrDefault("")
                if (content.isEmpty()) {
                    ExportOutcome.NothingCaptured
                } else {
                    val name = InspectorExport.fileName(format, System.currentTimeMillis())
                    val uri = InspectorDownload.saveToPublicDownloads(context, name, format.mimeType, content)
                    if (uri != null) {
                        ExportOutcome.Saved(name, uri, format.mimeType)
                    } else {
                        ExportOutcome.NeedsSaveDialog(name, content)
                    }
                }
            }
            when (outcome) {
                is ExportOutcome.Saved -> confirm(
                    message = "Saved to ${InspectorDownload.publicDownloadsLocation()}/${outcome.name}",
                    uri = outcome.uri,
                    mimeType = outcome.mimeType,
                )

                is ExportOutcome.NeedsSaveDialog -> {
                    pendingDocumentContent = outcome.content
                    if (format == ExportFormat.TEXT) {
                        textDocumentSaver.launch(outcome.name)
                    } else {
                        jsonDocumentSaver.launch(outcome.name)
                    }
                }

                ExportOutcome.NothingCaptured -> confirm("Nothing has been captured yet")
            }
        }
    }

    Surface(
        modifier = modifier
            .fillMaxSize()
            .testTag("network_inspector_screen"),
        color = MaterialTheme.colorScheme.background,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier.testTag("inspector_back_button"),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Network Inspector",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = if (state.enabled) {
                                "Debug build · capture on · ${state.totalEntries} request(s) in buffer"
                            } else {
                                "Capture is switched off"
                            },
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(
                        onClick = { saveExport(ExportFormat.TEXT) },
                        enabled = state.enabled,
                        modifier = Modifier.testTag("inspector_download_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = "Download report",
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    InspectorBadge(text = "DEBUG ONLY", color = InspectorIndigo)
                    Spacer(modifier = Modifier.size(4.dp))
                }

                FullCaptureBanner(revealSensitiveValues = revealSensitiveValues)

                ScrollableTabRow(
                    selectedTabIndex = tab.ordinal,
                    edgePadding = 12.dp,
                    modifier = Modifier.testTag("inspector_tabs"),
                ) {
                    InspectorTab.values().forEach { candidate ->
                        Tab(
                            selected = tab == candidate,
                            onClick = { tab = candidate },
                            text = {
                                Text(
                                    text = when (candidate) {
                                        InspectorTab.NETWORK -> "Network (${state.totalEntries})"
                                        InspectorTab.COOKIES -> "Cookies (${state.cookies.size})"
                                        InspectorTab.CONSOLE -> "Console (${state.consoleTotal})"
                                        InspectorTab.SETTINGS -> "Info"
                                    },
                                    fontSize = 13.sp,
                                )
                            },
                        )
                    }
                }

                InspectorLimitBanner(
                    droppedEntries = state.droppedEntries,
                    droppedConsole = state.droppedConsole,
                    droppedByRateLimit = state.droppedByRateLimit,
                    droppedByPageScript = state.droppedByPageScript,
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    when (tab) {
                        InspectorTab.NETWORK -> NetworkTrafficTab(
                            state = state,
                            onFilterChange = onFilterChange,
                            onOpenEntry = onOpenEntry,
                            onClearEntries = onClearEntries,
                        )
                        InspectorTab.COOKIES -> CookieInspectorTab(
                            state = state,
                            onRefresh = onRefreshCookies,
                        )
                        InspectorTab.CONSOLE -> ConsoleInspectorTab(
                            state = state,
                            onClearConsole = onClearConsole,
                        )
                        InspectorTab.SETTINGS -> InspectorInfoTab(
                            state = state,
                            onSettingChange = onSettingChange,
                            onClearAll = onClearAll,
                            onEndSession = onEndSession,
                            onExport = { format -> saveExport(format) },
                            engine = BrowserEngineKind.current(context),
                        )
                    }
                }
            }

            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(8.dp),
            )
        }
    }

    state.openEntry?.let { entry ->
        RequestDetailScreen(
            entry = entry,
            revealSensitiveValues = revealSensitiveValues,
            onBack = onCloseEntry,
        )
    }
}

/** What one tap on an export button produced. */
private sealed interface ExportOutcome {
    /** Written to the public Downloads folder; the document can be opened straight away. */
    data class Saved(val name: String, val uri: Uri, val mimeType: String) : ExportOutcome

    /** Pre-Android-10: the text is waiting for the user to pick a location. */
    data class NeedsSaveDialog(val name: String, val content: String) : ExportOutcome

    /** The buffers are empty (or capture is off), so there is nothing to write. */
    object NothingCaptured : ExportOutcome
}

/** Network list: search, filter chips, and one row per request/response pair. */
@Composable
private fun NetworkTrafficTab(
    state: InspectorUiState,
    onFilterChange: (InspectorFilterState) -> Unit,
    onOpenEntry: (Long) -> Unit,
    onClearEntries: () -> Unit,
) {
    var showExtraFilters by remember { mutableStateOf(false) }
    val filter = state.filter

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = filter.query,
                onValueChange = { onFilterChange(filter.copy(query = it)) },
                modifier = Modifier
                    .weight(1f)
                    .testTag("inspector_search_field"),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall,
                placeholder = { Text("Search URL, method, status, type…", fontSize = 12.sp) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (filter.query.isNotEmpty()) {
                        IconButton(onClick = { onFilterChange(filter.copy(query = "")) }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear search")
                        }
                    }
                },
            )
            TextButton(
                onClick = { showExtraFilters = !showExtraFilters },
                modifier = Modifier.testTag("inspector_filter_toggle"),
            ) {
                Text(
                    text = if (state.activeFilterCount > 0) {
                        "Filters (${state.activeFilterCount})"
                    } else {
                        "Filters"
                    },
                    fontSize = 12.sp,
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ResourceCategory.values().forEach { category ->
                FilterChip(
                    selected = filter.category == category,
                    onClick = { onFilterChange(filter.copy(category = category)) },
                    label = { Text(category.label, fontSize = 12.sp) },
                    modifier = Modifier.testTag("inspector_category_${category.name.lowercase()}"),
                    colors = FilterChipDefaults.filterChipColors(),
                )
            }
        }

        if (showExtraFilters) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                StatusFilter.values().forEach { status ->
                    FilterChip(
                        selected = filter.status == status,
                        onClick = { onFilterChange(filter.copy(status = status)) },
                        label = { Text(status.label, fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(),
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                listOf("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS").forEach { method ->
                    FilterChip(
                        selected = filter.method == method,
                        onClick = {
                            onFilterChange(
                                filter.copy(method = if (filter.method == method) null else method)
                            )
                        },
                        label = { Text(method, fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(),
                    )
                }
                FilterChip(
                    selected = !filter.onlyCurrentTab,
                    onClick = { onFilterChange(filter.copy(onlyCurrentTab = !filter.onlyCurrentTab)) },
                    label = { Text("All tabs", fontSize = 12.sp) },
                    colors = FilterChipDefaults.filterChipColors(),
                )
                FilterChip(
                    selected = filter.onlyWithRequestBody,
                    onClick = { onFilterChange(filter.copy(onlyWithRequestBody = !filter.onlyWithRequestBody)) },
                    label = { Text("Has body", fontSize = 12.sp) },
                    colors = FilterChipDefaults.filterChipColors(),
                )
                FilterChip(
                    selected = filter.onlyWithRedactions,
                    onClick = { onFilterChange(filter.copy(onlyWithRedactions = !filter.onlyWithRedactions)) },
                    label = { Text("Masked", fontSize = 12.sp) },
                    colors = FilterChipDefaults.filterChipColors(),
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${state.entries.size} of ${state.totalEntries} shown",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onClearEntries, modifier = Modifier.testTag("inspector_clear_entries")) {
                Text("Clear", fontSize = 12.sp)
            }
        }

        if (state.entries.isEmpty()) {
            InspectorEmptyState(
                title = "No requests match",
                message = if (state.totalEntries == 0) {
                    "Browse a page and requests will appear here. Requests that WebView serves without " +
                        "a callback (cache hits) cannot be shown, and this is stated in the detail view " +
                        "for every row."
                } else {
                    "Adjust the filters or the search text."
                },
            )
            return@Column
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(items = state.entries, key = { it.id }) { entry ->
                NetworkEntryRow(entry = entry, onClick = { onOpenEntry(entry.id) })
            }
            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}

/** One row: method, status, host/path, category and timing. */
@Composable
private fun NetworkEntryRow(entry: InspectorEntryView, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .testTag("inspector_entry_${entry.id}"),
        verticalAlignment = Alignment.Top,
    ) {
        InspectorBadge(text = entry.method, color = methodColor(entry.method))
        Spacer(modifier = Modifier.size(6.dp))
        InspectorBadge(
            text = entry.statusLabel,
            color = statusColor(entry.statusCode, entry.state),
        )
        Spacer(modifier = Modifier.size(6.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.displayUrl,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = entry.timeLabel,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = entry.categoryLabel + (entry.categorySourceLabel?.let { " · $it" } ?: ""),
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                entry.durationLabel?.let {
                    Text(text = it, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (entry.redacted) {
                    Text(text = "masked", fontSize = 10.sp, color = InspectorIndigo)
                }
                if (entry.noteCount > 0) {
                    Text(
                        text = if (entry.noteCount == 1) "1 note" else "${entry.noteCount} notes",
                        fontSize = 10.sp,
                        color = InspectorIndigo,
                    )
                }
            }
        }
    }
}

private fun methodColor(method: String): Color = when (method.uppercase()) {
    "GET" -> InspectorGreen
    "POST" -> InspectorIndigo
    "PUT", "PATCH" -> InspectorAmber
    "DELETE" -> InspectorRed
    else -> InspectorGrey
}
