package com.topjohnwu.magisk.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.topjohnwu.magisk.core.Config

object ThemeState {
    var darkTheme by mutableIntStateOf(Config.darkTheme)
    var primaryAccent by mutableIntStateOf(Config.accentPrimary)
    var secondaryAccent by mutableIntStateOf(Config.accentSecondary)
}

private fun contentColor(background: Color) =
    if (background.luminance() > 0.45f) Color(0xFF101010) else Color(0xFFF9F9F9)

@Composable
fun MagiskTheme(
    content: @Composable () -> Unit
) {
    val primary = Color(ThemeState.primaryAccent)
    val secondary = Color(ThemeState.secondaryAccent)
    val base = if (ThemeState.darkTheme == Config.Value.THEME_DARK) {
        darkColorScheme()
    } else {
        lightColorScheme()
    }
    val colorScheme = base.copy(
        primary = primary,
        onPrimary = contentColor(primary),
        primaryContainer = primary,
        onPrimaryContainer = contentColor(primary),
        secondary = secondary,
        onSecondary = contentColor(secondary),
        secondaryContainer = secondary,
        onSecondaryContainer = contentColor(secondary),
    )

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
