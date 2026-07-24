package com.google.chrome.recovery.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.toPath
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph

/**
 * Terminal-state badges for the flash and erase flows.
 *
 * The success badge is the flow's one moment of celebration: a burst polygon
 * that morphs into a circle around the checkmark, driven by the theme's
 * expressive motion scheme (Material's sanctioned shape-morph pattern:
 * [MaterialShapes] polygons + a graphics-shapes [Morph] + an animated float —
 * the same construction LoadingIndicator uses internally).
 *
 * The error badge is deliberately calmer — a static soft "bun" container in
 * error-container colors, no motion — so a failed flash reads as "stop and
 * read this", not as an alarm.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MorphingSuccessBadge(contentDescription: String?, modifier: Modifier = Modifier) {
    val morph = remember { Morph(MaterialShapes.SoftBurst, MaterialShapes.Circle) }
    val progress = remember { Animatable(0f) }
    // Spatial (springy) spec for the scale-in; the morph fraction itself is
    // clamped because a spring overshoot past 1f has no defined shape.
    val spec = MaterialTheme.motionScheme.slowSpatialSpec<Float>()
    LaunchedEffect(Unit) {
        progress.animateTo(1f, spec)
    }

    val morphFraction = progress.value.coerceIn(0f, 1f)
    val shape = remember(morphFraction) {
        GenericShape { size, _ ->
            val path = morph.toPath(morphFraction)
            val matrix = Matrix()
            matrix.scale(size.width, size.height)
            path.transform(matrix)
            addPath(path)
        }
    }

    Box(
        modifier = modifier
            .size(88.dp)
            .scale(0.6f + 0.4f * progress.value)
            .clip(shape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Filled.Check,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(40.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ErrorBadge(contentDescription: String?, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(88.dp)
            .clip(MaterialShapes.Bun.toShape())
            .background(MaterialTheme.colorScheme.errorContainer),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Filled.Warning,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.size(40.dp)
        )
    }
}
