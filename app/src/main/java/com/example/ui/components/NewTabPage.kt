package com.example.ui.components

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.example.data.model.Bookmark
import com.example.ui.theme.ChromeBlue
import com.example.ui.theme.ChromeGreen
import com.example.ui.theme.ChromeRed
import com.example.ui.theme.ChromeYellow
import com.example.ui.theme.IncognitoBg
import com.example.ui.theme.IncognitoSurface

data class QuickShortcut(
    val title: String,
    val url: String,
    val letter: String,
    val color: Color
)

val defaultShortcuts = listOf(
    QuickShortcut("Google", "https://www.google.com", "G", ChromeBlue),
    QuickShortcut("YouTube", "https://www.youtube.com", "Y", ChromeRed),
    QuickShortcut("Wikipedia", "https://www.wikipedia.org", "W", Color(0xFF6B7280)),
    QuickShortcut("GitHub", "https://www.github.com", "GH", Color(0xFF1F2937)),
    QuickShortcut("Reddit", "https://www.reddit.com", "R", Color(0xFFFF4500)),
    QuickShortcut("BBC News", "https://www.bbc.com/news", "B", Color(0xFFB91C1C)),
    QuickShortcut("Android", "https://developer.android.com", "A", ChromeGreen),
    QuickShortcut("Weather", "https://weather.com", "☀️", Color(0xFF0284C7))
)

@Composable
fun NewTabPage(
    isIncognito: Boolean,
    bookmarks: List<Bookmark>,
    onSearchClick: () -> Unit,
    onNavigateUrl: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (isIncognito) {
        IncognitoStartPage(modifier = modifier)
    } else {
        RegularNewTabPage(
            bookmarks = bookmarks,
            onSearchClick = onSearchClick,
            onNavigateUrl = onNavigateUrl,
            modifier = modifier
        )
    }
}

@Composable
private fun RegularNewTabPage(
    bookmarks: List<Bookmark>,
    onSearchClick: () -> Unit,
    onNavigateUrl: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            Spacer(modifier = Modifier.height(36.dp))

            // Google / Chrome Colorful Branding
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.padding(bottom = 24.dp)
            ) {
                Text(
                    text = "G",
                    fontSize = 42.sp,
                    fontWeight = FontWeight.Bold,
                    color = ChromeBlue
                )
                Text(
                    text = "o",
                    fontSize = 42.sp,
                    fontWeight = FontWeight.Bold,
                    color = ChromeRed
                )
                Text(
                    text = "o",
                    fontSize = 42.sp,
                    fontWeight = FontWeight.Bold,
                    color = ChromeYellow
                )
                Text(
                    text = "g",
                    fontSize = 42.sp,
                    fontWeight = FontWeight.Bold,
                    color = ChromeBlue
                )
                Text(
                    text = "l",
                    fontSize = 42.sp,
                    fontWeight = FontWeight.Bold,
                    color = ChromeGreen
                )
                Text(
                    text = "e",
                    fontSize = 42.sp,
                    fontWeight = FontWeight.Bold,
                    color = ChromeRed
                )
            }

            // Big Search / Omnibox Pill
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .clip(RoundedCornerShape(26.dp))
                    .clickable(onClick = onSearchClick)
                    .testTag("ntp_search_bar"),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(26.dp),
                shadowElevation = 1.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Search",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Search or type URL",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 16.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Quick shortcuts 4x2 grid
            Text(
                text = "Most Visited",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 14.dp)
            )

            // Shortcuts in 2 rows of 4
            val rows = defaultShortcuts.chunked(4)
            for (row in rows) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    for (item in row) {
                        ShortcutTile(
                            shortcut = item,
                            onClick = { onNavigateUrl(item.url) }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }

        // Recent Bookmarks section
        if (bookmarks.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Bookmark,
                        contentDescription = null,
                        tint = ChromeBlue,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Bookmarks",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            items(bookmarks.take(5)) { bookmark ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                        .clickable { onNavigateUrl(bookmark.url) }
                        .testTag("ntp_bookmark_${bookmark.id}"),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surface),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = bookmark.title.take(1).uppercase(),
                                fontWeight = FontWeight.Bold,
                                color = ChromeBlue,
                                fontSize = 16.sp
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = bookmark.title,
                                fontWeight = FontWeight.Medium,
                                fontSize = 14.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = bookmark.url,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun ShortcutTile(
    shortcut: QuickShortcut,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(68.dp)
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = shortcut.letter,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = shortcut.color
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = shortcut.title,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun IncognitoStartPage(
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(IncognitoBg)
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            Spacer(modifier = Modifier.height(48.dp))

            // Fedora & Sunglasses Mask Icon
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF35363A)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.VisibilityOff,
                    contentDescription = "Incognito",
                    tint = Color(0xFFE8EAED),
                    modifier = Modifier.size(42.dp)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "You've gone incognito",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFE8EAED),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = "Now you can browse privately, and other people who use this device won't see your activity. However, downloads and bookmarks will be saved.",
                fontSize = 14.sp,
                color = Color(0xFF9AA0A6),
                lineHeight = 20.sp,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(28.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = IncognitoSurface),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(
                        text = "Chrome won't save:",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFFE8EAED)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("• Your browsing history", color = Color(0xFF9AA0A6), fontSize = 13.sp)
                    Text("• Cookies and site data", color = Color(0xFF9AA0A6), fontSize = 13.sp)
                    Text("• Information entered in forms", color = Color(0xFF9AA0A6), fontSize = 13.sp)

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "Your activity might still be visible to:",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFFE8EAED)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("• Websites that you visit", color = Color(0xFF9AA0A6), fontSize = 13.sp)
                    Text("• Your employer or school", color = Color(0xFF9AA0A6), fontSize = 13.sp)
                    Text("• Your internet service provider", color = Color(0xFF9AA0A6), fontSize = 13.sp)
                }
            }

            Spacer(modifier = Modifier.height(36.dp))
        }
    }
}
