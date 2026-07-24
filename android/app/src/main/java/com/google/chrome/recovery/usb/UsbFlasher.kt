package com.google.chrome.recovery.usb

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.google.chrome.recovery.R
import com.google.chrome.recovery.usb.bot.BotDevice
import com.google.chrome.recovery.usb.verify.BoundedDigest
import com.google.chrome.recovery.usb.verify.IncrementalDigest
import java.io.FilterInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Handles the low-level USB writing of the recovery image.
 *
 * This class abstracts the Android USB Host API, handling the complexities of:
 * 1. Claiming the correct USB Interfaces and Endpoints for block-level transfer.
 * 2. Unzipping the ChromeOS recovery `.bin` payload on the fly from a `.zip` stream.
 * 3. Safely executing bulk transfers of the 16GB+ images to the physical flash drive
 *    in chunks to prevent OutOfMemory errors on constrained mobile devices.
 * 4. Verifying the result: the downloaded `.zip` is hashed in flight and compared
 *    against the manifest checksum (authenticity), and the written sectors are read
 *    back over the same bulk endpoints and compared against the digest of the data
 *    that was written (write verification).
 *
 * Note: Since standard Android devices lack root block access (`/dev/block/sda`), this
 * implementation relies on the USB Mass Storage Class protocol to communicate via SCSI commands.
 */
class UsbFlasher(private val usbManager: UsbManager, private val context: Context) {

    companion object {
        private const val TAG = "UsbFlasher"
        private const val SECTOR_SIZE = 512

        /** Read-back chunk: 128 sectors = 64 KB, the same buffer discipline as the write path. */
        private const val VERIFY_CHUNK_SECTORS = 128
    }

    /** Terminal outcome of a flash attempt. */
    sealed interface Result {
        /** The image was written and verification reached the stated level. */
        data class Success(val verification: Verification) : Result

        /** The user cancelled; the drive holds a partial image. */
        data object Cancelled : Result

        /** Writing failed. [message] is localized and user-facing. */
        data class WriteError(val message: String) : Result

        /**
         * The write completed but verification failed — either the download's
         * checksum didn't match the manifest or the read-back didn't match
         * what was written. The drive contents cannot be trusted; the caller
         * should offer a retry.
         */
        data class VerificationError(val message: String) : Result
    }

    /** How far verification got on a successful flash. */
    enum class Verification {
        /** Download matched the manifest checksum AND the read-back matched the write. */
        AUTHENTIC,

        /** No manifest checksum available (local image); the read-back matched the write. */
        WRITE_VERIFIED,

        /** The user chose to skip mid-verification. Skipping is not a failure. */
        SKIPPED
    }

    @Volatile
    private var isCancelled = false

    @Volatile
    private var isVerificationSkipped = false

    fun cancel() {
        isCancelled = true
    }

    /** Requests that an in-flight verification pass end early (skip ≠ fail). */
    fun skipVerification() {
        isVerificationSkipped = true
    }

