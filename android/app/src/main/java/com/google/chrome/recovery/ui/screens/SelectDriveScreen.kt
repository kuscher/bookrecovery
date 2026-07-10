package com.google.chrome.recovery.ui.screens

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.chrome.recovery.R
import com.google.chrome.recovery.ui.wizardContentWidth

@Composable
fun SelectDriveScreen(isEraseFlow: Boolean = false, onNext: (UsbDevice) -> Unit, onEraseFirst: ((UsbDevice) -> Unit)? = null) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val usbManager = remember { context.getSystemService(Context.USB_SERVICE) as UsbManager }
    var selectedDevice by remember { mutableStateOf<UsbDevice?>(null) }

    // Keep the device list live: plugging in a drive while this screen is open
    // (the natural order of operations) should make it appear without leaving
    // and re-entering. Detach also clears a now-stale selection.
    var deviceList by remember { mutableStateOf(usbManager.deviceList.values.toList()) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                deviceList = usbManager.deviceList.values.toList()
                if (intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED &&
                    selectedDevice != null && selectedDevice !in deviceList
                ) {
                    selectedDevice = null
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        // NOT_EXPORTED still receives system broadcasts; it only blocks other apps.
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose {
            context.unregisterReceiver(receiver)
        }
    }

    var showErasePrompt by remember { mutableStateOf(false) }

    val ACTION_USB_PERMISSION = "com.google.chrome.recovery.USB_PERMISSION"
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var permissionError by remember { mutableStateOf<String?>(null) }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (ACTION_USB_PERMISSION == intent.action) {
                    synchronized(this) {
                        val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                        }
                        if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                            device?.let {
                                pendingAction?.invoke()
                                pendingAction = null
                            }
                        } else {
                            permissionError = context.getString(R.string.permission_denied_usb)
                            pendingAction = null
                        }
                    }
                }
            }
        }
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        onDispose {
            context.unregisterReceiver(receiver)
        }
    }

    val handleDeviceSelection: (UsbDevice, () -> Unit) -> Unit = { device, action ->
        if (usbManager.hasPermission(device)) {
            action()
        } else {
            pendingAction = action
            permissionError = null
            val permissionIntent = PendingIntent.getBroadcast(
                context,
                0,
                Intent(ACTION_USB_PERMISSION).apply { setPackage(context.packageName) },
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                else
                    PendingIntent.FLAG_UPDATE_CURRENT
            )
            usbManager.requestPermission(device, permissionIntent)
        }
    }

    Column(modifier = Modifier.wizardContentWidth().padding(16.dp)) {
        Text(stringResource(R.string.select_drive_title), style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(16.dp))
        Text(stringResource(R.string.select_drive_body), style = MaterialTheme.typography.bodyLarge)
        
        Spacer(modifier = Modifier.height(24.dp))

        if (deviceList.isEmpty()) {
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.select_drive_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(deviceList) { device ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable {
                                if (selectedDevice != device) {
                                    haptics.performHapticFeedback(HapticFeedbackType.ToggleOn)
                                }
                                selectedDevice = device
                            },
                        colors = CardDefaults.cardColors(
                            containerColor = if (selectedDevice == device) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Build, contentDescription = null, modifier = Modifier.size(32.dp))
                            Spacer(modifier = Modifier.width(16.dp))
                            Column {
                                Text(device.productName ?: stringResource(R.string.select_drive_unknown_device), fontWeight = FontWeight.Medium)
                                Text(stringResource(R.string.select_drive_manufacturer, device.manufacturerName ?: stringResource(R.string.select_drive_unknown_manufacturer)), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = { 
                    if (!isEraseFlow) {
                        showErasePrompt = true
                    } else {
                        selectedDevice?.let { dev -> handleDeviceSelection(dev) { onNext(dev) } }
                    }
                },
                enabled = selectedDevice != null
            ) {
                Text(stringResource(R.string.action_continue))
            }
        }
    }

    if (showErasePrompt) {
        AlertDialog(
            onDismissRequest = { showErasePrompt = false },
            title = { Text(stringResource(R.string.erase_prompt_title)) },
            text = { Text(stringResource(R.string.erase_prompt_body)) },
            confirmButton = {
                Button(onClick = {
                    showErasePrompt = false
                    selectedDevice?.let { dev ->
                        handleDeviceSelection(dev) {
                            onEraseFirst?.invoke(dev) ?: onNext(dev)
                        }
                    }
                }) {
                    Text(stringResource(R.string.erase_prompt_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showErasePrompt = false
                    selectedDevice?.let { dev -> handleDeviceSelection(dev) { onNext(dev) } }
                }) {
                    Text(stringResource(R.string.erase_prompt_skip))
                }
            }
        )
    }

    if (permissionError != null) {
        AlertDialog(
            onDismissRequest = { permissionError = null },
            title = { Text(stringResource(R.string.permission_required_title)) },
            text = { Text(permissionError ?: "") },
            confirmButton = {
                Button(onClick = { permissionError = null }) {
                    Text(stringResource(R.string.action_ok))
                }
            }
        )
    }
}
