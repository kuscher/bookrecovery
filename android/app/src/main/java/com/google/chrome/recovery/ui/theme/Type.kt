package com.google.chrome.recovery.ui.theme

import androidx.compose.material3.Typography

/**
 * The app's type scale: the stock Material 3 typography, which on material3
 * 1.5.x includes the full Expressive scale (the 15 *Emphasized styles) used
 * for the flash flow's terminal states.
 *
 * The previous hand-rolled bodyLarge/titleLarge matched the Material defaults
 * except titleLarge's weight (500 vs the spec's 400); adopting the stock
 * scale keeps this file the single source of truth going forward.
 */
val Typography = Typography()
