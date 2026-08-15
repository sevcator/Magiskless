package com.topjohnwu.magisk.hideapps

data class HideAppsStatus(
    val available: Boolean,
    val serviceVersion: Int,
    val filterCount: Int,
)
