package com.topjohnwu.magisk.ui.settings

import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.ShortcutManagerCompat
import com.topjohnwu.magisk.core.BuildConfig
import com.topjohnwu.magisk.core.Config
import com.topjohnwu.magisk.core.Const
import com.topjohnwu.magisk.core.Info
import com.topjohnwu.magisk.core.isRunningAsStub
import com.topjohnwu.magisk.core.ktx.toast
import com.topjohnwu.magisk.core.tasks.AppMigration
import com.topjohnwu.magisk.core.utils.LocaleSetting
import com.topjohnwu.magisk.core.utils.MediaStoreUtils
import com.topjohnwu.magisk.ui.ThemeState
import com.topjohnwu.magisk.ui.component.SettingsArrow
import com.topjohnwu.magisk.ui.component.SettingsDropdown
import com.topjohnwu.magisk.ui.component.SettingsSwitch
import com.topjohnwu.magisk.ui.component.SmallTitle
import com.topjohnwu.magisk.ui.component.rememberLoadingDialog
import kotlinx.coroutines.launch
import com.topjohnwu.magisk.core.R as CoreR

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(CoreR.string.settings)) },
                scrollBehavior = scrollBehavior
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(horizontal = 12.dp)
                .padding(bottom = 88.dp)
        ) {
            CustomizationSection(viewModel)
            Spacer(Modifier.height(12.dp))
            AppSettingsSection()
            if (Info.env.isActive) {
                Spacer(Modifier.height(12.dp))
                MagiskSection(viewModel)
            }
            if (Info.showSuperUser) {
                Spacer(Modifier.height(12.dp))
                SuperuserSection(viewModel)
            }
        }
    }
}

// --- Customization ---

@Composable
private fun CustomizationSection(viewModel: SettingsViewModel) {
    val context = LocalContext.current

    SmallTitle(text = stringResource(CoreR.string.settings_customization))
    Card(modifier = Modifier.fillMaxWidth()) {
        if (LocaleSetting.useLocaleManager) {
            val locale = LocaleSetting.instance.appLocale
            val summary = locale?.getDisplayName(locale) ?: stringResource(CoreR.string.system_default)
            SettingsArrow(
                title = stringResource(CoreR.string.language),
                summary = summary,
                onClick = {
                    context.startActivity(LocaleSetting.localeSettingsIntent)
                }
            )
        } else {
            val names = remember { LocaleSetting.available.names }
            val tags = remember { LocaleSetting.available.tags }
            var selectedIndex by remember {
                mutableIntStateOf(tags.indexOf(Config.locale).coerceAtLeast(0))
            }
            SettingsDropdown(
                title = stringResource(CoreR.string.language),
                items = names.toList(),
                selectedIndex = selectedIndex,
                onSelectedIndexChange = { index ->
                    selectedIndex = index
                    Config.locale = tags[index]
                }
            )
        }

        val resources = LocalResources.current
        val themeEntries = remember {
            resources.getStringArray(CoreR.array.theme_mode).toList()
        }
        var themeMode by remember {
            mutableIntStateOf(if (Config.darkTheme == Config.Value.THEME_DARK) 1 else 0)
        }
        SettingsDropdown(
            title = stringResource(CoreR.string.settings_theme_mode),
            items = themeEntries,
            selectedIndex = themeMode,
            onSelectedIndexChange = { index ->
                themeMode = index
                Config.darkTheme = if (index == 1) {
                    Config.Value.THEME_DARK
                } else {
                    Config.Value.THEME_LIGHT
                }
                ThemeState.darkTheme = Config.darkTheme
            }
        )

        val accentEntries = remember {
            resources.getStringArray(CoreR.array.accent_colors).toList()
        }
        var primaryAccent by remember {
            mutableIntStateOf(Config.accentPrimary.coerceIn(accentEntries.indices))
        }
        SettingsDropdown(
            title = stringResource(CoreR.string.settings_accent_primary),
            items = accentEntries,
            selectedIndex = primaryAccent,
            onSelectedIndexChange = { index ->
                primaryAccent = index
                Config.accentPrimary = index
                ThemeState.primaryAccent = index
            }
        )

        var secondaryAccent by remember {
            mutableIntStateOf(Config.accentSecondary.coerceIn(accentEntries.indices))
        }
        SettingsDropdown(
            title = stringResource(CoreR.string.settings_accent_secondary),
            items = accentEntries,
            selectedIndex = secondaryAccent,
            onSelectedIndexChange = { index ->
                secondaryAccent = index
                Config.accentSecondary = index
                ThemeState.secondaryAccent = index
            }
        )

        val startupEntries = remember {
            resources.getStringArray(CoreR.array.startup_colors).toList()
        }
        var startupColor by remember {
            mutableIntStateOf(Config.startupColor.coerceIn(startupEntries.indices))
        }
        SettingsDropdown(
            title = stringResource(CoreR.string.settings_startup_color),
            items = startupEntries,
            selectedIndex = startupColor,
            onSelectedIndexChange = { index ->
                startupColor = index
                Config.startupColor = index
            }
        )

        if (isRunningAsStub && ShortcutManagerCompat.isRequestPinShortcutSupported(context)) {
            SettingsArrow(
                title = stringResource(CoreR.string.add_shortcut_title),
                summary = stringResource(CoreR.string.setting_add_shortcut_summary),
                onClick = { viewModel.requestAddShortcut() }
            )
        }
    }
}

