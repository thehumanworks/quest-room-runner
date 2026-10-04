package com.thehumanworks.roomrunner.core

import kotlin.math.sqrt

/**
 * Tiny immutable vector type used by all of the pure-Kotlin game logic (level generation,
 * character physics, networking). Kept free of Android / Spatial SDK types so the whole game core
 * can be unit tested on a plain JVM. Convention: +Y is up, the horizontal plane is XZ.
 */
data class V3(val x: Float, val y: Float, val z: Float) {
  operator fun plus(o: V3) = V3(x + o.x, y + o.y, z + o.z)
  operator fun minus(o: V3) = V3(x - o.x, y - o.y, z - o.z)
  operator fun times(s: Float) = V3(x * s, y * s, z * s)
  operator fun div(s: Float) = V3(x / s, y / s, z / s)
  operator fun unaryMinus() = V3(-x, -y, -z)
  fun dot(o: V3) = x * o.x + y * o.y + z * o.z
  fun length() = sqrt(x * x + y * y + z * z)
  fun flat() = V3(x, 0f, z)
  fun flatLength() = sqrt(x * x + z * z)
  fun normalized(): V3 {
    val l = length()
    return if (l < 1e-6f) ZERO else this / l
  }
  fun flatNormalized(): V3 {
    val l = flatLength()
    return if (l < 1e-6f) ZERO else V3(x / l, 0f, z / l)
  }
  fun distFlat(o: V3): Float {
    val dx = x - o.x
    val dz = z - o.z
    return sqrt(dx * dx + dz * dz)
  }
  fun dist(o: V3) = (this - o).length()
  fun withY(ny: Float) = V3(x, ny, z)
  fun lerp(o: V3, t: Float) = V3(x + (o.x - x) * t, y + (o.y - y) * t, z + (o.z - z) * t)

  companion object {
    val ZERO = V3(0f, 0f, 0f)
    val UP = V3(0f, 1f, 0f)
  }
}

fun clampf(v: Float, lo: Float, hi: Float) = if (v < lo) lo else if (v > hi) hi else v

/**
 * Rotates a horizontal vector by a 2D "heading" given as a unit (cos, sin) pair. A heading is
 * stored as the platform's local X axis expressed in world XZ: axisX = (c, 0, s). The local Z axis
 * is then (-s, 0, c). This is purely a convention shared by every peer, so it is independent of
 * the engine's handedness.
 */
object Heading {
  fun localToWorld(lx: Float, lz: Float, c: Float, s: Float): Pair<Float, Float> =
      Pair(lx * c - lz * s, lx * s + lz * c)

  fun worldToLocal(wx: Float, wz: Float, c: Float, s: Float): Pair<Float, Float> =
      Pair(wx * c + wz * s, -wx * s + wz * c)
}
