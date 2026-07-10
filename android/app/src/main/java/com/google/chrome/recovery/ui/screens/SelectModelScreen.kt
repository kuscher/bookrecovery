package com.google.chrome.recovery.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.navigation.NavigableListDetailPaneScaffold
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import androidx.window.core.layout.WindowSizeClass
import com.google.chrome.recovery.R
import com.google.chrome.recovery.data.RecoveryImage
import com.google.chrome.recovery.data.RecoveryRepository
import com.google.chrome.recovery.ui.wizardContentWidth
import kotlinx.coroutines.launch

/**
 * The Model Selection Screen (Wizard Step 2b).
 *
 * This screen is presented if the user opts to manually select their Chromebook model
 * instead of entering their hardware ID (hwid). It fetches the full list of recovery
 * images and adapts its layout to the window width:
 *
 * - **Compact/medium widths** (phones, small windows): the original flow — cascading
 *   manufacturer and product dropdowns, then a separate channel-selection step.
 * - **Expanded widths** (tablets, desktop windows, unfolded foldables): a
 *   list–detail pane layout. A searchable model list fills the left pane; choosing a
 *   model shows its details and release channels on the right, collapsing the model
 *   and channel steps into one screen. This follows the list-detail canonical layout
 *   from the official adaptive guidance.
 *
 * @param onNext Compact flow: called with the chosen model name; navigates to the
 *   channel-selection step.
 * @param onImageSelected Expanded flow: called with the chosen image's download URL
 *   (channel already picked in the detail pane); navigates straight to drive selection.
 */
@Composable
fun SelectModelScreen(onNext: (String) -> Unit, onImageSelected: (RecoveryImage) -> Unit) {
    val repository = RecoveryRepository.instance
    var images by remember { mutableStateOf<List<RecoveryImage>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        images = repository.fetchRecoveryImages()
        isLoading = false
    }

    val windowSizeClass = currentWindowAdaptiveInfo().windowSizeClass
    if (windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND)) {
        ModelListDetail(images, isLoading, onImageSelected)
    } else {
        ModelDropdowns(images, isLoading, onNext)
    }
}

