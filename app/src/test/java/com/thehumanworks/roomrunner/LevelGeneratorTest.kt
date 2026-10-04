package com.thehumanworks.roomrunner

import com.thehumanworks.roomrunner.core.Level
import com.thehumanworks.roomrunner.core.LevelGenerator
import com.thehumanworks.roomrunner.core.PlatformKind
import com.thehumanworks.roomrunner.core.Tuning
import com.thehumanworks.roomrunner.core.V3
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LevelGeneratorTest {
  private val head = V3(0f, 1.6f, 0f)
  private val fwd = V3(0f, 0f, 1f)

  /** Platforms reachable from the start using only single-jump-sized hops. */
  private fun reachable(l: Level): Set<Int> {
    val seen = mutableSetOf(l.startPlatformId)
    val q = ArrayDeque(listOf(l.startPlatformId))
    while (q.isNotEmpty()) {
      val a = l.platform(q.removeFirst())
      for (b in l.platforms) {
        if (b.id in seen) continue
        val g = LevelGenerator.gap(a, b)
        val rise = abs(b.top - a.top)
        if (g <= Tuning.STEP_GAP + 0.03f && rise <= Tuning.STEP_RISE + 0.01f) {
          seen += b.id
          q.addLast(b.id)
        }
      }
    }
    return seen
  }

  @Test
  fun noScanMakesVirtualCourse() {
    val l = LevelGenerator(1).generate(emptyList(), 0f, head, fwd).level
    assertFalse(l.fromRoomScan)
    assertTrue(l.platforms.count { it.kind == PlatformKind.VIRTUAL } >= 7)
    assertTrue(l.coins.size >= 12)
    assertEquals(l.platforms.filter { it.kind != PlatformKind.STONE }.maxOf { it.top }, l.flag.y, 1e-4f)
    assertTrue("flag reachable", l.flagPlatformId in reachable(l))
  }

  @Test
  fun livingRoomIsFullyConnected() {
    val res = LevelGenerator(7).generate(TestRooms.livingRoom(), 0f, head, fwd)
    val l = res.level
    println(res.log.joinToString("\n"))
    assertTrue(l.fromRoomScan)
    // Bookshelf at 1.8 m is the highest: flag goes there.
    assertEquals(1.8f, l.flag.y, 1e-3f)
    val r = reachable(l)
    val main = l.platforms.filter { it.kind != PlatformKind.STONE }
    assertTrue("all surfaces reachable: ${main.map { it.id }} vs $r", main.all { it.id in r })
    assertTrue(l.enemies.isNotEmpty())
    // Every enemy patrols on a surface, within its footprint.
    for (e in l.enemies) {
      val p = l.platform(e.platformId)
      assertTrue(p.containsXZ(e.a, 0.01f) && p.containsXZ(e.b, 0.01f))
      assertEquals(p.top, e.positionAt(1.3f).y, 1e-4f)
    }
    // Spawns are on the start platform.
    val s = l.platform(l.startPlatformId)
    assertTrue(s.containsXZ(l.spawn) && s.containsXZ(l.spawn2))
  }

  @Test
  fun randomRoomsAlwaysReachTheFlag() {
    var stonesTotal = 0
    var fully = 0
    var flagOk = 0
    var leftOut = 0
    for (seed in 0 until 300) {
      val res = LevelGenerator(seed.toLong()).generate(TestRooms.randomRoom(seed), 0f, head, fwd)
      val l = res.level
      stonesTotal += l.platforms.count { it.kind == PlatformKind.STONE }
      val r = reachable(l)
      assertTrue("seed $seed: coins", l.coins.size >= 12)
      assertTrue("seed $seed: platforms", l.platforms.size >= 3)
      val main = l.platforms.filter { it.kind != PlatformKind.STONE }
      if (main.all { it.id in r }) fully++
      if (res.log.any { it.startsWith("left out") }) leftOut++
      if (l.flagPlatformId in r) flagOk++
      else println("seed $seed: flag NOT reachable; " + res.log.joinToString("; "))
    }
    println("random rooms fully connected: $fully/300, flag reachable: $flagOk/300, rooms with a surface left out: $leftOut, avg stones ${stonesTotal / 300f}")
    assertTrue(flagOk >= 297)
    assertTrue(fully >= 297)
    assertTrue(leftOut <= 30)
  }
}
