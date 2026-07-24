package com.google.chrome.recovery.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Maximum width of a wizard step's content column.
 *
 * The wizard screens are single-column forms; letting text fields and body copy
 * stretch across a 1000dp+ tablet or desktop window hurts readability and looks
 * unfinished. 600dp matches the compact/medium window-class boundary, so phones
 * are unaffected and anything wider gets a centered column.
 */
private val WizardContentMaxWidth = 600.dp

/**
 * Fills the available space, then caps the content at [WizardContentMaxWidth]
 * and centers it horizontally. Drop-in replacement for the screens' previous
 * `Modifier.fillMaxSize()` root modifier — narrow windows lay out exactly as
 * before, per the canonical-layout guidance for feeds/forms on expanded widths.
 */
fun Modifier.wizardContentWidth(): Modifier =
    fillMaxSize()
        .wrapContentWidth(Alignment.CenterHorizontally)
        .widthIn(max = WizardContentMaxWidth)
        .fillMaxWidth()
