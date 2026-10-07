package com.example.devtools.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Colours used only by the developer tools UI.
 *
 * They are separated from the browser theme on purpose: the inspector has to look like a tool that is
 * bolted onto the debug build, not like part of the product, so nobody mistakes it for a user-facing
 * feature and the colour scheme survives dark/incognito themes.
 */
val InspectorGreen = Color(0xFF1E8E3E)
val InspectorAmber = Color(0xFFE37400)
val InspectorRed = Color(0xFFD93025)
val InspectorIndigo = Color(0xFF1A73E8)
val InspectorGrey = Color(0xFF5F6368)
val InspectorPurple = Color(0xFF8430CE)
