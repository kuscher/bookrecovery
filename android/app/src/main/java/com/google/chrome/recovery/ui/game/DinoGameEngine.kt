package com.google.chrome.recovery.ui.game

import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.random.Random

/*
 * Game constants and mechanics in this file are extracted from the Chromium
 * T-Rex runner source (components/neterror/resources/dino_game/ — offline.ts,
 * trex.ts, obstacle.ts, horizon.ts, horizon_line.ts, cloud.ts, night_mode.ts,
 * distance_meter.ts, offline_sprite_definitions.ts, fetched from
 * chromium.googlesource.com @ main, 2026-07-10).
 *
 * Copyright 2014 The Chromium Authors
 * Use of this source code is governed by a BSD-style license that can be
 * found in the Chromium LICENSE file (https://chromium.googlesource.com/chromium/src/+/refs/heads/main/LICENSE).
 */

/**
 * The T-Rex runner game logic: a pure-Kotlin, frame-stepped engine with no
 * Android or Compose dependencies, so the physics, spawning, collision, and
 * scoring are all unit-testable on the JVM.
 *
 * All coordinates are in the game's native 1x logical units on a 600x150
 * canvas (the renderer scales up by an integer factor and maps to the 2x
 * sprite sheet). [update] advances the simulation by real elapsed time,
 * internally normalized to the original game's 60fps frame units so the
 * mechanics match Chromium's frame-based constants exactly.
 */
class DinoGameEngine(private val random: Random = Random.Default) {

    // Runner defaultBaseConfig + normalModeConfig (offline.ts).
    object Config {
        const val WIDTH = 600f
        const val HEIGHT = 150f
        const val FPS = 60f
        const val MS_PER_FRAME = 1000f / FPS
        const val SPEED = 6f
        const val MAX_SPEED = 13f
        const val ACCELERATION = 0.001f
        const val BOTTOM_PAD = 10f
        const val CLEAR_TIME = 3000f
        const val GAP_COEFFICIENT = 0.6f
        const val MAX_GAP_COEFFICIENT = 1.5f
        const val MAX_OBSTACLE_LENGTH = 3
        const val MAX_OBSTACLE_DUPLICATION = 2
        const val SPEED_DROP_COEFFICIENT = 3f
        const val GAMEOVER_CLEAR_TIME = 1200f
        const val INVERT_DISTANCE = 700
        const val INVERT_FADE_DURATION = 12000f
        const val MAX_CLOUDS = 6
        const val CLOUD_FREQUENCY = 0.5f
        const val BG_CLOUD_SPEED = 0.2f
        const val SCORE_COEFFICIENT = 0.025f
        const val ACHIEVEMENT_DISTANCE = 100
        const val SCORE_FLASH_DURATION = 250f // distance_meter.ts FLASH_DURATION = 1000/4
        const val SCORE_FLASH_ITERATIONS = 3
        // Simulation guard, not a Chromium constant: a single step never advances
        // more than this, so a paused/janky frame can't teleport the game state.
        const val MAX_STEP_MS = 100f
    }

    // trex.ts defaultTrexConfig + normalJumpConfig.
    object Trex {
        const val WIDTH = 44f
        const val HEIGHT = 47f
        const val WIDTH_DUCK = 59f
        const val START_X_POS = 50f
        const val INTRO_DURATION = 1500f
        const val GRAVITY = 0.6f
        const val INITIAL_JUMP_VELOCITY = -10f
        const val DROP_VELOCITY = -5f
        const val MIN_JUMP_HEIGHT = 30f
        const val BLINK_TIMING = 7000f
        val GROUND_Y = Config.HEIGHT - HEIGHT - Config.BOTTOM_PAD // 93
        val MIN_JUMP_Y = GROUND_Y - MIN_JUMP_HEIGHT

        // Animation frame timing (ms per frame) per status, from trex.ts animFrames.
        const val MS_PER_FRAME_WAITING = 1000f / 3f
        const val MS_PER_FRAME_RUNNING = 1000f / 12f
        const val MS_PER_FRAME_DUCKING = 1000f / 8f
    }

