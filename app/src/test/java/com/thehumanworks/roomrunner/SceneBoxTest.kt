package com.thehumanworks.roomrunner

import com.thehumanworks.roomrunner.core.SceneBox
import com.thehumanworks.roomrunner.core.V3
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneBoxTest {
  /** Rotate local corners by yaw around Y and translate, like an anchor pose would. */
  private fun worldCorners(min: V3, max: V3, yaw: Float, t: V3, zUp: Boolean): List<V3> =
      SceneBox.localCorners(min, max).map { c0 ->
        // Optionally model an anchor whose local Z axis points up (MRUK-style volumes).
        val c = if (zUp) V3(c0.x, c0.z, -c0.y) else c0
        val x = c.x * cos(yaw) - c.z * sin(yaw)
        val z = c.x * sin(yaw) + c.z * cos(yaw)
        V3(x + t.x, c.y + t.y, z + t.z)
      }

  @Test
  fun yUpBox() {
    val p = SceneBox.platformFromCorners(0, worldCorners(V3(-0.5f, -0.75f, -0.3f), V3(0.5f, 0f, 0.3f), 0.4f, V3(1f, 0.75f, 2f), false), "TABLE")
    assertNotNull(p)
    p!!
    assertEquals(0.75f, p.top, 1e-4f)
    assertEquals(0.75f, p.depth, 1e-4f)
    assertEquals(1f, p.center.x, 1e-4f)
    assertEquals(2f, p.center.z, 1e-4f)
    val halves = listOf(p.halfX, p.halfZ).sorted()
    assertEquals(0.3f, halves[0], 1e-3f)
    assertEquals(0.5f, halves[1], 1e-3f)
    // A point on the real table top must be inside, one just outside must not be.
    val inside = V3(1f + 0.45f * cos(0.4f), 0.75f, 2f + 0.45f * sin(0.4f))
    assertTrue(p.containsXZ(inside))
  }

  @Test
  fun zUpVolumeAnchor() {
    // Volume extends 0..-0.42 along local Z (downwards once rotated), anchor at the top face.
    val p = SceneBox.platformFromCorners(1, worldCorners(V3(-0.6f, -0.25f, -0.42f), V3(0.6f, 0.25f, 0f), 1.2f, V3(-1f, 0.42f, 0.5f), true), "COUCH")!!
    assertEquals(0.42f, abs(p.top), 1e-3f)
    assertEquals(0.42f, p.depth, 1e-3f)
    assertEquals(-1f, p.center.x, 1e-3f)
  }
}
