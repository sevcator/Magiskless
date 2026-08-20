package com.topjohnwu.magisk.core.tasks

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import com.topjohnwu.magisk.StubApk
import com.topjohnwu.magisk.core.AppApkPath
import com.topjohnwu.magisk.core.BuildConfig.APP_PACKAGE_NAME
import com.topjohnwu.magisk.core.Config
import com.topjohnwu.magisk.core.Const
import com.topjohnwu.magisk.core.R
import com.topjohnwu.magisk.core.ktx.toast
import com.topjohnwu.magisk.core.ktx.writeTo
import com.topjohnwu.magisk.core.signing.JarMap
import com.topjohnwu.magisk.core.signing.SignApk
import com.topjohnwu.magisk.core.utils.AXML
import com.topjohnwu.magisk.core.utils.Keygen
import com.topjohnwu.magisk.utils.APKInstall
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.security.SecureRandom
import kotlin.random.asKotlinRandom

object AppMigration {

    private const val ALPHA = "abcdefghijklmnopqrstuvwxyz"
    private const val ALPHADOTS = "$ALPHA....."
    private const val ANDROID_MANIFEST = "AndroidManifest.xml"
    private const val TEST_PKG_NAME = "$APP_PACKAGE_NAME.test"
    private val PACKAGE_NAME = Regex("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+")

    const val MAX_LABEL_LENGTH = 32
    const val PLACEHOLDER = "COMPONENT_PLACEHOLDER"

    private fun isValidPackageName(pkg: String) = PACKAGE_NAME.matches(pkg)

