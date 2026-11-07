package com.altitudeplusplus.android.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val colors = darkColorScheme(
    primary = Color(0xFF9BB7D4),
    onPrimary = Color(0xFF0B1E3A),
    secondary = Color(0xFFE0B04A),
    background = Color(0xFF0A0F18),
    surface = Color(0xFF121A27),
    surfaceVariant = Color(0xFF1C2636),
)

@Composable
fun AltitudeTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = colors, content = content)
