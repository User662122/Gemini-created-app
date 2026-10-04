package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.BrowserTab
import com.example.ui.theme.ChromeBlue
import com.example.ui.theme.ChromeDarkBg
import com.example.ui.theme.ChromeDarkSurfaceVariant
import com.example.ui.theme.IncognitoBg
import com.example.ui.theme.IncognitoPill

@Composable
fun ChromeTopBar(
    tab: BrowserTab?,
    tabsCount: Int,
    isIncognito: Boolean,
    onOmniboxClick: () -> Unit,
    onTabSwitcherClick: () -> Unit,
    onMenuClick: () -> Unit,
    onReloadClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val barBg = if (isIncognito) IncognitoBg else MaterialTheme.colorScheme.surface
    val pillBg = if (isIncognito) IncognitoPill else MaterialTheme.colorScheme.surfaceVariant
    val textColor = if (isIncognito) Color(0xFFE8EAED) else MaterialTheme.colorScheme.onSurface
    val subTextColor = if (isIncognito) Color(0xFF9AA0A6) else MaterialTheme.colorScheme.onSurfaceVariant

    val isHttps = tab?.url?.startsWith("https://") == true
    val isNewTab = tab?.isNewTabPage == true

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(barBg)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Incognito indicator if active
            if (isIncognito) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF35363A)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.VisibilityOff,
                        contentDescription = "Incognito Mode",
                        tint = Color(0xFFE8EAED),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // Omnibox Pill
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .clickable(onClick = onOmniboxClick)
                    .testTag("omnibox_bar"),
                color = pillBg,
                shape = RoundedCornerShape(24.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Security or Search Icon
                    val icon = when {
                        isNewTab -> Icons.Default.Search
                        isHttps -> Icons.Default.Lock
                        else -> Icons.Outlined.Security
                    }
                    val iconTint = when {
                        isNewTab -> subTextColor
                        isHttps -> if (isIncognito) Color(0xFF8AB4F8) else ChromeBlue
                        else -> Color(0xFFEA4335)
                    }

                    Icon(
                        imageVector = icon,
                        contentDescription = if (isHttps) "Secure connection" else "Search",
                        tint = iconTint,
                        modifier = Modifier.size(16.dp)
                    )

                    Spacer(modifier = Modifier.width(10.dp))

                    // Host / Title or placeholder
                    val displayText = when {
                        isNewTab -> "Search or type URL"
                        else -> tab?.displayHost ?: "Search or type URL"
                    }

                    Text(
                        text = displayText,
                        color = if (isNewTab) subTextColor else textColor,
                        fontSize = 15.sp,
                        fontWeight = if (isNewTab) FontWeight.Normal else FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )

                    // Reload button inside or next to omnibox if browsing a page
                    if (!isNewTab && tab != null) {
                        IconButton(
                            onClick = onReloadClick,
                            modifier = Modifier
                                .size(28.dp)
                                .testTag("reload_button")
                        ) {
                            Icon(
                                imageVector = if (tab.isLoading) Icons.Default.Close else Icons.Default.Refresh,
                                contentDescription = if (tab.isLoading) "Stop loading" else "Reload page",
                                tint = subTextColor,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }

            // Tabs count button (Chrome style square chip)
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .border(
                        width = 1.8.dp,
                        color = subTextColor,
                        shape = RoundedCornerShape(7.dp)
                    )
                    .clickable(onClick = onTabSwitcherClick)
                    .testTag("tab_switcher_button"),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = tabsCount.coerceAtMost(99).toString(),
                    color = textColor,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
            }

            // Chrome 3-dots overflow menu
            IconButton(
                onClick = onMenuClick,
                modifier = Modifier
                    .size(36.dp)
                    .testTag("overflow_menu_button")
            ) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "Chrome menu",
                    tint = textColor
                )
            }
        }

        // Animated Loading Progress Bar
        val isPageLoading = tab?.isLoading == true && tab.progress < 100
        val animatedProgress by animateFloatAsState(
            targetValue = if (tab != null) (tab.progress / 100f).coerceIn(0f, 1f) else 0f,
            label = "load_progress"
        )

        AnimatedVisibility(visible = isPageLoading) {
            LinearProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp),
                color = ChromeBlue,
                trackColor = Color.Transparent
            )
        }
    }
}
