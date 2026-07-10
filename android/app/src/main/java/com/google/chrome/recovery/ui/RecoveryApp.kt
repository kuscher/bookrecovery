package com.google.chrome.recovery.ui

import android.hardware.usb.UsbDevice
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import com.google.chrome.recovery.R
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
    var selectedDevice by remember { mutableStateOf<UsbDevice?>(null) }
    var eraseFirst by remember { mutableStateOf(false) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            selectedUrl = uri.toString()
            navController.navigate(Route.SelectDrive.pattern)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    val stepIndex = when (currentRoute) {
                        Route.Welcome.pattern -> 1
                        Route.Identify.pattern, Route.SelectModel.pattern -> 2
                        Route.SelectDrive.pattern, Route.EraseDrive.pattern -> 3
                        Route.Flash.pattern, Route.EraseFlash.pattern -> 4
                        else -> 1
                    }
                    Column {
                        Text(stringResource(R.string.app_name))
                        if (currentRoute != Route.Welcome.pattern && currentRoute != null) {
                            Text(
                                stringResource(R.string.title_step_of, stepIndex, 4),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f)
                            )
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
                    if (currentRoute != Route.Welcome.pattern) {
                        if (currentRoute == Route.Flash.pattern || currentRoute == Route.EraseFlash.pattern) {
                            IconButton(onClick = { navController.popBackStack(Route.Welcome.pattern, inclusive = false) }) {
                                Icon(Icons.Filled.Home, contentDescription = stringResource(R.string.action_home))
                            }
                        } else {
                            IconButton(onClick = { navController.navigateUp() }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                            }
                        }
                    }
                },
                actions = {
                    var expanded by remember { mutableStateOf(false) }
                    IconButton(onClick = { expanded = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.action_more))
                    }
                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_erase_media)) },
                            onClick = { 
                                expanded = false
                                navController.navigate(Route.EraseDrive.pattern)
                            }
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Route.Welcome.pattern,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Route.Welcome.pattern) {
                WelcomeScreen(onNext = { navController.navigate(Route.Identify.pattern) })
            }
            composable(Route.Identify.pattern) {
                IdentifyScreen(
                    onNext = { modelName ->
                        navController.navigate(Route.SelectChannel.forModel(modelName))
                    },
                    onSelectFromList = {
                        navController.navigate(Route.SelectModel.pattern)
                    },
                    onSelectLocalImage = {
                        filePickerLauncher.launch(arrayOf("*/*"))
                    }
                )
            }
            composable(Route.SelectModel.pattern) {
                SelectModelScreen(onNext = { modelName ->
                    navController.navigate(Route.SelectChannel.forModel(modelName))
                })
            }
            composable(Route.SelectChannel.pattern) { backStackEntry ->
                val modelName = Uri.decode(backStackEntry.arguments?.getString(Route.ARG_MODEL_NAME) ?: "")
                SelectChannelScreen(
                    modelName = modelName,
                    onNext = { modelUrl ->
                        selectedUrl = modelUrl
                        navController.navigate(Route.SelectDrive.pattern)
                    }
                )
            }
            composable(Route.SelectDrive.pattern) {
                SelectDriveScreen(
                    isEraseFlow = false,
                    onNext = { device ->
                        selectedDevice = device
                        eraseFirst = false
                        navController.navigate(Route.Flash.pattern)
                    },
                    onEraseFirst = { device ->
                        selectedDevice = device
                        eraseFirst = true
                        navController.navigate(Route.Flash.pattern)
                    }
                )
            }
            composable(Route.EraseDrive.pattern) {
                SelectDriveScreen(
                    isEraseFlow = true,
                    onNext = { device ->
                        selectedDevice = device
                        navController.navigate(Route.EraseFlash.pattern)
                    }
                )
            }
            composable(Route.Flash.pattern) {
                if (selectedUrl != null && selectedDevice != null) {
                    FlashScreen(
                        url = selectedUrl!!,
                        device = selectedDevice!!,
                        eraseFirst = eraseFirst,
                        onFinish = {
                            navController.popBackStack(Route.Welcome.pattern, inclusive = false)
                        }
                    )
                } else {
                    navController.popBackStack()
                }
            }
            composable(Route.EraseFlash.pattern) {
                if (selectedDevice != null) {
                    EraseScreen(
                        device = selectedDevice!!,
                        onFinish = {
                            navController.popBackStack(Route.Welcome.pattern, inclusive = false)
                        }
                    )
                } else {
                    navController.popBackStack()
                }
            }
        }
    }
}
