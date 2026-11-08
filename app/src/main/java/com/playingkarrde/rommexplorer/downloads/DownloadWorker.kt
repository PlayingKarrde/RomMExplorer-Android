package com.playingkarrde.rommexplorer.downloads

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.playingkarrde.rommexplorer.MainActivity
import com.playingkarrde.rommexplorer.R
import com.playingkarrde.rommexplorer.RommApp
import com.playingkarrde.rommexplorer.ui.formatBytes
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/** Foreground worker that drains the download queue. It never touches the UI. */
class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val container get() = (applicationContext as RommApp).container

    override suspend fun doWork(): Result {
        val repo = container.downloads
        container.restoreSession()
        if (!repo.hasQueued) return Result.success()
        ensureChannel()
        setForeground(foregroundInfo(build(repo.items.value)))
        try {
            coroutineScope {
                val updates = launch {
                    repo.items.sample(1000).collect { setForeground(foregroundInfo(build(it))) }
                }
                repo.runQueue()
                updates.cancel()
            }
        } catch (e: CancellationException) {
            repo.onWorkerStopped(cancelledByUser = stopReason == WorkInfo.STOP_REASON_CANCELLED_BY_APP)
            throw e
        }
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        ensureChannel()
        return foregroundInfo(build(container.downloads.items.value))
    }

    private fun foregroundInfo(n: android.app.Notification) =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, n)
        }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = applicationContext.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW))
            }
        }
    }

    private fun build(items: List<DownloadItem>): android.app.Notification {
        val running = items.filter { it.status is DownloadStatus.Downloading || it.status is DownloadStatus.Extracting }
        val queued = items.count { it.status is DownloadStatus.Queued }
        val first = running.firstOrNull()
        val title = when {
            first == null -> "Preparing downloads…"
            running.size == 1 -> first.rom.title
            else -> "Downloading ${running.size} games"
        }
        val dl = first?.status as? DownloadStatus.Downloading
        val text = buildString {
            if (dl != null) append("${formatBytes(dl.bytes)} of ${formatBytes(dl.total)}")
            if (first?.status is DownloadStatus.Extracting) append("Extracting…")
            if (queued > 0) append(if (isEmpty()) "$queued queued" else " · $queued queued")
        }
        val open = PendingIntent.getActivity(
            applicationContext, 0,
            Intent(applicationContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val cancel = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        return NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setProgress(100, ((dl?.fraction ?: 0f) * 100).toInt(), dl == null)
            .addAction(0, "Cancel all", cancel)
            .build()
    }

    companion object {
        const val CHANNEL = "downloads"
        const val NOTIFICATION_ID = 1001
    }
}
