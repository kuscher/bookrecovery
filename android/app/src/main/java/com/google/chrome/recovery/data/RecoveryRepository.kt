package com.google.chrome.recovery.data

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Repository responsible for fetching the latest ChromeOS recovery image metadata
 * from official Google endpoints.
 *
 * This data source provides a comprehensive JSON list of all supported Chromebook
 * hardware models (hwids), channels (stable, beta, dev), and direct download URLs
 * for the zipped `.bin` payloads.
 *
 * It aggregates both standard ChromeOS models and CloudReady (ChromeOS Flex) models.
 *
 * The manifest is fetched once per process and cached: several wizard screens
 * (identify, model selection, channel selection, and the expanded-width detail
 * pane) all need the same ~2,500-entry list, and previously each of them
 * re-downloaded it. Access the repository through [RecoveryRepository.instance].
 */
class RecoveryRepository private constructor() {

    companion object {
        /** Process-wide instance so every screen shares one cached manifest. */
        val instance = RecoveryRepository()
    }

    private val RECOVERY_URL = "https://dl.google.com/dl/edgedl/chromeos/recovery/recovery2.json"
    private val CLOUDREADY_URL = "https://dl.google.com/dl/edgedl/chromeos/recovery/cloudready_recovery2.json"

    private val mutex = Mutex()
    private var cached: List<RecoveryImage>? = null

    suspend fun fetchRecoveryImages(): List<RecoveryImage> = mutex.withLock {
        cached?.let { return@withLock it }
        val images = fetchFromNetwork()
        // An empty result means the fetch failed; don't cache it, so the next
        // screen gets another chance once connectivity returns.
        if (images.isNotEmpty()) {
            cached = images
        }
        images
    }

    private suspend fun fetchFromNetwork(): List<RecoveryImage> = withContext(Dispatchers.IO) {
        val images = mutableListOf<RecoveryImage>()
        try {
            val listType = object : TypeToken<List<RecoveryImage>>() {}.type
            val gson = Gson()

            // Fetch normal ChromeOS recovery
            val url = URL(RECOVERY_URL)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val reader = InputStreamReader(connection.inputStream)
                val result: List<RecoveryImage> = gson.fromJson(reader, listType)
                images.addAll(result)
                reader.close()
            }
            connection.disconnect()

            // Fetch CloudReady recovery
            val cloudUrl = URL(CLOUDREADY_URL)
            val cloudConnection = cloudUrl.openConnection() as HttpURLConnection
            cloudConnection.requestMethod = "GET"
            if (cloudConnection.responseCode == HttpURLConnection.HTTP_OK) {
                val reader = InputStreamReader(cloudConnection.inputStream)
                val result: List<RecoveryImage> = gson.fromJson(reader, listType)
                images.addAll(result)
                reader.close()
            }
            cloudConnection.disconnect()

        } catch (e: Exception) {
            e.printStackTrace()
        }
        return@withContext images
    }
}
