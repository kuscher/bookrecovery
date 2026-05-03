package com.google.chrome.recovery.ui.screens

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun SelectDriveScreen(isEraseFlow: Boolean = false, onNext: (UsbDevice) -> Unit, onEraseFirst: ((UsbDevice) -> Unit)? = null) {
    val context = LocalContext.current
    val usbManager = remember { context.getSystemService(Context.USB_SERVICE) as UsbManager }
    var selectedDevice by remember { mutableStateOf<UsbDevice?>(null) }
    
    // In a real app we would listen for attach/detach broadcasts
    val deviceList = remember { usbManager.deviceList.values.toList() }

    var showErasePrompt by remember { mutableStateOf(false) }

    val ACTION_USB_PERMISSION = "com.google.chrome.recovery.USB_PERMISSION"
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var permissionError by remember { mutableStateOf<String?>(null) }

    DisposableEffect(context) {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context, intent: android.content.Intent) {
                if (ACTION_USB_PERMISSION == intent.action) {
                    synchronized(this) {
                        val device: UsbDevice? = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
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
                            android.util.Log.d("USB", "permission denied for device $device")
                            permissionError = "Permission denied for USB device."
                            pendingAction = null
                        }
                    }
                }
            }
        }
        val filter = android.content.IntentFilter(ACTION_USB_PERMISSION)
        androidx.core.content.ContextCompat.registerReceiver(
            context, 
            receiver, 
            filter, 
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
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
            val permissionIntent = android.app.PendingIntent.getBroadcast(
                context, 
                0, 
                android.content.Intent(ACTION_USB_PERMISSION).apply { setPackage(context.packageName) }, 
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) 
                    android.app.PendingIntent.FLAG_MUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT 
                else 
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT
            )
            usbManager.requestPermission(device, permissionIntent)
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Insert your USB flash drive or SD card", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(16.dp))
        Text("Select the media you'd like to use.", style = MaterialTheme.typography.bodyLarge)
        
        Spacer(modifier = Modifier.height(24.dp))

        if (deviceList.isEmpty()) {
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text("Please insert a USB drive.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(deviceList) { device ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable { selectedDevice = device },
                        colors = CardDefaults.cardColors(
                            containerColor = if (selectedDevice == device) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Build, contentDescription = null, modifier = Modifier.size(32.dp))
                            Spacer(modifier = Modifier.width(16.dp))
                            Column {
                                Text(device.productName ?: "Unknown USB Device", fontWeight = FontWeight.Medium)
                                Text("Manufacturer: ${device.manufacturerName ?: "Unknown"}", style = MaterialTheme.typography.bodySmall)
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
                Text("Continue")
            }
        }
    }

    if (showErasePrompt) {
        AlertDialog(
            onDismissRequest = { showErasePrompt = false },
            title = { Text("Erase drive before proceeding?") },
            text = { Text("Do you want to format and erase the drive before proceeding with the recovery image? This can help resolve issues with previously used media.") },
            confirmButton = {
                Button(onClick = {
                    showErasePrompt = false
                    selectedDevice?.let { dev ->
                        handleDeviceSelection(dev) {
                            onEraseFirst?.invoke(dev) ?: onNext(dev)
                        }
                    }
                }) {
                    Text("Yes, erase first")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showErasePrompt = false
                    selectedDevice?.let { dev -> handleDeviceSelection(dev) { onNext(dev) } }
                }) {
                    Text("Skip and proceed")
                }
            }
        )
    }

    if (permissionError != null) {
        AlertDialog(
            onDismissRequest = { permissionError = null },
            title = { Text("Permission Required") },
            text = { Text(permissionError ?: "") },
            confirmButton = {
                Button(onClick = { permissionError = null }) {
                    Text("OK")
                }
            }
        )
    }
}
