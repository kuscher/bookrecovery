package com.google.chrome.recovery.ui.screens

import android.app.Application
import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.chrome.recovery.R
import com.google.chrome.recovery.usb.UsbFlasher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Snapshot of everything [EraseScreen] needs to render. */
data class EraseUiState(
    val stepText: String = "",
    val detailText: String? = null,
    val progress: Float = 0f,
    val isFinished: Boolean = false,
    val hasError: Boolean = false,
)

/**
 * ViewModel for the standalone erase flow (the top-bar "Erase recovery media"
 * action). Drives [UsbFlasher.eraseDevice] — a real zeroing of the drive's
 * partition structures, replacing the previous 3-second animation that wrote
 * nothing and then claimed success.
 *
 * The erase takes seconds, so unlike the flash flow there is no foreground
 * service or notification story; the ViewModel just needs to survive
 * configuration changes mid-wipe.
 */
class EraseViewModel(
    application: Application,
    private val device: UsbDevice,
) : AndroidViewModel(application) {

    private val usbManager = application.getSystemService(Context.USB_SERVICE) as UsbManager
    private val flasher = UsbFlasher(usbManager, application)

    private val _uiState = MutableStateFlow(
        EraseUiState(stepText = application.getString(R.string.erase_step))
    )
    val uiState: StateFlow<EraseUiState> = _uiState.asStateFlow()

    private var started = false

    /** Starts the wipe. Idempotent across recomposition and recreation. */
    fun start() {
        if (started) return
        started = true

        viewModelScope.launch {
            val result = flasher.eraseDevice(device) { p ->
                _uiState.update { it.copy(progress = p) }
            }
            when (result) {
                is UsbFlasher.EraseResult.Done -> _uiState.update {
                    it.copy(
                        stepText = string(R.string.erase_success),
                        detailText = string(R.string.erase_success_detail),
                        progress = 1f,
                        isFinished = true
                    )
                }
                is UsbFlasher.EraseResult.Error -> _uiState.update {
                    it.copy(
                        stepText = string(R.string.flash_error, result.message),
                        isFinished = true,
                        hasError = true
                    )
                }
            }
        }
    }

    private fun string(resId: Int, vararg args: Any): String =
        getApplication<Application>().getString(resId, *args)
}
