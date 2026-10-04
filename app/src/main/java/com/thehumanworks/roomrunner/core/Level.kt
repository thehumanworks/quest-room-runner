package com.thehumanworks.roomrunner.core

/** What a platform is, mostly so the renderer can draw it differently. */
enum class PlatformKind(val code: Int) {
  /** A real piece of furniture from the room scan (table top, couch, bed, storage...). */
  FURNITURE(0),
  /** A virtual floating platform (used when there is no scan, or to pad out a sparse room). */
  VIRTUAL(1),
  /** A small floating stepping stone bridging a gap between two other platforms. */
  STONE(2);

  companion object {
    fun of(code: Int) = entries.first { it.code == code }
  }
}

/**
 * An oriented box the character can stand on. [center] is the centre of the TOP face. The box
 * extends [halfX]/[halfZ] along its local axes (local X axis = (cosA, 0, sinA) in world XZ) and
 * [depth] metres downward from the top face.
 */
data class Platform(
    val id: Int,
    val kind: PlatformKind,
    val center: V3,
    val halfX: Float,
    val halfZ: Float,
    val depth: Float,
    val cosA: Float = 1f,
    val sinA: Float = 0f,
    val label: String = "",
) {
  val top: Float
    get() = center.y

  val bottom: Float
    get() = center.y - depth

  val area: Float
    get() = 4f * halfX * halfZ

  /** Converts a world point to this platform's local horizontal frame (origin = top centre). */
  fun toLocal(p: V3): V3 {
    val (lx, lz) = Heading.worldToLocal(p.x - center.x, p.z - center.z, cosA, sinA)
    return V3(lx, p.y - center.y, lz)
  }

  fun toWorld(lx: Float, ly: Float, lz: Float): V3 {
    val (wx, wz) = Heading.localToWorld(lx, lz, cosA, sinA)
    return V3(center.x + wx, center.y + ly, center.z + wz)
  }

  /** True if the world point's horizontal position lies over the top face (with [margin]). */
  fun containsXZ(p: V3, margin: Float = 0f): Boolean {
    val l = toLocal(p)
    return kotlin.math.abs(l.x) <= halfX + margin && kotlin.math.abs(l.z) <= halfZ + margin
  }

  /** Closest point on the top-face rectangle (inset by [inset]) to the world point [p]. */
  fun closestTopPoint(p: V3, inset: Float = 0f): V3 {
    val l = toLocal(p)
    val ix = (halfX - inset).coerceAtLeast(0f)
    val iz = (halfZ - inset).coerceAtLeast(0f)
    return toWorld(clampf(l.x, -ix, ix), 0f, clampf(l.z, -iz, iz))
  }

  /** The longest local axis as a world direction, and its half length. */
  fun longAxis(): Pair<V3, Float> =
      if (halfX >= halfZ) Pair(V3(cosA, 0f, sinA), halfX) else Pair(V3(-sinA, 0f, cosA), halfZ)
}

data class Coin(val id: Int, val pos: V3, val platformId: Int = -1, val high: Boolean = false)

/** A patrolling blob. Its position is a pure function of match time (see [Enemy.positionAt]). */
data class Enemy(
    val id: Int,
    val platformId: Int,
    val a: V3,
    val b: V3,
    val speed: Float,
    val phase: Float,
) {
  val length: Float
    get() = a.dist(b)

  /** Deterministic ping-pong patrol, so host and guest agree given only the shared clock. */
  fun positionAt(t: Float): V3 {
    val len = length
    if (len < 1e-4f) return a
    val period = 2f * len / speed
    var u = ((t + phase) % period + period) % period
    u /= period // 0..1
    val f = if (u < 0.5f) u * 2f else (1f - u) * 2f
    return a.lerp(b, f)
  }

  /** Direction of travel at time t (for facing the blob the right way). */
  fun headingAt(t: Float): V3 {
    val p0 = positionAt(t)
    val p1 = positionAt(t + 0.05f)
    val d = (p1 - p0).flatNormalized()
    return if (d == V3.ZERO) (b - a).flatNormalized() else d
  }
}

/**
 * A complete level: generated at runtime from the room (or procedurally) by [LevelGenerator].
 * In two-player mode the host serialises this (in the shared, aligned frame) and sends it to the
 * guest, so both headsets play exactly the same level.
 */
data class Level(
    val platforms: List<Platform>,
    val coins: List<Coin>,
    val enemies: List<Enemy>,
    val startPlatformId: Int,
    val spawn: V3,
    val spawn2: V3,
    val flag: V3,
    val flagPlatformId: Int,
    val floorY: Float,
    val fromRoomScan: Boolean,
) {
  fun platform(id: Int) = platforms.first { it.id == id }
}
