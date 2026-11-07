package com.altitudeplusplus.android.download

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.annotation.OptIn
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Scheduler
import com.altitudeplusplus.android.AltitudeApp
import com.altitudeplusplus.android.MainActivity
import com.altitudeplusplus.android.R

/** Runs downloads in the background with a progress notification. */
@OptIn(UnstableApi::class)
class AltitudeDownloadService : DownloadService(
    FOREGROUND_NOTIFICATION_ID,
    DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
    CHANNEL_ID,
    R.string.download_channel_name,
    R.string.download_channel_description,
) {
    override fun getDownloadManager(): DownloadManager {
        val store = (application as AltitudeApp).graph.offline
        // Post "saved" / "failed" notifications once per manager.
        if (!listenerAdded) {
            listenerAdded = true
            store.downloadManager.addListener(TerminalStateNotifier(applicationContext))
        }
        return store.downloadManager
    }

    override fun getScheduler(): Scheduler? = null

    override fun getForegroundNotification(downloads: MutableList<Download>, notMetRequirements: Int): Notification =
        DownloadNotificationHelper(this, CHANNEL_ID).buildProgressNotification(
            this, R.drawable.ic_download, openApp(this), null, downloads, notMetRequirements,
        )

    private class TerminalStateNotifier(private val context: Context) : DownloadManager.Listener {
        private val helper = DownloadNotificationHelper(context, CHANNEL_ID)
        private var nextId = FOREGROUND_NOTIFICATION_ID + 1

        override fun onDownloadChanged(manager: DownloadManager, download: Download, finalException: Exception?) {
            val title = (context.applicationContext as AltitudeApp).graph.offline.meta(download.request)?.title
            val notification = when (download.state) {
                Download.STATE_COMPLETED -> helper.buildDownloadCompletedNotification(
                    context, R.drawable.ic_download, openApp(context), "Saved: ${title ?: "video"}",
                )
                Download.STATE_FAILED -> helper.buildDownloadFailedNotification(
                    context, R.drawable.ic_download, openApp(context), "Couldn't save: ${title ?: "video"}",
                )
                else -> return
            }
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                NotificationManagerCompat.from(context).notify(nextId++, notification)
            }
        }
    }

    companion object {
        private const val FOREGROUND_NOTIFICATION_ID = 4100
        private const val CHANNEL_ID = "downloads"
        private var listenerAdded = false

        private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
