package com.google.chrome.recovery.ui

import android.net.Uri

/**
 * Type-safe definitions of every destination in the recovery wizard's navigation graph.
 *
 * Each object carries the route [pattern] registered with the NavHost. Destinations
 * without arguments navigate using the pattern directly; [SelectChannel] takes the
 * selected model name as a path argument and exposes [SelectChannel.forModel] to build
 * a concrete navigation target (URL-encoding the name, since model names can contain
 * spaces and slashes).
 *
 * Centralizing the routes here keeps `RecoveryApp`'s graph, the top-bar step indicator,
 * and every `navigate()` call in agreement, instead of scattering string literals that
 * can silently drift apart.
 */
sealed class Route(val pattern: String) {
    data object Welcome : Route("welcome")
    data object Identify : Route("identify")
    data object SelectModel : Route("select_model")
    data object SelectDrive : Route("select_drive")
    data object EraseDrive : Route("erase_drive")
    data object Flash : Route("flash")
    data object EraseFlash : Route("erase_flash")

    data object SelectChannel : Route("select_channel/{$ARG_MODEL_NAME}") {
        /** Builds the concrete route for navigating to the channel list of [modelName]. */
        fun forModel(modelName: String): String = "select_channel/${Uri.encode(modelName)}"
    }

    companion object {
        /** Name of the model-name path argument used by [SelectChannel]. */
        const val ARG_MODEL_NAME = "modelName"
    }
}
