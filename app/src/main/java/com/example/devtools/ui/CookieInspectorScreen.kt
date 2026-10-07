package com.example.devtools.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.devtools.CookieRecord
import com.example.devtools.EvidenceSource
import com.example.devtools.InspectorExplanations
import com.example.devtools.InspectorUiState
import com.example.devtools.InspectorValue
import com.example.devtools.ui.theme.InspectorGreen
import com.example.devtools.ui.theme.InspectorGrey
import com.example.devtools.ui.theme.InspectorRed

/**
 * Cookie Inspector.
 *
 * Shows, per cookie: name (value always masked), the domain/path/flags/expiry **when they are
 * knowable**, and for every row the source of that knowledge. Rows read back from `CookieManager` can
 * only show name and a masked value — Android does not expose the attributes — so those rows say so
 * instead of showing made-up values.
 */
@Composable
fun CookieInspectorTab(
    state: InspectorUiState,
    onRefresh: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${state.cookies.size} cookie(s) visible to this app" +
                    if (state.cookieRefreshPending) " · refreshing…" else "",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRefresh, modifier = Modifier.testTag("cookies_refresh")) {
                Text("Refresh", fontSize = 12.sp)
            }
        }

        Text(
            text = InspectorExplanations.COOKIE_FIELD_UNAVAILABLE,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        Spacer(modifier = Modifier.height(6.dp))

        if (state.cookies.isEmpty()) {
            InspectorEmptyState(
                title = "No cookies to show",
                message = "Either this site set no cookies for the pages you visited, or no page has " +
                    "loaded yet in this tab. Cookie values are remembered only as masks, so nothing " +
                    "sensitive is stored here.",
            )
            return@Column
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(items = state.cookies, key = { it.id }) { cookie ->
                CookieRow(cookie)
            }
            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun CookieRow(cookie: CookieRecord) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(10.dp)
            .testTag("cookie_row_${cookie.id}"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = cookie.name,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            cookie.sources.forEach { source ->
                InspectorBadge(
                    text = when (source) {
                        EvidenceSource.COOKIE_MANAGER -> "CookieManager"
                        EvidenceSource.SET_COOKIE_HEADER -> "Set-Cookie"
                        EvidenceSource.APP_HTTP_CLIENT -> "set by app"
                        else -> source.label
                    },
                    color = InspectorGrey,
                )
                Spacer(modifier = Modifier.size(4.dp))
            }
        }
        Text(
            text = "value: ${cookie.value.value ?: "(masked)"}",
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )

        CookieField("Domain", cookie.domain)
        CookieField("Path", cookie.path)
        CookieField("Secure", cookie.secure)
        CookieField("HttpOnly", cookie.httpOnly)
        CookieField("Expires", cookie.expiration)

        Text(
            text = "Observed for: ${cookie.observedForUrl}",
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        cookie.notes.forEach { note ->
            Text(
                text = "• $note",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun CookieField(label: String, value: InspectorValue<*>) {
    Row(modifier = Modifier.padding(top = 2.dp), verticalAlignment = Alignment.Top) {
        Text(
            text = label,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 6.dp),
        )
        val known = value.isKnown
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = when (val raw = value.value) {
                    null -> "not available"
                    is Boolean -> if (raw) "Yes" else "No"
                    else -> raw.toString()
                },
                fontSize = 11.sp,
                color = if (known) {
                    if (label == "Secure" || label == "HttpOnly") {
                        if (value.value == true) InspectorGreen else InspectorGrey
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                } else {
                    InspectorRed
                },
            )
            if (!known && value.note != null) {
                Text(
                    text = value.note,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (known && value.note != null) {
                Text(
                    text = value.note,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

