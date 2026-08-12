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

private val primaryColors = listOf(
    Color(0xFFF4A6C1), Color(0xFF7E57C2),
    Color(0xFF4EAFF5), Color(0xFF68A17F), Color(0xFFF2B90D), Color(0xFFDB7366),
    Color(0xFF009688), Color(0xFF607D8B),
)

private val secondaryColors = listOf(
    Color(0xFFD97A9C), Color(0xFF5E35B1),
    Color(0xFF3E78AF), Color(0xFF2F6D43), Color(0xFFB29667), Color(0xFFB65247),
    Color(0xFF00796B), Color(0xFF455A64),
)

private fun contentColor(background: Color) =
    if (background.luminance() > 0.45f) Color(0xFF101010) else Color(0xFFF9F9F9)

@Composable
fun MagiskTheme(
    content: @Composable () -> Unit
) {
    val primary = primaryColors[ThemeState.primaryAccent.coerceIn(primaryColors.indices)]
    val secondary = secondaryColors[ThemeState.secondaryAccent.coerceIn(secondaryColors.indices)]
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
