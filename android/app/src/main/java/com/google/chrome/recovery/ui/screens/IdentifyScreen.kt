package com.google.chrome.recovery.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.chrome.recovery.R
import com.google.chrome.recovery.data.RecoveryImage
import com.google.chrome.recovery.data.RecoveryRepository

@OptIn(ExperimentalMaterial3Api::class)
/**
 * The Identify Screen (Wizard Step 2a).
 *
 * This screen allows users to input their 20-character Chromebook Hardware ID (hwid)
 * to automatically identify the exact model they need to recover.
 * 
 * If a matching model is found, the screen displays a verification checkmark and enables
 * navigation. If the user doesn't know their hwid, they can choose to select the model
 * from a dropdown list (navigating to [SelectModelScreen]).
 * 
 * Users can also opt to flash a local `.bin`/`.zip` image from their device storage.
 */
@Composable
fun IdentifyScreen(
    onNext: (String) -> Unit,
    onSelectFromList: () -> Unit,
    onSelectLocalImage: () -> Unit
) {
    val repository = remember { RecoveryRepository() }
    var images by remember { mutableStateOf<List<RecoveryImage>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    var typedModel by remember { mutableStateOf("") }
    var showError by remember { mutableStateOf(false) }
    var showHowToDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        images = repository.fetchRecoveryImages()
        isLoading = false
    }

    if (showHowToDialog) {
        AlertDialog(
            onDismissRequest = { showHowToDialog = false },
            title = { Text("How to find your model number") },
            text = {
                Column {
                    Text("The model number is located at the bottom of the recovery screen on your Book.")
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Example: \nPEPPY C6A-V7C-A5Q", fontWeight = FontWeight.Bold)
                }
            },
            confirmButton = {
                TextButton(onClick = { showHowToDialog = false }) {
                    Text("OK")
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Identify your Book", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "Enter the model number of the Book you want to recover.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        Spacer(modifier = Modifier.height(32.dp))

        Image(
            painter = painterResource(id = R.drawable.insert),
            contentDescription = "Identify your Book",
            modifier = Modifier.height(140.dp),
            contentScale = ContentScale.Fit
        )

        Spacer(modifier = Modifier.height(32.dp))

        ElevatedCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Dynamically find a match as the user types
                val upperModel = typedModel.trim().uppercase()
                val match = if (upperModel.isNotBlank()) {
                    images.find {
                        val hwidRegex = it.hwidmatch?.toRegex(RegexOption.IGNORE_CASE)
                        (hwidRegex != null && hwidRegex.matches(upperModel)) || 
                        it.model?.uppercase()?.contains(upperModel) == true ||
                        it.hwids?.any { id -> id.uppercase() == upperModel } == true
                    }
                } else null

                OutlinedTextField(
                    value = typedModel,
                    onValueChange = { 
                        typedModel = it 
                        showError = false
                    },
                    label = { Text("Type model number") },
                    isError = showError,
                    supportingText = if (showError) { { Text("Model not found. Please check and try again.") } } else null,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    trailingIcon = {
                        if (match != null) {
                            Icon(
                                imageVector = Icons.Filled.CheckCircle,
                                contentDescription = "Valid Model",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Text(
                    text = "How to find this?",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .clickable { showHowToDialog = true }
                        .padding(4.dp)
                )

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = {
                        if (match?.name != null) {
                            onNext(match.name)
                        } else {
                            showError = true
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isLoading && typedModel.isNotBlank()
                ) {
                    Text("Continue")
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        
        Text("OR", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.outline)

        Spacer(modifier = Modifier.height(24.dp))

        OutlinedButton(
            onClick = onSelectFromList,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Select a model from a list")
        }
        
        Spacer(modifier = Modifier.height(16.dp))

        OutlinedButton(
            onClick = onSelectLocalImage,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Use local image")
        }
    }
}
