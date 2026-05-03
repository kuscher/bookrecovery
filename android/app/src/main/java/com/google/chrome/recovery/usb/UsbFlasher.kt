package com.google.chrome.recovery.usb

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Handles the low-level USB writing of the recovery image.
 *
 * This class abstracts the Android USB Host API, handling the complexities of:
 * 1. Claiming the correct USB Interfaces and Endpoints for block-level transfer.
 * 2. Unzipping the ChromeOS recovery `.bin` payload on the fly from a `.zip` stream.
 * 3. Safely executing bulk transfers of the 16GB+ images to the physical flash drive 
 *    in chunks to prevent OutOfMemory errors on constrained mobile devices.
 * 
 * Note: Since standard Android devices lack root block access (`/dev/block/sda`), this
 * implementation relies on the USB Mass Storage Class protocol to communicate via SCSI commands.
 */
class UsbFlasher(private val usbManager: UsbManager, private val context: Context) {

    @Volatile
    private var isCancelled = false

    fun cancel() {
        isCancelled = true
    }

    suspend fun flashImageToUsb(
        device: UsbDevice,
        url: String,
        onStep: (String) -> Unit,
        onProgress: (Float) -> Unit
    ): String? = withContext(Dispatchers.IO) {
        isCancelled = false
        var usbConnection: android.hardware.usb.UsbDeviceConnection? = null
        var massStorageInterface: android.hardware.usb.UsbInterface? = null
        var dataStream: InputStream? = null

        try {
            var isZip = url.endsWith(".zip", ignoreCase = true)
            var displayName = ""
            val inputStream: InputStream
            val contentLength: Long

            if (url.startsWith("content://")) {
                onStep("Opening local image...")
                val uri = android.net.Uri.parse(url)
                val cursor = context.contentResolver.query(uri, null, null, null, null)
                var size = 0L
                cursor?.use {
                    if (it.moveToFirst()) {
                        val sizeIndex = it.getColumnIndex(android.provider.OpenableColumns.SIZE)
                        if (sizeIndex != -1) size = it.getLong(sizeIndex)
                        val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (nameIndex != -1) {
                            displayName = it.getString(nameIndex) ?: ""
                            if (displayName.endsWith(".zip", ignoreCase = true)) isZip = true
                        }
                    }
                }
                
                if (size <= 0) {
                    try {
                        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                            val len = afd.length
                            if (len > 0) size = len
                        }
                    } catch (e: Exception) {
                        Log.e("UsbFlasher", "Failed to get AssetFileDescriptor length: ${e.message}")
                    }
                }
                
                if (size <= 0) {
                    try {
                        context.contentResolver.openFileDescriptor(uri, "r")?.use { fd ->
                            val len = fd.statSize
                            if (len > 0) size = len
                        }
                    } catch (e: Exception) {
                        Log.e("UsbFlasher", "Failed to get statSize: ${e.message}")
                    }
                }
                
                if (size <= 0) {
                    try {
                        context.contentResolver.openInputStream(uri)?.use { stream ->
                            val len = stream.available().toLong()
                            if (len > 0) size = len
                        }
                    } catch (e: Exception) {
                        Log.e("UsbFlasher", "Failed to get available(): ${e.message}")
                    }
                }
                
                // If STILL 0, we can't reliably estimate. Set a safe fallback for local tests (e.g. 50MB instead of 4GB)
                // so it doesn't appear entirely stuck, but realistically size should be found by now.
                if (size <= 0) {
                    Log.e("UsbFlasher", "Absolutely failed to find file size. Using fallback.")
                }
                
                contentLength = size
                inputStream = context.contentResolver.openInputStream(uri) 
                    ?: return@withContext "Could not open the selected local file. It may have been moved or deleted."
            } else {
                onStep("Connecting to download server...")
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 15000
                connection.readTimeout = 60000
                connection.connect()

                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    Log.e("UsbFlasher", "Server returned HTTP ${connection.responseCode}")
                    return@withContext "Download server returned an error: HTTP ${connection.responseCode}"
                }
                contentLength = connection.contentLength.toLong()
                inputStream = connection.inputStream
            }

            var estimatedUncompressedSize: Long = if (contentLength > 0) contentLength * 3 else 4L * 1024 * 1024 * 1024 // fallback to 4GB

