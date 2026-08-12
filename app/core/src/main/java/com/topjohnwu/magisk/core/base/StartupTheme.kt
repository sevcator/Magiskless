package com.topjohnwu.magisk.core.base

import android.app.Activity
import com.topjohnwu.magisk.core.Config
import com.topjohnwu.magisk.core.R

object StartupTheme {
    const val COLOR_COUNT = 8

    private val mainStyles = intArrayOf(
        R.style.SplashTheme_Pink,
        R.style.SplashTheme_Purple,
        R.style.SplashTheme_Blue,
        R.style.SplashTheme_Green,
        R.style.SplashTheme_Amber,
        R.style.SplashTheme_Red,
        R.style.SplashTheme_Teal,
        R.style.SplashTheme_White,
    )

    private val stubStyles = intArrayOf(
        R.style.StubSplashTheme_Pink,
        R.style.StubSplashTheme_Purple,
        R.style.StubSplashTheme_Blue,
        R.style.StubSplashTheme_Green,
        R.style.StubSplashTheme_Amber,
        R.style.StubSplashTheme_Red,
        R.style.StubSplashTheme_Teal,
        R.style.StubSplashTheme_White,
    )

    fun apply(activity: Activity, stub: Boolean = false) {
        val index = Config.startupColor.coerceIn(0, COLOR_COUNT - 1)
        activity.setTheme(if (stub) stubStyles[index] else mainStyles[index])
    }
}