    data class Box(val x: Float, val y: Float, val width: Float, val height: Float)

    // Reduced collision boxes from trex.ts.
    private val trexRunningBoxes = listOf(
        Box(22f, 0f, 17f, 16f), Box(1f, 18f, 30f, 9f), Box(10f, 35f, 14f, 8f),
        Box(1f, 24f, 29f, 5f), Box(5f, 30f, 21f, 4f), Box(9f, 34f, 15f, 4f)
    )
    private val trexDuckingBoxes = listOf(Box(1f, 18f, 55f, 25f))

    /** Obstacle definitions from offline_sprite_definitions.ts. */
    enum class ObstacleType(
        val width: Float,
        val height: Float,
        val yPositions: List<Float>,
        val multipleSpeed: Float,
        val minGap: Float,
        val minSpeed: Float,
        val speedOffsetMagnitude: Float,
        val numFrames: Int,
        val msPerFrame: Float,
        val collisionBoxes: List<Box>,
    ) {
        CACTUS_SMALL(
            width = 17f, height = 35f, yPositions = listOf(105f),
            multipleSpeed = 4f, minGap = 120f, minSpeed = 0f,
            speedOffsetMagnitude = 0f, numFrames = 1, msPerFrame = 0f,
            collisionBoxes = listOf(Box(0f, 7f, 5f, 27f), Box(4f, 0f, 6f, 34f), Box(10f, 4f, 7f, 14f))
        ),
        CACTUS_LARGE(
            width = 25f, height = 50f, yPositions = listOf(90f),
            multipleSpeed = 7f, minGap = 120f, minSpeed = 0f,
            speedOffsetMagnitude = 0f, numFrames = 1, msPerFrame = 0f,
            collisionBoxes = listOf(Box(0f, 12f, 7f, 38f), Box(8f, 0f, 7f, 49f), Box(13f, 10f, 10f, 38f))
        ),
        PTERODACTYL(
            width = 46f, height = 40f, yPositions = listOf(100f, 75f, 50f),
            multipleSpeed = 999f, minGap = 150f, minSpeed = 8.5f,
            speedOffsetMagnitude = 0.8f, numFrames = 2, msPerFrame = 1000f / 6f,
            collisionBoxes = listOf(
                Box(15f, 15f, 16f, 5f), Box(18f, 21f, 24f, 6f), Box(2f, 14f, 4f, 3f),
                Box(6f, 10f, 4f, 7f), Box(10f, 8f, 6f, 9f)
            )
        );
    }

    class Obstacle(
        val type: ObstacleType,
        var x: Float,
        val y: Float,
        val size: Int,
        val gap: Float,
        val speedOffset: Float,
        val collisionBoxes: List<Box>,
    ) {
        var frame: Int = 0
        var frameTimer: Float = 0f
        var followingObstacleCreated: Boolean = false
        val width: Float get() = type.width * size
        val isVisible: Boolean get() = x + width > 0
    }

    class Cloud(var x: Float, val y: Float, val gap: Float)

    enum class TrexStatus { WAITING, RUNNING, JUMPING, DUCKING, CRASHED }
    enum class GameStatus { WAITING, RUNNING, CRASHED }

    // --- Public, renderer-facing state ---
    var gameStatus = GameStatus.WAITING; private set
    var trexStatus = TrexStatus.WAITING; private set
    var trexX = 0f; private set
    var trexY = Trex.GROUND_Y; private set
    var trexFrame = 0; private set
    var blinking = false; private set
    val obstacles = ArrayDeque<Obstacle>()
    val clouds = ArrayDeque<Cloud>()
    var currentSpeed = 0f; private set

    /** Two scrolling ground tiles; x positions and whether each uses the bumpy variant. */
    var horizonX1 = 0f; private set
    var horizonX2 = Config.WIDTH; private set
    var horizonBumpy1 = false; private set
    var horizonBumpy2 = true; private set

    var score = 0; private set
    var highScore = 0
    var achievementFlashOn = false; private set

