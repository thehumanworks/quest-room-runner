package com.thehumanworks.roomrunner.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Builds a playable level from whatever the room scan gave us. Every room produces a different
 * level:
 * 1. Real furniture tops (tables, couch, bed, storage...) become platforms.
 * 2. If the room is sparse (or there is no scan at all) virtual floating platforms are added
 *    around the player at furniture-like heights.
 * 3. Platforms are linked with a minimum spanning tree; any link too long or too steep for a
 *    single jump gets floating stepping stones (in a helix when the climb is steep).
 * 4. Coins go on surfaces, over stepping stones and floating high up (double-jump coins), the goal
 *    flag goes on the highest surface and patrolling blobs go on the larger surfaces.
 */
class LevelGenerator(seed: Long = 1234L) {
  private val rnd = Random(seed)

  data class Result(val level: Level, val log: List<String>)

  fun generate(
      furnitureIn: List<Platform>,
      floorY: Float,
      playerHead: V3,
      playerForward: V3,
  ): Result {
    val log = mutableListOf<String>()
    val fwd = playerForward.flatNormalized().let { if (it == V3.ZERO) V3(0f, 0f, 1f) else it }

    // 1. Usable furniture surfaces.
    var furniture =
        furnitureIn
            .filter {
              val rise = it.top - floorY
              rise in 0.10f..1.9f && it.halfX >= 0.05f && it.halfZ >= 0.05f
            }
            .sortedBy { it.center.distFlat(playerHead) }
            .take(14)
    // Drop surfaces hidden underneath a taller piece (e.g. a box under a table or desk).
    furniture =
        furniture.filter { a ->
          furniture.none { b -> b !== a && b.top > a.top + 0.05f && b.containsXZ(a.center) }
        }
    log += "furniture usable: ${furniture.size} of ${furnitureIn.size}"

    val platforms = mutableListOf<Platform>()
    var nextId = 0
    for (f in furniture) platforms += f.copy(id = nextId++, kind = PlatformKind.FURNITURE)

    // 2. Pad with virtual floating platforms.
    val wanted = when {
      platforms.isEmpty() -> 7
      platforms.size < 3 -> 5
      else -> platforms.size
    }
    var attempts = 0
    var ring = 0
    while (platforms.size < wanted && attempts < 400) {
      attempts++
      val i = ring
      val angle = (i * 0.95f) + rnd.nextFloat() * 0.4f - 0.2f
      val radius = 0.6f + 0.13f * i + rnd.nextFloat() * 0.25f
      val dir = rotateFlat(fwd, angle)
      val c = playerHead.flat() + dir * radius
      val rise = clampf(0.32f + 0.14f * i + rnd.nextFloat() * 0.06f, 0.25f, 1.45f)
      val hx = 0.11f + rnd.nextFloat() * 0.08f
      val hz = 0.11f + rnd.nextFloat() * 0.08f
      val yaw = rnd.nextFloat() * PI.toFloat()
      val cand =
          Platform(
              id = nextId,
              kind = PlatformKind.VIRTUAL,
              center = V3(c.x, floorY + rise, c.z),
              halfX = hx,
              halfZ = hz,
              depth = 0.04f,
              cosA = cos(yaw),
              sinA = sin(yaw),
              label = "VIRTUAL",
          )
      val clear =
          platforms.none { p ->
            val r1 = max(p.halfX, p.halfZ)
            val r2 = max(hx, hz)
            p.center.distFlat(cand.center) < r1 + r2 + 0.10f &&
                abs(p.top - cand.top) < 0.5f
          } && cand.center.distFlat(playerHead) > 0.45f
      if (clear) {
        platforms += cand
        nextId++
        ring++
      } else if (attempts % 12 == 0) {
        ring++ // give up on this slot, try further out
      }
    }
    log += "platforms after padding: ${platforms.size}"

    // 3. Start platform: near, low and preferably in front of the player.
    val start =
        platforms.minByOrNull { p ->
          val to = (p.center - playerHead).flat()
          val d = to.flatLength()
          val facing = if (d > 1e-3f) to.flatNormalized().dot(fwd) else 0f
          d + 0.6f * (p.top - floorY) - 0.35f * facing
        }!!

    // 4+5. Grow a spanning tree from the start platform, cheapest jump-cost link first. Each link
    //    that is too long or too steep for one jump gets stepping stones (built by a greedy walk:
    //    each stone within one comfortable jump of the previous one, rising at most STEP_RISE,
    //    never buried in / tucked just under furniture, keeping head room clear of other stones;
    //    steep climbs naturally become spiral staircases). If a link can't be bridged we try it
    //    in reverse, then the next-best link; surfaces that still can't be reached are left out.
    val stones = mutableListOf<Platform>()
    val inTree = mutableSetOf(start.id)
    val failed = mutableSetOf<Pair<Int, Int>>()
    var links = 0
    while (inTree.size < platforms.size) {
      val cands =
          platforms
              .filter { it.id in inTree }
              .flatMap { a -> platforms.filter { it.id !in inTree }.map { b -> a to b } }
              .filter { (a, b) -> (a.id to b.id) !in failed }
              .sortedBy { (a, b) -> gap(a, b) + 1.2f * abs(a.top - b.top) }
      if (cands.isEmpty()) break
      val (a, b) = cands.first()
      val bridge = buildBridge(a, b, platforms, stones, fwd) ?: buildBridge(b, a, platforms, stones, fwd)
      if (bridge == null) {
        failed += a.id to b.id
        continue
      }
      for (st in bridge) stones += st.copy(id = nextId++)
      inTree += b.id
      links++
    }
    val unreachable = platforms.filter { it.id !in inTree }
    if (unreachable.isNotEmpty()) {
      log += "left out (unreachable): ${unreachable.map { it.label }}"
      platforms.removeAll { it.id !in inTree }
    }
    log += "links: $links, stepping stones: ${stones.size}"
    val all = platforms + stones

    // 6. Goal flag on the highest (non-start if possible) surface.
    val flagPlat =
        platforms.filter { it.id != start.id }.maxByOrNull { it.top }
            ?: start
    val flagPos = flagPlat.center

    // 7. Spawn points.
    val (axis, half) = start.longAxis()
    val off = min(0.06f, half * 0.5f)
    val spawn = start.center + axis * (-off)
    val spawn2 = start.center + axis * off

    // 8. Coins.
    val coins = mutableListOf<Coin>()
    var coinId = 0
    fun addCoin(p: V3, plat: Int, high: Boolean = false) {
      if (coins.size >= 44) return
      if (coins.any { it.pos.dist(p) < 0.07f }) return
      if (p.distFlat(flagPos) < 0.08f && abs(p.y - flagPos.y) < 0.3f) return
      if (p.distFlat(spawn) < 0.05f && abs(p.y - spawn.y) < 0.15f) return
      coins += Coin(coinId++, p, plat, high)
    }
    for (p in platforms) {
      val (ax, h) = p.longAxis()
      val n = if (p.id == start.id) 1 else clampInt((p.area / 0.05f).toInt(), 1, 5)
      val span = (h - 0.05f).coerceAtLeast(0f)
      for (k in 0 until n) {
        val t = if (n == 1) 0f else -span + 2f * span * k / (n - 1)
        val base = p.center + ax * t
        val pos = if (p.id == start.id) p.center + ax * (min(0.1f, h - 0.03f).coerceAtLeast(0f)) else base
        addCoin(pos.withY(p.top + 0.05f), p.id)
      }
    }
    stones.forEachIndexed { i, s -> if (i % 2 == 0) addCoin(s.center.withY(s.top + 0.07f), s.id) }
    val highCands =
        platforms.filter { it.area >= 0.03f && it.id != start.id }.shuffled(rnd).take(3)
    for (p in highCands) addCoin(p.center.withY(p.top + 0.34f), p.id, high = true)
    // Make sure there is always something to chase.
    var pad = 0
    while (coins.size < 12 && pad < 40) {
      val p = platforms[pad % platforms.size]
      val ang = pad * 1.7f
      val rr = min(p.halfX, p.halfZ) * 0.6f
      addCoin(p.toWorld(cos(ang) * rr, 0.05f, sin(ang) * rr), p.id)
      pad++
    }
    log += "coins: ${coins.size}"

    // 9. Patrolling blobs on the larger surfaces.
    val enemyCands =
        platforms
            .filter { it.id != start.id && it.longAxis().second >= 0.2f }
            .sortedByDescending { it.area }
    val enemyCount = min(if (platforms.size > 8) 3 else 2, enemyCands.size)
    val enemies =
        enemyCands.take(enemyCount).mapIndexed { i, p ->
          val (ax, h) = p.longAxis()
          val l = h - 0.06f
          Enemy(
              id = i,
              platformId = p.id,
              a = p.center + ax * (-l),
              b = p.center + ax * l,
              speed = 0.10f + rnd.nextFloat() * 0.05f,
              phase = rnd.nextFloat() * 5f,
          )
        }
    log += "enemies: ${enemies.size}"

    return Result(
        Level(
            platforms = all,
            coins = coins,
            enemies = enemies,
            startPlatformId = start.id,
            spawn = spawn,
            spawn2 = spawn2,
            flag = flagPos,
            flagPlatformId = flagPlat.id,
            floorY = floorY,
            fromRoomScan = furniture.isNotEmpty(),
        ),
        log,
    )
  }

