package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Tab
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.BrowserTab
import com.example.ui.theme.ChromeBlue
import com.example.ui.theme.ChromeDarkBg
import com.example.ui.theme.ChromeDarkSurface
import com.example.ui.theme.IncognitoBg
import com.example.ui.theme.IncognitoSurface

@Composable
fun TabSwitcherSheet(
    regularTabs: List<BrowserTab>,
    activeRegularTabId: String,
    incognitoTabs: List<BrowserTab>,
    activeIncognitoTabId: String,
    isIncognitoView: Boolean,
    onTabSelected: (String, Boolean) -> Unit,
    onTabClosed: (String, Boolean) -> Unit,
    onCloseAllTabs: (Boolean) -> Unit,
    onNewTab: (Boolean) -> Unit,
    onSwitchIncognitoView: (Boolean) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showMenu by remember { mutableStateOf(false) }

    val currentTabs = if (isIncognitoView) incognitoTabs else regularTabs
    val activeTabId = if (isIncognitoView) activeIncognitoTabId else activeRegularTabId
    val bgColor = if (isIncognitoView) IncognitoBg else MaterialTheme.colorScheme.background

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(bgColor)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header Top Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Segment Switcher: Regular vs Incognito
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (isIncognitoView) Color(0xFF2E2E2E) else MaterialTheme.colorScheme.surfaceVariant)
                        .padding(3.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Regular tabs toggle
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (!isIncognitoView) MaterialTheme.colorScheme.surface else Color.Transparent)
                            .clickable { onSwitchIncognitoView(false) }
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                            .testTag("tab_switch_regular"),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Tab,
                                contentDescription = "Regular tabs",
                                tint = if (!isIncognitoView) ChromeBlue else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "${regularTabs.size}",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (!isIncognitoView) ChromeBlue else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // Incognito tabs toggle
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (isIncognitoView) Color(0xFF454545) else Color.Transparent)
                            .clickable { onSwitchIncognitoView(true) }
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                            .testTag("tab_switch_incognito"),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.VisibilityOff,
                                contentDescription = "Incognito tabs",
                                tint = if (isIncognitoView) Color(0xFFE8EAED) else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "${incognitoTabs.size}",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isIncognitoView) Color(0xFFE8EAED) else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // Right controls: Menu (Close all tabs) & Done
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box {
                        IconButton(
                            onClick = { showMenu = true },
                            modifier = Modifier.testTag("tab_switcher_more_menu")
                        ) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "More options",
                                tint = if (isIncognitoView) Color(0xFFE8EAED) else MaterialTheme.colorScheme.onSurface
                            )
                        }

                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(if (isIncognitoView) "Close all incognito tabs" else "Close all tabs") },
                                onClick = {
                                    showMenu = false
                                    onCloseAllTabs(isIncognitoView)
                                },
                                modifier = Modifier.testTag("menu_close_all_tabs")
                            )
                        }
                    }

                    IconButton(
                        onClick = onDone,
                        modifier = Modifier.testTag("tab_switcher_done")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Done",
                            tint = if (isIncognitoView) Color(0xFFE8EAED) else ChromeBlue
                        )
                    }
                }
            }

            // Tabs Grid
            if (currentTabs.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = if (isIncognitoView) Icons.Default.VisibilityOff else Icons.Default.Tab,
                            contentDescription = null,
                            tint = if (isIncognitoView) Color(0xFF5F6368) else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(56.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = if (isIncognitoView) "No incognito tabs open" else "No tabs open",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (isIncognitoView) Color(0xFF9AA0A6) else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(currentTabs, key = { it.id }) { tab ->
                        TabCard(
                            tab = tab,
                            isSelected = tab.id == activeTabId,
                            isIncognito = isIncognitoView,
                            onClick = { onTabSelected(tab.id, isIncognitoView) },
                            onClose = { onTabClosed(tab.id, isIncognitoView) }
                        )
                    }
                }
            }
        }

        // Floating Action Button to add new tab
        FloatingActionButton(
            onClick = { onNewTab(isIncognitoView) },
            containerColor = if (isIncognitoView) Color(0xFF454545) else ChromeBlue,
            contentColor = Color.White,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(24.dp)
                .testTag("new_tab_fab")
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = if (isIncognitoView) "New incognito tab" else "New tab"
            )
        }
    }
}

@Composable
private fun TabCard(
    tab: BrowserTab,
    isSelected: Boolean,
    isIncognito: Boolean,
    onClick: () -> Unit,
    onClose: () -> Unit
) {
    val borderColor = when {
        isSelected -> ChromeBlue
        isIncognito -> Color(0xFF3C4043)
        else -> MaterialTheme.colorScheme.outlineVariant
    }

    val cardBg = if (isIncognito) IncognitoSurface else MaterialTheme.colorScheme.surface
    val headerBg = if (isIncognito) Color(0xFF333333) else MaterialTheme.colorScheme.surfaceVariant

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp)
            .clip(RoundedCornerShape(14.dp))
            .border(
                width = if (isSelected) 2.5.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(14.dp)
            )
            .clickable(onClick = onClick)
            .testTag("tab_card_${tab.id}"),
        colors = CardDefaults.cardColors(containerColor = cardBg),
        shape = RoundedCornerShape(14.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isSelected) 4.dp else 1.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Tab Header with favicon/initial, Title, and Close Button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .background(headerBg)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Favicon or Domain Initial
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(if (isIncognito) Color(0xFF454545) else MaterialTheme.colorScheme.surface),
                    contentAlignment = Alignment.Center
                ) {
                    if (tab.isNewTabPage) {
                        Icon(
                            imageVector = if (isIncognito) Icons.Default.VisibilityOff else Icons.Default.Language,
                            contentDescription = null,
                            tint = if (isIncognito) Color(0xFFE8EAED) else ChromeBlue,
                            modifier = Modifier.size(12.dp)
                        )
                    } else {
                        Text(
                            text = tab.displayHost.take(1).uppercase(),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isIncognito) Color(0xFFE8EAED) else ChromeBlue
                        )
                    }
                }

                Spacer(modifier = Modifier.width(6.dp))

                Text(
                    text = if (tab.isNewTabPage) "New tab" else tab.title,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (isIncognito) Color(0xFFE8EAED) else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )

                IconButton(
                    onClick = onClose,
                    modifier = Modifier
                        .size(26.dp)
                        .testTag("close_tab_${tab.id}")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close tab",
                        tint = if (isIncognito) Color(0xFF9AA0A6) else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            // Preview Area
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    if (tab.isNewTabPage) {
                        Text(
                            text = "New tab",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isIncognito) Color(0xFF9AA0A6) else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Language,
                            contentDescription = null,
                            tint = if (isIncognito) Color(0xFF5F6368) else ChromeBlue.copy(alpha = 0.5f),
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = tab.displayHost,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (isIncognito) Color(0xFF9AA0A6) else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}
