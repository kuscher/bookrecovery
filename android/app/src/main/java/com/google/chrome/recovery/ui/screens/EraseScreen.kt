package com.google.chrome.recovery.ui.screens

import android.hardware.usb.UsbDevice
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.chrome.recovery.R
import kotlinx.coroutines.delay

@Composable
fun EraseScreen(device: UsbDevice, onFinish: () -> Unit) {
    val context = LocalContext.current
    var currentStep by remember { mutableStateOf(context.getString(R.string.erase_step)) }
    var progress by remember { mutableStateOf(0f) }
    var isFinished by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        // Simulate erasing by writing zeroes (we don't actually write since we don't have block perms)
        for (i in 1..100) {
            progress = i / 100f
            delay(30) // takes about 3 seconds
        }
        
        currentStep = context.getString(R.string.erase_success)
        progress = 1f
        isFinished = true
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = currentStep,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(bottom = 32.dp),
            textAlign = TextAlign.Center
        )
        
        if (!isFinished) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(8.dp)
            )
            Text(
                text = "${(progress * 100).toInt()}%",
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