    /**
     * Streams the recovery image at [url] onto [device], then verifies it.
     *
     * @param expectedZipSha1 The manifest's SHA-1 of the downloaded `.zip`, when
     *   flashing an official image. Null for local files, which get write
     *   verification only.
     * @param onStep Human-readable phase announcements.
     * @param onProgress Write progress, 0..1.
     * @param onVerifyProgress Read-back verification progress, 0..1.
     */
    suspend fun flashImageToUsb(
        device: UsbDevice,
        url: String,
        expectedZipSha1: String? = null,
        expectedImageSize: Long? = null,
        onStep: (String) -> Unit,
        onProgress: (Float) -> Unit,
        onVerifyProgress: (Float) -> Unit = {}
    ): Result = withContext(Dispatchers.IO) {
        isCancelled = false
        isVerificationSkipped = false
        var usbConnection: UsbDeviceConnection? = null
        var massStorageInterface: UsbInterface? = null
        var dataStream: InputStream? = null

        try {
            var isZip = url.endsWith(".zip", ignoreCase = true)
            val inputStream: InputStream
            val contentLength: Long

            if (url.startsWith("content://")) {
                onStep(context.getString(R.string.step_opening_local))
                val uri = Uri.parse(url)
                val cursor = context.contentResolver.query(uri, null, null, null, null)
                var size = 0L
                cursor?.use {
                    if (it.moveToFirst()) {
                        val sizeIndex = it.getColumnIndex(OpenableColumns.SIZE)
                        if (sizeIndex != -1) size = it.getLong(sizeIndex)
                        val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIndex != -1) {
                            val displayName = it.getString(nameIndex) ?: ""
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
                        Log.e(TAG, "Failed to get AssetFileDescriptor length: ${e.message}")
                    }
                }

                if (size <= 0) {
                    try {
                        context.contentResolver.openFileDescriptor(uri, "r")?.use { fd ->
                            val len = fd.statSize
                            if (len > 0) size = len
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to get statSize: ${e.message}")
                    }
                }

                if (size <= 0) {
                    try {
                        context.contentResolver.openInputStream(uri)?.use { stream ->
                            val len = stream.available().toLong()
                            if (len > 0) size = len
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to get available(): ${e.message}")
                    }
                }

                if (size <= 0) {
                    Log.e(TAG, "Absolutely failed to find file size. Using fallback.")
                }

                contentLength = size
                inputStream = context.contentResolver.openInputStream(uri)
                    ?: return@withContext Result.WriteError(context.getString(R.string.error_open_local))
            } else {
                onStep(context.getString(R.string.step_connecting_server))
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 15000
                connection.readTimeout = 60000
                connection.connect()

                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    Log.e(TAG, "Server returned HTTP ${connection.responseCode}")
                    return@withContext Result.WriteError(
                        context.getString(R.string.error_http_server, connection.responseCode)
                    )
                }
                // contentLength (Int) overflows on >2GB downloads; several official
                // images exceed that compressed.
                contentLength = connection.contentLengthLong
                inputStream = connection.inputStream
            }

            // When the manifest gave us a checksum, hash the raw (compressed) stream
            // as it flows past — by the time the write finishes we have the digest of
            // the entire download for the authenticity check.
            val sourceDigest = if (expectedZipSha1 != null) IncrementalDigest() else null
            val sourceStream: InputStream =
                if (sourceDigest != null) DigestingInputStream(inputStream, sourceDigest) else inputStream

            // Progress denominator, best source first: the manifest's exact
            // uncompressed size, then the zip entry header, then a 3x-compressed
            // guess from the download size, then a 4GB fallback.
            var estimatedUncompressedSize: Long = when {
                expectedImageSize != null && expectedImageSize > 0 -> expectedImageSize
                contentLength > 0 -> contentLength * 3
                else -> 4L * 1024 * 1024 * 1024
            }

            if (isZip) {
                onStep(if (url.startsWith("content://")) context.getString(R.string.step_unpacking_local) else context.getString(R.string.step_downloading_unpacking))
                val zipInputStream = ZipInputStream(sourceStream)
                var entry = zipInputStream.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && entry.name.endsWith(".bin", ignoreCase = true)) {
                        Log.i(TAG, "Found binary image: ${entry.name}")
                        if (expectedImageSize == null && entry.size > 0) estimatedUncompressedSize = entry.size
                        break
                    }
                    entry = zipInputStream.nextEntry
                }

                if (entry == null) {
                    Log.e(TAG, "No .bin file found in the downloaded zip.")
                    zipInputStream.close()
                    return@withContext Result.WriteError(context.getString(R.string.error_invalid_zip))
                }
                dataStream = zipInputStream
            } else {
                onStep(if (url.startsWith("content://")) context.getString(R.string.step_reading_local) else context.getString(R.string.step_downloading))
                if (expectedImageSize == null && contentLength > 0) estimatedUncompressedSize = contentLength
                dataStream = sourceStream
            }

            // At this point we have a stream of the decompressed .bin file.
            usbConnection = usbManager.openDevice(device)
            if (usbConnection == null) {
                Log.e(TAG, "Permission denied for USB device.")
                return@withContext Result.WriteError(context.getString(R.string.error_usb_permission))
            }

            var endpointIn: UsbEndpoint? = null
            var endpointOut: UsbEndpoint? = null

            for (i in 0 until device.interfaceCount) {
                val intf = device.getInterface(i)
                if (intf.interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE) {
                    massStorageInterface = intf
                    for (j in 0 until intf.endpointCount) {
                        val ep = intf.getEndpoint(j)
                        if (ep.direction == UsbConstants.USB_DIR_IN) {
                            endpointIn = ep
                        } else if (ep.direction == UsbConstants.USB_DIR_OUT) {
                            endpointOut = ep
                        }
                    }
                    break
                }
            }

            if (massStorageInterface == null || endpointIn == null || endpointOut == null) {
                Log.e(TAG, "Could not find mass storage interface or endpoints.")
                return@withContext Result.WriteError(context.getString(R.string.error_not_mass_storage))
            }

            if (!usbConnection.claimInterface(massStorageInterface, true)) {
                Log.e(TAG, "Could not claim mass storage interface.")
                return@withContext Result.WriteError(context.getString(R.string.error_claim_interface))
            }

            val botDevice = BotDevice(usbConnection, massStorageInterface, endpointIn, endpointOut)

            // Stream the image to the drive, digesting the decompressed bytes as they
            // pass — this digest is the reference the read-back is checked against.
            onStep(context.getString(R.string.step_writing_usb))
            val writtenDigest = IncrementalDigest()
            var totalRead: Long = 0
            var currentLba = 0
            var lastUpdateMs = System.currentTimeMillis()

            val readBuffer = ByteArray(1024 * 64)
            val chunkBuffer = ByteArray(1024 * 128)
            var chunkPos = 0

            var bytesRead = dataStream.read(readBuffer)
            while (bytesRead != -1) {
                if (isCancelled) {
                    return@withContext Result.Cancelled
                }

                writtenDigest.update(readBuffer, 0, bytesRead)

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
                        Log.e(TAG, "Failed to write sectors at LBA $currentLba")
                        return@withContext Result.WriteError(context.getString(R.string.error_hardware_write))
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
                    onProgress(progress.coerceIn(0f, 1f))
                }

                bytesRead = dataStream.read(readBuffer)
            }

