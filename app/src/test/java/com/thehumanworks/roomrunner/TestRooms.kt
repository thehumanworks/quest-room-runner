package com.thehumanworks.roomrunner

import com.thehumanworks.roomrunner.core.Platform
import com.thehumanworks.roomrunner.core.PlatformKind
import com.thehumanworks.roomrunner.core.V3
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

object TestRooms {
  fun box(id: Int, label: String, cx: Float, top: Float, cz: Float, hx: Float, hz: Float, yawRad: Float = 0f) =
      Platform(id, PlatformKind.FURNITURE, V3(cx, top, cz), hx, hz, top, cos(yawRad), sin(yawRad), label)

  /** A plausible UK living room, floor at y=0, player standing at the origin facing +Z. */
  fun livingRoom() =
      listOf(
          box(0, "COUCH", 0.0f, 0.85f, 2.2f, 1.0f, 0.45f),
          box(1, "TABLE", 0.1f, 0.42f, 1.2f, 0.5f, 0.3f, 0.1f), // coffee table
          box(2, "STORAGE", -1.8f, 0.55f, 0.4f, 0.25f, 0.8f), // TV unit
          box(3, "STORAGE", -1.9f, 1.8f, 1.9f, 0.2f, 0.4f), // tall bookshelf
          box(4, "TABLE", 1.8f, 0.75f, -0.6f, 0.6f, 0.45f, 0.3f), // dining table
          box(5, "OTHER", 1.0f, 0.3f, 1.9f, 0.15f, 0.15f), // pouffe
      )

  fun randomRoom(seed: Int): List<Platform> {
    val r = Random(seed)
    val n = r.nextInt(0, 9)
    val out = mutableListOf<Platform>()
    var tries = 0
    while (out.size < n && tries < 200) {
      tries++
      val cand = box(
          out.size,
          listOf("TABLE", "COUCH", "BED", "STORAGE", "OTHER")[r.nextInt(5)],
          r.nextFloat() * 5f - 2.5f,
          0.2f + r.nextFloat() * 1.6f,
          r.nextFloat() * 5f - 2.5f,
          0.15f + r.nextFloat() * 0.8f,
          0.15f + r.nextFloat() * 0.6f,
          r.nextFloat() * 3.1f,
      )
      // Real furniture doesn't interpenetrate: reject heavily overlapping footprints.
      val overlaps = out.any { o ->
        o.center.distFlat(cand.center) < minOf(o.halfX, o.halfZ) + minOf(cand.halfX, cand.halfZ)
      }
      if (!overlaps) out += cand
    }
    return out
  }
}
