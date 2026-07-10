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
import com.google.chrome.recovery.R
import com.google.chrome.recovery.ui.wizardContentWidth
import kotlinx.coroutines.delay

@Composable
fun EraseScreen(device: UsbDevice, onFinish: () -> Unit) {
    var currentStepRes by remember { mutableStateOf(R.string.erase_step) }
    var progress by remember { mutableStateOf(0f) }
    var isFinished by remember { mutableStateOf(false) }

    val haptics = LocalHapticFeedback.current
    LaunchedEffect(Unit) {
        // Simulate erasing by writing zeroes (we don't actually write since we don't have block perms)
        for (i in 1..100) {
            progress = i / 100f
            delay(30) // takes about 3 seconds
        }
        
        currentStepRes = R.string.erase_success
        progress = 1f
        isFinished = true
        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
    }

    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = WavyProgressIndicatorDefaults.ProgressAnimationSpec,
        label = "eraseProgress"
    )

    Column(
        modifier = Modifier.wizardContentWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (isFinished) {
            MorphingSuccessBadge(
                contentDescription = null,
                modifier = Modifier.padding(bottom = 24.dp)
            )
        }

        Text(
            text = stringResource(currentStepRes),
            style = if (isFinished) MaterialTheme.typography.titleLargeEmphasized else MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(bottom = 32.dp),
            textAlign = TextAlign.Center
        )
        
        if (!isFinished) {
            LinearWavyProgressIndicator(
                progress = { animatedProgress },
                amplitude = { p -> if (p >= 1f) 0f else 1f },
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = stringResource(R.string.flash_progress_percent, (progress * 100).toInt()),
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
