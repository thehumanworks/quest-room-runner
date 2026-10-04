package com.thehumanworks.roomrunner.view

import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector3
import com.meta.spatial.toolkit.Scale
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.Visible
import com.thehumanworks.roomrunner.core.Level
import com.thehumanworks.roomrunner.core.Platform
import com.thehumanworks.roomrunner.core.PlatformKind
import com.thehumanworks.roomrunner.core.V3
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** All the entities for one generated level. */
class LevelView(val level: Level) {
  private val statics = mutableListOf<Entity>()
  val coins = HashMap<Int, CoinModel>()
  val enemies = HashMap<Int, BlobModel>()
  val flag = FlagModel()

  init {
    for (p in level.platforms) buildPlatform(p)
    for (c in level.coins) coins[c.id] = CoinModel()
    for (e in level.enemies) enemies[e.id] = BlobModel()
    flag.place(level.flag, V3(0f, 0f, 1f))
  }

  private fun rotationOf(p: Platform): Quaternion =
      // Platform local Z axis in world = (-sin, 0, cos). Boxes are symmetric, so handedness of
      // the derived local X axis does not matter.
      Quaternion.lookRotationAroundY(Vector3(-p.sinA, 0f, p.cosA))

  private fun boxAt(hex: Long, center: V3, q: Quaternion, half: Vector3, yMin: Float, yMax: Float, unlit: Boolean = false) {
    val e = boxEntity(mat(hex, unlit), Vector3(-half.x, yMin, -half.z), Vector3(half.x, yMax, half.z))
    e.setComponent(Transform(Pose(center.sv(), q)))
    statics += e
  }

  private fun buildPlatform(p: Platform) {
    val q = rotationOf(p)
    when (p.kind) {
      PlatformKind.FURNITURE -> {
        // Real furniture stays real (passthrough); we just outline its top with glowing rails so
        // the player can see it is part of the course.
        val hx = p.halfX
        val hz = p.halfZ
        val t = 0.010f
        val h = 0.008f
        val railHex = 0xFFD54FL
        boxAt(railHex, p.toWorld(0f, 0f, hz - t / 2), q, Vector3(hx, 0f, t / 2), 0f, h, true)
        boxAt(railHex, p.toWorld(0f, 0f, -hz + t / 2), q, Vector3(hx, 0f, t / 2), 0f, h, true)
        boxAt(railHex, p.toWorld(hx - t / 2, 0f, 0f), q, Vector3(t / 2, 0f, hz), 0f, h, true)
        boxAt(railHex, p.toWorld(-hx + t / 2, 0f, 0f), q, Vector3(t / 2, 0f, hz), 0f, h, true)
        // Corner studs.
        for (sx in listOf(-1f, 1f)) for (sz in listOf(-1f, 1f)) {
          boxAt(0xFF7043L, p.toWorld(sx * (hx - t), 0f, sz * (hz - t)), q, Vector3(t, 0f, t), 0f, h * 1.8f, true)
        }
      }
      PlatformKind.VIRTUAL -> {
        // Mario-style floating block: grass top on a brick body.
        boxAt(0x43A047L, p.center, q, Vector3(p.halfX, 0f, p.halfZ), -0.012f, 0f)
        boxAt(0xA1662FL, p.center, q, Vector3(p.halfX * 0.97f, 0f, p.halfZ * 0.97f), -p.depth, -0.012f)
      }
      PlatformKind.STONE -> {
        boxAt(0xFFB300L, p.center, q, Vector3(p.halfX, 0f, p.halfZ), -p.depth, 0f)
        boxAt(0xFFF59DL, p.center, q, Vector3(p.halfX * 0.55f, 0f, p.halfZ * 0.55f), 0f, 0.002f, true)
      }
    }
  }

  fun destroy() {
    statics.forEach { it.destroy() }
    statics.clear()
    coins.values.forEach { it.destroy() }
    enemies.values.forEach { it.destroy() }
    flag.destroy()
  }
}

/** Celebration burst of little coloured cubes. */
class Confetti(origin: V3) {
  private class P(val e: Entity, var pos: V3, var vel: V3, val spin: Float, val size: Float)
  private val parts = mutableListOf<P>()
  private var age = 0f
  private val colors = listOf(0xE53935L, 0xFDD835L, 0x43A047L, 0x1E88E5L, 0x8E24AAL, 0xFB8C00L, 0xFFFFFFL)

  init {
    val r = Random(System.nanoTime())
    repeat(90) {
      val a = r.nextFloat() * 6.283f
      val sp = 0.3f + r.nextFloat() * 0.6f
      val e = boxEntity(mat(colors[it % colors.size], unlit = true))
      parts += P(e, origin + V3(0f, 0.3f, 0f), V3(cos(a) * sp, 1.0f + r.nextFloat() * 1.2f, sin(a) * sp), r.nextFloat() * 20f - 10f, 0.008f + r.nextFloat() * 0.01f)
    }
  }

  /** Returns false when finished (and cleaned up). */
  fun update(dt: Float): Boolean {
    age += dt
    for (p in parts) {
      p.vel = V3(p.vel.x * (1f - 0.8f * dt), p.vel.y - 2.2f * dt, p.vel.z * (1f - 0.8f * dt))
      p.pos = p.pos + p.vel * dt
      val q = Quaternion(age * p.spin * 30f, age * p.spin * 20f, 0f)
      p.e.setComponent(Transform(Pose(p.pos.sv(), q)))
      p.e.setComponent(Scale(Vector3(p.size, p.size * 0.4f, p.size)))
    }
    if (age > 4f) {
      parts.forEach { it.e.destroy() }
      parts.clear()
      return false
    }
    return true
  }

  fun destroy() {
    parts.forEach { it.e.destroy() }
    parts.clear()
  }
}

fun Entity.show(v: Boolean) = setComponent(Visible(v))
