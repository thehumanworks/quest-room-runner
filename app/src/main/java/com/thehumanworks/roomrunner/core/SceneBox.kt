package com.thehumanworks.roomrunner.core

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Converts a scene-understanding volume (8 world-space corners of an oriented box, as produced by
 * transforming an MRUK anchor's local volume bounds by the anchor pose) into a walkable
 * [Platform] (top face). This is deliberately convention-agnostic: it does not assume which local
 * axis of the anchor points up, it finds it from the corners.
 */
object SceneBox {
  fun platformFromCorners(
      id: Int,
      corners: List<V3>,
      label: String,
      kind: PlatformKind = PlatformKind.FURNITURE,
  ): Platform? {
    if (corners.size != 8) return null
    // Corners are expected in binary order: index bit0 -> x(min/max), bit1 -> y, bit2 -> z.
    val c0 = corners[0]
    val ex = corners[1] - c0
    val ey = corners[2] - c0
    val ez = corners[4] - c0
    val axes = listOf(ex, ey, ez)
    // The "up" axis is the one most aligned with world Y.
    val upIdx = axes.indices.maxByOrNull { abs(axes[it].y) / (axes[it].length() + 1e-6f) }!!
    val horiz = axes.indices.filter { it != upIdx }.map { axes[it] }
    val up = axes[upIdx]
    val height = abs(up.y)
    if (height < 1e-3f) return null
    val minY = corners.minOf { it.y }
    val maxY = corners.maxOf { it.y }
    val centerAll = corners.fold(V3.ZERO) { a, b -> a + b } / 8f
    val a = horiz[0].flat()
    val b = horiz[1].flat()
    val la = a.flatLength()
    val lb = b.flatLength()
    if (la < 0.02f || lb < 0.02f) return null
    val dir = a / la
    val c = dir.x
    val s = dir.z
    return Platform(
        id = id,
        kind = kind,
        center = V3(centerAll.x, maxY, centerAll.z),
        halfX = la / 2f,
        halfZ = lb / 2f,
        depth = maxY - minY,
        cosA = c,
        sinA = s,
        label = label,
    )
  }

  /** Builds the 8 corners of an axis-aligned local box, in the bit order expected above. */
  fun localCorners(min: V3, max: V3): List<V3> =
      (0 until 8).map { i ->
        V3(
            if (i and 1 == 0) min.x else max.x,
            if (i and 2 == 0) min.y else max.y,
            if (i and 4 == 0) min.z else max.z,
        )
      }

  fun norm2(x: Float, z: Float) = sqrt(x * x + z * z)
}
