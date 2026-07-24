package com.google.chrome.recovery.ui.game

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.google.chrome.recovery.R
import kotlin.math.roundToInt

/**
 * Renders a [DinoGameEngine] onto a Compose [Canvas] and feeds it input.
 *
 * Rendering is sprite blitting only: every visual comes from the bundled
 * Chromium sprite sheet (2x), drawn with [FilterQuality.None] at an integer
 * scale factor so pixels stay crisp. The frame loop runs on withFrameNanos
 * with real delta times; the engine caps a single step so background jank
 * can't teleport the game.
 *
 * Night mode approximates Chromium's CSS `filter: invert()` with a color
 * matrix on the sprite draws plus an inverted background.
 *
 * @param active While false the frame loop idles (the flash finished and the
 *   completion UI owns the screen) — the game pauses rather than running blind.
 * @param onCrashed Reports the final and high score after a crash, so the
 *   caller can persist the high score.
 */
@Composable
fun DinoGameCanvas(
    engine: DinoGameEngine,
    active: Boolean,
    modifier: Modifier = Modifier,
    onCrashed: (score: Int, highScore: Int) -> Unit = { _, _ -> },
) {
    val sprites = ImageBitmap.imageResource(R.drawable.dino_sprites)

    // Bumping this state each frame is what drives recomposition/redraw.
    var frameTick by remember { mutableLongStateOf(0L) }

    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        var lastNanos = 0L
        var wasCrashed = engine.isCrashed
        while (true) {
            androidx.compose.runtime.withFrameNanos { nanos ->
                if (lastNanos != 0L) {
                    engine.update((nanos - lastNanos) / 1_000_000f)
                }
                lastNanos = nanos
                frameTick++
            }
            if (engine.isCrashed && !wasCrashed) {
                onCrashed(engine.score, engine.highScore)
            }
            wasCrashed = engine.isCrashed
        }
    }

    // BoxWithConstraints reads the real bounded width from the parent (the
    // wizard content column) so the canvas is sized explicitly, instead of via
    // aspectRatio — which was overflowing the column and pinning the game to the
    // right edge. The Box then centers the canvas for equal margins.
    BoxWithConstraints(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.TopCenter
    ) {
        val canvasWidth = maxWidth
        Canvas(
            modifier = Modifier
                .width(canvasWidth)
                .height(canvasWidth * (DinoGameEngine.Config.HEIGHT / DinoGameEngine.Config.WIDTH))
                .pointerInput(engine) {
                detectTapGestures(
                    onPress = {
                        engine.onJumpPressed()
                        tryAwaitRelease()
                        engine.onJumpReleased()
                    }
                )
            }
            .pointerInput(engine) {
                detectVerticalDragGestures(
                    onDragEnd = { engine.onDuckReleased() },
                    onDragCancel = { engine.onDuckReleased() }
                ) { _, dragAmount ->
                    if (dragAmount > 0) engine.onDuckPressed()
                }
            }
            .onPreviewKeyEvent { event ->
                when (event.key) {
                    Key.Spacebar, Key.DirectionUp -> {
                        if (event.type == KeyEventType.KeyDown) engine.onJumpPressed() else engine.onJumpReleased()
                        true
                    }
                    Key.DirectionDown -> {
                        if (event.type == KeyEventType.KeyDown) engine.onDuckPressed() else engine.onDuckReleased()
                        true
                    }
                    else -> false
                }
            }
            .focusable()
    ) {
        // Reading frameTick ties this draw to the frame loop.
        @Suppress("UNUSED_EXPRESSION")
        frameTick

        // Chrome scales the 600-logical-px game to the viewport width with image
        // smoothing off; do the same (nearest-neighbor via FilterQuality.None).
        // Integer-only scaling would leave the game at ~55% width on common
        // phone densities.
        val scale = size.width / DinoGameEngine.Config.WIDTH
        val night = engine.nightMode

        // Clip drawing to the canvas: the second scrolling ground tile and
        // obstacles waiting off-screen live at logical x >= the canvas width, and
        // Compose's Canvas does not clip by default — so without this they paint
        // past the right edge into the parent, which is what pinned the game to
        // the screen edge instead of staying within its centered column.
        clipRect {
        drawRect(if (night) Color(0xFF202124) else Color.Transparent)

        val filter = if (night) INVERT_FILTER else null

        fun blit(sprite: Sprite, x: Float, y: Float, widthUnits: Int = 1) {
            drawImage(
                image = sprites,
                srcOffset = IntOffset(sprite.x, sprite.y),
                srcSize = IntSize(sprite.width * widthUnits, sprite.height),
                dstOffset = IntOffset((x * scale).roundToInt(), (y * scale).roundToInt()),
                // Sprite source is 2x; logical units are 1x, so dst = logical
                // extent (srcPx / 2) times the canvas scale.
                dstSize = IntSize(
                    (sprite.width * widthUnits / 2f * scale).roundToInt(),
                    (sprite.height / 2f * scale).roundToInt()
                ),
                filterQuality = FilterQuality.None,
                colorFilter = filter
            )
        }

        // Ground.
        blit(if (engine.horizonBumpy1) DinoSprites.HORIZON_BUMPY else DinoSprites.HORIZON_FLAT, engine.horizonX1, DinoSprites.HORIZON_DRAW_Y)
        blit(if (engine.horizonBumpy2) DinoSprites.HORIZON_BUMPY else DinoSprites.HORIZON_FLAT, engine.horizonX2, DinoSprites.HORIZON_DRAW_Y)

        // Night sky.
        if (night) {
            blit(DinoSprites.MOON_PHASES[engine.moonPhase], DinoGameEngine.Config.WIDTH - 100f, 30f)
            blit(DinoSprites.STAR, engine.starX1, engine.starY1)
            blit(DinoSprites.STAR, engine.starX2, engine.starY2)
        }

        // Clouds.
        engine.clouds.forEach { cloud -> blit(DinoSprites.CLOUD, cloud.x, cloud.y) }

        // Obstacles.
        engine.obstacles.forEach { obstacle ->
            when (obstacle.type) {
                DinoGameEngine.ObstacleType.CACTUS_SMALL ->
                    blit(DinoSprites.CACTUS_SMALL, obstacle.x, obstacle.y, widthUnits = obstacle.size)
                DinoGameEngine.ObstacleType.CACTUS_LARGE ->
                    blit(DinoSprites.CACTUS_LARGE, obstacle.x, obstacle.y, widthUnits = obstacle.size)
                DinoGameEngine.ObstacleType.PTERODACTYL ->
                    blit(DinoSprites.PTERODACTYL[obstacle.frame], obstacle.x, obstacle.y)
            }
        }

        // T-Rex.
        val trexSprite = when (engine.trexStatus) {
            DinoGameEngine.TrexStatus.WAITING -> DinoSprites.TREX_WAITING[engine.trexFrame % 2]
            DinoGameEngine.TrexStatus.RUNNING -> DinoSprites.TREX_RUNNING[engine.trexFrame % 2]
            DinoGameEngine.TrexStatus.JUMPING -> DinoSprites.TREX_JUMPING[0]
            DinoGameEngine.TrexStatus.DUCKING -> DinoSprites.TREX_DUCKING[engine.trexFrame % 2]
            DinoGameEngine.TrexStatus.CRASHED -> DinoSprites.TREX_CRASHED[0]
        }
        // Ducking sprite is shorter; anchor to the ground like the source does.
        val trexDrawY = if (engine.trexStatus == DinoGameEngine.TrexStatus.DUCKING) {
            DinoGameEngine.Trex.GROUND_Y + DinoGameEngine.Trex.HEIGHT - 25f
        } else engine.trexY
        blit(trexSprite, engine.trexX, trexDrawY)

        // Score meter (top right, 5 digits; achievement flash blinks it).
        drawScore(scale, engine, ::blit)

        // Game over.
        if (engine.isCrashed) {
            val textX = (DinoGameEngine.Config.WIDTH - 191f) / 2f
            val textY = ((DinoGameEngine.Config.HEIGHT - 25f) / 3f)
            blit(DinoSprites.GAME_OVER_TEXT, textX, textY)
            blit(DinoSprites.RESTART, (DinoGameEngine.Config.WIDTH - 36f) / 2f, textY + 24f)
        }
        }
        }
    }
}

