package com.google.chrome.recovery.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.compose.ui.platform.LocalContext
import com.google.chrome.recovery.usb.UsbFlasher

/**
 * FlashScreen handles the core execution logic of the Book Recovery utility.
 * 
 * This screen is responsible for:
 * 1. Tracking and displaying the status of writing the image to the USB device.
 * 2. Simulating a device erasure if [eraseFirst] is selected.
 * 3. Safely spinning up the [KeepAliveService] (Foreground Service) the moment the screen 
 *    is composed. This elevates the app to a foreground priority, ensuring the OS doesn't
 *    kill the background write process if the user navigates to their home screen.
 * 4. Generating and updating an Android 16 (API 36) Live Update status bar chip using 
 *    `Notification.ProgressStyle()` to keep the user informed natively.
 *
 * @param url The local or remote URL pointing to the recovery image .zip file.
 * @param device The physical USB device the user has authorized writing to.
 * @param eraseFirst If true, the screen will execute a 3-second simulation block before flashing.
 * @param onFinish Callback invoked when the user dismisses the success/error summary.
 */
@Composable
fun FlashScreen(url: String, device: UsbDevice, eraseFirst: Boolean = false, onFinish: () -> Unit) {
    var currentStep by remember { mutableStateOf("Starting...") }
    var progress by remember { mutableStateOf(0f) }
    var isFinished by remember { mutableStateOf(false) }
    var hasError by remember { mutableStateOf(false) }
    
    var isErasing by remember { mutableStateOf(eraseFirst) }
    var isCancelledErase by remember { mutableStateOf(false) }
    var isFlashing by remember { mutableStateOf(!eraseFirst) }

    val context = LocalContext.current
    val usbManager = remember { context.getSystemService(Context.USB_SERVICE) as UsbManager }
    val flasher = remember { UsbFlasher(usbManager, context) }

    var isLifecycleBackgrounded by remember { mutableStateOf(false) }
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    val view = androidx.compose.ui.platform.LocalView.current
    var isWindowFocused by remember { mutableStateOf(view.hasWindowFocus()) }

    DisposableEffect(view) {
        val listener = android.view.ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
            isWindowFocused = hasFocus
        }
        view.viewTreeObserver.addOnWindowFocusChangeListener(listener)
        onDispose {
            view.viewTreeObserver.removeOnWindowFocusChangeListener(listener)
        }
    }

    val isBackgrounded = isLifecycleBackgrounded || !isWindowFocused
    val isBackgroundedState = androidx.compose.runtime.rememberUpdatedState(isBackgrounded)

    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) {}

    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE || event == androidx.lifecycle.Lifecycle.Event.ON_STOP) {
                isLifecycleBackgrounded = true
            } else if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME || event == androidx.lifecycle.Lifecycle.Event.ON_START) {
                isLifecycleBackgrounded = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val notificationManager = remember { context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager }

    val pendingIntent = remember(context) {
        val intent = android.content.Intent(context, com.google.chrome.recovery.MainActivity::class.java).apply {
            flags = android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        android.app.PendingIntent.getActivity(
            context, 0, intent, android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
    }

    val buildNotification: (Float, String, String, String) -> android.app.Notification = remember(context, pendingIntent) {
        { p, title, text, chipText ->
            if (android.os.Build.VERSION.SDK_INT >= 36) {
                val nativeBuilder = android.app.Notification.Builder(context, "flash_progress")
                    .setSmallIcon(android.R.drawable.stat_sys_download)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setColor(android.graphics.Color.parseColor("#4285F4"))
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setCategory(android.app.Notification.CATEGORY_PROGRESS)
                    .setContentIntent(pendingIntent)
                    .setAutoCancel(true)
                    .setProgress(100, (p * 100).toInt(), false)

                try {
                    nativeBuilder.setShortCriticalText(chipText)
                    val progressStyle = android.app.Notification.ProgressStyle()
                    progressStyle.setProgress((p * 100).toInt())
                    nativeBuilder.setStyle(progressStyle)
                    
                    val extras = android.os.Bundle()
                    extras.putBoolean("android.requestPromotedOngoing", true)
                    nativeBuilder.addExtras(extras)
                } catch (e: Exception) {
                    android.util.Log.e("FlashScreen", "Error setting Live Update styles", e)
                }
                
                val built = nativeBuilder.build()
                built.flags = built.flags or 262144
                built
            } else {
                androidx.core.app.NotificationCompat.Builder(context, "flash_progress")
                    .setSmallIcon(android.R.drawable.stat_sys_download)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setColor(android.graphics.Color.parseColor("#4285F4"))
                    .setProgress(1000, (p * 1000).toInt(), false)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setCategory(androidx.core.app.NotificationCompat.CATEGORY_PROGRESS)
                    .setContentIntent(pendingIntent)
                    .setAutoCancel(true)
                    .build()
            }
        }
    }

    val funWords = remember { listOf("Flashing...", "Fluxabilating...", "Gone fishing...", "Crunching bytes...", "Reticulating splines...", "Writing magic...", "Doing the heavy lifting...") }
    val shortChipTexts = remember { listOf("Working", "Writing", "Wait...", "Almost", "Hold on", "Steady", "Busy") }

    LaunchedEffect(Unit) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                permissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                "flash_progress",
                "Flash Progress",
                android.app.NotificationManager.IMPORTANCE_DEFAULT
            )
            notificationManager.createNotificationChannel(channel)
        }

        // Start Foreground Service gracefully once at the beginning to claim foreground priority
        // This prevents ForegroundServiceStartNotAllowedException if the user minimizes during eraseFirst
        val initialNotif = buildNotification(0f, "Preparing to flash", "Initializing...", "Started")
        com.google.chrome.recovery.usb.KeepAliveService.currentNotification = initialNotif
        
        val serviceIntent = android.content.Intent(context, com.google.chrome.recovery.usb.KeepAliveService::class.java)
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (e: Exception) {
            android.util.Log.e("FlashScreen", "Could not start KeepAliveService", e)
        }
    }

    LaunchedEffect(isBackgrounded) {
        if (!isBackgrounded) {
            notificationManager.cancel(1001)
        } else if ((isFlashing || isErasing) && !isFinished && !hasError) {
            // Post an immediate notification when focus is lost
            val p = progress
            val wordIndex = ((System.currentTimeMillis() / 10000) % funWords.size).toInt()
            val currentFunWord = if (isErasing) "Erasing media..." else funWords[wordIndex]
            val chipText = if (isErasing) "Erasing" else shortChipTexts[wordIndex]
            
            val notif = buildNotification(p, currentFunWord, String.format(java.util.Locale.US, "%.1f%% complete", p * 100), chipText)
            
            if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                notificationManager.notify(1001, notif)
            }
        }
    }

    LaunchedEffect(isErasing) {
        if (isErasing) {
            currentStep = "Erasing media..."
            for (i in 1..100) {
                progress = i / 100f
                val notif = buildNotification(progress, "Erasing media...", String.format(java.util.Locale.US, "%.1f%% complete", progress * 100), "Erasing")
                com.google.chrome.recovery.usb.KeepAliveService.currentNotification = notif
                if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    notificationManager.notify(1001, notif)
                }
                delay(30)
            }
            if (isCancelledErase) {
                currentStep = "Success! Your USB has been reset."
                progress = 1f
                isFinished = true
                hasError = false
            } else {
                isErasing = false
                isFlashing = true
            }
        }
    }

    LaunchedEffect(isFlashing) {
        if (!isFlashing) return@LaunchedEffect

        val errorMsg = flasher.flashImageToUsb(
            device = device,
            url = url,
            onStep = { step -> currentStep = step },
            onProgress = { p -> 
                progress = p 
                val wordIndex = ((System.currentTimeMillis() / 10000) % funWords.size).toInt()
                val currentFunWord = funWords[wordIndex]
                val chipText = shortChipTexts[wordIndex]

                val text = String.format(java.util.Locale.US, "%.1f%% complete", p * 100)
                val notif = buildNotification(p, currentFunWord, text, chipText)
                
                com.google.chrome.recovery.usb.KeepAliveService.currentNotification = notif

                if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    notificationManager.notify(1001, notif)
                }
            }
        )

        if (errorMsg == "Cancelled") {
            context.stopService(android.content.Intent(context, com.google.chrome.recovery.usb.KeepAliveService::class.java))
        } else if (errorMsg == null) {
            currentStep = "Success! Your recovery media is ready."
            progress = 1f
            isFinished = true
            context.stopService(android.content.Intent(context, com.google.chrome.recovery.usb.KeepAliveService::class.java))
            if (isBackgroundedState.value) {
                val builder = androidx.core.app.NotificationCompat.Builder(context, "flash_progress")
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle("Recovery Media Ready")
                    .setContentText("Success! Your recovery media is ready.")
                    .setOngoing(false)
                    .setAutoCancel(true)
                    .setContentIntent(pendingIntent)
                if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    notificationManager.notify(1001, builder.build())
                }
            }
        } else {
            currentStep = "Error: $errorMsg"
            hasError = true
            isFinished = true
            context.stopService(android.content.Intent(context, com.google.chrome.recovery.usb.KeepAliveService::class.java))
            if (isBackgroundedState.value) {
                val builder = androidx.core.app.NotificationCompat.Builder(context, "flash_progress")
                    .setSmallIcon(android.R.drawable.stat_notify_error)
                    .setContentTitle("Error creating media")
                    .setContentText(errorMsg)
                    .setOngoing(false)
                    .setAutoCancel(true)
                    .setContentIntent(pendingIntent)
                if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    notificationManager.notify(1001, builder.build())
                }
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (isFinished) {
            if (!hasError) {
                androidx.compose.material3.Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = "Success",
                    tint = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                    modifier = androidx.compose.ui.Modifier.size(64.dp).padding(bottom = 16.dp)
                )
            } else {
                androidx.compose.material3.Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = "Error",
                    tint = androidx.compose.material3.MaterialTheme.colorScheme.error,
                    modifier = androidx.compose.ui.Modifier.size(64.dp).padding(bottom = 16.dp)
                )
            }
        }

        androidx.compose.material3.Text(
            text = currentStep,
            style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
            modifier = androidx.compose.ui.Modifier.padding(bottom = 32.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        
        if (!isFinished && !isErasing) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(8.dp)
            )
            Text(
                text = "${(progress * 100).toInt()}%",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp)
            )
            Spacer(modifier = Modifier.height(24.dp))
            OutlinedButton(onClick = { 
                flasher.cancel()
                currentStep = "Erasing media..."
                progress = 0f
                isCancelledErase = true
                isErasing = true
            }) {
                Text("Cancel and reset USB Stick")
            }
        } else if (isErasing && !isFinished) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(8.dp)
            )
            Text(
                text = "${(progress * 100).toInt()}%",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp)
            )
        } else {
            Button(onClick = onFinish) {
                Text("Back to Home")
            }
        }
    }
}
