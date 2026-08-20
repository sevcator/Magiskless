package com.topjohnwu.magisk.core

import android.util.Base64
import com.topjohnwu.superuser.Shell

object Udonge {
    private val root = "${Const.SECURE_DIR}/${Const.UDONGE_DIR}"
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

    fun setAnonymous(enabled: Boolean): Boolean {
        // The runtime script does the real work (firewall + identity
        // randomization). When the on-device runtime predates anonymous
        // mode we still set the flag, which takes effect after the
        // runtime is updated and the device reboots.
        val action = if (enabled) {
            "mkdir -p '$state' && : > '$state/anonymous' && " +
                "if [ -f '$runtime/anonymous.sh' ]; then sh '$runtime/anonymous.sh' enable; fi"
        } else {
            "rm -f '$state/anonymous' '$state/anonymous_clear_data' && " +
                "if [ -f '$runtime/anonymous.sh' ]; then sh '$runtime/anonymous.sh' disable; fi"
        }
        val success = Shell.cmd(action).exec().isSuccess
        if (success) Config.anonymousEnabled = enabled
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

    fun setRomKeywords(value: String): Boolean {
        val normalized = value.lineSequence()
            .map(String::trim)
            .filter { it.length >= 3 && it.none { c -> c.isWhitespace() } }
            .distinct()
            .take(32)
            .joinToString("\n")
        val encoded = Base64.encodeToString(normalized.toByteArray(), Base64.NO_WRAP)
        val command = "mkdir -p '$state' && printf '%s' '$encoded' | " +
            "base64 -d > '$state/rom_keywords.conf'"
        val success = Shell.cmd(command).exec().isSuccess
        if (success) Config.udongeRomKeywords = normalized
        return success
    }
}
