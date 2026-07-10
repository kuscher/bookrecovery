package com.google.chrome.recovery.ui.game

/*
 * Sprite-sheet coordinates for the bundled Chromium T-Rex runner sheet
 * (res/drawable-nodpi/dino_sprites.png = Chromium's
 * components/neterror/resources/images/default_200_percent/offline/200-offline-sprite.png,
 * 2446x194). Positions from offline_sprite_definitions.ts (HDPI set) and the
 * per-entity source files; all values are 2x pixels.
 *
 * Copyright 2021 The Chromium Authors
 * Use of this source code is governed by a BSD-style license that can be
 * found in the Chromium LICENSE file.
 */

/** A rectangle on the 2x sprite sheet. */
data class Sprite(val x: Int, val y: Int, val width: Int, val height: Int)

object DinoSprites {

    // T-Rex block base position (hdpi tRex = 1678,2). Frame x-offsets are the
    // 1x animFrames values from trex.ts, doubled.
    private const val TREX_X = 1678
    private const val TREX_Y = 2
    const val TREX_STAND_W = 88 // 44 * 2
    const val TREX_STAND_H = 94 // 47 * 2
    const val TREX_DUCK_W = 118 // 59 * 2
    const val TREX_DUCK_H = 50 // 25 * 2

    val TREX_WAITING = listOf(trexFrame(88), trexFrame(0))
    val TREX_RUNNING = listOf(trexFrame(176), trexFrame(264))
    val TREX_JUMPING = listOf(trexFrame(0))
    val TREX_CRASHED = listOf(trexFrame(440))
    val TREX_DUCKING = listOf(
        Sprite(TREX_X + 528, TREX_Y, TREX_DUCK_W, TREX_DUCK_H),
        Sprite(TREX_X + 646, TREX_Y, TREX_DUCK_W, TREX_DUCK_H)
    )

    private fun trexFrame(offset2x: Int) = Sprite(TREX_X + offset2x, TREX_Y, TREX_STAND_W, TREX_STAND_H)

    // Obstacles: unit sprites; multi-cactus draws size * unit width from x.
    val CACTUS_SMALL = Sprite(446, 2, 34, 70)
    val CACTUS_LARGE = Sprite(652, 2, 50, 100)
    val PTERODACTYL = listOf(
        Sprite(260, 2, 92, 80),
        Sprite(260 + 92, 2, 92, 80)
    )

    val CLOUD = Sprite(166, 2, 92, 28)

    // Ground: 1200x12 (1x) per variant, flat first then bumpy, at hdpi (2,104).
    val HORIZON_FLAT = Sprite(2, 104, 1200, 24)
    val HORIZON_BUMPY = Sprite(2 + 1200, 104, 1200, 24)
    const val HORIZON_DRAW_Y = 127f // horizon_line.ts yPos (1x units)

    // Night mode (night_mode.ts): moon phases are x-offsets into the moon block.
    private const val MOON_X = 954
    val MOON_PHASES = listOf(140, 120, 100, 60, 40, 20, 0).map { offset1x ->
        Sprite(MOON_X + offset1x * 2, 2, 40, 80)
    }
    val STAR = Sprite(1276, 2, 18, 18) // star frames stack vertically, 9px (1x) apart

    // Text sprite block (distance_meter.ts + game_over_panel.ts).
    private const val TEXT_X = 1294
    private const val TEXT_Y = 2
    const val DIGIT_W = 20 // 10 * 2
    const val DIGIT_H = 26 // 13 * 2

    fun digit(n: Int) = Sprite(TEXT_X + n * DIGIT_W, TEXT_Y, DIGIT_W, DIGIT_H)
    val HI_H = digit(10)
    val HI_I = digit(11)

    // "GAME OVER" text: 1x (0,13) 191x11 within the text block.
    val GAME_OVER_TEXT = Sprite(TEXT_X, TEXT_Y + 26, 382, 22)

    // Restart button: 36x32 (1x) at hdpi (2,130); 8 animation frames.
    val RESTART = Sprite(2, 130, 72, 64)
}
