package com.cryptonews.push.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val AppColors = darkColorScheme(
    primary = Color(0xFFF97316),
    secondary = Color(0xFF38BDF8),
    background = Color(0xFF07111F),
    surface = Color(0xFFFFFFFF),
    onPrimary = Color.White,
    onBackground = Color.White,
    onSurface = Color(0xFF0F172A)
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AppColors,
        content = content
    )
}
