package com.topjohnwu.magisk.ui.theme

import android.app.Activity
import android.graphics.Color
import com.topjohnwu.magisk.R
import com.topjohnwu.magisk.core.Config

object Theme {
    const val COLOR_COUNT = 8

    private val primaryColors = intArrayOf(
        Color.rgb(244, 166, 193),
        Color.rgb(126, 87, 194),
        Color.rgb(78, 175, 245),
        Color.rgb(104, 161, 127),
        Color.rgb(242, 185, 13),
        Color.rgb(219, 115, 102),
        Color.rgb(0, 150, 136),
        Color.rgb(96, 125, 139),
    )

    private val secondaryColors = intArrayOf(
        Color.rgb(217, 122, 156),
        Color.rgb(94, 53, 177),
        Color.rgb(62, 120, 175),
        Color.rgb(47, 109, 67),
        Color.rgb(178, 150, 103),
        Color.rgb(182, 82, 71),
        Color.rgb(0, 121, 107),
        Color.rgb(69, 90, 100),
    )

    private val primaryStyles = intArrayOf(
        R.style.ThemeOverlay_Magisk_Primary_Pink,
        R.style.ThemeOverlay_Magisk_Primary_Purple,
        R.style.ThemeOverlay_Magisk_Primary_Blue,
        R.style.ThemeOverlay_Magisk_Primary_Green,
        R.style.ThemeOverlay_Magisk_Primary_Amber,
        R.style.ThemeOverlay_Magisk_Primary_Red,
        R.style.ThemeOverlay_Magisk_Primary_Teal,
        R.style.ThemeOverlay_Magisk_Primary_Gray,
    )

    private val secondaryStyles = intArrayOf(
        R.style.ThemeOverlay_Magisk_Secondary_Pink,
        R.style.ThemeOverlay_Magisk_Secondary_Purple,
        R.style.ThemeOverlay_Magisk_Secondary_Blue,
        R.style.ThemeOverlay_Magisk_Secondary_Green,
        R.style.ThemeOverlay_Magisk_Secondary_Amber,
        R.style.ThemeOverlay_Magisk_Secondary_Red,
        R.style.ThemeOverlay_Magisk_Secondary_Teal,
        R.style.ThemeOverlay_Magisk_Secondary_Gray,
    )

    fun apply(activity: Activity) {
        activity.setTheme(R.style.ThemeFoundationMD2)
        applyOverlays(activity)
    }

    fun applyOverlays(activity: Activity) {
        activity.theme.applyStyle(primaryStyles[nearestColorIndex(true, Config.accentPrimary)], true)
        activity.theme.applyStyle(
            secondaryStyles[nearestColorIndex(false, Config.accentSecondary)],
            true,
        )
    }

    fun nearestColorIndex(primary: Boolean, color: Int): Int {
        val colors = if (primary) primaryColors else secondaryColors
        val red = Color.red(color)
        val green = Color.green(color)
        val blue = Color.blue(color)
        return colors.indices.minBy { index ->
            val candidate = colors[index]
            val dr = red - Color.red(candidate)
            val dg = green - Color.green(candidate)
            val db = blue - Color.blue(candidate)
            dr * dr + dg * dg + db * db
        }
    }
}
