package com.google.chrome.recovery.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.os.Build
import android.view.ViewTreeObserver
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.chrome.recovery.R

/**
 * FlashScreen renders the progress and outcome of the flash flow.
 *
 * All orchestration — the erase/flash/success/error state machine, the
 * [com.google.chrome.recovery.usb.UsbFlasher] lifecycle, the KeepAlive Foreground
 * Service, and the Android 16 (API 36) Live Update notification — lives in
 * [FlashViewModel]. The screen keeps only the two responsibilities that need the
 * view layer:
 * 1. Requesting the POST_NOTIFICATIONS runtime permission (needs an Activity).
 * 2. Tracking lifecycle state and window focus (needs a View) so the ViewModel
 *    knows when the user is looking at the app versus relying on the notification.
 *
 * @param url The local or remote URL pointing to the recovery image .zip file.
 * @param device The physical USB device the user has authorized writing to.
 * @param eraseFirst If true, a 3-second erase simulation runs before flashing.
 * @param onFinish Callback invoked when the user dismisses the success/error summary.
 */
@Composable
fun FlashScreen(url: String, device: UsbDevice, eraseFirst: Boolean = false, onFinish: () -> Unit) {
    val viewModel: FlashViewModel = viewModel {
        FlashViewModel(
            application = checkNotNull(this[AndroidViewModelFactory.APPLICATION_KEY]),
            device = device,
            url = url,
            eraseFirst = eraseFirst
        )
    }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {}

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        viewModel.start()
    }

    // The app counts as backgrounded when it is lifecycle-paused OR its window lost
    // focus (e.g. hidden behind another freeform window). Either way the user can't
    // see the in-app progress, so the ViewModel switches to notifications.
    var isLifecycleBackgrounded by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val view = LocalView.current
    var isWindowFocused by remember { mutableStateOf(view.hasWindowFocus()) }

    DisposableEffect(view) {
        val listener = ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
            isWindowFocused = hasFocus
        }
        view.viewTreeObserver.addOnWindowFocusChangeListener(listener)
        onDispose {
            view.viewTreeObserver.removeOnWindowFocusChangeListener(listener)
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                isLifecycleBackgrounded = true
            } else if (event == Lifecycle.Event.ON_RESUME || event == Lifecycle.Event.ON_START) {
                isLifecycleBackgrounded = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val isBackgrounded = isLifecycleBackgrounded || !isWindowFocused
    LaunchedEffect(isBackgrounded) {
        viewModel.onBackgroundedChanged(isBackgrounded)
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (uiState.isFinished) {
            if (!uiState.hasError) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = stringResource(R.string.flash_success_icon),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(64.dp).padding(bottom = 16.dp)
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = stringResource(R.string.flash_error_icon),
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(64.dp).padding(bottom = 16.dp)
                )
            }
        }

        Text(
            text = uiState.stepText,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(bottom = 32.dp),
            textAlign = TextAlign.Center
        )

        if (!uiState.isFinished && !uiState.isErasing) {
            LinearProgressIndicator(
                progress = { uiState.progress },
                modifier = Modifier.fillMaxWidth().height(8.dp)
            )
            Text(
                text = stringResource(R.string.flash_progress_percent, (uiState.progress * 100).toInt()),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp)
            )
            Spacer(modifier = Modifier.height(24.dp))
            OutlinedButton(onClick = { viewModel.cancelFlashAndReset() }) {
                Text(stringResource(R.string.flash_cancel_and_reset))
            }
        } else if (uiState.isErasing && !uiState.isFinished) {
            LinearProgressIndicator(
                progress = { uiState.progress },
                modifier = Modifier.fillMaxWidth().height(8.dp)
            )
            Text(
                text = stringResource(R.string.flash_progress_percent, (uiState.progress * 100).toInt()),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp)
            )
        } else {
            Button(onClick = onFinish) {
                Text(stringResource(R.string.action_back_to_home))
            }
        }
    }
}