    /** Night mode: whether inverted, plus moon phase and star positions. */
    var nightMode = false; private set
    var moonPhase = 0; private set
    var starX1 = 0f; private set
    var starY1 = 0f; private set
    var starX2 = 0f; private set
    var starY2 = 0f; private set

    /** True once the crash pose + game-over panel should render. */
    val isCrashed: Boolean get() = gameStatus == GameStatus.CRASHED

    // --- Internals ---
    private var distanceRan = 0f
    private var runningTime = 0f
    private var playingIntro = false
    private var introTimer = 0f
    private var jumpVelocity = 0f
    private var reachedMinHeight = false
    private var speedDrop = false
    private var jumpKeyHeld = false
    private var duckHeld = false
    private var animTimer = 0f
    private var blinkDelay = 0f
    private var blinkTimer = 0f
    private var crashTime = 0f
    private var timeSinceCrash = 0f
    private var obstacleHistory = ArrayDeque<ObstacleType>()
    private var invertTimer = 0f
    private var lastAchievementScore = 0
    private var flashTimer = 0f
    private var flashesLeft = 0

    /** Advances the simulation by [deltaMs] of real time. */
    fun update(deltaMs: Float) {
        val delta = deltaMs.coerceAtMost(Config.MAX_STEP_MS)
        val frames = delta / Config.MS_PER_FRAME

        when (gameStatus) {
            GameStatus.WAITING -> updateWaiting(delta)
            GameStatus.RUNNING -> updateRunning(delta, frames)
            GameStatus.CRASHED -> timeSinceCrash += delta
        }
    }

    private fun updateWaiting(delta: Float) {
        // Blink while waiting (trex.ts BLINK_TIMING).
        if (blinkDelay <= 0f) blinkDelay = ceil(random.nextFloat() * Trex.BLINK_TIMING)
        blinkTimer += delta
        if (blinkTimer >= blinkDelay) {
            blinking = true
            if (blinkTimer >= blinkDelay + Trex.MS_PER_FRAME_WAITING) {
                blinking = false
                blinkTimer = 0f
                blinkDelay = 0f
            }
        }
        trexFrame = if (blinking) 1 else 0
    }

    private fun updateRunning(delta: Float, frames: Float) {
        runningTime += delta

        if (playingIntro) {
            introTimer += delta
            trexX = Trex.START_X_POS * (introTimer / Trex.INTRO_DURATION).coerceAtMost(1f)
            if (introTimer >= Trex.INTRO_DURATION) {
                playingIntro = false
                trexX = Trex.START_X_POS
            }
        }

        // offline.ts: distanceRan += currentSpeed * deltaTime / msPerFrame;
        // acceleration applies per frame-equivalent.
        distanceRan += currentSpeed * frames
        if (currentSpeed < Config.MAX_SPEED) {
            currentSpeed = (currentSpeed + Config.ACCELERATION * frames).coerceAtMost(Config.MAX_SPEED)
        }

        updateTrex(delta, frames)
        updateHorizon(frames)
        updateClouds(delta)
        updateObstacles(frames)
        updateScore(delta)
        updateNightMode(delta, frames)

        obstacles.firstOrNull()?.let { first ->
            if (checkCollision(first)) crash()
        }
    }

