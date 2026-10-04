package com.thehumanworks.roomrunner.view

import android.net.Uri
import com.meta.spatial.core.Color4
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector3
import com.meta.spatial.toolkit.Box
import com.meta.spatial.toolkit.Material
import com.meta.spatial.toolkit.Mesh
import com.meta.spatial.toolkit.MeshCollision
import com.meta.spatial.toolkit.Scale
import com.meta.spatial.toolkit.Sphere
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.Visible
import com.thehumanworks.roomrunner.core.V3
import kotlin.math.sqrt

fun V3.sv() = Vector3(x, y, z)

fun Vector3.v3() = V3(x, y, z)

fun color(hex: Long, a: Float = 1f): Color4 =
    Color4(
        ((hex shr 16) and 0xFF) / 255f,
        ((hex shr 8) and 0xFF) / 255f,
        (hex and 0xFF) / 255f,
        a,
    )

fun mat(hex: Long, unlit: Boolean = false, rough: Float = 0.6f, metal: Float = 0f) =
    Material().apply {
      baseColor = color(hex)
      this.unlit = unlit
      roughness = rough
      metallic = metal
    }

/** Unit sphere (radius 0.5) / unit box primitives, sized with [Scale]. */
fun sphereEntity(m: Material): Entity =
    Entity.create(
        listOf(
            Mesh(Uri.parse("mesh://sphere"), MeshCollision.NoCollision),
            Sphere(0.5f),
            m,
            Transform(Pose()),
            Scale(Vector3(0.01f)),
            Visible(true),
        ))

fun boxEntity(m: Material, min: Vector3 = Vector3(-0.5f), max: Vector3 = Vector3(0.5f)): Entity =
    Entity.create(
        listOf(
            Mesh(Uri.parse("mesh://box"), MeshCollision.NoCollision),
            Box(min, max),
            m,
            Transform(Pose()),
            Scale(Vector3(1f)),
            Visible(true),
        ))

/** One primitive in a model: local offset (model space, +Z = facing) and size in metres. */
class Part(val entity: Entity, val offset: V3, val size: V3, val spin: Boolean = false)

/**
 * A model made of primitives whose world transforms we compute every frame ourselves (no
 * transform hierarchy), so squash-and-stretch can scale offsets and sizes together.
 */
open class Model {
  val parts = mutableListOf<Part>()
  private var visible = true

  fun sphere(hex: Long, offset: V3, size: V3, unlit: Boolean = false, rough: Float = 0.5f): Part {
    val p = Part(sphereEntity(mat(hex, unlit, rough)), offset, size)
    parts += p
    return p
  }

  fun box(hex: Long, offset: V3, size: V3, unlit: Boolean = false): Part {
    val p = Part(boxEntity(mat(hex, unlit)), offset, size)
    parts += p
    return p
  }

  fun setVisible(v: Boolean) {
    if (v == visible) return
    visible = v
    parts.forEach { it.entity.setComponent(Visible(v)) }
  }

  /**
   * Places the model with its feet at [pos], facing horizontal direction [facing], with vertical
   * squash factor [squash] (volume preserving) and an extra uniform [scale].
   */
  fun place(pos: V3, facing: V3, squash: Float = 1f, scale: Float = 1f, extraRot: Quaternion? = null) {
    val f = facing.flatNormalized().let { if (it == V3.ZERO) V3(0f, 0f, 1f) else it }
    var q = Quaternion.lookRotationAroundY(f.sv())
    if (extraRot != null) q = q * extraRot
    val sxz = scale / sqrt(squash)
    val sy = scale * squash
    for (p in parts) {
      val local = Vector3(p.offset.x * sxz, p.offset.y * sy, p.offset.z * sxz)
      val world = pos.sv() + (q * local)
      p.entity.setComponent(Transform(Pose(world, q)))
      p.entity.setComponent(Scale(Vector3(p.size.x * sxz, p.size.y * sy, p.size.z * sxz)))
    }
  }

  fun destroy() {
    parts.forEach { it.entity.destroy() }
    parts.clear()
  }
}

/** The tiny hero (~13 cm): body, head, eyes, cap with brim, feet. */
class RunnerModel(capHex: Long, bodyHex: Long) : Model() {
  private val footL: Part
  private val footR: Part

