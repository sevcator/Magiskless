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
import com.topjohnwu.magisk.core.model.su.SuPolicy
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
    private const val ANDROID_MANIFEST = "AndroidManifest.xml"
    private const val TEST_PKG_NAME = "$APP_PACKAGE_NAME.test"
    // The bundled stub is generated from Magisk's original manifest and can
    // still contain the upstream package even though this build uses the
    // Reisenless application id. Both names must be rewritten during hiding.
    private const val LEGACY_PACKAGE_NAME = "com.topjohnwu.magisk"
    private val PACKAGE_ROOTS = arrayOf(
        "com", "org", "net", "io", "co", "app", "dev", "me", "tech", "cloud",
    )
    private val FALLBACK_APP_NAMES = arrayOf(
        "telegram", "whatsapp", "chrome", "camera", "calendar", "gallery", "notes",
        "music", "weather", "drive", "maps", "photos", "clock", "files",
    )
    @Suppress("DEPRECATION")
    private val FRAMEWORK_ICONS = intArrayOf(
        android.R.drawable.sym_def_app_icon,
        android.R.drawable.ic_dialog_alert,
        android.R.drawable.ic_dialog_info,
        android.R.drawable.ic_menu_camera,
        android.R.drawable.ic_menu_compass,
        android.R.drawable.ic_menu_gallery,
        android.R.drawable.ic_menu_manage,
        android.R.drawable.ic_menu_mapmode,
        android.R.drawable.ic_menu_myplaces,
    )
    private val PACKAGE_NAME = Regex("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+")

    const val PLACEHOLDER = "COMPONENT_PLACEHOLDER"

    private data class HiddenIdentity(
        val label: String,
        val packageName: String,
        val minSdk: Int,
        val versionName: String,
        val versionCode: Int,
        val icon: Int,
    )

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

    @Suppress("DEPRECATION")
    private fun installedUid(context: Context, pkg: String): Int? {
        return try {
            context.packageManager.getApplicationInfo(pkg, 0).uid
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }

    /** Give a newly installed migration target root before its first launch. */
    private fun authorizeMigrationTarget(uid: Int): Boolean {
        val query = "REPLACE INTO policies " +
            "(uid, policy, until, logging, notification) " +
            "VALUES ($uid, ${SuPolicy.ALLOW}, 0, 0, 0)"
        return Shell.cmd("${Const.MAIN_BIN} --sqlite '$query'").exec().isSuccess
    }

    private fun revokeMigrationPolicy(uid: Int) {
        Shell.cmd(
            "${Const.MAIN_BIN} --sqlite 'DELETE FROM policies WHERE uid=$uid'"
        ).exec()
    }

    /** Make the full APK available before the hidden stub's first process starts. */
    @Suppress("DEPRECATION")
    private fun seedMigrationTarget(context: Context, pkg: String, uid: Int): Boolean {
        val info = try {
            context.packageManager.getApplicationInfo(pkg, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            return false
        }
        val dataDir = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            info.deviceProtectedDataDir
        } else {
            info.dataDir
        }
        val dataContext = Shell.cmd("ls -Zd $dataDir").exec().out
            .firstOrNull()?.substringBefore(' ')
            ?.takeIf { it.count { c -> c == ':' } >= 3 }
            ?: return false
        val dynDir = File(dataDir, "dyn")
        val currentApk = File(dynDir, "current.apk")
        return Shell.cmd(
            "mkdir -p ${dynDir.path}",
            "cp -f $AppApkPath ${currentApk.path}",
            "chown $uid:$uid ${dynDir.path} ${currentApk.path}",
            "chmod 700 ${dynDir.path}",
            "chmod 400 ${currentApk.path}",
            "chcon $dataContext ${dynDir.path} ${currentApk.path}",
        ).exec().isSuccess
    }

    @Suppress("DEPRECATION")
    private fun generateIdentity(context: Context): HiddenIdentity {
        val random = SecureRandom()
        val installedLabels = context.packageManager.getInstalledApplications(0)
            .asSequence()
            .map { it.loadLabel(context.packageManager).toString().lowercase() }
            .map { label -> label.filter { it in 'a'..'z' } }
            .filter { it.length >= 3 }
            .distinct()
            .toMutableList()
            .apply { addAll(FALLBACK_APP_NAMES) }
            .distinct()

        val first = installedLabels[random.nextInt(installedLabels.size)]
        var second = installedLabels[random.nextInt(installedLabels.size)]
        while (installedLabels.size > 1 && second == first) {
            second = installedLabels[random.nextInt(installedLabels.size)]
        }
        val kRandom = random.asKotlinRandom()
        val length = 3 + random.nextInt(2)
        val mixed = buildList {
            add(first[random.nextInt(first.length)])
            add(second[random.nextInt(second.length)])
            addAll((first + second).toList().shuffled(kRandom).take(length - 2))
        }.shuffled(kRandom)
        val labelLower = mixed.joinToString("")
        val label = labelLower.replaceFirstChar(Char::uppercaseChar)

        fun randomWord(length: Int) = buildString(length) {
            repeat(length) { append(ALPHA[random.nextInt(ALPHA.length)]) }
        }

        val packageName = buildString {
            append(PACKAGE_ROOTS[random.nextInt(PACKAGE_ROOTS.size)])
            append('.')
            append(randomWord(4 + random.nextInt(8)))
            append('.')
            append(labelLower)
        }

        val components = IntArray(3 + random.nextInt(2)) { index ->
            if (index == 0) 1 + random.nextInt(15) else random.nextInt(100)
        }
        val suffix = if (random.nextInt(3) == 0) "-${randomWord(5 + random.nextInt(5))}" else ""
        val versionName = components.joinToString(".") + suffix
        val versionCode = components.fold(0) { code, component -> code * 100 + component }

        return HiddenIdentity(
            label = label,
            packageName = packageName,
            minSdk = 5 + random.nextInt(9),
            versionName = versionName,
            versionCode = versionCode.coerceAtLeast(1),
            // The stub has no public resources. Its real resources are encrypted
            // and loaded only after process startup, so the package installer and
            // launcher can resolve only framework-owned icon resource IDs.
            icon = FRAMEWORK_ICONS[random.nextInt(FRAMEWORK_ICONS.size)],
        )
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
        identity: HiddenIdentity,
    ): Boolean {
        val pm = context.packageManager
        val packageInfo = pm.getPackageArchiveInfo(apk.path, 0) ?: return false
        val info = packageInfo.applicationInfo ?: return false
        info.sourceDir = apk.path
        info.publicSourceDir = apk.path
        // Resolve resource-backed labels as well as literal android:label values.
        // The previous nonLocalizedLabel lookup returned "null" for the shipped
        // APK, so the hidden package kept the old visible Reisenless label.
        val origLabel = info.loadLabel(pm).toString()
        try {
            JarMap.open(apk, true).use { jar ->
                val je = jar.getJarEntry(ANDROID_MANIFEST)
                val xml = AXML(jar.getRawData(je))
                val generator = classNameGenerator()
                val sourcePackages = setOf(APP_PACKAGE_NAME, LEGACY_PACKAGE_NAME)
                val p = xml.patchStrings {
                    when {
                        sourcePackages.any(it::contains) -> sourcePackages.fold(it) { value, source ->
                            value.replace(source, identity.packageName)
                        }
                        it.contains(PLACEHOLDER) -> generator.next()
                        it == origLabel -> identity.label
                        it == packageInfo.versionName -> identity.versionName
                        else -> it
                    }
                }
                if (!p ||
                    !xml.patchIntAttribute("minSdkVersion", identity.minSdk) ||
                    !xml.patchIntAttribute("versionCode", identity.versionCode) ||
                    !xml.patchIntAttribute("icon", identity.icon)
                ) return false

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
                val sourcePackages = setOf(sourcePkg, LEGACY_PACKAGE_NAME)
                val p = xml.patchStrings {
                    sourcePackages.fold(it) { value, source ->
                        value.replace(source, targetPkg)
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

    /** Install migration APKs from the already-rooted app shell. */
    private fun installMigrationApk(apk: File): Boolean {
        // Copy to a world-readable path before switching to the system UID.
        // Mark the migration as a device restore: it is an app identity handoff,
        // not a user-requested installation from an unknown package source.
        val tmp = "/data/local/tmp/reisenless-migration.apk"
        if (!Shell.cmd("cp -f ${apk.absolutePath} $tmp", "chmod 644 $tmp").exec().isSuccess) {
            return false
        }
        return try {
            Shell.cmd("su 1000 -c 'pm install -g --install-reason 2 $tmp'").exec().isSuccess ||
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

    suspend fun patchAndHide(context: Context): Boolean =
        withContext(Dispatchers.IO) {
            val workDir = File(context.cacheDir, "app-migration")
            workDir.deleteRecursively()
            if (!workDir.mkdirs()) return@withContext false
            var installedTestPackage: String? = null
            var installedMainPackage: String? = null
            var installedMainUid: Int? = null
            val previousManager = Config.suManager
            var managerChanged = false
            var committed = false
            try {
                val stub = File(workDir, Const.STUB_NAME)
                try {
                    context.assets.open(Const.STUB_NAME).writeTo(stub)
                } catch (_: IOException) {
                    return@withContext false
                }

                val identity = generateSequence { generateIdentity(context) }
                    .take(8)
                    .firstOrNull {
                        !isInstalled(context, it.packageName) &&
                            !isInstalled(context, "${it.packageName}.test")
                    }
                    ?: return@withContext false
                val newPackage = identity.packageName
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
                    if (!patch(context, stub, it, identity)) {
                        return@withContext false
                    }
                }

                if (!installMigrationApk(repack)) {
                    return@withContext false
                }
                installedMainPackage = newPackage
                val newUid = installedUid(context, newPackage)
                    ?: return@withContext false
                installedMainUid = newUid
                if (!authorizeMigrationTarget(newUid)) {
                    return@withContext false
                }
                if (!seedMigrationTarget(context, newPackage, newUid)) {
                    return@withContext false
                }
                if (!Shell.cmd("${Const.MAIN_BIN} --sulist add $newPackage").exec().isSuccess) {
                    return@withContext false
                }
                // The daemon recognizes the configured manager before applying
                // Sulist restrictions. Publish the new identity before its first
                // root request; the target cannot set this itself without root.
                Config.suManager = newPackage
                managerChanged = true
                Shell.cmd("touch $AppApkPath").exec()
                if (!launchApp(context, newPackage)) return@withContext false
                committed = true
                return@withContext true
            } finally {
                if (!committed) {
                    if (managerChanged) Config.suManager = previousManager
                    installedTestPackage?.let { Shell.cmd("pm uninstall $it").exec() }
                    installedMainUid?.let(::revokeMigrationPolicy)
                    installedMainPackage?.let {
                        Shell.cmd("${Const.MAIN_BIN} --sulist rm $it").exec()
                    }
                    installedMainPackage?.let { Shell.cmd("pm uninstall $it").exec() }
                }
                workDir.deleteRecursively()
            }
        }

    @Suppress("DEPRECATION")
    suspend fun hide(activity: Activity) {
        val dialog = android.app.ProgressDialog(activity).apply {
            setTitle(activity.getString(R.string.hide_app_title))
            isIndeterminate = true
            setCancelable(false)
            show()
        }
        val success = patchAndHide(activity)
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
        var installedMainUid: Int? = null
        val previousManager = Config.suManager
        var managerChanged = false
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
                val newUid = installedUid(context, APP_PACKAGE_NAME)
                    ?: return@withContext false
                installedMainUid = newUid
                if (!authorizeMigrationTarget(newUid)) {
                    return@withContext false
                }
                // An empty requester selects the signed original package.
                Config.suManager = ""
                managerChanged = true
                Shell.cmd("touch $AppApkPath").exec()
                if (launchApp(context, APP_PACKAGE_NAME)) {
                    committed = true
                    return@withContext true
                }
            }
            return@withContext false
        } finally {
            if (!committed) {
                if (managerChanged) Config.suManager = previousManager
                if (installedTest) Shell.cmd("pm uninstall $TEST_PKG_NAME").exec()
                installedMainUid?.let(::revokeMigrationPolicy)
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
        val sourceUid = installedUid(context, source)
        val sourceUidIsExclusive = sourceUid != null &&
            context.packageManager.getPackagesForUid(sourceUid).orEmpty().singleOrNull() == source
        val sourceTest = "$source.test"
        Shell.cmd("${Const.MAIN_BIN} --sulist rm $source").exec()
        if (isInstalled(context, sourceTest)) {
            Shell.cmd("pm uninstall $sourceTest").exec()
        }
        if (isInstalled(context, source)) {
            Shell.cmd("pm uninstall $source").exec()
        }
        val complete = !isInstalled(context, sourceTest) && !isInstalled(context, source)
        if (complete) {
            if (sourceUidIsExclusive) sourceUid?.let(::revokeMigrationPolicy)
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
        @Suppress("DEPRECATION")
        val current = context.packageManager.getPackageInfo(context.packageName, 0)
        val appInfo = current.applicationInfo ?: return null
        val currentCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            current.longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION")
            current.versionCode
        }
        val identity = HiddenIdentity(
            label = appInfo.loadLabel(context.packageManager).toString(),
            packageName = context.packageName,
            minSdk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                appInfo.minSdkVersion
            } else {
                Build.VERSION.SDK_INT
            },
            versionName = current.versionName ?: "1.0",
            versionCode = currentCode,
            icon = appInfo.icon.takeIf { it != 0 } ?: android.R.drawable.sym_def_app_icon,
        )
        val session = APKInstall.startSession(context)
        return withContext(Dispatchers.IO) {
            session.openStream(context).use {
                if (!patch(context, apk, it, identity)) {
                    return@withContext null
                }
            }
            session.waitIntent()
        }
    }
}
