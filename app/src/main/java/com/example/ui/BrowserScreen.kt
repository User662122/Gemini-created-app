package com.example.ui

import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.mozilla.geckoview.GeckoView

@Composable
fun BrowserScreen(viewModel: BrowserViewModel) {
    val activeTab = viewModel.activeTab ?: return
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val context = LocalContext.current

    var address by remember(activeTab.id) { mutableStateOf(activeTab.url) }
    var isEditingAddress by remember(activeTab.id) { mutableStateOf(false) }
    var showTabPicker by remember { mutableStateOf(false) }
    var showNetworkInspector by remember { mutableStateOf(false) }
    val exportHarLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) viewModel.exportNetworkLog(context.contentResolver, uri)
    }
    val geckoView = remember(activeTab.id) { mutableStateOf<GeckoView?>(null) }

    LaunchedEffect(activeTab.id, activeTab.url) {
        if (!isEditingAddress) address = activeTab.url
    }

    fun submitAddress() {
        isEditingAddress = false
        focusManager.clearFocus()
        keyboardController?.hide()
        viewModel.navigate(address)
    }

    BackHandler(enabled = activeTab.canGoBack && !showNetworkInspector && !showTabPicker) {
        viewModel.goBack()
    }

    DisposableEffect(activeTab.id) {
        activeTab.session.setActive(true)
        onDispose {
            geckoView.value?.releaseSession()
            geckoView.value = null
            if (activeTab.isClosing) {
                viewModel.finishClosingTab(activeTab.id)
            } else {
                activeTab.session.setActive(false)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 8.dp, end = 4.dp, top = 6.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    OutlinedTextField(
                        value = address,
                        onValueChange = {
                            address = it
                            isEditingAddress = true
                        },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        shape = RoundedCornerShape(24.dp),
                        placeholder = { Text("Search or enter address", maxLines = 1) },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Go,
                            autoCorrect = false,
                        ),
                        keyboardActions = KeyboardActions(onGo = { submitAddress() }),
                    )

                    IconButton(
                        onClick = {
                            if (activeTab.isLoading) viewModel.stopLoading() else viewModel.reload()
                        },
                    ) {
                        Icon(
                            imageVector = if (activeTab.isLoading) Icons.Default.Close else Icons.Default.Refresh,
                            contentDescription = if (activeTab.isLoading) "Stop loading" else "Reload page",
                        )
                    }
                    IconButton(onClick = { viewModel.openNewTab() }) {
                        Icon(Icons.Default.Add, contentDescription = "New tab")
                    }
                    IconButton(onClick = { showTabPicker = true }) {
                        Text(
                            text = viewModel.tabs.size.toString(),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 4.dp, end = 4.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = viewModel::goBack, enabled = activeTab.canGoBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                    IconButton(onClick = viewModel::goForward, enabled = activeTab.canGoForward) {
                        Icon(Icons.Default.ArrowForward, contentDescription = "Forward")
                    }
                    TextButton(onClick = { showNetworkInspector = true }) {
                        Text(
                            text = if (viewModel.isNetworkRecording) {
                                "● REC · Traffic ${viewModel.networkEntries.size}"
                            } else {
                                "Traffic ${viewModel.networkEntries.size}"
                            },
                            color = if (viewModel.isNetworkRecording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        )
                    }
                }

                if (activeTab.isLoading) {
                    LinearProgressIndicator(
                        progress = activeTab.progress / 100f,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp),
                    )
                }
            }
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            key(activeTab.id) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { viewContext ->
                        GeckoView(viewContext).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                            setSession(activeTab.session)
                            geckoView.value = this
                        }
                    },
                    update = { view -> geckoView.value = view },
                )
            }

            if (activeTab.url.isBlank()) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("New tab", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Search or enter a web address",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (showNetworkInspector) {
        NetworkInspectorDialog(
            entries = viewModel.networkEntries,
            captureReady = viewModel.isNetworkCaptureReady,
            recording = viewModel.isNetworkRecording,
            captureError = viewModel.networkCaptureError,
            exportStatus = viewModel.networkExportStatus,
            onRecordingChange = viewModel::setNetworkCaptureEnabled,
            onClear = viewModel::clearNetworkLogs,
            onExport = { exportHarLauncher.launch("network-activity.har") },
            onDismiss = { showNetworkInspector = false },
        )
    }

    if (showTabPicker) {
        AlertDialog(
            onDismissRequest = { showTabPicker = false },
            title = { Text("Tabs") },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    viewModel.tabs.forEach { tab ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(
                                onClick = {
                                    viewModel.selectTab(tab.id)
                                    showTabPicker = false
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Text(
                                        text = tab.title.ifBlank { "New tab" },
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (tab.url.isNotBlank()) {
                                        Text(
                                            text = tab.url,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.labelSmall,
                                        )
                                    }
                                }
                            }
                            IconButton(onClick = { viewModel.closeTab(tab.id) }) {
                                Icon(Icons.Default.Close, contentDescription = "Close tab")
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showTabPicker = false }) {
                    Text("Done")
                }
            },
        )
    }
}
