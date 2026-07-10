package com.google.chrome.recovery.ui.game

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Engine tests computed against the Chromium constants independently of the
 * engine's own integration code, stepping in exact 60fps frames.
 */
class DinoGameEngineTest {

    private val frameMs = DinoGameEngine.Config.MS_PER_FRAME

    private fun runningEngine(seed: Int = 1): DinoGameEngine =
        DinoGameEngine(Random(seed)).apply { restart() }

    /** Steps one 60fps frame. */
    private fun DinoGameEngine.step(frames: Int = 1) = repeat(frames) { update(frameMs) }

    @Test
    fun `held jump reaches the computed apex and returns to the ground`() {
        val engine = runningEngine()
        val startSpeed = engine.currentSpeed
        engine.onJumpPressed() // held for the whole arc

        // Independent discrete integration of trex.ts updateJump:
        // v0 = INITIAL_JUMP_VELOCITY - speed/10; y += round(v); v += g each frame.
        var expectedY = DinoGameEngine.Trex.GROUND_Y
        var v = DinoGameEngine.Trex.INITIAL_JUMP_VELOCITY - startSpeed / 10f
        var expectedApex = expectedY
        while (true) {
            expectedY += Math.round(v)
            v += DinoGameEngine.Trex.GRAVITY
            if (expectedY < expectedApex) expectedApex = expectedY
            if (expectedY >= DinoGameEngine.Trex.GROUND_Y) break
        }

        var apex = engine.trexY
        var frames = 0
        do {
            engine.step()
            frames++
            if (engine.trexY < apex) apex = engine.trexY
        } while (engine.trexStatus == DinoGameEngine.TrexStatus.JUMPING && frames < 300)

        // The engine accelerates during the arc, shifting the apex by at most a
        // couple of pixels versus the fixed-speed integration.
        assertTrue("jump did not land", frames < 300)
        assertEquals(expectedApex, apex, 3f)
        assertEquals(DinoGameEngine.Trex.GROUND_Y, engine.trexY, 0.01f)
        assertTrue(
            engine.trexStatus == DinoGameEngine.TrexStatus.RUNNING ||
                engine.trexStatus == DinoGameEngine.TrexStatus.DUCKING
        )
    }

    @Test
    fun `released jump is clamped but clears the minimum height`() {
        val engine = runningEngine()
        engine.onJumpPressed()
        engine.step(2)
        engine.onJumpReleased()

        var apex = engine.trexY
        var frames = 0
        do {
            engine.step()
            frames++
            if (engine.trexY < apex) apex = engine.trexY
        } while (engine.trexStatus == DinoGameEngine.TrexStatus.JUMPING && frames < 300)

        val heldApex = heldJumpApex()
        val shortJumpHeight = DinoGameEngine.Trex.GROUND_Y - apex
        val heldJumpHeight = DinoGameEngine.Trex.GROUND_Y - heldApex

        assertTrue("short jump should clear MIN_JUMP_HEIGHT", shortJumpHeight >= DinoGameEngine.Trex.MIN_JUMP_HEIGHT)
        assertTrue("short jump ($shortJumpHeight) should stay below a held jump ($heldJumpHeight)", shortJumpHeight < heldJumpHeight)
    }

    private fun heldJumpApex(): Float {
        val engine = runningEngine()
        engine.onJumpPressed()
        var apex = engine.trexY
        var frames = 0
        do {
            engine.step()
            frames++
            if (engine.trexY < apex) apex = engine.trexY
        } while (engine.trexStatus == DinoGameEngine.TrexStatus.JUMPING && frames < 300)
        return apex
    }

    @Test
    fun `running into a mid-height pterodactyl crashes, ducking under it does not`() {
        // yPos 75 pterodactyl: its lowest reduced collision box ends at y=102;
        // the running trex's boxes start at y=93, the ducking box at y=111.
        fun engineWithPterodactyl(duck: Boolean): DinoGameEngine {
            val engine = runningEngine()
            if (duck) engine.onDuckPressed()
            val type = DinoGameEngine.ObstacleType.PTERODACTYL
            engine.obstacles.addLast(
                DinoGameEngine.Obstacle(
                    type = type,
                    x = engine.trexX,
                    y = 75f,
                    size = 1,
                    gap = 999f,
                    speedOffset = 0f,
                    collisionBoxes = type.collisionBoxes
                )
            )
            engine.step()
            return engine
        }

        assertTrue(engineWithPterodactyl(duck = false).isCrashed)
        assertFalse(engineWithPterodactyl(duck = true).isCrashed)
    }

