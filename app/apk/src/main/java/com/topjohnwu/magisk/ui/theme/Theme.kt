package com.topjohnwu.magisk.ui.theme

import android.app.Activity
import com.topjohnwu.magisk.R
import com.topjohnwu.magisk.core.Config

object Theme {
    const val COLOR_COUNT = 8

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
        activity.theme.applyStyle(primaryStyles[Config.accentPrimary.coerceIn(0, COLOR_COUNT - 1)], true)
        activity.theme.applyStyle(
            secondaryStyles[Config.accentSecondary.coerceIn(0, COLOR_COUNT - 1)],
            true,
        )
    }
}