  /** Stepping stones from [a] to [b] (ids are placeholders), empty if none needed, null if stuck. */
  private fun buildBridge(
      a: Platform,
      b: Platform,
      platforms: List<Platform>,
      existing: List<Platform>,
      fwd: V3,
  ): List<Platform>? {
    var pa = a.closestTopPoint(b.center, 0.03f)
    val pb0 = b.closestTopPoint(pa, 0.03f)
    pa = a.closestTopPoint(pb0, 0.03f)
    if (pa.distFlat(pb0) <= Tuning.STEP_GAP && abs(b.top - a.top) <= Tuning.STEP_RISE) return emptyList()
    val out = mutableListOf<Platform>()
    var cur = pa
    var curHalf = 0f
    while (out.size < 24 && existing.size + out.size < 100) {
      val pb = b.closestTopPoint(cur, 0.03f)
      val gapToB = (pb.distFlat(cur) - curHalf).coerceAtLeast(0f)
      if (gapToB <= Tuning.STEP_GAP && abs(b.top - cur.y) <= Tuning.STEP_RISE) return out
      val dyLeft = b.top - cur.y
      val ny = cur.y + clampf(dyLeft, -Tuning.STEP_RISE * 0.95f, Tuning.STEP_RISE * 0.95f)
      val toward = (pb - cur).flatNormalized().let { if (it == V3.ZERO) fwd else it }
      val horizLeft = pb.distFlat(cur)
      var best: V3? = null
      var bestScore = Float.MAX_VALUE
      val all = existing + out
      for (ring in listOf(0.27f, 0.22f, 0.31f, 0.18f)) {
        for (k in 0 until 24) {
          val ang = k * (PI.toFloat() / 12f)
          val dir = rotateFlat(toward, ang)
          val r = if (k == 0) min(ring, max(horizLeft - Tuning.STONE_HALF, 0.17f)) else ring
          val c = cur + dir * r
          val cand = V3(c.x, ny, c.z)
          if (!stoneFits(cand, platforms, all)) continue
          // Prefer progress towards B; small penalty for turning (keeps paths tidy).
          val turn = if (k > 12) 24 - k else k
          val score = cand.distFlat(b.closestTopPoint(cand)) + 0.015f * turn
          if (score < bestScore) {
            bestScore = score
            best = cand
          }
        }
        if (best != null) break
      }
      val pos = best ?: return null
      val heading = (pos - cur).flatNormalized().let { if (it == V3.ZERO) V3(1f, 0f, 0f) else it }
      out +=
          Platform(
              id = -1,
              kind = PlatformKind.STONE,
              center = pos,
              halfX = Tuning.STONE_HALF,
              halfZ = Tuning.STONE_HALF,
              depth = Tuning.STONE_DEPTH,
              cosA = heading.x,
              sinA = heading.z,
              label = "STONE",
          )
      cur = pos
      curHalf = Tuning.STONE_HALF
    }
    return null
  }

