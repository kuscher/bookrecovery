package com.google.chrome.recovery.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.chrome.recovery.data.RecoveryImage
import com.google.chrome.recovery.data.RecoveryRepository

/**
 * The Channel Selection Screen (Wizard Step 3).
 *
 * This screen displays the available ChromeOS release channels for the previously 
 * selected [modelName]. It filters the full recovery manifest down to just the variants 
 * associated with this exact hardware model.
 * 
 * Users can typically choose between:
 * - Stable Channel (Recommended for most users)
 * - Beta Channel
 * - Dev Channel
 * 
 * Selecting a channel passes the final ZIP download URL to the next screen.
 */
@Composable
fun SelectChannelScreen(modelName: String, onNext: (String) -> Unit) {
    val repository = remember { RecoveryRepository() }
    var images by remember { mutableStateOf<List<RecoveryImage>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        images = repository.fetchRecoveryImages()
        isLoading = false
    }

    val availableImages = remember(images, modelName) {
        images.filter { it.name == modelName }.sortedBy { it.channel }
    }

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Text("Select a channel", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Text("Choose the recovery channel for $modelName.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(32.dp))

        if (isLoading) {
            CircularProgressIndicator()
        } else {
            if (availableImages.isEmpty()) {
                Text("No channels found for this model.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
            } else {
                availableImages.forEach { image ->
                    val channelLabel = image.channel ?: "STABLE"
                    ElevatedButton(
                        onClick = { image.url?.let { onNext(it) } },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                    ) {
                        Text(channelLabel)
                    }
                }
            }
        }
    }
}
