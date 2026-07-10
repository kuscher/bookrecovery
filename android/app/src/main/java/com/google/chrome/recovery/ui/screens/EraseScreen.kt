package com.google.chrome.recovery.ui.screens

import android.hardware.usb.UsbDevice
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.chrome.recovery.R
import com.google.chrome.recovery.ui.wizardContentWidth

/**
 * Renders the standalone erase flow: a real zeroing of the drive's partition
 * structures, driven by [EraseViewModel]. The screen is a pure renderer, in
 * the same shape as [FlashScreen].
 */
@Composable
fun EraseScreen(device: UsbDevice, onFinish: () -> Unit) {
    val viewModel: EraseViewModel = viewModel {
        EraseViewModel(
            application = checkNotNull(this[AndroidViewModelFactory.APPLICATION_KEY]),
            device = device
        )
    }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.start()
    }

    val haptics = LocalHapticFeedback.current
    LaunchedEffect(uiState.isFinished) {
        if (uiState.isFinished) {
            haptics.performHapticFeedback(
                if (uiState.hasError) HapticFeedbackType.Reject else HapticFeedbackType.Confirm
            )
        }
    }

    val animatedProgress by animateFloatAsState(
        targetValue = uiState.progress,
        animationSpec = WavyProgressIndicatorDefaults.ProgressAnimationSpec,
        label = "eraseProgress"
    )

    Column(
        modifier = Modifier.wizardContentWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (uiState.isFinished) {
            if (!uiState.hasError) {
                MorphingSuccessBadge(
                    contentDescription = null,
                    modifier = Modifier.padding(bottom = 24.dp)
                )
            } else {
                ErrorBadge(
                    contentDescription = stringResource(R.string.flash_error_icon),
                    modifier = Modifier.padding(bottom = 24.dp)
                )
            }
        }

        Text(
            text = uiState.stepText,
            style = if (uiState.isFinished) MaterialTheme.typography.titleLargeEmphasized else MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(bottom = 16.dp),
            textAlign = TextAlign.Center
        )

        uiState.detailText?.let { detail ->
            Text(
                text = detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 16.dp)
            )
        }

        if (!uiState.isFinished) {
            Spacer(modifier = Modifier.height(16.dp))
            LinearWavyProgressIndicator(
                progress = { animatedProgress },
                amplitude = { p -> if (p >= 1f) 0f else 1f },
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = stringResource(R.string.flash_progress_percent, (uiState.progress * 100).toInt()),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.erase_do_not_remove),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Button(onClick = onFinish) {
                Text(stringResource(R.string.action_back_to_home))
            }
        }
    }
}
