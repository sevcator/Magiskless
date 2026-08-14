package com.topjohnwu.magisk.core

import android.util.Base64
import com.topjohnwu.superuser.Shell

object Udonge {
    private val root = "${Const.SECURE_DIR}/udonge"
    private val state = "$root/state"
    private val runtime = "$root/runtime"

    fun setEnabled(enabled: Boolean): Boolean {
        val action = if (enabled) {
            "rm -f '$state/disabled' '$state/unloaded'"
        } else {
            "mkdir -p '$state' && : > '$state/disabled'"
        }
        val success = Shell.cmd(action).exec().isSuccess
        if (success) Config.udongeEnabled = enabled
        return success
    }

    fun setKeyboxUrls(value: String): Boolean {
        val normalized = value.lineSequence()
            .map(String::trim)
            .filter { it.startsWith("https://") && it.length <= 2048 }
            .distinct()
            .take(16)
            .joinToString("\n")
            .ifBlank { Config.DEFAULT_UDONGE_KEYBOX_URLS }
        val encoded = Base64.encodeToString(normalized.toByteArray(), Base64.NO_WRAP)
        val command = "mkdir -p '$state' && printf '%s' '$encoded' | " +
            "base64 -d > '$state/keybox_urls.conf' && : > '$state/.keybox-refresh'"
        val success = Shell.cmd(command).exec().isSuccess
        if (success) Config.udongeKeyboxUrls = normalized
        return success
    }

    fun refreshKeyboxes(): Boolean = Shell.cmd(
        "mkdir -p '$state' && : > '$state/.keybox-refresh' && " +
            "('$runtime/service.sh' </dev/null >/dev/null 2>&1 &)"
    ).exec().isSuccess
}
