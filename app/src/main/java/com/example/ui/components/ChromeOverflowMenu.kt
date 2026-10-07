package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.FindInPage
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.BrowserTab
import com.example.ui.theme.ChromeBlue
import com.example.ui.theme.ChromeYellow

@Composable
fun ChromeOverflowMenu(
    expanded: Boolean,
    tab: BrowserTab?,
    isBookmarked: Boolean,
    onDismissRequest: () -> Unit,
    onNewTab: () -> Unit,
    onNewIncognitoTab: () -> Unit,
    onHistory: () -> Unit,
    onBookmarks: () -> Unit,
    onShare: () -> Unit,
    onFindInPage: () -> Unit,
    onToggleDesktopSite: () -> Unit,
    onSettings: () -> Unit,
    onReload: () -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onToggleBookmark: () -> Unit,
    isRecordingAutomation: Boolean,
    onAutomations: () -> Unit,
    /**
     * Opens the developer Network Inspector. Null in any build where the inspector is unavailable
     * (release builds, non-debuggable packages), in which case the menu entry is not rendered at all.
     */
    onNetworkInspector: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        offset = DpOffset(x = (-12).dp, y = 0.dp),
        modifier = modifier
            .width(260.dp)
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
            .testTag("chrome_overflow_menu")
    ) {
        // Quick Actions Row (Back, Forward, Star, Reload)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val canBack = tab?.canGoBack == true
            IconButton(
                onClick = {
                    onBack()
                    onDismissRequest()
                },
                enabled = canBack,
                modifier = Modifier.size(38.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = if (canBack) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                )
            }

            val canForward = tab?.canGoForward == true
            IconButton(
                onClick = {
                    onForward()
                    onDismissRequest()
                },
                enabled = canForward,
                modifier = Modifier.size(38.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = "Forward",
                    tint = if (canForward) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                )
            }

            IconButton(
                onClick = {
                    onToggleBookmark()
                    onDismissRequest()
                },
                modifier = Modifier.size(38.dp)
            ) {
                Icon(
                    imageVector = if (isBookmarked) Icons.Default.Star else Icons.Default.StarBorder,
                    contentDescription = "Bookmark",
                    tint = if (isBookmarked) ChromeYellow else MaterialTheme.colorScheme.onSurface
                )
            }

            IconButton(
                onClick = {
                    onReload()
                    onDismissRequest()
                },
                modifier = Modifier.size(38.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Reload",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        // New tab
        DropdownMenuItem(
            text = { Text("New tab", fontSize = 14.sp) },
            leadingIcon = {
                Icon(
                    Icons.Default.Add,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
            },
            onClick = {
                onNewTab()
                onDismissRequest()
            },
            modifier = Modifier.testTag("menu_new_tab")
        )

        // New incognito tab
        DropdownMenuItem(
            text = { Text("New incognito tab", fontSize = 14.sp) },
            leadingIcon = {
                Icon(
                    Icons.Default.VisibilityOff,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
            },
            onClick = {
                onNewIncognitoTab()
                onDismissRequest()
            },
            modifier = Modifier.testTag("menu_new_incognito_tab")
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        // History
        DropdownMenuItem(
            text = { Text("History", fontSize = 14.sp) },
            leadingIcon = {
                Icon(
                    Icons.Default.History,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
            },
            onClick = {
                onHistory()
                onDismissRequest()
            },
            modifier = Modifier.testTag("menu_history")
        )

        // Bookmarks
        DropdownMenuItem(
            text = { Text("Bookmarks", fontSize = 14.sp) },
            leadingIcon = {
                Icon(
                    Icons.Default.BookmarkBorder,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
            },
            onClick = {
                onBookmarks()
                onDismissRequest()
            },
            modifier = Modifier.testTag("menu_bookmarks")
        )

        // Share
        DropdownMenuItem(
            text = { Text("Share...", fontSize = 14.sp) },
            leadingIcon = {
                Icon(
                    Icons.Default.Share,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
            },
            onClick = {
                onShare()
                onDismissRequest()
            },
            modifier = Modifier.testTag("menu_share")
        )

        // Find in page
        DropdownMenuItem(
            text = { Text("Find in page", fontSize = 14.sp) },
            leadingIcon = {
                Icon(
                    Icons.Default.FindInPage,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
            },
            onClick = {
                onFindInPage()
                onDismissRequest()
            },
            modifier = Modifier.testTag("menu_find_in_page")
        )

        // Desktop site toggle row
        val isDesktop = tab?.isDesktopSite == true
        DropdownMenuItem(
            text = { Text("Desktop site", fontSize = 14.sp) },
            leadingIcon = {
                Icon(
                    Icons.Default.DesktopWindows,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
            },
            trailingIcon = {
                Checkbox(
                    checked = isDesktop,
                    onCheckedChange = {
                        onToggleDesktopSite()
                        onDismissRequest()
                    },
                    colors = CheckboxDefaults.colors(checkedColor = ChromeBlue)
                )
            },
            onClick = {
                onToggleDesktopSite()
                onDismissRequest()
            },
            modifier = Modifier.testTag("menu_desktop_site")
        )

        if (onNetworkInspector != null) {
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            DropdownMenuItem(
                text = { Text("Network Inspector (debug)", fontSize = 14.sp) },
                leadingIcon = {
                    Icon(
                        Icons.Default.BugReport,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                },
                onClick = {
                    onNetworkInspector()
                    onDismissRequest()
                },
                modifier = Modifier.testTag("menu_network_inspector")
            )
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        DropdownMenuItem(
            text = { Text(if (isRecordingAutomation) "Automation recording…" else "Automations", fontSize = 14.sp) },
            leadingIcon = {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
            },
            onClick = {
                onAutomations()
                onDismissRequest()
            },
            modifier = Modifier.testTag("menu_automations")
        )

        // Settings
        DropdownMenuItem(
            text = { Text("Settings", fontSize = 14.sp) },
            leadingIcon = {
                Icon(
                    Icons.Default.Settings,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
            },
            onClick = {
                onSettings()
                onDismissRequest()
            },
            modifier = Modifier.testTag("menu_settings")
        )
    }
}
