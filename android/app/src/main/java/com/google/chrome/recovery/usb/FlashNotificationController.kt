package com.google.chrome.recovery.usb

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.chrome.recovery.MainActivity
import java.util.Locale

/**
 * Owns every notification and foreground-service concern of the flash flow.
 *
 * The flashing pipeline needs three pieces of system plumbing to survive and stay
 * visible while the user is away from the app:
 * 1. The "flash_progress" notification channel.
 * 2. The [KeepAliveService] Foreground Service, which claims foreground priority so the
 *    OS doesn't kill the multi-minute write when the app is backgrounded.
 * 3. The progress notification itself — on API 36 (Android 16) an "amber-alert style"
 *    Live Update built with `Notification.ProgressStyle()` and `setShortCriticalText`
 *    (projected into the status-bar chip), with a `NotificationCompat` fallback for
 *    older releases.
 *
 * Keeping all of that here means [com.google.chrome.recovery.ui.screens.FlashViewModel]
 * only decides *when* to notify, never *how*.
 */
class FlashNotificationController(private val context: Context) {

    companion object {
        private const val TAG = "FlashNotifications"
        private const val CHANNEL_ID = "flash_progress"
        private const val NOTIFICATION_ID = 1001

        /**
         * `Notification.FLAG_PROMOTED_ONGOING` (1 shl 18). Requests promotion to a
         * Live Update on API 36. The constant is not exposed through the public SDK
         * stubs we compile against, so the raw value is used.
         */
        private const val FLAG_PROMOTED_ONGOING = 262144
    }

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    // Rotating titles shown while flashing. The status-bar chip only fits a short
    // word, hence the parallel list of compact variants.
    private val funWords = listOf("Flashing...", "Fluxabilating...", "Gone fishing...", "Crunching bytes...", "Reticulating splines...", "Writing magic...", "Doing the heavy lifting...")
    private val shortChipTexts = listOf("Working", "Writing", "Wait...", "Almost", "Hold on", "Steady", "Busy")

    private val pendingIntent: PendingIntent by lazy {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        PendingIntent.getActivity(
            context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** Creates the progress notification channel. Safe to call repeatedly. */
    fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Flash Progress",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * Starts [KeepAliveService] with an initial notification so the app holds
     * foreground priority for the whole flash, even if the user immediately
     * minimizes during an erase-first pass.
     */
    fun startKeepAlive() {
        val initialNotif = buildProgressNotification(0f, "Preparing to flash", "Initializing...", "Started")
        KeepAliveService.currentNotification = initialNotif

        val serviceIntent = Intent(context, KeepAliveService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not start KeepAliveService", e)
        }
    }

    /** Stops [KeepAliveService] once the flash finishes, fails, or is cancelled. */
    fun stopKeepAlive() {
        context.stopService(Intent(context, KeepAliveService::class.java))
    }

    /**
     * Publishes the current progress: refreshes the notification the Foreground
     * Service is holding and (if the user granted notification permission) posts it.
     */
    fun postProgress(progress: Float, isErasing: Boolean) {
        val wordIndex = ((System.currentTimeMillis() / 10000) % funWords.size).toInt()
        val title = if (isErasing) "Erasing media..." else funWords[wordIndex]
        val chipText = if (isErasing) "Erasing" else shortChipTexts[wordIndex]
        val text = String.format(Locale.US, "%.1f%% complete", progress * 100)

        val notification = buildProgressNotification(progress, title, text, chipText)
        KeepAliveService.currentNotification = notification
        notifyIfPermitted(notification)
    }

    /** Removes the progress notification (the user is looking at the app again). */
    fun dismissProgress() {
        notificationManager.cancel(NOTIFICATION_ID)
    }

    /** Posts the terminal success/error notification shown when the app is backgrounded. */
    fun postCompletion(title: String, text: String, isError: Boolean) {
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(if (isError) android.R.drawable.stat_notify_error else android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
        notifyIfPermitted(builder.build())
    }

    private fun notifyIfPermitted(notification: Notification) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            notificationManager.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun buildProgressNotification(progress: Float, title: String, text: String, chipText: String): Notification {
        return if (Build.VERSION.SDK_INT >= 36) {
            val nativeBuilder = Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(title)
                .setContentText(text)
                .setColor(Color.parseColor("#4285F4"))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setProgress(100, (progress * 100).toInt(), false)

            try {
                nativeBuilder.setShortCriticalText(chipText)
                val progressStyle = Notification.ProgressStyle()
                progressStyle.setProgress((progress * 100).toInt())
                nativeBuilder.setStyle(progressStyle)

                val extras = Bundle()
                extras.putBoolean("android.requestPromotedOngoing", true)
                nativeBuilder.addExtras(extras)
            } catch (e: Exception) {
                Log.e(TAG, "Error setting Live Update styles", e)
            }

            val built = nativeBuilder.build()
            built.flags = built.flags or FLAG_PROMOTED_ONGOING
            built
        } else {
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(title)
                .setContentText(text)
                .setColor(Color.parseColor("#4285F4"))
                .setProgress(1000, (progress * 1000).toInt(), false)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build()
        }
    }
}