    @Test
    fun `high pterodactyl passes over a running trex`() {
        val engine = runningEngine()
        val type = DinoGameEngine.ObstacleType.PTERODACTYL
        engine.obstacles.addLast(
            DinoGameEngine.Obstacle(
                type = type, x = engine.trexX, y = 50f, size = 1,
                gap = 999f, speedOffset = 0f, collisionBoxes = type.collisionBoxes
            )
        )
        engine.step()
        assertFalse(engine.isCrashed)
    }

    @Test
    fun `ground-level cactus crashes a running trex`() {
        val engine = runningEngine()
        val type = DinoGameEngine.ObstacleType.CACTUS_LARGE
        engine.obstacles.addLast(
            DinoGameEngine.Obstacle(
                type = type, x = engine.trexX, y = 90f, size = 1,
                gap = 999f, speedOffset = 0f, collisionBoxes = type.collisionBoxes
            )
        )
        engine.step()
        assertTrue(engine.isCrashed)
    }

    @Test
    fun `obstacles spawn only after the clear time and never pterodactyls at low speed`() {
        val engine = runningEngine(seed = 7)
        var elapsed = 0f
        while (elapsed < DinoGameEngine.Config.CLEAR_TIME) {
            assertTrue("no obstacles before clearTime", engine.obstacles.isEmpty())
            engine.step()
            elapsed += frameMs
        }
        // Run long enough for several spawns.
        engine.step(1200)
        assertTrue("obstacles should have spawned", engine.obstacles.isNotEmpty() || engine.isCrashed)
        // minSpeed for pterodactyls is 8.5; the game starts at 6 and accelerates
        // 0.001/frame, so none may appear in this window (speed < 8.5).
        assertTrue(
            "no pterodactyl below its minSpeed",
            engine.obstacles.none { it.type == DinoGameEngine.ObstacleType.PTERODACTYL }
        )
    }

    @Test
    fun `speed accelerates toward but never exceeds the maximum`() {
        val engine = runningEngine()
        val start = engine.currentSpeed
        engine.step(600)
        assertTrue(engine.currentSpeed > start)
        assertTrue(engine.currentSpeed <= DinoGameEngine.Config.MAX_SPEED)
    }

    @Test
    fun `score follows the distance coefficient`() {
        val engine = runningEngine()
        // Avoid crashing into spawned obstacles: jump forever isn't needed —
        // just check early, before clearTime allows spawns.
        engine.step(120) // 2 seconds
        // distance = sum(speed_i) over frames; speed grows 0.001/frame from 6.
        var expectedDistance = 0f
        var speed = DinoGameEngine.Config.SPEED
        repeat(120) {
            expectedDistance += speed
            speed = (speed + DinoGameEngine.Config.ACCELERATION).coerceAtMost(DinoGameEngine.Config.MAX_SPEED)
        }
        val expectedScore = Math.round(expectedDistance * DinoGameEngine.Config.SCORE_COEFFICIENT)
        assertEquals(expectedScore.toFloat(), engine.score.toFloat(), 1f)
    }

    @Test
    fun `crash preserves high score and restart resets the run`() {
        val engine = runningEngine()
        engine.step(120)
        val scoreBeforeCrash = engine.score
        val type = DinoGameEngine.ObstacleType.CACTUS_LARGE
        engine.obstacles.addLast(
            DinoGameEngine.Obstacle(
                type = type, x = engine.trexX, y = 90f, size = 1,
                gap = 999f, speedOffset = 0f, collisionBoxes = type.collisionBoxes
            )
        )
        engine.step()
        assertTrue(engine.isCrashed)
        assertEquals(scoreBeforeCrash, engine.highScore)

        engine.restart()
        assertEquals(0, engine.score)
        assertEquals(scoreBeforeCrash, engine.highScore)
        assertEquals(DinoGameEngine.GameStatus.RUNNING, engine.gameStatus)
        assertTrue(engine.obstacles.isEmpty())
        assertEquals(DinoGameEngine.Config.SPEED, engine.currentSpeed, 0.001f)
    }

    @Test
    fun `jump-key restart honors the game-over clear time`() {
        val engine = runningEngine()
        val type = DinoGameEngine.ObstacleType.CACTUS_LARGE
        engine.obstacles.addLast(
            DinoGameEngine.Obstacle(
                type = type, x = engine.trexX, y = 90f, size = 1,
                gap = 999f, speedOffset = 0f, collisionBoxes = type.collisionBoxes
            )
        )
        engine.step()
        assertTrue(engine.isCrashed)

        engine.onJumpPressed() // immediately: gated
        assertTrue(engine.isCrashed)

        // Advance past GAMEOVER_CLEAR_TIME, then the jump key restarts.
        engine.step((DinoGameEngine.Config.GAMEOVER_CLEAR_TIME / frameMs).toInt() + 2)
        engine.onJumpPressed()
        assertFalse(engine.isCrashed)
    }
}