// --- App Settings ---

@Composable
private fun AppSettingsSection() {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val loadingDialog = rememberLoadingDialog()
    val isHidden = context.packageName != BuildConfig.APP_PACKAGE_NAME
    var showHideDialog by rememberSaveable { mutableStateOf(false) }
    var showRestoreDialog by rememberSaveable { mutableStateOf(false) }

    if (showHideDialog) {
        HideAppDialog(
            onDismiss = { showHideDialog = false },
            onConfirm = { label ->
                showHideDialog = false
                scope.launch {
                    val success = loadingDialog.withLoading {
                        AppMigration.patchAndHide(context, label)
                    }
                    if (!success) context.toast(CoreR.string.failure, Toast.LENGTH_LONG)
                }
            }
        )
    }

    if (showRestoreDialog) {
        RestoreAppDialog(
            onDismiss = { showRestoreDialog = false },
            onConfirm = {
                showRestoreDialog = false
                scope.launch {
                    val success = loadingDialog.withLoading {
                        AppMigration.restoreApp(context)
                    }
                    if (!success) context.toast(CoreR.string.failure, Toast.LENGTH_LONG)
                }
            }
        )
    }

    SmallTitle(text = stringResource(CoreR.string.home_app_title))
    Card(modifier = Modifier.fillMaxWidth()) {
        // Update Channel
        val updateChannelEntries = remember {
            resources.getStringArray(CoreR.array.update_channel).toList()
        }
        var updateChannel by remember {
            mutableIntStateOf(Config.updateChannel.coerceIn(0, updateChannelEntries.size - 1))
        }
        var showUrlDialog by remember { mutableStateOf(false) }

        SettingsDropdown(
            title = stringResource(CoreR.string.settings_update_channel_title),
            items = updateChannelEntries,
            selectedIndex = updateChannel,
            onSelectedIndexChange = { index ->
                updateChannel = index
                Config.updateChannel = index
                Info.resetUpdate()
                if (index == Config.Value.CUSTOM_CHANNEL && Config.customChannelUrl.isBlank()) {
                    showUrlDialog = true
                }
            }
        )

        // Update Channel URL (for custom channel)
        if (updateChannel == Config.Value.CUSTOM_CHANNEL) {
            UpdateChannelUrlDialog(
                show = showUrlDialog,
                onDismiss = { showUrlDialog = false }
            )
            SettingsArrow(
                title = stringResource(CoreR.string.settings_update_custom),
                summary = Config.customChannelUrl.ifBlank { null },
                onClick = { showUrlDialog = true }
            )
        }

        // DoH Toggle
        var doh by remember { mutableStateOf(Config.doh) }
        SettingsSwitch(
            title = stringResource(CoreR.string.settings_doh_title),
            summary = stringResource(CoreR.string.settings_doh_description),
            checked = doh,
            onCheckedChange = {
                doh = it
                Config.doh = it
            }
        )

        // Download Path
        var showDownloadDialog by remember { mutableStateOf(false) }
        DownloadPathDialog(
            show = showDownloadDialog,
            onDismiss = { showDownloadDialog = false }
        )
        SettingsArrow(
            title = stringResource(CoreR.string.settings_download_path_title),
            summary = MediaStoreUtils.fullPath(Config.downloadDir),
            onClick = {
                showDownloadDialog = true
            }
        )

        if (Info.env.isActive) {
            SettingsArrow(
                title = stringResource(
                    if (isHidden) CoreR.string.settings_restore_app_title
                    else CoreR.string.settings_hide_app_title
                ),
                summary = context.packageName,
                onClick = {
                    if (isHidden) showRestoreDialog = true else showHideDialog = true
                }
            )
        }

    }
}

// --- Magisk ---