private fun drawScore(scale: Float, engine: DinoGameEngine, blit: (Sprite, Float, Float, Int) -> Unit) {
    val digitW = 11f // DEST_WIDTH (1x) from distance_meter.ts
    val y = 5f
    var x = DinoGameEngine.Config.WIDTH - digitW * 6

    if (!engine.achievementFlashOn) {
        val text = engine.score.coerceAtMost(99999).toString().padStart(5, '0')
        text.forEachIndexed { i, c ->
            blit(DinoSprites.digit(c - '0'), x + i * digitW, y, 1)
        }
    }

    if (engine.highScore > 0) {
        var hx = x - digitW * 8
        blit(DinoSprites.HI_H, hx, y, 1)
        blit(DinoSprites.HI_I, hx + digitW, y, 1)
        val high = engine.highScore.coerceAtMost(99999).toString().padStart(5, '0')
        high.forEachIndexed { i, c ->
            blit(DinoSprites.digit(c - '0'), hx + digitW * 2.5f + i * digitW, y, 1)
        }
    }
}

/** Approximates Chromium's CSS invert() for night mode. */
private val INVERT_FILTER = ColorFilter.colorMatrix(
    ColorMatrix(
        floatArrayOf(
            -1f, 0f, 0f, 0f, 255f,
            0f, -1f, 0f, 0f, 255f,
            0f, 0f, -1f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f
        )
    )
)
