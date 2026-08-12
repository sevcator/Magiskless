package com.topjohnwu.magisk.core.base

import android.Manifest.permission.REQUEST_INSTALL_PACKAGES
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.topjohnwu.magisk.StubApk
import com.topjohnwu.magisk.core.BuildConfig
import com.topjohnwu.magisk.core.BuildConfig.APP_PACKAGE_NAME
import com.topjohnwu.magisk.core.Config
import com.topjohnwu.magisk.core.Const
import com.topjohnwu.magisk.core.Info
import com.topjohnwu.magisk.core.isRunningAsStub
import com.topjohnwu.magisk.core.ktx.writeTo
import com.topjohnwu.magisk.core.tasks.AppMigration
import com.topjohnwu.magisk.core.utils.RootUtils
import com.topjohnwu.magisk.view.Notifications
import com.topjohnwu.magisk.view.Shortcuts
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

interface SplashScreenHost : IActivityExtension {
    val splashController: SplashController<*>

    fun onCreateUi(savedInstanceState: Bundle?)
    fun showInvalidStateMessage()
}

class SplashController<T>(private val activity: T)
    where T : ComponentActivity, T: SplashScreenHost {

    companion object {
        private var splashShown = false
    }

    private var shouldCreateUiOnResume = false

    fun preOnCreate() {
        if (isRunningAsStub && !splashShown) {
            StartupTheme.apply(activity, stub = true)
        }
    }

    fun onCreate(savedInstanceState: Bundle?) {
        if (!isRunningAsStub) {
            val splashScreen = activity.installSplashScreen()
            splashScreen.setKeepOnScreenCondition { !splashShown }
        }

        if (splashShown) {
            doCreateUi(savedInstanceState)
        } else {
            Shell.getShell(Shell.EXECUTOR) {
                if (isRunningAsStub && !it.isRoot) {
                    activity.showInvalidStateMessage()
                    return@getShell
                }
                activity.initializeApp()
                activity.runOnUiThread {
                    splashShown = true
                    if (isRunningAsStub) {
                        // Re-launch main activity without splash theme
                        activity.relaunch()
                    } else {
                        if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                            doCreateUi(savedInstanceState)
                        } else {
                            shouldCreateUiOnResume = true
                        }
                    }
                }
            }
        }
    }

    fun onResume() {
        if (shouldCreateUiOnResume) {
            doCreateUi(null)
        }
    }

    private fun doCreateUi(savedInstanceState: Bundle?) {
        shouldCreateUiOnResume = false
        activity.onCreateUi(savedInstanceState)
    }

    private fun T.initializeApp() {
        val prevPkg = intent.getStringExtra(Const.Key.PREV_PACKAGE)
        val prevConfig = intent.getBundleExtra(Const.Key.PREV_CONFIG)
        val migrationSource = AppMigration.pendingMigrationSource(this, prevPkg)
        val authenticatedConfig = migrationSource != null &&
            prevPkg == migrationSource &&
            prevConfig != null

        Config.init(if (authenticatedConfig) prevConfig else null)

        if (packageName == APP_PACKAGE_NAME) {
            Config.suManager = ""
        } else {
            Config.suManager = packageName
        }

        if (migrationSource != null && AppMigration.completeMigration(this, migrationSource)) {
            intent.removeExtra(Const.Key.PREV_PACKAGE)
            intent.removeExtra(Const.Key.PREV_CONFIG)
            runOnUiThread {
                StubApk.restartProcess(this)
            }
            return
        }

        Shell.cmd("rm -f /cache/magisk.log").exec()

        // Validate stub APK
        if (isRunningAsStub && (
                // Version mismatch
                Info.stub!!.version != BuildConfig.STUB_VERSION ||
                // Not properly patched
                intent.component!!.className.contains(AppMigration.PLACEHOLDER))
        ) {
            withPermission(REQUEST_INSTALL_PACKAGES) { granted ->
                if (granted) {
                    lifecycleScope.launch {
                        val apk = File(cacheDir, "stub.apk")
                        try {
                            assets.open("stub.apk").writeTo(apk)
                            AppMigration.upgradeStub(activity, apk)?.let {
                                startActivity(it)
                            }
                        } catch (e: IOException) {
                        }
                    }
                }
            }
            return
        }

        Notifications.setup()
        Shortcuts.setupDynamic(this)

        RootUtils.Connection.await()
    }
}