@Composable
private fun MagiskSection(viewModel: SettingsViewModel) {
    SmallTitle(text = stringResource(CoreR.string.magisk))
    Card(modifier = Modifier.fillMaxWidth()) {
        // Systemless Hosts
        SettingsArrow(
            title = stringResource(CoreR.string.settings_hosts_title),
            summary = stringResource(CoreR.string.settings_hosts_summary),
            onClick = { viewModel.createHosts() }
        )

        if (Const.Version.atLeast_24_0()) {
            // Zygisk
            var zygisk by remember { mutableStateOf(Config.zygisk) }
            SettingsSwitch(
                title = stringResource(CoreR.string.zygisk),
                summary = stringResource(
                    if (zygisk != Info.isZygiskEnabled) CoreR.string.reboot_apply_change
                    else CoreR.string.settings_zygisk_summary
                ),
                checked = zygisk,
                onCheckedChange = {
                    zygisk = it
                    Config.zygisk = it
                    viewModel.notifyZygiskChange()
                }
            )

            // DenyList
            val denyListEnabled by viewModel.denyListEnabled.collectAsState()
            SettingsSwitch(
                title = stringResource(CoreR.string.settings_denylist_title),
                summary = stringResource(CoreR.string.settings_denylist_summary),
                checked = denyListEnabled,
                onCheckedChange = { viewModel.toggleDenyList(it) }
            )

            // DenyList Config
            SettingsArrow(
                title = stringResource(CoreR.string.settings_denylist_config_title),
                summary = stringResource(CoreR.string.settings_denylist_config_summary),
                onClick = { viewModel.navigateToDenyList() }
            )
        }
    }
}

// --- Superuser ---

@Composable
private fun SuperuserSection(viewModel: SettingsViewModel) {
    val context = LocalContext.current
    val resources = LocalResources.current

    SmallTitle(text = stringResource(CoreR.string.superuser))
    Card(modifier = Modifier.fillMaxWidth()) {
        // Tapjack (SDK < S)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            var tapjack by remember { mutableStateOf(Config.suTapjack) }
            SettingsSwitch(
                title = stringResource(CoreR.string.settings_su_tapjack_title),
                summary = stringResource(CoreR.string.settings_su_tapjack_summary),
                checked = tapjack,
                onCheckedChange = {
                    tapjack = it
                    Config.suTapjack = it
                }
            )
        }

        // Authentication
        var suAuth by remember { mutableStateOf(Config.suAuth) }
        SettingsSwitch(
            title = stringResource(CoreR.string.settings_su_auth_title),
            summary = stringResource(
                if (Info.isDeviceSecure) CoreR.string.settings_su_auth_summary
                else CoreR.string.settings_su_auth_insecure
            ),
            checked = suAuth,
            enabled = Info.isDeviceSecure,
            onCheckedChange = { newValue ->
                viewModel.withAuth {
                    suAuth = newValue
                    Config.suAuth = newValue
                }
            }
        )

        // Access Mode
        val accessEntries = remember {
            resources.getStringArray(CoreR.array.su_access).toList()
        }
        var accessMode by remember { mutableIntStateOf(Config.rootMode) }
        SettingsDropdown(
            title = stringResource(CoreR.string.superuser_access),
            items = accessEntries,
            selectedIndex = accessMode,
            onSelectedIndexChange = {
                accessMode = it
                Config.rootMode = it
            }
        )

        // Multiuser Mode
        val multiuserEntries = remember {
            resources.getStringArray(CoreR.array.multiuser_mode).toList()
        }
        val multiuserDescriptions = remember {
            resources.getStringArray(CoreR.array.multiuser_summary).toList()
        }
        var multiuserMode by remember { mutableIntStateOf(Config.suMultiuserMode) }
        SettingsDropdown(
            title = stringResource(CoreR.string.multiuser_mode),
            summary = multiuserDescriptions.getOrElse(multiuserMode) { "" },
            items = multiuserEntries,
            selectedIndex = multiuserMode,
            enabled = Const.USER_ID == 0,
            onSelectedIndexChange = {
                multiuserMode = it
                Config.suMultiuserMode = it
            }
        )

        // Mount Namespace Mode
        val namespaceEntries = remember {
            resources.getStringArray(CoreR.array.namespace).toList()
        }
        val namespaceDescriptions = remember {
            resources.getStringArray(CoreR.array.namespace_summary).toList()
        }
        var mntNamespaceMode by remember { mutableIntStateOf(Config.suMntNamespaceMode) }
        SettingsDropdown(
            title = stringResource(CoreR.string.mount_namespace_mode),
            summary = namespaceDescriptions.getOrElse(mntNamespaceMode) { "" },
            items = namespaceEntries,
            selectedIndex = mntNamespaceMode,
            onSelectedIndexChange = {
                mntNamespaceMode = it
                Config.suMntNamespaceMode = it
            }
        )

        // Automatic Response
        val autoResponseEntries = remember {
            resources.getStringArray(CoreR.array.auto_response).toList()
        }
        var autoResponse by remember { mutableIntStateOf(Config.suAutoResponse) }
        SettingsDropdown(
            title = stringResource(CoreR.string.auto_response),
            items = autoResponseEntries,
            selectedIndex = autoResponse,
            onSelectedIndexChange = { newIndex ->
                val doIt = {
                    autoResponse = newIndex
                    Config.suAutoResponse = newIndex
                }
                if (Config.suAuth) viewModel.withAuth(doIt) else doIt()
            }
        )

        // Request Timeout
        val timeoutEntries = remember {
            resources.getStringArray(CoreR.array.request_timeout).toList()
        }
        val timeoutValues = remember { listOf(10, 15, 20, 30, 45, 60) }
        var timeoutIndex by remember {
            mutableIntStateOf(timeoutValues.indexOf(Config.suDefaultTimeout).coerceAtLeast(0))
        }
        SettingsDropdown(
            title = stringResource(CoreR.string.request_timeout),
            items = timeoutEntries,
            selectedIndex = timeoutIndex,
            onSelectedIndexChange = {
                timeoutIndex = it
                Config.suDefaultTimeout = timeoutValues[it]
            }
        )

        // SU Notification
        val notifEntries = remember {
            resources.getStringArray(CoreR.array.su_notification).toList()
        }
        var suNotification by remember { mutableIntStateOf(Config.suNotification) }
        SettingsDropdown(
            title = stringResource(CoreR.string.superuser_notification),
            items = notifEntries,
            selectedIndex = suNotification,
            onSelectedIndexChange = {
                suNotification = it
                Config.suNotification = it
            }
        )

        // Reauthenticate (SDK < O)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            var reAuth by remember { mutableStateOf(Config.suReAuth) }
            SettingsSwitch(
                title = stringResource(CoreR.string.settings_su_reauth_title),
                summary = stringResource(CoreR.string.settings_su_reauth_summary),
                checked = reAuth,
                onCheckedChange = {
                    reAuth = it
                    Config.suReAuth = it
                }
            )
        }

        // Restrict (version >= 30.1)
        if (Const.Version.atLeast_30_1()) {
            var restrict by remember { mutableStateOf(Config.suRestrict) }
            SettingsSwitch(
                title = stringResource(CoreR.string.settings_su_restrict_title),
                summary = stringResource(CoreR.string.settings_su_restrict_summary),
                checked = restrict,
                onCheckedChange = {
                    restrict = it
                    Config.suRestrict = it
                }
            )
        }
    }
}