  init {
    footL = box(0x4E342E, V3(-0.016f, 0.008f, 0.004f), V3(0.020f, 0.016f, 0.030f))
    footR = box(0x4E342E, V3(0.016f, 0.008f, 0.004f), V3(0.020f, 0.016f, 0.030f))
    sphere(bodyHex, V3(0f, 0.042f, 0f), V3(0.060f, 0.060f, 0.055f)) // body / overalls
    sphere(capHex, V3(0f, 0.060f, 0.004f), V3(0.052f, 0.030f, 0.048f)) // shirt
    sphere(0xFFE0BD, V3(0f, 0.093f, 0f), V3(0.058f, 0.056f, 0.056f)) // head
    sphere(0xFFFFFF, V3(-0.012f, 0.098f, 0.024f), V3(0.014f, 0.018f, 0.010f), unlit = true)
    sphere(0xFFFFFF, V3(0.012f, 0.098f, 0.024f), V3(0.014f, 0.018f, 0.010f), unlit = true)
    sphere(0x111111, V3(-0.012f, 0.098f, 0.029f), V3(0.007f, 0.010f, 0.005f), unlit = true)
    sphere(0x111111, V3(0.012f, 0.098f, 0.029f), V3(0.007f, 0.010f, 0.005f), unlit = true)
    sphere(0xF4A582, V3(0f, 0.088f, 0.029f), V3(0.012f, 0.010f, 0.010f)) // nose
    sphere(capHex, V3(0f, 0.115f, -0.002f), V3(0.060f, 0.030f, 0.060f)) // cap dome
    box(capHex, V3(0f, 0.110f, 0.026f), V3(0.044f, 0.005f, 0.026f)) // brim
  }

  private val baseL = footL.offset
  private val baseR = footR.offset

  /** Little walk cycle: feet shuffle back and forth. */
  fun animate(walkPhase: Float, moving: Boolean) {
    val s = if (moving) kotlin.math.sin(walkPhase) * 0.010f else 0f
    footOffset(footL, baseL, s)
    footOffset(footR, baseR, -s)
  }

  private fun footOffset(p: Part, base: V3, dz: Float) {
    val i = parts.indexOf(p)
    if (i >= 0) parts[i] = Part(p.entity, V3(base.x, base.y, base.z + dz), p.size)
  }
}

/** Goomba-ish blob: brown dome, angry eyes, little feet. */
class BlobModel : Model() {
  init {
    box(0x3E2723, V3(-0.014f, 0.006f, 0.004f), V3(0.020f, 0.012f, 0.026f))
    box(0x3E2723, V3(0.014f, 0.006f, 0.004f), V3(0.020f, 0.012f, 0.026f))
    sphere(0x8D5524, V3(0f, 0.032f, 0f), V3(0.068f, 0.052f, 0.064f))
    sphere(0xF5DEB3, V3(0f, 0.020f, 0.012f), V3(0.044f, 0.024f, 0.044f))
    sphere(0xFFFFFF, V3(-0.011f, 0.040f, 0.028f), V3(0.014f, 0.018f, 0.008f), unlit = true)
    sphere(0xFFFFFF, V3(0.011f, 0.040f, 0.028f), V3(0.014f, 0.018f, 0.008f), unlit = true)
    sphere(0x000000, V3(-0.010f, 0.038f, 0.032f), V3(0.007f, 0.010f, 0.004f), unlit = true)
    sphere(0x000000, V3(0.010f, 0.038f, 0.032f), V3(0.007f, 0.010f, 0.004f), unlit = true)
    box(0x1B0F0A, V3(-0.011f, 0.051f, 0.029f), V3(0.016f, 0.004f, 0.004f)) // brows
    box(0x1B0F0A, V3(0.011f, 0.051f, 0.029f), V3(0.016f, 0.004f, 0.004f))
  }
}

/** Spinning gold coin (a flattened sphere with a darker rim). */
class CoinModel : Model() {
  init {
    sphere(0xFFC107, V3(0f, 0f, 0f), V3(0.044f, 0.044f, 0.010f), rough = 0.25f)
    sphere(0xFFE082, V3(0f, 0f, 0f), V3(0.026f, 0.026f, 0.012f), unlit = true)
  }
}

/** Goal: flag pole, waving red flag and a spinning star on top. */
class FlagModel : Model() {
  init {
    box(0xEEEEEE, V3(0f, 0.15f, 0f), V3(0.008f, 0.30f, 0.008f))
    box(0x2E7D32, V3(0f, 0.006f, 0f), V3(0.07f, 0.012f, 0.07f))
    box(0xE53935, V3(0.035f, 0.26f, 0f), V3(0.065f, 0.045f, 0.004f), unlit = true)
    sphere(0xFFEB3B, V3(0f, 0.32f, 0f), V3(0.045f, 0.045f, 0.045f), unlit = true)
  }
}

/** The other player's head: a small floating helmet with a visor and their colour. */
class HeadAvatarModel(hex: Long) : Model() {
  init {
    sphere(hex, V3(0f, 0f, 0f), V3(0.12f, 0.13f, 0.13f))
    box(0x101010, V3(0f, 0.01f, 0.052f), V3(0.10f, 0.035f, 0.03f), unlit = true)
    sphere(0x80DEEA, V3(-0.022f, 0.012f, 0.067f), V3(0.016f, 0.016f, 0.006f), unlit = true)
    sphere(0x80DEEA, V3(0.022f, 0.012f, 0.067f), V3(0.016f, 0.016f, 0.006f), unlit = true)
  }
}

/** Soft dark disc under a character so you can judge where it will land. */
class ShadowModel : Model() {
  init {
    sphere(0x202020, V3(0f, 0f, 0f), V3(0.05f, 0.003f, 0.05f), unlit = true)
  }
}