            // Write any remaining data
            if (chunkPos > 0) {
                val paddedLength = ((chunkPos + 511) / 512) * 512
                val dataToWrite = ByteArray(paddedLength)
                System.arraycopy(chunkBuffer, 0, dataToWrite, 0, chunkPos)
                // padding is automatically 0 since ByteArray initializes to 0
                if (!botDevice.writeSectors(currentLba, dataToWrite)) {
                    Log.e(TAG, "Failed to write final sectors at LBA $currentLba")
                    return@withContext Result.WriteError(context.getString(R.string.error_drive_too_small))
                }
            }

            // Authenticity: the zip's trailing bytes (central directory) sit past the
            // .bin entry, so drain the source to finish the download digest before
            // comparing against the manifest checksum.
            if (sourceDigest != null) {
                val drain = ByteArray(1024 * 64)
                while (sourceStream.read(drain) != -1) {
                    if (isCancelled) return@withContext Result.Cancelled
                }
                val downloadHex = sourceDigest.hexDigest()
                if (!IncrementalDigest.matches(expectedZipSha1, downloadHex)) {
                    Log.e(TAG, "Download checksum mismatch: manifest=$expectedZipSha1 actual=$downloadHex")
                    return@withContext Result.VerificationError(
                        context.getString(R.string.error_authenticity_failed)
                    )
                }
            }

            // Write verification: read back exactly the bytes written (whole sectors
            // off the wire, but only totalRead bytes into the digest — the final
            // sector's padding was never part of the image) and compare digests.
            onStep(context.getString(R.string.step_verifying))
            onVerifyProgress(0f)

            val expectedWriteHex = writtenDigest.hexDigest()
            val readBack = BoundedDigest(totalRead)
            val totalSectors = (totalRead + SECTOR_SIZE - 1) / SECTOR_SIZE
            var lba = 0L
            var lastVerifyUpdateMs = System.currentTimeMillis()

            while (lba < totalSectors) {
                if (isCancelled) {
                    return@withContext Result.Cancelled
                }
                if (isVerificationSkipped) {
                    return@withContext Result.Success(Verification.SKIPPED)
                }

                val sectors = minOf(VERIFY_CHUNK_SECTORS.toLong(), totalSectors - lba).toInt()
                val data = botDevice.readSectors(lba.toInt(), sectors)
                    ?: return@withContext Result.VerificationError(
                        context.getString(R.string.error_verification_failed)
                    )
                readBack.offer(data, data.size)
                lba += sectors

                val now = System.currentTimeMillis()
                if (now - lastVerifyUpdateMs > 500) {
                    lastVerifyUpdateMs = now
                    onVerifyProgress((readBack.bytesDigested.toDouble() / totalRead.toDouble()).toFloat().coerceIn(0f, 1f))
                }
            }
            onVerifyProgress(1f)

            if (!IncrementalDigest.matches(expectedWriteHex, readBack.hexDigest())) {
                Log.e(TAG, "Read-back digest mismatch: written=$expectedWriteHex readback=${readBack.hexDigest()}")
                return@withContext Result.VerificationError(
                    context.getString(R.string.error_verification_failed)
                )
            }

            return@withContext Result.Success(
                if (expectedZipSha1 != null) Verification.AUTHENTIC else Verification.WRITE_VERIFIED
            )

        } catch (e: Exception) {
            Log.e(TAG, "Error flashing to USB", e)
            return@withContext Result.WriteError(
                context.getString(R.string.error_unexpected, e.message ?: e.javaClass.simpleName)
            )
        } finally {
            try {
                dataStream?.close()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to close input stream", e)
            }
            try {
                if (massStorageInterface != null) {
                    usbConnection?.releaseInterface(massStorageInterface)
                }
                usbConnection?.close()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to close USB connection", e)
            }
        }
    }

    /**
     * Passes every byte read through [digest]. Used to hash the raw download
     * while ZipInputStream consumes it on the fly.
     */
    private class DigestingInputStream(
        source: InputStream,
        private val digest: IncrementalDigest
    ) : FilterInputStream(source) {

        private val single = ByteArray(1)

        override fun read(): Int {
            val value = super.read()
            if (value != -1) {
                single[0] = value.toByte()
                digest.update(single, 0, 1)
            }
            return value
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val count = super.read(b, off, len)
            if (count > 0) {
                digest.update(b, off, count)
            }
            return count
        }
    }
}