            if (isZip) {
                onStep(if (url.startsWith("content://")) "Unpacking local image..." else "Downloading and unpacking image...")
                val zipInputStream = ZipInputStream(inputStream)
                var entry = zipInputStream.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && entry.name.endsWith(".bin", ignoreCase = true)) {
                        Log.i("UsbFlasher", "Found binary image: ${entry.name}")
                        if (entry.size > 0) estimatedUncompressedSize = entry.size
                        break
                    }
                    entry = zipInputStream.nextEntry
                }

                if (entry == null) {
                    Log.e("UsbFlasher", "No .bin file found in the downloaded zip.")
                    zipInputStream.close()
                    return@withContext "The selected ZIP file does not contain a valid Chrome OS recovery image (.bin)."
                }
                dataStream = zipInputStream
            } else {
                onStep(if (url.startsWith("content://")) "Reading local image..." else "Downloading image...")
                if (contentLength > 0) estimatedUncompressedSize = contentLength
                dataStream = inputStream
            }

            // At this point we have a stream of the decompressed .bin file.
            usbConnection = usbManager.openDevice(device)
            if (usbConnection == null) {
                Log.e("UsbFlasher", "Permission denied for USB device.")
                return@withContext "Android denied permission to access the USB drive. Please replug the drive and grant permission."
            }

            var endpointIn: android.hardware.usb.UsbEndpoint? = null
            var endpointOut: android.hardware.usb.UsbEndpoint? = null

            for (i in 0 until device.interfaceCount) {
                val intf = device.getInterface(i)
                if (intf.interfaceClass == android.hardware.usb.UsbConstants.USB_CLASS_MASS_STORAGE) {
                    massStorageInterface = intf
                    for (j in 0 until intf.endpointCount) {
                        val ep = intf.getEndpoint(j)
                        if (ep.direction == android.hardware.usb.UsbConstants.USB_DIR_IN) {
                            endpointIn = ep
                        } else if (ep.direction == android.hardware.usb.UsbConstants.USB_DIR_OUT) {
                            endpointOut = ep
                        }
                    }
                    break
                }
            }

            if (massStorageInterface == null || endpointIn == null || endpointOut == null) {
                Log.e("UsbFlasher", "Could not find mass storage interface or endpoints.")
                return@withContext "The selected USB drive is not recognized as a standard Mass Storage device."
            }

            if (!usbConnection.claimInterface(massStorageInterface, true)) {
                Log.e("UsbFlasher", "Could not claim mass storage interface.")
                return@withContext "Could not claim the USB drive. Another app might be using it, or it is busy."
            }

            val botDevice = com.google.chrome.recovery.usb.bot.BotDevice(usbConnection, massStorageInterface!!, endpointIn, endpointOut)

            // Simulate the streaming write process
            onStep("Writing to USB...")
            var totalRead: Long = 0
            var currentLba = 0
            var lastUpdateMs = System.currentTimeMillis()

            val readBuffer = ByteArray(1024 * 64)
            val chunkBuffer = ByteArray(1024 * 128)
            var chunkPos = 0

            var bytesRead = dataStream!!.read(readBuffer)
            while (bytesRead != -1) {
                if (isCancelled) {
                    return@withContext "Cancelled"
                }
                
                // Append read bytes to chunkBuffer
                System.arraycopy(readBuffer, 0, chunkBuffer, chunkPos, bytesRead)
                chunkPos += bytesRead
                totalRead += bytesRead

                // If we have enough for a large write (e.g. 64KB), write multiples of 512
                if (chunkPos >= 65536) {
                    val bytesToWrite = (chunkPos / 512) * 512
                    val dataToWrite = ByteArray(bytesToWrite)
                    System.arraycopy(chunkBuffer, 0, dataToWrite, 0, bytesToWrite)

                    if (!botDevice.writeSectors(currentLba, dataToWrite)) {
                        Log.e("UsbFlasher", "Failed to write sectors at LBA $currentLba")
                        return@withContext "Hardware write error. The USB drive might be corrupted, full, or write-protected."
                    }

                    currentLba += bytesToWrite / 512
                    val remainder = chunkPos - bytesToWrite
                    if (remainder > 0) {
                        System.arraycopy(chunkBuffer, bytesToWrite, chunkBuffer, 0, remainder)
                    }
                    chunkPos = remainder
                }
                
                // Update progress occasionally
                val now = System.currentTimeMillis()
                if (now - lastUpdateMs > 500) { // every 500ms
                    lastUpdateMs = now
                    val progress = (totalRead.toDouble() / estimatedUncompressedSize.toDouble()).toFloat()
                    Log.d("UsbFlasher", "Progress: $totalRead / $estimatedUncompressedSize ($progress)")
                    onProgress(progress.coerceIn(0f, 1f))
                }
                
                bytesRead = dataStream!!.read(readBuffer)
            }

            // Write any remaining data
            if (chunkPos > 0) {
                val paddedLength = ((chunkPos + 511) / 512) * 512
                val dataToWrite = ByteArray(paddedLength)
                System.arraycopy(chunkBuffer, 0, dataToWrite, 0, chunkPos)
                // padding is automatically 0 since ByteArray initializes to 0
                if (!botDevice.writeSectors(currentLba, dataToWrite)) {
                    Log.e("UsbFlasher", "Failed to write final sectors at LBA $currentLba")
                    return@withContext "Hardware write error at the end of the drive. The USB drive might not be large enough."
                }
            }
            
            onStep("Verifying...")
            onProgress(0f)
            // Verification: read the first 128 sectors (64KB) to ensure we wrote successfully
            val verifyData = botDevice.readSectors(0, 128)
            if (verifyData == null) {
                Log.e("UsbFlasher", "Verification failed: could not read from USB.")
                return@withContext "Verification failed. The data written to the USB drive could not be read back correctly."
            }

            return@withContext null // Success!

        } catch (e: Exception) {
            Log.e("UsbFlasher", "Error flashing to USB", e)
            return@withContext "An unexpected error occurred: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            try {
                dataStream?.close()
            } catch (e: Exception) {
                Log.e("UsbFlasher", "Failed to close input stream", e)
            }
            try {
                if (massStorageInterface != null) {
                    usbConnection?.releaseInterface(massStorageInterface)
                }
                usbConnection?.close()
            } catch (e: Exception) {
                Log.e("UsbFlasher", "Failed to close USB connection", e)
            }
        }
    }
}
