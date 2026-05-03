package com.google.chrome.recovery.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.google.chrome.recovery.ui.screens.*

/**
 * The main entry point for the UI of the Book Recovery Utility.
 * 
 * This composable sets up the Navigation graph using Jetpack Compose Navigation.
 * It manages the high-level shared state (such as the selected download URL,
 * the chosen USB device, and whether an erase should happen before flashing).
 * 
 * The application follows a "wizard" style navigation flow:
 * 1. Welcome -> 2. Identify / Select Model -> 3. Select Channel -> 4. Select Drive -> 5. Flash
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecoveryApp() {
    // Navigation controller for managing routing between wizard steps
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    var selectedUrl by remember { mutableStateOf<String?>(null) }
    var selectedDevice by remember { mutableStateOf<android.hardware.usb.UsbDevice?>(null) }
    var eraseFirst by remember { mutableStateOf(false) }

    val filePickerLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            selectedUrl = uri.toString()
            navController.navigate("select_drive")
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    val stepIndex = when (currentRoute) {
                        "welcome" -> 1
                        "identify", "select_model" -> 2
                        "select_drive", "erase_drive" -> 3
                        "flash", "erase_flash" -> 4
                        else -> 1
                    }
                    Column {
                        Text("Book Recovery Utility")
                        if (currentRoute != "welcome" && currentRoute != null) {
                            Text("Step $stepIndex of 4", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary
                ),
                navigationIcon = {
                    if (currentRoute != "welcome") {
                        if (currentRoute == "flash" || currentRoute == "erase_flash") {
                            IconButton(onClick = { navController.popBackStack("welcome", inclusive = false) }) {
                                Icon(Icons.Filled.Home, contentDescription = "Home")
                            }
                        } else {
                            IconButton(onClick = { navController.navigateUp() }) {
                                Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                            }
                        }
                    }
                },
                actions = {
                    var expanded by remember { mutableStateOf(false) }
                    IconButton(onClick = { expanded = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "More options")
                    }
                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Erase recovery media") },
                            onClick = { 
                                expanded = false
                                navController.navigate("erase_drive")
                            }
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "welcome",
            modifier = Modifier.padding(innerPadding)
        ) {
            composable("welcome") {
                WelcomeScreen(onNext = { navController.navigate("identify") })
            }
            composable("identify") {
                IdentifyScreen(
                    onNext = { modelName ->
                        navController.navigate("select_channel/${android.net.Uri.encode(modelName)}")
                    },
                    onSelectFromList = {
                        navController.navigate("select_model")
                    },
                    onSelectLocalImage = {
                        filePickerLauncher.launch(arrayOf("*/*"))
                    }
                )
            }
            composable("select_model") {
                SelectModelScreen(onNext = { modelName ->
                    navController.navigate("select_channel/${android.net.Uri.encode(modelName)}")
                })
            }
            composable("select_channel/{modelName}") { backStackEntry ->
                val modelName = android.net.Uri.decode(backStackEntry.arguments?.getString("modelName") ?: "")
                SelectChannelScreen(
                    modelName = modelName,
                    onNext = { modelUrl ->
                        selectedUrl = modelUrl
                        navController.navigate("select_drive")
                    }
                )
            }
            composable("select_drive") {
                SelectDriveScreen(
                    isEraseFlow = false,
                    onNext = { device ->
                        selectedDevice = device
                        eraseFirst = false
                        navController.navigate("flash")
                    },
                    onEraseFirst = { device ->
                        selectedDevice = device
                        eraseFirst = true
                        navController.navigate("flash")
                    }
                )
            }
            composable("erase_drive") {
                SelectDriveScreen(
                    isEraseFlow = true,
                    onNext = { device ->
                        selectedDevice = device
                        navController.navigate("erase_flash")
                    }
                )
            }
            composable("flash") {
                if (selectedUrl != null && selectedDevice != null) {
                    FlashScreen(
                        url = selectedUrl!!,
                        device = selectedDevice!!,
                        eraseFirst = eraseFirst,
                        onFinish = {
                            navController.popBackStack("welcome", inclusive = false)
                        }
                    )
                } else {
                    navController.popBackStack()
                }
            }
            composable("erase_flash") {
                if (selectedDevice != null) {
                    EraseScreen(
                        device = selectedDevice!!,
                        onFinish = {
                            navController.popBackStack("welcome", inclusive = false)
                        }
                    )
                } else {
                    navController.popBackStack()
                }
            }
        }
    }
}
