package com.topjohnwu.magisk.ui.settings

import android.content.res.Resources
import android.text.InputType
import android.view.View
import android.widget.EditText
import androidx.databinding.Bindable
import com.topjohnwu.magisk.BR
import com.topjohnwu.magisk.R
import com.topjohnwu.magisk.core.BuildConfig
import com.topjohnwu.magisk.core.Config
import com.topjohnwu.magisk.core.Info
import com.topjohnwu.magisk.core.Udonge
import com.topjohnwu.magisk.core.ktx.activity
import com.topjohnwu.magisk.core.utils.LocaleSetting
import com.topjohnwu.magisk.databinding.set
import com.topjohnwu.magisk.core.utils.TextHolder
import com.topjohnwu.magisk.core.utils.asText
import com.topjohnwu.magisk.view.MagiskDialog
import com.topjohnwu.superuser.Shell
import com.topjohnwu.magisk.core.R as CoreR

// --- Customization

object Customization : BaseSettingsItem.Section() {
    override val title = CoreR.string.settings_customization.asText()
}

object Language : BaseSettingsItem.Selector() {
    private val names: Array<String> get() = LocaleSetting.available.names
    private val tags: Array<String> get() = LocaleSetting.available.tags

    override var value
        get() = tags.indexOf(Config.locale)
        set(value) {
            Config.locale = tags[value]
        }

    override val title = CoreR.string.language.asText()

    override fun entries(res: Resources) = names
    override fun descriptions(res: Resources) = names
}

object Theme : BaseSettingsItem.Blank() {
    override val icon = R.drawable.ic_paint
    override val title = CoreR.string.section_theme.asText()
}

// --- App

object AppSettings : BaseSettingsItem.Section() {
    override val title = CoreR.string.home_app_title.asText()
}

object ShellHide : BaseSettingsItem.Blank() {
    @get:Bindable
    override val title get() = if (Config.shellHidden) {
        CoreR.string.settings_restore_shell_app_title.asText()
    } else {
        CoreR.string.settings_hide_shell_app_title.asText()
    }
    override val description get() = if (Config.shellHidden) {
        CoreR.string.settings_restore_shell_app_summary.asText()
    } else {
        CoreR.string.settings_hide_shell_app_summary.asText()
    }
}

object AddShortcut : BaseSettingsItem.Blank() {
    override val title = CoreR.string.add_shortcut_title.asText()
    override val description = CoreR.string.setting_add_shortcut_summary.asText()
}

object DoHToggle : BaseSettingsItem.Toggle() {
    override val title = CoreR.string.settings_doh_title.asText()
    override val description = CoreR.string.settings_doh_description.asText()
    override var value by Config::doh
}

object SystemlessHosts : BaseSettingsItem.Blank() {
    override val title = CoreR.string.settings_hosts_title.asText()
    override val description = CoreR.string.settings_hosts_summary.asText()
}

// --- Magisk

object Magisk : BaseSettingsItem.Section() {
    override val title = CoreR.string.magisk.asText()
}

object Zygisk : BaseSettingsItem.Toggle() {
    override val title = CoreR.string.zygisk.asText()
    override val description get() =
        if (mismatch) CoreR.string.reboot_apply_change.asText()
        else CoreR.string.settings_zygisk_summary.asText()
    override var value
        get() = Config.zygisk
        set(value) {
            Config.zygisk = value
            notifyPropertyChanged(BR.description)
        }
    val mismatch get() = value != Info.isZygiskEnabled
}

object SuList : BaseSettingsItem.SplitToggle() {
    override val title = CoreR.string.settings_sulist_title.asText()
    override val description = CoreR.string.settings_sulist_summary.asText()
    override var value by Config::sulist
}

object UdongeSettings : BaseSettingsItem.Section() {
    override val title = CoreR.string.udonge.asText()
}

object UdongeKeyboxes : BaseSettingsItem.SplitToggle() {
    override val title = CoreR.string.udonge_keybox_list_title.asText()
    override val description = CoreR.string.udonge_keybox_list_summary.asText()
    override var value by Config::udongeEnabled

    override fun onPressed(view: View, handler: Handler) {
        handler.onItemPressed(view, this) {
            val input = EditText(view.context).apply {
                hint = view.resources.getString(CoreR.string.udonge_keybox_hint)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                minLines = 4
                maxLines = 10
                setText(Config.udongeKeyboxUrls)
                setSelection(text.length)
            }
            MagiskDialog(view.activity).apply {
                setTitle(CoreR.string.udonge_keybox_list_title)
                setView(input)
                setButton(MagiskDialog.ButtonType.POSITIVE) {
                    text = android.R.string.ok
                    onClick {
                        Shell.EXECUTOR.execute {
                            if (Udonge.setKeyboxUrls(input.text.toString())) {
                                Udonge.refreshKeyboxes()
                            }
                        }
                    }
                }
                setButton(MagiskDialog.ButtonType.NEGATIVE) {
                    text = android.R.string.cancel
                }
            }.show()
        }
    }
}

object UdongeUpdate : BaseSettingsItem.Blank() {
    override val title = CoreR.string.udonge_update_title.asText()
    override val description = object : TextHolder() {
        override fun getText(resources: Resources) = resources.getString(
            CoreR.string.udonge_update_summary,
            BuildConfig.APP_VERSION_NAME,
        )
    }
}
