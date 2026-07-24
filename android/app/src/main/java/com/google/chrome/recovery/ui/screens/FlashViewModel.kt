package com.google.chrome.recovery.ui.screens

import android.app.Application
import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.chrome.recovery.R
import com.google.chrome.recovery.usb.FlashNotificationController
import com.google.chrome.recovery.usb.UsbFlasher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Snapshot of everything [FlashScreen] needs to render.
 *
 * @param stepText The headline status line (step description, success message, or error).
 * @param progress Overall progress of the current phase, 0f..1f.
 * @param isErasing True while the pre-flash (or cancel-triggered) erase pass is running.
 * @param isFinished True once the flow reached a terminal state (success or error).
 * @param hasError True if the terminal state was an error.
 */
data class FlashUiState(
    val stepText: String = "",
    val progress: Float = 0f,
    val isErasing: Boolean = false,
    val isFinished: Boolean = false,
    val hasError: Boolean = false,
)

/**
 * ViewModel owning the flash execution state machine:
 *
 *   (erasing) -> flashing -> success / error
 *                  |
 *                  +-- cancel -> erasing (reset) -> success
 *
 * It drives [UsbFlasher] on a background dispatcher, holds the UI state across
 * configuration changes, and delegates every notification / Foreground Service
 * concern to [FlashNotificationController]. [FlashScreen] is left as a pure
 * renderer of [FlashUiState]; the only view concerns remaining there are the
 * POST_NOTIFICATIONS permission prompt and window-focus tracking, which need
 * an Activity and a View respectively.
 *
 * Scoped to the flash destination's NavBackStackEntry, so the running flash
 * survives rotation/resize and is torn down when the user leaves the screen.
 *
 * @param device The physical USB device the user has authorized writing to.
 * @param url The local or remote URL pointing to the recovery image .zip file.
 * @param eraseFirst If true, a 3-second erase simulation runs before flashing.
 */
class FlashViewModel(
    application: Application,
    private val device: UsbDevice,
    private val url: String,
    private val eraseFirst: Boolean,
) : AndroidViewModel(application) {

    private val usbManager = application.getSystemService(Context.USB_SERVICE) as UsbManager
    private val flasher = UsbFlasher(usbManager, application)
    private val notifications = FlashNotificationController(application)

    private val _uiState = MutableStateFlow(
        FlashUiState(stepText = application.getString(R.string.flash_step_starting), isErasing = eraseFirst)
    )
    val uiState: StateFlow<FlashUiState> = _uiState.asStateFlow()

    private var started = false
    private var isCancelledReset = false

    /**
     * Tracks whether the app is currently backgrounded (reported by the screen),
     * so terminal notifications are only posted when the user isn't looking.
     * Volatile because the flash pipeline reads it from a background dispatcher.
     */
    @Volatile
    private var isBackgrounded = false

    /**
     * Kicks off the flow. Idempotent: recomposition, rotation, or returning to the
     * screen must not restart a running flash.
     */
    fun start() {
        if (started) return
        started = true

        notifications.createChannel()
        // Claim foreground priority once at the beginning. This prevents
        // ForegroundServiceStartNotAllowedException if the user minimizes during eraseFirst.
        notifications.startKeepAlive()

        if (eraseFirst) {
            simulateErase()
        } else {
            flash()
        }
    }

    /**
     * Cancels the in-flight write, then runs the erase simulation so the user
     * ends on a "USB reset" summary rather than a half-written drive left silently.
     */
    fun cancelFlashAndReset() {
        flasher.cancel()
        isCancelledReset = true
        _uiState.update { it.copy(stepText = string(R.string.flash_step_erasing), progress = 0f, isErasing = true) }
        simulateErase()
    }

    /**
     * Called by the screen whenever the app's foreground-ness changes (lifecycle
     * state or window focus). Foregrounded: the in-app UI shows progress, so the
     * notification is dismissed. Backgrounded mid-run: post one immediately so the
     * status chip appears without waiting for the next progress tick.
     */
    fun onBackgroundedChanged(backgrounded: Boolean) {
        isBackgrounded = backgrounded
        val state = _uiState.value
        if (!backgrounded) {
            notifications.dismissProgress()
        } else if (!state.isFinished && !state.hasError) {
            notifications.postProgress(state.progress, state.isErasing)
        }
    }

    /**
     * Simulated erase pass (about 3 seconds of ramping progress). Ends either in
     * the cancel-reset summary or by handing off to the real flash, depending on
     * whether it was entered via [cancelFlashAndReset] or eraseFirst.
     */
    private fun simulateErase() {
        viewModelScope.launch {
            _uiState.update { it.copy(stepText = string(R.string.flash_step_erasing), isErasing = true) }
            for (i in 1..100) {
                val p = i / 100f
                _uiState.update { it.copy(progress = p) }
                notifications.postProgress(p, isErasing = true)
                delay(30)
            }
            if (isCancelledReset) {
                _uiState.update {
                    it.copy(stepText = string(R.string.flash_reset_success), progress = 1f, isFinished = true, hasError = false)
                }
            } else {
                _uiState.update { it.copy(isErasing = false) }
                flash()
            }
        }
    }

    private fun string(resId: Int, vararg args: Any): String =
        getApplication<Application>().getString(resId, *args)

    private fun flash() {
        viewModelScope.launch {
            val errorMsg = flasher.flashImageToUsb(
                device = device,
                url = url,
                onStep = { step -> _uiState.update { it.copy(stepText = step) } },
                onProgress = { p ->
                    _uiState.update { it.copy(progress = p) }
                    notifications.postProgress(p, isErasing = false)
                }
            )

            when {
                errorMsg == UsbFlasher.RESULT_CANCELLED -> {
                    // cancelFlashAndReset() already owns the UI from here; just drop priority.
                    notifications.stopKeepAlive()
                }
                errorMsg == null -> {
                    val message = string(R.string.flash_success)
                    _uiState.update { it.copy(stepText = message, progress = 1f, isFinished = true) }
                    notifications.stopKeepAlive()
                    if (isBackgrounded) {
                        notifications.postCompletion(string(R.string.notif_success_title), message, isError = false)
                    }
                }
                else -> {
                    _uiState.update { it.copy(stepText = string(R.string.flash_error, errorMsg), isFinished = true, hasError = true) }
                    notifications.stopKeepAlive()
                    if (isBackgrounded) {
                        notifications.postCompletion(string(R.string.notif_error_title), errorMsg, isError = true)
                    }
                }
            }
        }
    }
}
