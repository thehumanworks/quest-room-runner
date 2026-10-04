package com.thehumanworks.roomrunner

import com.thehumanworks.roomrunner.core.Contacts
import com.thehumanworks.roomrunner.core.Platform
import com.thehumanworks.roomrunner.core.PlatformKind
import com.thehumanworks.roomrunner.core.Runner
import com.thehumanworks.roomrunner.core.RunnerEvent
import com.thehumanworks.roomrunner.core.RunnerInput
import com.thehumanworks.roomrunner.core.Tuning
import com.thehumanworks.roomrunner.core.V3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RunnerTest {
  private val dt = 1f / 72f
  private val table = Platform(0, PlatformKind.FURNITURE, V3(0f, 0.5f, 0f), 0.4f, 0.3f, 0.5f)

  private fun settle(r: Runner, plats: List<Platform>, frames: Int = 30) {
    repeat(frames) { r.step(dt, RunnerInput(), plats, 0f) }
  }

  @Test
  fun landsAndStaysGrounded() {
    val r = Runner(V3(0f, 0.6f, 0f))
    settle(r, listOf(table), 60)
    assertTrue(r.grounded)
    assertEquals(0.5f, r.pos.y, 1e-4f)
  }

  @Test
  fun jumpHeights() {
    val r = Runner(V3(0f, 0.5f, 0f))
    settle(r, listOf(table))
    var maxY = 0f
    r.step(dt, RunnerInput(jumpPressed = true, jumpHeld = true), listOf(table), 0f)
    repeat(80) {
      r.step(dt, RunnerInput(jumpHeld = true), listOf(table), 0f)
      maxY = maxOf(maxY, r.pos.y)
    }
    val single = maxY - 0.5f
    assertTrue("single jump $single", single in 0.24f..0.30f)

    val r2 = Runner(V3(0f, 0.5f, 0f))
    settle(r2, listOf(table))
    r2.step(dt, RunnerInput(jumpPressed = true, jumpHeld = true), listOf(table), 0f)
    maxY = 0f
    var frames = 0
    while (r2.vel.y > 0f && frames < 200) {
      r2.step(dt, RunnerInput(jumpHeld = true), listOf(table), 0f)
      frames++
    }
    val ev = r2.step(dt, RunnerInput(jumpPressed = true, jumpHeld = true), listOf(table), 0f)
    assertTrue(RunnerEvent.DOUBLE_JUMP in ev)
    repeat(100) {
      r2.step(dt, RunnerInput(jumpHeld = true), listOf(table), 0f)
      maxY = maxOf(maxY, r2.pos.y)
    }
    val dbl = maxY - 0.5f
    assertTrue("double jump $dbl", dbl in 0.42f..0.55f)
    // No triple jump.
    val r3 = r2
    r3.respawn(V3(0f, 1.2f, 0f))
    r3.step(dt, RunnerInput(jumpPressed = true), emptyList(), -10f)
    r3.step(dt, RunnerInput(jumpPressed = true), emptyList(), -10f)
    val ev3 = r3.step(dt, RunnerInput(jumpPressed = true), emptyList(), -10f)
    assertFalse(RunnerEvent.JUMP in ev3 || RunnerEvent.DOUBLE_JUMP in ev3)
  }

  @Test
  fun fallingToFloorRespawnsAtLastSafeSpot() {
    val r = Runner(V3(0f, 0.5f, 0f))
    settle(r, listOf(table))
    val evs = mutableListOf<RunnerEvent>()
    repeat(400) { evs += r.step(dt, RunnerInput(moveX = 1f), listOf(table), 0f) }
    assertTrue(RunnerEvent.FELL_TO_FLOOR in evs)
    assertTrue(table.containsXZ(r.pos, 0.01f))
    assertTrue(r.pos.y >= 0.49f)
  }

  @Test
  fun wallsBlockSideways() {
    val wall = Platform(1, PlatformKind.FURNITURE, V3(0.3f, 1.0f, 0f), 0.05f, 0.3f, 1.0f)
    val floorish = Platform(2, PlatformKind.VIRTUAL, V3(0f, 0.1f, 0f), 1f, 1f, 0.05f)
    val r = Runner(V3(0f, 0.1f, 0f))
    settle(r, listOf(wall, floorish))
    repeat(200) { r.step(dt, RunnerInput(moveX = 1f), listOf(wall, floorish), 0f) }
    assertTrue("x=${r.pos.x}", r.pos.x <= 0.25f - Tuning.CHAR_RADIUS + 1e-3f)
  }

  @Test
  fun coyoteTimeAllowsLateJump() {
    val r = Runner(V3(0.38f, 0.5f, 0f))
    settle(r, listOf(table))
    // Run off the edge, then press jump a few frames later.
    var left = false
    var frames = 0
    while (!left && frames < 100) {
      r.step(dt, RunnerInput(moveX = 1f), listOf(table), 0f)
      left = !r.grounded
      frames++
    }
    repeat(4) { r.step(dt, RunnerInput(moveX = 1f), listOf(table), 0f) }
    val ev = r.step(dt, RunnerInput(moveX = 1f, jumpPressed = true, jumpHeld = true), listOf(table), 0f)
    assertTrue(RunnerEvent.JUMP in ev)
  }

  /**
   * Simple bot: run at the target and jump near the edge, double-jumping at the apex. Proves the
   * generator's worst-case gap/rise is actually clearable with the controller tuning.
   */
  private fun botCrosses(gap: Float, rise: Float): Boolean {
    val a = Platform(0, PlatformKind.FURNITURE, V3(0f, 0.5f, 0f), 0.2f, 0.2f, 0.5f)
    val bx = 0.2f + gap + Tuning.STONE_HALF
    val b = Platform(1, PlatformKind.STONE, V3(bx, 0.5f + rise, 0f), Tuning.STONE_HALF, Tuning.STONE_HALF, Tuning.STONE_DEPTH)
    val plats = listOf(a, b)
    val r = Runner(V3(-0.1f, 0.5f, 0f))
    settle(r, plats)
    var jumped = false
    var doubled = false
    for (i in 0 until 600) {
      val dx = bx - r.pos.x
      val move = if (dx > 0.01f) 1f else if (dx < -0.01f) -1f else 0f
      var press = false
      if (!jumped && r.grounded && r.pos.x > 0.2f - 0.03f) {
        press = true
        jumped = true
      } else if (jumped && !doubled && r.vel.y < 0.1f && !r.grounded) {
        press = true
        doubled = true
      }
      r.step(dt, RunnerInput(moveX = move, jumpPressed = press, jumpHeld = true), plats, 0f)
      if (r.grounded && r.groundPlatformId == 1) return true
    }
    return false
  }

  @Test
  fun generatorLimitsAreClearable() {
    assertTrue(botCrosses(Tuning.STEP_GAP, Tuning.STEP_RISE))
    assertTrue(botCrosses(Tuning.STEP_GAP, 0f))
    assertTrue(botCrosses(0.05f, Tuning.STEP_RISE))
  }

  @Test
  fun stompVsHurt() {
    val r = Runner(V3(0f, 0.6f, 0f))
    r.vel = V3(0f, -1f, 0f)
    assertEquals(Contacts.EnemyContact.STOMP, Contacts.enemyContact(r, V3(0f, 0.55f, 0f)))
    val r2 = Runner(V3(0.03f, 0.5f, 0f))
    assertEquals(Contacts.EnemyContact.HURT, Contacts.enemyContact(r2, V3(0f, 0.5f, 0f)))
    r2.invulnerable = 1f
    assertEquals(Contacts.EnemyContact.NONE, Contacts.enemyContact(r2, V3(0f, 0.5f, 0f)))
  }
}