    private fun updateTrex(delta: Float, frames: Float) {
        when (trexStatus) {
            TrexStatus.JUMPING -> {
                // trex.ts updateJump: position by velocity, velocity by gravity,
                // both scaled by elapsed frame units; speed drop triples descent.
                val positionFrames = if (speedDrop) frames * Config.SPEED_DROP_COEFFICIENT else frames
                trexY += (jumpVelocity * positionFrames).roundToInt()
                jumpVelocity += Trex.GRAVITY * frames

                if (trexY < Trex.MIN_JUMP_Y || speedDrop) reachedMinHeight = true

                // endJump: releasing the key clamps ascent once past min height.
                if (!jumpKeyHeld && reachedMinHeight && jumpVelocity < Trex.DROP_VELOCITY) {
                    jumpVelocity = Trex.DROP_VELOCITY
                }

                if (trexY >= Trex.GROUND_Y) {
                    trexY = Trex.GROUND_Y
                    jumpVelocity = 0f
                    speedDrop = false
                    reachedMinHeight = false
                    trexStatus = if (duckHeld) TrexStatus.DUCKING else TrexStatus.RUNNING
                    animTimer = 0f
                    trexFrame = 0
                }
            }
            TrexStatus.RUNNING, TrexStatus.DUCKING -> {
                val msPerFrame =
                    if (trexStatus == TrexStatus.DUCKING) Trex.MS_PER_FRAME_DUCKING else Trex.MS_PER_FRAME_RUNNING
                animTimer += delta
                if (animTimer >= msPerFrame) {
                    animTimer %= msPerFrame
                    trexFrame = (trexFrame + 1) % 2
                }
            }
            else -> Unit
        }
    }

    private fun updateHorizon(frames: Float) {
        // horizon_line.ts: two tiles leapfrog; increment = floor per-frame in the
        // source, kept fractional here for smoothness at equal average speed.
        val increment = currentSpeed * frames
        horizonX1 -= increment
        horizonX2 -= increment
        if (horizonX1 + Config.WIDTH <= 0) {
            horizonX1 = horizonX2 + Config.WIDTH
            horizonBumpy1 = random.nextFloat() > 0.5f
        }
        if (horizonX2 + Config.WIDTH <= 0) {
            horizonX2 = horizonX1 + Config.WIDTH
            horizonBumpy2 = random.nextFloat() > 0.5f
        }
    }

    private fun updateClouds(delta: Float) {
        // cloud.ts: xPos moves by ceil((BG_CLOUD_SPEED / 1000) * delta * currentSpeed).
        val cloudSpeed = ceil(Config.BG_CLOUD_SPEED / 1000f * delta * currentSpeed)
        clouds.forEach { it.x -= cloudSpeed }
        clouds.removeAll { it.x + 46f < 0 }

        val last = clouds.lastOrNull()
        val shouldAdd = clouds.size < Config.MAX_CLOUDS &&
            (last == null || (Config.WIDTH - last.x) > last.gap) &&
            Config.CLOUD_FREQUENCY > random.nextFloat()
        if (shouldAdd) {
            clouds.addLast(
                Cloud(
                    x = Config.WIDTH,
                    y = 30f + random.nextFloat() * (71f - 30f), // MAX_SKY_LEVEL..MIN_SKY_LEVEL
                    gap = 100f + random.nextFloat() * (400f - 100f)
                )
            )
        }
    }

    private fun updateObstacles(frames: Float) {
        // Obstacles only spawn after the clear time (offline.ts clearTime).
        val iterator = obstacles.iterator()
        while (iterator.hasNext()) {
            val obstacle = iterator.next()
            obstacle.x -= (currentSpeed + obstacle.speedOffset) * frames
            if (obstacle.type.numFrames > 1) {
                obstacle.frameTimer += frames * Config.MS_PER_FRAME
                if (obstacle.frameTimer >= obstacle.type.msPerFrame) {
                    obstacle.frameTimer %= obstacle.type.msPerFrame
                    obstacle.frame = (obstacle.frame + 1) % obstacle.type.numFrames
                }
            }
            if (!obstacle.isVisible) iterator.remove()
        }

        if (runningTime < Config.CLEAR_TIME) return

        val last = obstacles.lastOrNull()
        if (last == null) {
            spawnObstacle()
        } else if (!last.followingObstacleCreated && last.isVisible &&
            last.x + last.width + last.gap < Config.WIDTH
        ) {
            spawnObstacle()
            last.followingObstacleCreated = true
        }
    }

