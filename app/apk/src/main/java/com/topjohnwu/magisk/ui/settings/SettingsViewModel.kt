package com.topjohnwu.magisk.ui.settings

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.lifecycle.viewModelScope
import com.topjohnwu.magisk.BR
import com.topjohnwu.magisk.arch.BaseViewModel
import com.topjohnwu.magisk.core.AppContext
import com.topjohnwu.magisk.core.Config
import com.topjohnwu.magisk.core.Const
import com.topjohnwu.magisk.core.Info
import com.topjohnwu.magisk.core.R
import com.topjohnwu.magisk.core.isRunningAsStub
import com.topjohnwu.magisk.core.ktx.activity
import com.topjohnwu.magisk.core.ktx.toast
import com.topjohnwu.magisk.core.Udonge
import com.topjohnwu.magisk.core.utils.LocaleSetting
import com.topjohnwu.magisk.core.utils.RootUtils
import com.topjohnwu.magisk.view.Shortcuts
import com.topjohnwu.magisk.databinding.bindExtra
import com.topjohnwu.magisk.events.AddHomeIconEvent
import com.topjohnwu.magisk.events.SnackbarEvent
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.launch

class SettingsViewModel : BaseViewModel(), BaseSettingsItem.Handler {

    val items = createItems()
    val extraBindings = bindExtra {
        it.put(BR.handler, this)
    }

    private fun createItems(): List<BaseSettingsItem> {
        val context = AppContext

        // Customization
        val list = mutableListOf(
            Customization,
            Theme, if (LocaleSetting.useLocaleManager) LanguageSystem else Language
        )
        if (isRunningAsStub && ShortcutManagerCompat.isRequestPinShortcutSupported(context))
            list.add(AddShortcut)

        // Manager
        list.addAll(listOf(
            AppSettings,
            DoHToggle, DownloadPath
        ))
        if (Config.shellHidden || (Info.env.isActive && Const.USER_ID == 0)) {
            list.add(ShellHide)
        }

        // Magisk
        if (Info.env.isActive) {
            list.addAll(listOf(
                Magisk,
                SystemlessHosts
            ))
            if (Const.Version.atLeast_24_0()) {
                list.addAll(listOf(Zygisk, SuList))
            }
            list.addAll(listOf(UdongeSettings, UdongeKeyboxes, UdongeUpdate))
        }

        return list
    }

    override fun onItemPressed(view: View, item: BaseSettingsItem, doAction: () -> Unit) {
        when (item) {
            DownloadPath -> withExternalRW(doAction)
            else -> doAction()
        }
    }

    override fun onItemAction(view: View, item: BaseSettingsItem) {
        when (item) {
            Theme -> SettingsFragmentDirections.actionSettingsFragmentToThemeFragment().navigate()
            LanguageSystem -> view.activity.startActivity(LocaleSetting.localeSettingsIntent)
            AddShortcut -> AddHomeIconEvent().publish()
            SystemlessHosts -> createHosts()
            SuList -> SettingsFragmentDirections.actionSettingsFragmentToDenyFragment().navigate()
            ShellHide -> {
                if (Config.shellHidden) {
                    Shortcuts.restoreLauncher(view.context)
                    ShellHide.notifyPropertyChanged(BR.title)
                    ShellHide.notifyPropertyChanged(BR.description)
                } else {
                    if (!Shortcuts.requestShellShortcut(view.context)) {
                        SnackbarEvent(R.string.add_shortcut_msg).publish()
                    }
                    ShellHide.notifyPropertyChanged(BR.title)
                    ShellHide.notifyPropertyChanged(BR.description)
                }
            }
            Zygisk -> if (Zygisk.mismatch) SnackbarEvent(R.string.reboot_apply_change).publish()
            UdongeUpdate -> SettingsFragmentDirections
                .actionSettingsFragmentToInstallFragment().navigate()
            else -> Unit
        }
    }

    override fun onItemToggleAction(view: View, item: BaseSettingsItem) {
        when (item) {
            SuList -> SnackbarEvent(R.string.reboot_apply_change).publish()
            UdongeKeyboxes -> {
                val requested = UdongeKeyboxes.value
                if (requested) Config.zygisk = true
                Shell.EXECUTOR.execute {
                    if (!Udonge.setEnabled(requested) && Config.udongeEnabled == requested) {
                        Config.udongeEnabled = !requested
                        view.post { UdongeKeyboxes.notifyPropertyChanged(BR.checked) }
                    }
                }
                SnackbarEvent(R.string.reboot_apply_change).publish()
            }
            else -> onItemAction(view, item)
        }
    }

    private fun createHosts() {
        viewModelScope.launch {
            RootUtils.addSystemlessHosts()
            AppContext.toast(R.string.settings_hosts_toast, Toast.LENGTH_SHORT)
        }
    }
}