/**
 * Expanded-width layout: searchable model list on the left, model details and
 * channel choice on the right. The scaffold handles pane visibility itself, so
 * if the window is resized below the two-pane threshold mid-selection it
 * degrades to single-pane navigation with back handling intact.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
private fun ModelListDetail(
    images: List<RecoveryImage>,
    isLoading: Boolean,
    onImageSelected: (RecoveryImage) -> Unit
) {
    val navigator = rememberListDetailPaneScaffoldNavigator<String>()
    val scope = rememberCoroutineScope()

    NavigableListDetailPaneScaffold(
        navigator = navigator,
        listPane = {
            AnimatedPane {
                ModelListPane(
                    images = images,
                    isLoading = isLoading,
                    selectedModel = navigator.currentDestination?.contentKey,
                    onModelClick = { modelName ->
                        scope.launch {
                            navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, modelName)
                        }
                    }
                )
            }
        },
        detailPane = {
            AnimatedPane {
                ModelDetailPane(
                    modelName = navigator.currentDestination?.contentKey,
                    images = images,
                    onImageSelected = onImageSelected
                )
            }
        }
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ModelListPane(
    images: List<RecoveryImage>,
    isLoading: Boolean,
    selectedModel: String?,
    onModelClick: (String) -> Unit
) {
    var searchText by rememberSaveable { mutableStateOf("") }

    // One row per distinct model name; manufacturer as supporting text.
    val models = remember(images) {
        images.mapNotNull { image ->
            image.name?.let { name -> name to (image.manufacturer ?: "") }
        }.distinct().sortedBy { it.first }
    }
    val filteredModels = remember(models, searchText) {
        if (searchText.isBlank()) models
        else models.filter { (name, manufacturer) ->
            name.contains(searchText, ignoreCase = true) ||
                manufacturer.contains(searchText, ignoreCase = true)
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(stringResource(R.string.identify_title), style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(16.dp))
        val keyboardController = LocalSoftwareKeyboardController.current
        OutlinedTextField(
            value = searchText,
            onValueChange = { searchText = it },
            label = { Text(stringResource(R.string.select_model_search)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { keyboardController?.hide() }),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))

        if (isLoading) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                LoadingIndicator()
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(filteredModels, key = { it.first }) { (name, manufacturer) ->
                    ListItem(
                        supportingContent = { Text(manufacturer) },
                        colors = ListItemDefaults.colors(
                            containerColor = if (name == selectedModel) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surface
                            }
                        ),
                        modifier = Modifier.clickable { onModelClick(name) }
                    ) {
                        Text(name)
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelDetailPane(
    modelName: String?,
    images: List<RecoveryImage>,
    onImageSelected: (RecoveryImage) -> Unit
) {
    val haptics = LocalHapticFeedback.current
    if (modelName == null) {
        Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(
                stringResource(R.string.select_model_pick_prompt),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    val availableImages = remember(images, modelName) {
        images.filter { it.name == modelName }.sortedBy { it.channel }
    }
    val manufacturer = availableImages.firstOrNull()?.manufacturer

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Text(modelName, style = MaterialTheme.typography.headlineMedium)
        if (manufacturer != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                manufacturer,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(24.dp))
        Text(stringResource(R.string.select_channel_title), style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(8.dp))

        if (availableImages.isEmpty()) {
            Text(
                stringResource(R.string.select_channel_none),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error
            )
        } else {
            availableImages.forEach { image ->
                val channelLabel = image.channel ?: stringResource(R.string.select_channel_default_label)
                ElevatedButton(
                    onClick = {
                        if (image.url != null) {
                            haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                            onImageSelected(image)
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                ) {
                    Text(channelLabel)
                }
            }
        }
    }
}

/**
 * Compact-width layout: the original cascading manufacturer/product dropdowns.
 *
 * Flow:
 * 1. User selects a Manufacturer (e.g., "Acer", "Google").
 * 2. User selects a specific Model belonging to that manufacturer.
 * 3. Navigates to [SelectChannelScreen] to pick the release channel.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ModelDropdowns(
    images: List<RecoveryImage>,
    isLoading: Boolean,
    onNext: (String) -> Unit
) {
    val haptics = LocalHapticFeedback.current
    var selectedManufacturer by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedModelName by rememberSaveable { mutableStateOf<String?>(null) }
    var mfrExpanded by remember { mutableStateOf(false) }
    var modelExpanded by remember { mutableStateOf(false) }

    var mfrSearchText by rememberSaveable { mutableStateOf("") }
    var modelSearchText by rememberSaveable { mutableStateOf("") }

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

    Column(modifier = Modifier.wizardContentWidth().padding(16.dp)) {
        Text(stringResource(R.string.identify_title), style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(16.dp))
        Text(stringResource(R.string.select_model_body), style = MaterialTheme.typography.bodyLarge)
        Spacer(modifier = Modifier.height(24.dp))

        if (isLoading) {
            LoadingIndicator()
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
                    label = { Text(stringResource(R.string.select_model_manufacturer_label)) },
                    readOnly = false,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = mfrExpanded) },
                    modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable).fillMaxWidth()
                )
                if (filteredManufacturers.isNotEmpty()) {
                    DropdownMenu(
                        expanded = mfrExpanded,
                        onDismissRequest = { mfrExpanded = false },
                        properties = PopupProperties(focusable = false),
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
                    label = { Text(stringResource(R.string.select_model_product_label)) },
                    readOnly = false,
                    enabled = selectedManufacturer != null,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelExpanded) },
                    modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable).fillMaxWidth()
                )
                if (filteredModelNames.isNotEmpty() && selectedManufacturer != null) {
                    DropdownMenu(
                        expanded = modelExpanded,
                        onDismissRequest = { modelExpanded = false },
                        properties = PopupProperties(focusable = false),
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
                    onClick = {
                        selectedModelName?.let {
                            haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                            onNext(it)
                        }
                    },
                    enabled = selectedModelName != null
                ) {
                    Text(stringResource(R.string.action_continue))
                }
            }
        }
    }
}