// --- Dialogs ---

@Composable
private fun UpdateChannelUrlDialog(show: Boolean, onDismiss: () -> Unit) {
    val showState = rememberSaveable { mutableStateOf(show) }
    showState.value = show
    var url by rememberSaveable { mutableStateOf(Config.customChannelUrl) }

    if (showState.value) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(CoreR.string.settings_update_custom_msg)) },
            text = {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        Config.customChannelUrl = url
                        Info.resetUpdate()
                        onDismiss()
                    }
                ) {
                    Text(stringResource(android.R.string.ok))
                }
            }
        )
    }
}

@Composable
private fun DownloadPathDialog(show: Boolean, onDismiss: () -> Unit) {
    val showState = rememberSaveable { mutableStateOf(show) }
    showState.value = show
    var path by rememberSaveable { mutableStateOf(Config.downloadDir) }

    if (showState.value) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(CoreR.string.settings_download_path_title)) },
            text = {
                Column {
                    Text(
                        text = stringResource(CoreR.string.settings_download_path_message, MediaStoreUtils.fullPath(path)),
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    OutlinedTextField(
                        value = path,
                        onValueChange = { path = it },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        Config.downloadDir = path
                        onDismiss()
                    }
                ) {
                    Text(stringResource(android.R.string.ok))
                }
            }
        )
    }
}

@Composable
private fun HideAppDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    val defaultName = stringResource(CoreR.string.settings)
    var appName by rememberSaveable { mutableStateOf(defaultName) }
    val isError = appName.isBlank() || appName.length > AppMigration.MAX_LABEL_LENGTH

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(CoreR.string.settings_hide_app_title)) },
        text = {
            OutlinedTextField(
                value = appName,
                onValueChange = { appName = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(CoreR.string.settings_app_name_hint)) },
                isError = isError,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(appName) },
                enabled = !isError,
            ) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}

@Composable
private fun RestoreAppDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(CoreR.string.settings_restore_app_title)) },
        text = { Text(stringResource(CoreR.string.restore_app_confirmation)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}
