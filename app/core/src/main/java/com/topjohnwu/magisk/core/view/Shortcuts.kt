package com.topjohnwu.magisk.view

import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.getSystemService
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.topjohnwu.magisk.core.Config
import com.topjohnwu.magisk.core.Const
import com.topjohnwu.magisk.core.R
import com.topjohnwu.magisk.core.isRunningAsStub
import com.topjohnwu.magisk.core.ktx.getBitmap
import java.util.UUID

object Shortcuts {

    private const val SHELL_SHORTCUT_ID = "reisenless-shell"
    private const val SHELL_TOKEN = "reisenless.shell.token"
    private const val LAUNCHER_ALIAS = "com.topjohnwu.magisk.ui.LauncherAlias"
    private const val MAIN_ACTIVITY = "com.topjohnwu.magisk.ui.MainActivity"

    fun setupDynamic(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            val manager = context.getSystemService<ShortcutManager>() ?: return
            manager.dynamicShortcuts = getShortCuts(context)
        }
    }

    fun addHomeIcon(context: Context) {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        val info = ShortcutInfoCompat.Builder(context, Const.Nav.HOME)
            .setShortLabel(context.getString(R.string.magisk))
            .setIntent(intent)
            .setIcon(context.getIconCompat(R.drawable.ic_launcher))
            .build()
        ShortcutManagerCompat.requestPinShortcut(context, info, null)
    }

    fun requestShellShortcut(context: Context): Boolean {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) return false
        val token = UUID.randomUUID().toString()
        val intent = Intent(Intent.ACTION_MAIN).apply {
            component = ComponentName(context.packageName, MAIN_ACTIVITY)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(SHELL_TOKEN, token)
        }
        val info = ShortcutInfoCompat.Builder(context, SHELL_SHORTCUT_ID)
            .setShortLabel(context.getString(R.string.magisk))
            .setIntent(intent)
            .setIcon(context.getIconCompat(R.drawable.ic_launcher))
            .build()
        val accepted = ShortcutManagerCompat.requestPinShortcut(context, info, null)
        if (accepted) Config.shellHideToken = token
        return accepted
    }

    fun consumeShellShortcut(context: Context, intent: Intent?): Boolean {
        val expected = Config.shellHideToken
        val supplied = intent?.getStringExtra(SHELL_TOKEN)
        if (expected.isBlank() || supplied != expected) return false
        Config.shellHideToken = ""
        setLauncherHidden(context, true)
        return true
    }

    fun restoreLauncher(context: Context) = setLauncherHidden(context, false)

    fun syncLauncherState(context: Context) = setLauncherHidden(context, Config.shellHidden)

    private fun setLauncherHidden(context: Context, hidden: Boolean) {
        val state = if (hidden) {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
        }
        context.packageManager.setComponentEnabledSetting(
            ComponentName(context.packageName, LAUNCHER_ALIAS),
            state,
            PackageManager.DONT_KILL_APP,
        )
        Config.shellHidden = hidden
    }

    private fun Context.getIcon(id: Int): Icon {
        return if (isRunningAsStub) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                Icon.createWithAdaptiveBitmap(getBitmap(id))
            else
                Icon.createWithBitmap(getBitmap(id))
        } else {
            Icon.createWithResource(this, id)
        }
    }

    private fun Context.getIconCompat(id: Int): IconCompat {
        return if (isRunningAsStub) {
            val bitmap = getBitmap(id)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                IconCompat.createWithAdaptiveBitmap(bitmap)
            else
                IconCompat.createWithBitmap(bitmap)
        } else {
            IconCompat.createWithResource(this, id)
        }
    }

    @RequiresApi(api = 25)
    private fun getShortCuts(context: Context): List<ShortcutInfo> = emptyList()
}