    private fun spawnObstacle() {
        // Type choice honors minSpeed and the duplication cap (obstacle.ts).
        var type: ObstacleType
        do {
            type = ObstacleType.entries[random.nextInt(ObstacleType.entries.size)]
        } while (duplicateObstacleCheck(type) || currentSpeed < type.minSpeed)

        var size = 1 + random.nextInt(Config.MAX_OBSTACLE_LENGTH)
        if (size > 1 && type.multipleSpeed > currentSpeed) size = 1

        val y = type.yPositions[random.nextInt(type.yPositions.size)]
        val speedOffset = if (type.speedOffsetMagnitude > 0f) {
            if (random.nextBoolean()) type.speedOffsetMagnitude else -type.speedOffsetMagnitude
        } else 0f

        val width = type.width * size
        val minGap = (width * currentSpeed + type.minGap * Config.GAP_COEFFICIENT).roundToInt().toFloat()
        val maxGap = (minGap * Config.MAX_GAP_COEFFICIENT).roundToInt().toFloat()
        val gap = minGap + random.nextFloat() * (maxGap - minGap)

        // Multi-cactus: middle collision box stretches to span the duplicated
        // middles; the last box hugs the right edge (obstacle.ts).
        val boxes = if (size > 1 && type.collisionBoxes.size == 3) {
            val first = type.collisionBoxes[0]
            val middle = type.collisionBoxes[1]
            val lastBox = type.collisionBoxes[2]
            listOf(
                first,
                Box(middle.x, middle.y, width - first.width - lastBox.width, middle.height),
                Box(width - lastBox.width, lastBox.y, lastBox.width, lastBox.height)
            )
        } else type.collisionBoxes

        obstacles.addLast(
            Obstacle(type, x = Config.WIDTH + width, y = y, size = size, gap = gap, speedOffset = speedOffset, collisionBoxes = boxes)
        )
        obstacleHistory.addFirst(type)
        while (obstacleHistory.size > Config.MAX_OBSTACLE_DUPLICATION) obstacleHistory.removeLast()
    }

    private fun duplicateObstacleCheck(next: ObstacleType): Boolean =
        obstacleHistory.size >= Config.MAX_OBSTACLE_DUPLICATION && obstacleHistory.all { it == next }

    private fun updateScore(delta: Float) {
        score = (distanceRan * Config.SCORE_COEFFICIENT).roundToInt()

        if (score > 0 && score % Config.ACHIEVEMENT_DISTANCE == 0 && score != lastAchievementScore) {
            lastAchievementScore = score
            flashesLeft = Config.SCORE_FLASH_ITERATIONS * 2
            flashTimer = 0f
        }
        if (flashesLeft > 0) {
            flashTimer += delta
            if (flashTimer >= Config.SCORE_FLASH_DURATION) {
                flashTimer %= Config.SCORE_FLASH_DURATION
                flashesLeft--
            }
            achievementFlashOn = flashesLeft % 2 == 1
        } else {
            achievementFlashOn = false
        }
    }

    private fun updateNightMode(delta: Float, frames: Float) {
        if (!nightMode && score > 0 && score % Config.INVERT_DISTANCE == 0 && score != 0) {
            nightMode = true
            invertTimer = 0f
            moonPhase = (moonPhase + 1) % 7
            starX1 = random.nextFloat() * Config.WIDTH
            starY1 = random.nextFloat() * 70f
            starX2 = random.nextFloat() * Config.WIDTH
            starY2 = random.nextFloat() * 70f
        } else if (nightMode) {
            invertTimer += delta
            if (invertTimer > Config.INVERT_FADE_DURATION) {
                nightMode = false
                invertTimer = 0f
            }
            // Moon and stars drift at their own speeds (night_mode.ts).
            starX1 -= 0.3f * frames
            starX2 -= 0.3f * frames
        }
    }

