package com.google.chrome.recovery.usb

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * A Foreground Service that runs alongside the flashing process to prevent the 
 * Android system from killing the application when it's moved to the background.
 *
 * This is particularly critical because downloading and writing a 16GB .bin file 
 * to a USB block device can take 10+ minutes. If the user navigates away from the 
 * app during this time without a Foreground Service, the OS will terminate the 
 * process to save memory, resulting in a corrupted USB drive.
 *
 * For API 36 (Android 16), this service also hosts the "Live Update" notification 
 * which projects the `setShortCriticalText` into the status bar chip.
 */
class KeepAliveService : Service() {
    companion object {
        var currentNotification: Notification? = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        currentNotification?.let {
            try {
                if (android.os.Build.VERSION.SDK_INT >= 34) {
                    // API 34+ requires specifying foregroundServiceType in startForeground
                    val type = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                    startForeground(1001, it, type)
                } else {
                    startForeground(1001, it)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return START_NOT_STICKY
    }
}
