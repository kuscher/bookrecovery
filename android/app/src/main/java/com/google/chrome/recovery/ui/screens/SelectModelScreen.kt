package com.google.chrome.recovery.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.chrome.recovery.data.RecoveryImage
import com.google.chrome.recovery.data.RecoveryRepository

@OptIn(ExperimentalMaterial3Api::class)
/**
 * The Model Selection Screen (Wizard Step 2b).
 *
 * This screen is presented if the user opts to manually select their Chromebook model
 * instead of entering their hardware ID (hwid). It fetches the full list of recovery 
 * images, groups them by manufacturer, and populates cascading dropdown menus.
 * 
 * Flow:
 * 1. User selects a Manufacturer (e.g., "Acer", "Google").
 * 2. User selects a specific Model belonging to that manufacturer.
 * 3. Navigates to [SelectChannelScreen] to pick the release channel.
 */
@Composable
fun SelectModelScreen(onNext: (String) -> Unit) {
    val repository = remember { RecoveryRepository() }
    var images by remember { mutableStateOf<List<RecoveryImage>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    var selectedManufacturer by remember { mutableStateOf<String?>(null) }
    var selectedModelName by remember { mutableStateOf<String?>(null) }
    var mfrExpanded by remember { mutableStateOf(false) }
    var modelExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        images = repository.fetchRecoveryImages()
        isLoading = false
    }

    var mfrSearchText by remember { mutableStateOf("") }
    var modelSearchText by remember { mutableStateOf("") }

    val manufacturers = remember(images) {
        images.mapNotNull { it.manufacturer }.distinct().sorted()
    }
    val filteredManufacturers = remember(manufacturers, mfrSearchText) {
        if (mfrSearchText.isEmpty() || selectedManufacturer == mfrSearchText) manufacturers 
        else manufacturers.filter { it.contains(mfrSearchText, ignoreCase = true) }
    }

    val modelNames = remember(images, selectedManufacturer) {
        images.filter { it.manufacturer == selectedManufacturer }
              .mapNotNull { it.name }
              .distinct()
              .sorted()
    }
    val filteredModelNames = remember(modelNames, modelSearchText) {
        if (modelSearchText.isEmpty() || selectedModelName == modelSearchText) modelNames
        else modelNames.filter { it.contains(modelSearchText, ignoreCase = true) }
    }



    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Identify your Book", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(16.dp))
        Text("Select the manufacturer and product.", style = MaterialTheme.typography.bodyLarge)
        Spacer(modifier = Modifier.height(24.dp))

        if (isLoading) {
            CircularProgressIndicator()
        } else {
            // Manufacturer Dropdown
            ExposedDropdownMenuBox(
                expanded = mfrExpanded,
                onExpandedChange = { mfrExpanded = it }
            ) {
                OutlinedTextField(
                    value = mfrSearchText,
                    onValueChange = { 
                        mfrSearchText = it
                        mfrExpanded = true 
                        if (selectedManufacturer != null && it != selectedManufacturer) {
                            selectedManufacturer = null
                            selectedModelName = null
                            modelSearchText = ""
                        }
                    },
                    label = { Text("Select a manufacturer") },
                    readOnly = false,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = mfrExpanded) },
                    modifier = Modifier.menuAnchor().fillMaxWidth()
                )
                if (filteredManufacturers.isNotEmpty()) {
                    DropdownMenu(
                        expanded = mfrExpanded,
                        onDismissRequest = { mfrExpanded = false },
                        properties = androidx.compose.ui.window.PopupProperties(focusable = false),
                        modifier = Modifier.exposedDropdownSize()
                    ) {
                        filteredManufacturers.forEach { mfr ->
                            DropdownMenuItem(
                                text = { Text(mfr) },
                                onClick = {
                                    selectedManufacturer = mfr
                                    mfrSearchText = mfr
                                    selectedModelName = null
                                    modelSearchText = ""
                                    mfrExpanded = false
                                }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Model Dropdown
            ExposedDropdownMenuBox(
                expanded = modelExpanded,
                onExpandedChange = { if (selectedManufacturer != null) modelExpanded = it }
            ) {
                OutlinedTextField(
                    value = modelSearchText,
                    onValueChange = { 
                        modelSearchText = it
                        modelExpanded = true 
                        if (selectedModelName != null && it != selectedModelName) {
                            selectedModelName = null
                        }
                    },
                    label = { Text("Select a product") },
                    readOnly = false,
                    enabled = selectedManufacturer != null,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelExpanded) },
                    modifier = Modifier.menuAnchor().fillMaxWidth()
                )
                if (filteredModelNames.isNotEmpty() && selectedManufacturer != null) {
                    DropdownMenu(
                        expanded = modelExpanded,
                        onDismissRequest = { modelExpanded = false },
                        properties = androidx.compose.ui.window.PopupProperties(focusable = false),
                        modifier = Modifier.exposedDropdownSize()
                    ) {
                        filteredModelNames.forEach { modelName ->
                            DropdownMenuItem(
                                text = { Text(modelName) },
                                onClick = {
                                    selectedModelName = modelName
                                    modelSearchText = modelName
                                    modelExpanded = false
                                }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { selectedModelName?.let { onNext(it) } },
                    enabled = selectedModelName != null
                ) {
                    Text("Continue")
                }
            }
        }
    }
}