    private fun checkCollision(obstacle: Obstacle): Boolean {
        val ducking = trexStatus == TrexStatus.DUCKING
        val trexWidth = if (ducking) Trex.WIDTH_DUCK else Trex.WIDTH

        // Outer bounds with the 1px white-border compensation (offline.ts).
        val trexOuter = Box(trexX + 1f, trexY + 1f, trexWidth - 2f, Trex.HEIGHT - 2f)
        val obstacleOuter = Box(obstacle.x + 1f, obstacle.y + 1f, obstacle.width - 2f, obstacle.type.height - 2f)
        if (!boxCompare(trexOuter, obstacleOuter)) return false

        val trexBoxes = if (ducking) trexDuckingBoxes else trexRunningBoxes
        for (t in trexBoxes) {
            val adjustedTrex = Box(t.x + trexX, t.y + trexY, t.width, t.height)
            for (o in obstacle.collisionBoxes) {
                val adjustedObstacle = Box(o.x + obstacle.x, o.y + obstacle.y, o.width, o.height)
                if (boxCompare(adjustedTrex, adjustedObstacle)) return true
            }
        }
        return false
    }

    private fun boxCompare(a: Box, b: Box): Boolean =
        a.x < b.x + b.width && a.x + a.width > b.x && a.y < b.y + b.height && a.y + a.height > b.y

    private fun crash() {
        gameStatus = GameStatus.CRASHED
        trexStatus = TrexStatus.CRASHED
        trexFrame = 0
        timeSinceCrash = 0f
        if (score > highScore) highScore = score
    }

    // --- Input ---

    /** Jump input pressed (tap, space, up-arrow). Starts the game from WAITING. */
    fun onJumpPressed() {
        when (gameStatus) {
            GameStatus.WAITING -> startGame()
            GameStatus.RUNNING -> {
                jumpKeyHeld = true
                if (trexStatus != TrexStatus.JUMPING && trexStatus != TrexStatus.DUCKING) {
                    startJump()
                }
            }
            GameStatus.CRASHED -> {
                // Jump-key restart honors the game-over clear time (offline.ts).
                if (timeSinceCrash >= Config.GAMEOVER_CLEAR_TIME) restart()
            }
        }
    }

    fun onJumpReleased() {
        jumpKeyHeld = false
    }

    /** Duck input pressed (swipe down, down-arrow). Mid-air it speed-drops. */
    fun onDuckPressed() {
        duckHeld = true
        if (gameStatus != GameStatus.RUNNING) return
        when (trexStatus) {
            TrexStatus.JUMPING -> {
                // trex.ts setSpeedDrop.
                speedDrop = true
                jumpVelocity = 1f
            }
            TrexStatus.RUNNING -> {
                trexStatus = TrexStatus.DUCKING
                animTimer = 0f
                trexFrame = 0
            }
            else -> Unit
        }
    }

    fun onDuckReleased() {
        duckHeld = false
        if (trexStatus == TrexStatus.DUCKING) {
            trexStatus = TrexStatus.RUNNING
            animTimer = 0f
            trexFrame = 0
        }
    }

    /** Restart is always allowed via the explicit restart button (offline.ts). */
    fun restart() {
        obstacles.clear()
        obstacleHistory.clear()
        clouds.clear()
        distanceRan = 0f
        runningTime = 0f
        score = 0
        lastAchievementScore = 0
        flashesLeft = 0
        achievementFlashOn = false
        nightMode = false
        invertTimer = 0f
        currentSpeed = Config.SPEED
        jumpVelocity = 0f
        speedDrop = false
        reachedMinHeight = false
        trexY = Trex.GROUND_Y
        trexStatus = TrexStatus.RUNNING
        trexFrame = 0
        gameStatus = GameStatus.RUNNING
        horizonX1 = 0f
        horizonX2 = Config.WIDTH
    }

    private fun startGame() {
        gameStatus = GameStatus.RUNNING
        trexStatus = TrexStatus.JUMPING
        currentSpeed = Config.SPEED
        playingIntro = true
        introTimer = 0f
        jumpKeyHeld = true
        startJump()
    }

    private fun startJump() {
        trexStatus = TrexStatus.JUMPING
        trexFrame = 0
        // trex.ts startJump: initial velocity deepens slightly with speed.
        jumpVelocity = Trex.INITIAL_JUMP_VELOCITY - (currentSpeed / 10f)
        reachedMinHeight = false
        speedDrop = false
    }
}