    @Suppress("DEPRECATION")
    private fun isInstalled(context: Context, pkg: String): Boolean {
        return try {
            context.packageManager.getApplicationInfo(pkg, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun genPackageName(): String {
        val random = SecureRandom()
        val len = 5 + random.nextInt(15)
        val builder = StringBuilder(len)
        var next: Char
        var prev = 0.toChar()
        for (i in 0 until len) {
            next = if (prev == '.' || i == 0 || i == len - 1) {
                ALPHA[random.nextInt(ALPHA.length)]
            } else {
                ALPHADOTS[random.nextInt(ALPHADOTS.length)]
            }
            builder.append(next)
            prev = next
        }
        if (!builder.contains('.')) {
            // Pick a random index and set it as dot
            val idx = random.nextInt(len - 2)
            builder[idx + 1] = '.'
        }
        return builder.toString()
    }

    private fun classNameGenerator() = sequence {
        val c1 = mutableListOf<String>()
        val c2 = mutableListOf<String>()
        val c3 = mutableListOf<String>()
        val random = SecureRandom()
        val kRandom = random.asKotlinRandom()

        fun <T> chain(vararg iters: Iterable<T>) = sequence {
            iters.forEach { it.forEach { v -> yield(v) } }
        }

        for (a in chain('a'..'z', 'A'..'Z')) {
            if (a != 'a' && a != 'A') {
                c1.add("$a")
            }
            for (b in chain('a'..'z', 'A'..'Z', '0'..'9')) {
                c2.add("$a$b")
                for (c in chain('a'..'z', 'A'..'Z', '0'..'9')) {
                    c3.add("$a$b$c")
                }
            }
        }

        c1.shuffle(random)
        c2.shuffle(random)
        c3.shuffle(random)

        fun notJavaKeyword(name: String) = when (name) {
            "do", "if", "for", "int", "new", "try" -> false
            else -> true
        }

        fun List<String>.process() = asSequence().filter(::notJavaKeyword)

        val names = mutableListOf<String>()
        names.addAll(c1)
        names.addAll(c2.process().take(30))
        names.addAll(c3.process().take(30))

        while (true) {
            val seg = 2 + random.nextInt(4)
            val cls = StringBuilder()
            for (i in 0 until seg) {
                cls.append(names.random(kRandom))
                if (i != seg - 1)
                    cls.append('.')
            }
            cls[0] = cls[0].lowercaseChar()
            yield(cls.toString())
        }
    }.distinct().iterator()

    private fun patch(
        context: Context,
        apk: File, out: OutputStream,
        pkg: String, label: CharSequence
    ): Boolean {
        val pm = context.packageManager
        val info = pm.getPackageArchiveInfo(apk.path, 0)?.applicationInfo ?: return false
        // Resolve resource-backed labels as well as literal android:label values.
        // The previous nonLocalizedLabel lookup returned "null" for the shipped
        // APK, so the hidden package kept the old visible Reisenless label.
        val origLabel = info.loadLabel(pm).toString()
        try {
            JarMap.open(apk, true).use { jar ->
                val je = jar.getJarEntry(ANDROID_MANIFEST)
                val xml = AXML(jar.getRawData(je))
                val generator = classNameGenerator()
                val p = xml.patchStrings {
                    when {
                        it.contains(APP_PACKAGE_NAME) -> it.replace(APP_PACKAGE_NAME, pkg)
                        it.contains(PLACEHOLDER) -> generator.next()
                        it == origLabel -> label.toString()
                        else -> it
                    }
                }
                if (!p) return false

                jar.getOutputStream(je).use { it.write(xml.bytes) }
                val keys = Keygen()
                SignApk.sign(keys.cert, keys.key, jar, out)
                return true
            }
        } catch (e: Exception) {
            return false
        }
    }

    private fun patchTest(
        apk: File,
        out: File,
        sourcePkg: String,
        targetPkg: String,
    ): Boolean {
        try {
            JarMap.open(apk, true).use { jar ->
                val je = jar.getJarEntry(ANDROID_MANIFEST)
                val xml = AXML(jar.getRawData(je))
                val p = xml.patchStrings {
                    when (it) {
                        sourcePkg -> targetPkg
                        "$sourcePkg.test" -> "$targetPkg.test"
                        else -> it
                    }
                }
                if (!p) return false

                jar.getOutputStream(je).use { it.write(xml.bytes) }
                val keys = Keygen()
                out.outputStream().use { SignApk.sign(keys.cert, keys.key, jar, it) }
                return true
            }
        } catch (e: Exception) {
            return false
        }
    }

    /**
     * Install migration APKs from the already-rooted app shell.  Calling the
     * shell helper used for normal module installs changes the installer UID
     * to Android's shell user; on current Android that starts an interactive
     * Play Protect verification flow and the handoff never completes.
     */
    private fun installMigrationApk(apk: File): Boolean {
        // The system UID has INSTALL_PACKAGES and does not route this
        // dynamically signed APK through the shell user's Play Protect UI.
        // Copy to a world-readable temporary path before switching UID.
        val tmp = "/data/local/tmp/reisenless-migration.apk"
        if (!Shell.cmd("cp -f ${apk.absolutePath} $tmp", "chmod 644 $tmp").exec().isSuccess) {
            return false
        }
        return try {
            Shell.cmd("su 1000 -c 'pm install -g $tmp'").exec().isSuccess ||
                Shell.cmd("pm install -g $tmp").exec().isSuccess
        } finally {
            Shell.cmd("rm -f $tmp").exec()
        }
    }

    private suspend fun launchApp(context: Context, pkg: String): Boolean {
        if (!isValidPackageName(pkg) || pkg == context.packageName) return false
        val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        Config.migrationSource = context.packageName
        Config.migrationTarget = pkg
        intent.putExtra(Const.Key.PREV_CONFIG, Config.toBundle())
        intent.putExtra(Const.Key.PREV_PACKAGE, context.packageName)
        val options = ActivityOptions.makeBasic()
        if (Build.VERSION.SDK_INT >= 34) {
            options.setShareIdentityEnabled(true)
        }
        val launched = withContext(Dispatchers.Main.immediate) {
            try {
                context.startActivity(intent, options.toBundle())
                if (context is Activity) context.finish()
                true
            } catch (_: RuntimeException) {
                false
            }
        }
        if (!launched) {
            Config.migrationSource = ""
            Config.migrationTarget = ""
        }
        return launched
    }

    suspend fun patchAndHide(context: Context, label: String, pkg: String? = null): Boolean =
        withContext(Dispatchers.IO) {
            if (label.isBlank() || label.length > MAX_LABEL_LENGTH) return@withContext false
            val workDir = File(context.cacheDir, "app-migration")
            workDir.deleteRecursively()
            if (!workDir.mkdirs()) return@withContext false
            var installedTestPackage: String? = null
            var installedMainPackage: String? = null
            var committed = false
            try {
                val stub = File(workDir, "stub.apk")
                try {
                    context.assets.open("stub.apk").writeTo(stub)
                } catch (_: IOException) {
                    return@withContext false
                }

                val newPackage = pkg ?: generateSequence(::genPackageName)
                    .take(8)
                    .firstOrNull {
                        !isInstalled(context, it) && !isInstalled(context, "$it.test")
                    }
                    ?: return@withContext false
                if (!isValidPackageName(newPackage) || newPackage == context.packageName) {
                    return@withContext false
                }
                if (isInstalled(context, newPackage) || isInstalled(context, "$newPackage.test")) {
                    return@withContext false
                }
                Config.keyStoreRaw = ""
                val oldTestPackage = "${context.packageName}.test"

                try {
                    val info = context.packageManager.getApplicationInfo(oldTestPackage, 0)
                    val testApk = File(info.sourceDir)
                    val testRepack = File(workDir, "test.apk")
                    if (!patchTest(
                            testApk,
                            testRepack,
                            context.packageName,
                            newPackage,
                        )) return@withContext false
                    if (!installMigrationApk(testRepack)) {
                        return@withContext false
                    }
                    installedTestPackage = "$newPackage.test"
                } catch (_: PackageManager.NameNotFoundException) {
                }

                val repack = File(workDir, "patched.apk")
                repack.outputStream().use {
                    if (!patch(context, stub, it, newPackage, label.lowercase())) {
                        return@withContext false
                    }
                }

                if (!installMigrationApk(repack)) {
                    return@withContext false
                }
                installedMainPackage = newPackage
                Shell.cmd("touch $AppApkPath").exec()
                if (!launchApp(context, newPackage)) return@withContext false
                committed = true
                return@withContext true
            } finally {
                if (!committed) {
                    installedTestPackage?.let { Shell.cmd("pm uninstall $it").exec() }
                    installedMainPackage?.let { Shell.cmd("pm uninstall $it").exec() }
                }
                workDir.deleteRecursively()
            }
        }

    @Suppress("DEPRECATION")
    suspend fun hide(activity: Activity, label: String) {
        val dialog = android.app.ProgressDialog(activity).apply {
            setTitle(activity.getString(R.string.hide_app_title))
            isIndeterminate = true
            setCancelable(false)
            show()
        }
        val success = patchAndHide(activity, label)
        if (!success) {
            dialog.dismiss()
            activity.toast(R.string.failure, Toast.LENGTH_LONG)
        }
    }

    suspend fun restoreApp(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (context.packageName == APP_PACKAGE_NAME || isInstalled(context, APP_PACKAGE_NAME)) {
            return@withContext false
        }
        val workDir = File(context.cacheDir, "app-migration")
        workDir.deleteRecursively()
        if (!workDir.mkdirs()) return@withContext false
        var installedTest = false
        var installedMain = false
        var committed = false
        try {
            val sourceTestPackage = "${context.packageName}.test"
            if (isInstalled(context, sourceTestPackage)) {
                if (isInstalled(context, TEST_PKG_NAME)) return@withContext false
                val info = try {
                    context.packageManager.getApplicationInfo(sourceTestPackage, 0)
                } catch (_: PackageManager.NameNotFoundException) {
                    return@withContext false
                }
                val testRepack = File(workDir, "test.apk")
                if (!patchTest(
                        File(info.sourceDir),
                        testRepack,
                        context.packageName,
                        APP_PACKAGE_NAME,
                    )) return@withContext false
                if (!installMigrationApk(testRepack)) {
                    return@withContext false
                }
                installedTest = true
            }

            val apk = StubApk.current(context)
            if (installMigrationApk(apk)) {
                installedMain = true
                Shell.cmd("touch $AppApkPath").exec()
                if (launchApp(context, APP_PACKAGE_NAME)) {
                    committed = true
                    return@withContext true
                }
            }
            return@withContext false
        } finally {
            if (!committed) {
                if (installedTest) Shell.cmd("pm uninstall $TEST_PKG_NAME").exec()
                if (installedMain) Shell.cmd("pm uninstall $APP_PACKAGE_NAME").exec()
            }
            workDir.deleteRecursively()
        }
    }

    fun pendingMigrationSource(context: Context, requestedSource: String?): String? {
        val source = Config.migrationSource
        val target = Config.migrationTarget
        if (target != context.packageName || source == target || !isValidPackageName(source)) {
            return null
        }
        return source.takeIf { requestedSource == null || requestedSource == it }
    }

    fun completeMigration(context: Context, source: String): Boolean {
        if (!isValidPackageName(source) || source == context.packageName) return false
        val sourceTest = "$source.test"
        if (isInstalled(context, sourceTest)) {
            Shell.cmd("pm uninstall $sourceTest").exec()
        }
        if (isInstalled(context, source)) {
            Shell.cmd("pm uninstall $source").exec()
        }
        val complete = !isInstalled(context, sourceTest) && !isInstalled(context, source)
        if (complete) {
            Config.migrationSource = ""
            Config.migrationTarget = ""
        }
        return complete
    }

    @Suppress("DEPRECATION")
    suspend fun restore(activity: Activity) {
        val dialog = android.app.ProgressDialog(activity).apply {
            setTitle(activity.getString(R.string.restore_img_msg))
            isIndeterminate = true
            setCancelable(false)
            show()
        }
        if (!restoreApp(activity)) {
            activity.toast(R.string.failure, Toast.LENGTH_LONG)
        }
        dialog.dismiss()
    }

    suspend fun upgradeStub(context: Context, apk: File): Intent? {
        val label = context.applicationInfo.nonLocalizedLabel
        val pkg = context.packageName
        val session = APKInstall.startSession(context)
        return withContext(Dispatchers.IO) {
            session.openStream(context).use {
                if (!patch(context, apk, it, pkg, label)) {
                    return@withContext null
                }
            }
            session.waitIntent()
        }
    }
}