  companion object {
    /** Can the player stand on a stone at [c] without clipping furniture or other stones? */
    fun stoneFits(c: V3, platforms: List<Platform>, stones: List<Platform>): Boolean {
      val clearance = Tuning.STONE_HALF + Tuning.CHAR_RADIUS + 0.01f
      for (q in platforms) {
        if (!q.containsXZ(c, clearance)) continue
        // Stone inside the furniture box, or so close above its top that you'd bump your head.
        if (c.y > q.bottom - Tuning.CHAR_HEIGHT - 0.06f && c.y < q.top + Tuning.CHAR_HEIGHT + 0.03f) return false
      }
      for (s in stones) {
        val d = s.center.distFlat(c)
        if (d < 2f * Tuning.STONE_HALF + 0.03f && abs(s.top - c.y) < 0.33f) return false
      }
      return true
    }

    /** Horizontal edge-to-edge distance between two platform tops (approximate). */
    fun gap(a: Platform, b: Platform): Float {
      var pa = a.closestTopPoint(b.center)
      val pb = b.closestTopPoint(pa)
      pa = a.closestTopPoint(pb)
      return pa.distFlat(pb)
    }

    fun rotateFlat(v: V3, angle: Float): V3 {
      val c = cos(angle)
      val s = sin(angle)
      return V3(v.x * c - v.z * s, 0f, v.x * s + v.z * c)
    }

    fun clampInt(v: Int, lo: Int, hi: Int) = if (v < lo) lo else if (v > hi) hi else v
  }
}
