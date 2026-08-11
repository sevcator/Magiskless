package com.topjohnwu.magisk.core.download

import android.app.PendingIntent
import android.content.Context
import android.net.Uri
import android.os.Parcelable
import androidx.core.net.toUri
import com.topjohnwu.magisk.core.model.module.OnlineModule
import com.topjohnwu.magisk.core.utils.MediaStoreUtils
import com.topjohnwu.magisk.view.Notifications
import kotlinx.parcelize.Parcelize
import java.io.File
import java.util.UUID

abstract class Subject : Parcelable {

    abstract val url: String
    abstract val file: Uri
    abstract val title: String
    abstract val notifyId: Int
    open val autoLaunch: Boolean get() = true

    open fun pendingIntent(context: Context): PendingIntent? = null

    abstract class Module : Subject() {
        abstract val module: OnlineModule
        final override val url: String get() = module.zipUrl
        final override val title: String get() = module.downloadFilename
        final override val file by lazy {
            MediaStoreUtils.getFile(title).uri
        }
    }

    @Parcelize
    class Test(
        override val notifyId: Int = Notifications.nextId(),
        override val title: String = UUID.randomUUID().toString().substring(0, 6)
    ) : Subject() {
        override val url get() = "https://link.testfile.org/250MB"
        override val file get() = File("/dev/null").toUri()
        override val autoLaunch get() = false
    }

}
