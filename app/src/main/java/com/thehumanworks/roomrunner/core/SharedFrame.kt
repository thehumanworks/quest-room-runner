package com.thehumanworks.roomrunner.core

/**
 * Colocation without cloud anchors: each headset marks the SAME physical spot and direction
 * (e.g. both players put their right controller on the same table corner, pointing along the same
 * edge, and press the calibrate button). That gives each headset an [origin] and horizontal
 * [forward] in its own tracking space. Everything sent over the network is expressed in this
 * shared frame, so a point converted local(A) -> shared -> local(B) lands on the same physical
 * spot for both players.
 */
class SharedFrame(val origin: V3, forwardIn: V3) {
  val forward: V3 = forwardIn.flatNormalized().let { if (it == V3.ZERO) V3(0f, 0f, 1f) else it }
  /** Perpendicular horizontal axis. Basis (right, up, forward) is a proper rotation. */
  val right: V3 = V3(forward.z, 0f, -forward.x)

  fun pointToShared(p: V3): V3 {
    val d = p - origin
    return V3(d.dot(right), d.y, d.dot(forward))
  }

  fun pointToLocal(s: V3): V3 = origin + right * s.x + V3.UP * s.y + forward * s.z

  fun dirToShared(d: V3): V3 = V3(d.dot(right), d.y, d.dot(forward))

  fun dirToLocal(s: V3): V3 = right * s.x + V3.UP * s.y + forward * s.z

  fun platformToShared(p: Platform): Platform {
    val a = dirToShared(V3(p.cosA, 0f, p.sinA)).flatNormalized()
    return p.copy(center = pointToShared(p.center), cosA = a.x, sinA = a.z)
  }

  fun platformToLocal(p: Platform): Platform {
    val a = dirToLocal(V3(p.cosA, 0f, p.sinA)).flatNormalized()
    return p.copy(center = pointToLocal(p.center), cosA = a.x, sinA = a.z)
  }

  fun levelToShared(l: Level) = mapLevel(l, ::pointToShared, ::platformToShared)

  fun levelToLocal(l: Level) = mapLevel(l, ::pointToLocal, ::platformToLocal)

  private fun mapLevel(l: Level, pt: (V3) -> V3, pl: (Platform) -> Platform): Level {
    // Floor height: the frame only rotates about Y and translates, so the floor maps through the
    // origin's height offset.
    val floor = pt(V3(origin.x, l.floorY, origin.z)).y
    return l.copy(
        platforms = l.platforms.map(pl),
        coins = l.coins.map { it.copy(pos = pt(it.pos)) },
        enemies = l.enemies.map { it.copy(a = pt(it.a), b = pt(it.b)) },
        spawn = pt(l.spawn),
        spawn2 = pt(l.spawn2),
        flag = pt(l.flag),
        floorY = floor,
    )
  }

  companion object {
    /** Identity frame (used in solo, or before calibration). */
    val IDENTITY = SharedFrame(V3.ZERO, V3(0f, 0f, 1f))
  }
}
