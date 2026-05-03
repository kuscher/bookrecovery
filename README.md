# Book Recovery Utility

A modern, robust Android application built to recover Chromebooks by securely downloading and flashing ChromeOS and ChromeOS Flex recovery images directly onto USB storage devices using Android's native USB Host API.

## Features

- **Automated Device Identification**: Input a Chromebook Hardware ID (hwid) to automatically identify the corresponding recovery image.
- **Extensive Model Catalog**: Manually browse through an extensive catalog of ChromeOS and CloudReady (Flex) hardware models organized by manufacturer.
- **Direct Flashing**: Streams and unzips `.bin` payloads directly from Google's official recovery servers, writing them directly to a physical USB thumb drive.
- **Live Update Support**: Integrates tightly with Android 16 (API 36) Foreground Services and `ProgressStyle` notifications to render live status bar chips while flashing in the background.

## Architecture

This application is built with **Jetpack Compose** and leverages modern Android components:
- **`RecoveryApp.kt`**: The root Compose Navigation graph that controls the multi-step "wizard" flow.
- **`RecoveryRepository.kt`**: Fetches the official ChromeOS `recovery2.json` manifest.
- **`UsbFlasher.kt`**: The core workhorse. Uses the `UsbManager` and SCSI protocol to execute raw block-level writes to USB mass storage devices, without requiring root access.
- **`KeepAliveService.kt`**: A Foreground Service ensuring that the Android OS does not aggressively terminate the memory-intensive USB flashing process when the user backgrounds the application.

## Requirements

- **Android Device**: Requires a device with USB OTG (On-The-Go) support.
- **Target API**: Android 16 (API 36).
- **Permissions**: Requires USB Host authorization and Notification posting permissions.

## Releases

Ready to try it out? You can find pre-compiled debug binaries in the `releases/` directory:
- [book-recovery-debug.apk](releases/book-recovery-debug.apk)
- [book-recovery-debug.aab](releases/book-recovery-debug.aab)

## Building from Source

To build this project locally:
1. Clone the repository.
2. Open the `android/` directory in Android Studio.
3. Sync Gradle and run the `app` configuration.

```bash
./gradlew assembleDebug
```
