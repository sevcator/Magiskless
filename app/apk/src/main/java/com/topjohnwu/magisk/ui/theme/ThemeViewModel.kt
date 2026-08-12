package com.topjohnwu.magisk.ui.theme

import android.view.View
import com.topjohnwu.magisk.arch.BaseViewModel
import com.topjohnwu.magisk.core.Config
import com.topjohnwu.magisk.core.base.StartupTheme
import com.topjohnwu.magisk.core.utils.asText
import com.topjohnwu.magisk.events.RecreateEvent
import com.topjohnwu.magisk.ui.settings.BaseSettingsItem
import com.topjohnwu.magisk.core.R as CoreR

object ThemeModeSetting : BaseSettingsItem.Selector() {
    override val title = CoreR.string.settings_theme_mode.asText()
    override val entryRes = CoreR.array.theme_mode
    override var value
        get() = if (Config.darkTheme == Config.Value.THEME_DARK) 1 else 0
        set(value) {
            Config.darkTheme = if (value == 1) {
                Config.Value.THEME_DARK
            } else {
                Config.Value.THEME_LIGHT
            }
        }
}

object PrimaryAccentSetting : BaseSettingsItem.Selector() {
    override val title = CoreR.string.settings_accent_primary.asText()
    override val entryRes = CoreR.array.accent_colors
    override var value
        get() = Config.accentPrimary.coerceIn(0, Theme.COLOR_COUNT - 1)
        set(value) {
            Config.accentPrimary = value.coerceIn(0, Theme.COLOR_COUNT - 1)
        }
}

object SecondaryAccentSetting : BaseSettingsItem.Selector() {
    override val title = CoreR.string.settings_accent_secondary.asText()
    override val entryRes = CoreR.array.accent_colors
    override var value
        get() = Config.accentSecondary.coerceIn(0, Theme.COLOR_COUNT - 1)
        set(value) {
            Config.accentSecondary = value.coerceIn(0, Theme.COLOR_COUNT - 1)
        }
}

object StartupColorSetting : BaseSettingsItem.Selector() {
    override val title = CoreR.string.settings_startup_color.asText()
    override val entryRes = CoreR.array.startup_colors
    override var value
        get() = Config.startupColor.coerceIn(0, StartupTheme.COLOR_COUNT - 1)
        set(value) {
            Config.startupColor = value.coerceIn(0, StartupTheme.COLOR_COUNT - 1)
        }
}

class ThemeViewModel : BaseViewModel(), BaseSettingsItem.Handler {

    val themeMode: BaseSettingsItem = ThemeModeSetting
    val primaryAccent: BaseSettingsItem = PrimaryAccentSetting
    val secondaryAccent: BaseSettingsItem = SecondaryAccentSetting
    val startupColor: BaseSettingsItem = StartupColorSetting

    override fun onItemPressed(
        view: View,
        item: BaseSettingsItem,
        doAction: () -> Unit,
    ) {
        doAction()
    }

    override fun onItemAction(view: View, item: BaseSettingsItem) {
        RecreateEvent().publish()
    }
}
