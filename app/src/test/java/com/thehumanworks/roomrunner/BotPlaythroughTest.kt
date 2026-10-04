package com.thehumanworks.roomrunner

import com.thehumanworks.roomrunner.core.Level
import com.thehumanworks.roomrunner.core.LevelGenerator
import com.thehumanworks.roomrunner.core.Platform
import com.thehumanworks.roomrunner.core.PlatformKind
import com.thehumanworks.roomrunner.core.Runner
import com.thehumanworks.roomrunner.core.RunnerEvent
import com.thehumanworks.roomrunner.core.RunnerInput
import com.thehumanworks.roomrunner.core.Tuning
import com.thehumanworks.roomrunner.core.V3
import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end: a scripted "player" uses the real character controller to hop along the generated
 * course from the spawn to the flag. This checks that level generation and movement tuning agree
 * (gaps clearable, no head-bonks on stepping stones, nothing unreachable).
 */
class BotPlaythroughTest {
  private val dt = 1f / 72f
  private var debug = false

  private fun path(l: Level): List<Platform>? {
    val prev = HashMap<Int, Int>()
    val seen = mutableSetOf(l.startPlatformId)
    val q = ArrayDeque(listOf(l.startPlatformId))
    while (q.isNotEmpty()) {
      val a = l.platform(q.removeFirst())
      if (a.id == l.flagPlatformId) break
      for (b in l.platforms) {
        if (b.id in seen) continue
        if (LevelGenerator.gap(a, b) <= Tuning.STEP_GAP + 0.03f && abs(b.top - a.top) <= Tuning.STEP_RISE + 0.01f) {
          seen += b.id
          prev[b.id] = a.id
          q.addLast(b.id)
        }
      }
    }
    if (l.flagPlatformId !in seen) return null
    val out = mutableListOf(l.platform(l.flagPlatformId))
    var cur = l.flagPlatformId
    while (cur != l.startPlatformId) {
      cur = prev[cur]!!
      out.add(0, l.platform(cur))
    }
    return out
  }

  /** Returns number of falls, or -1 if it got stuck on some hop. */
  private fun playthrough(l: Level): Int {
    val route = path(l) ?: return -1
    val r = Runner(l.spawn)
    repeat(30) { r.step(dt, RunnerInput(), l.platforms, l.floorY) }
    var falls = 0
    for (i in 1 until route.size) {
      val from = route[i - 1]
      val to = route[i]
      var ok = false
      var tries = 0
      while (!ok && tries < 4) {
        tries++
        var jumped = false
        var doubled = false
        for (f in 0 until 72 * 6) {
          val inset = if (to.kind == PlatformKind.STONE) Tuning.STONE_HALF else 0.06f
          val target = if (to.kind == PlatformKind.STONE) to.center else to.closestTopPoint(r.pos, inset)
          val d = (target - r.pos).flat()
          val gain = if (r.grounded) 6f else 4f
          var mv = d * gain
          if (mv.flatLength() > 1f) mv = mv.flatNormalized()
          var press = false
          if (r.grounded) {
            val next = r.pos + V3(mv.x, 0f, mv.z) * (Tuning.MOVE_SPEED * dt * 3f)
            val leaving = !from.containsXZ(next, -0.004f) && r.groundPlatformId == from.id
            val needUp = to.top > r.pos.y + 0.02f && d.flatLength() < 0.16f
            if (r.groundPlatformId == from.id && (leaving || needUp)) {
              press = true
              jumped = true
              doubled = false
            }
          } else if (jumped && !doubled && r.vel.y < 0.25f && (r.pos.y < to.top + 0.06f || d.flatLength() > 0.08f)) {
            press = true
            doubled = true
          }
          val evs = r.step(dt, RunnerInput(mv.x, mv.z, press, true), l.platforms, l.floorY)
          if (RunnerEvent.FELL_TO_FLOOR in evs) {
            falls++
            break
          }
          if (r.grounded && r.groundPlatformId == to.id) {
            ok = true
            break
          }
          // Landed somewhere else (e.g. back on 'from'): try again from here.
          if (r.grounded && r.groundPlatformId != from.id && jumped) {
            if (r.groundPlatformId != to.id) break
          }
        }
        if (!ok && r.groundPlatformId != from.id) {
          // Put the bot back on the 'from' platform for another attempt.
          r.respawn(from.center)
          repeat(20) { r.step(dt, RunnerInput(), l.platforms, l.floorY) }
        }
      }
      if (!ok) {
        if (debug) {
          val above = l.platforms.filter { it.id != to.id && it.id != from.id && it.containsXZ(to.center, 0.1f) && it.bottom > to.top && it.bottom < to.top + 0.4f }
          val tgt = to.closestTopPoint(r.pos, 0.03f)
          val blockers = l.platforms.filter { q -> q.id != from.id && q.id != to.id && (0..20).any { k -> val p = r.pos.lerp(tgt, k / 20f); q.containsXZ(p, Tuning.CHAR_RADIUS) && q.top > r.pos.y + 0.01f && q.bottom < r.pos.y + Tuning.CHAR_HEIGHT + 0.25f } }
          println("  blockers=${blockers.map { "${it.kind}(${it.id}) top=${it.top} bottom=${it.bottom}" }} target=$tgt")
          println("  stuck hop ${from.kind}(${from.id}) top=${from.top} -> ${to.kind}(${to.id}) top=${to.top} gap=${LevelGenerator.gap(from, to)} rise=${to.top - from.top} runner=${r.pos} ground=${r.groundPlatformId} overhead=${above.map { it.kind.toString() + "@" + it.bottom }}")
        }
        return -1
      }
    }
    return falls
  }

  @Test
  fun botClearsLivingRoom() {
    val l = LevelGenerator(7).generate(TestRooms.livingRoom(), 0f, V3(0f, 1.6f, 0f), V3(0f, 0f, 1f)).level
    val falls = playthrough(l)
    println("living room: falls=$falls, route=${path(l)?.size}")
    assertTrue("bot reached the flag", falls >= 0)
  }

  @Test
  fun botClearsManyRooms() {
    var cleared = 0
    var totalFalls = 0
    val n = 120
    val stuck = mutableListOf<Int>()
    for (seed in 0 until n) {
      val l = LevelGenerator(seed.toLong()).generate(TestRooms.randomRoom(seed), 0f, V3(0f, 1.6f, 0f), V3(0f, 0f, 1f)).level
      var f = playthrough(l)
      if (f < 0) {
        debug = true
        println("seed $seed:")
        f = playthrough(l)
        debug = false
      }
      if (f >= 0) {
        cleared++
        totalFalls += f
      } else stuck += seed
    }
    println("bot cleared $cleared/$n random rooms, total falls $totalFalls, stuck seeds: $stuck")
    assertTrue(cleared >= n * 0.85)
  }
}
