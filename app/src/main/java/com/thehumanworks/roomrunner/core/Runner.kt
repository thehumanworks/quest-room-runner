package com.thehumanworks.roomrunner.core

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/** One frame of player intent. [moveX]/[moveZ] are a world-space direction with length <= 1. */
data class RunnerInput(
    val moveX: Float = 0f,
    val moveZ: Float = 0f,
    val jumpPressed: Boolean = false,
    val jumpHeld: Boolean = false,
)

enum class RunnerEvent {
  JUMP,
  DOUBLE_JUMP,
  LAND,
  BONK,
  FELL_TO_FLOOR,
}

/**
 * Kinematic character controller for the tiny runner: acceleration-based movement, gravity,
 * coyote time, jump buffering, variable jump height, a double jump, oriented-box collisions
 * against platforms (sides, tops and undersides) and squash-and-stretch state for the renderer.
 * Position is the centre of the character's feet.
 */
class Runner(spawn: V3) {
  var pos: V3 = spawn
  var vel: V3 = V3.ZERO
  var grounded = false
  var groundPlatformId = -1
  var facing: V3 = V3(0f, 0f, 1f)
  var lastSafe: V3 = spawn
  var jumpsUsed = 0
  var coyote = 0f
  var jumpBuffer = 0f
  var invulnerable = 0f
  var lastLandImpact = 0f

  /** Vertical visual scale (1 = rest, >1 stretched, <1 squashed). */
  var squash = 1f
  private var squashVel = 0f
  /** Walk-cycle phase for little feet / bobbing. */
  var walkPhase = 0f

  fun respawn(at: V3) {
    pos = at
    vel = V3.ZERO
    grounded = false
    jumpsUsed = 0
    coyote = 0f
    jumpBuffer = 0f
    invulnerable = 1.5f
    squash = 1.25f
  }

  fun bounce(speed: Float) {
    vel = vel.withY(speed)
    grounded = false
    jumpsUsed = 1
    squash = 1.3f
  }

  fun knockback(from: V3) {
    val away = (pos - from).flatNormalized().let { if (it == V3.ZERO) facing * -1f else it }
    vel = V3(away.x * 0.9f, 1.1f, away.z * 0.9f)
    grounded = false
    jumpsUsed = 2
    invulnerable = 1.5f
  }

  fun step(dt: Float, input: RunnerInput, platforms: List<Platform>, floorY: Float): List<RunnerEvent> {
    val events = mutableListOf<RunnerEvent>()
    var remaining = min(dt, 0.1f)
    var first = true
    while (remaining > 1e-5f) {
      val h = min(remaining, 1f / 120f)
      substep(h, if (first) input else input.copy(jumpPressed = false), platforms, floorY, events)
      first = false
      remaining -= h
    }
    // Squash spring (critically-ish damped).
    val k = 260f
    val damp = 16f
    squashVel += ((1f - squash) * k - squashVel * damp) * min(dt, 0.05f)
    squash = clampf(squash + squashVel * min(dt, 0.05f), 0.55f, 1.5f)
    if (invulnerable > 0f) invulnerable -= dt
    return events
  }

  private fun substep(
      dt: Float,
      input: RunnerInput,
      platforms: List<Platform>,
      floorY: Float,
      events: MutableList<RunnerEvent>,
  ) {
    // --- Horizontal intent -------------------------------------------------
    var mx = input.moveX
    var mz = input.moveZ
    val ml = sqrt(mx * mx + mz * mz)
    if (ml > 1f) {
      mx /= ml
      mz /= ml
    }
    val targetX = mx * Tuning.MOVE_SPEED
    val targetZ = mz * Tuning.MOVE_SPEED
    val accel = (if (grounded) Tuning.GROUND_ACCEL else Tuning.AIR_ACCEL) * dt
    var vx = approach(vel.x, targetX, accel)
    var vz = approach(vel.z, targetZ, accel)
    // Skid to a stop quickly on the ground with no input.
    if (grounded && ml < 0.05f) {
      vx = approach(vx, 0f, Tuning.GROUND_ACCEL * 1.5f * dt)
      vz = approach(vz, 0f, Tuning.GROUND_ACCEL * 1.5f * dt)
    }
    val hs = sqrt(vx * vx + vz * vz)
    if (hs > 0.05f) facing = V3(vx / hs, 0f, vz / hs)
    if (grounded) walkPhase += hs * dt * 40f

    // --- Jumping -----------------------------------------------------------
    if (input.jumpPressed) jumpBuffer = Tuning.JUMP_BUFFER
    var vy = vel.y
    if (jumpBuffer > 0f) {
      if (grounded || coyote > 0f) {
        vy = Tuning.JUMP_SPEED
        grounded = false
        coyote = 0f
        jumpsUsed = 1
        jumpBuffer = 0f
        squash = 1.32f
        events += RunnerEvent.JUMP
      } else if (jumpsUsed < 2) {
        vy = Tuning.DOUBLE_JUMP_SPEED
        jumpsUsed = 2
        jumpBuffer = 0f
        squash = 1.28f
        events += RunnerEvent.DOUBLE_JUMP
      }
    }
    jumpBuffer -= dt
    if (coyote > 0f) coyote -= dt

    // --- Gravity (heavier when the jump button is released early) ---------
    if (!grounded) {
      val g = if (vy > 0f && !input.jumpHeld) Tuning.GRAVITY * 2.0f else Tuning.GRAVITY
      vy = (vy - g * dt).coerceAtLeast(-Tuning.MAX_FALL_SPEED)
    } else {
      vy = 0f
    }

    // --- Horizontal move + side collisions --------------------------------
    var p = V3(pos.x + vx * dt, pos.y, pos.z + vz * dt)
    val r = Tuning.CHAR_RADIUS
    val hgt = Tuning.CHAR_HEIGHT
    for (pl in platforms) {
      val l = pl.toLocal(p)
      val feet = p.y
      if (feet >= pl.top - 0.002f || feet + hgt <= pl.bottom) continue
      val px = pl.halfX + r - abs(l.x)
      val pz = pl.halfZ + r - abs(l.z)
      if (px <= 0f || pz <= 0f) continue
      // Tiny lips: just step up.
      if (pl.top - feet < 0.012f && grounded) {
        p = p.withY(pl.top)
        continue
      }
      val nl =
          if (px < pz) V3(if (l.x >= 0) l.x + px else l.x - px, l.y, l.z)
          else V3(l.x, l.y, if (l.z >= 0) l.z + pz else l.z - pz)
      val w = pl.toWorld(nl.x, 0f, nl.z)
      p = V3(w.x, p.y, w.z)
      // Kill velocity into the wall (approx: project out along push direction).
      val push = (V3(w.x, 0f, w.z) - V3(pos.x + vx * dt, 0f, pos.z + vz * dt)).flatNormalized()
      val into = vx * push.x + vz * push.z
      if (into < 0f) {
        vx -= into * push.x
        vz -= into * push.z
      }
    }

    // --- Vertical move: landing and bonking -------------------------------
    val feet0 = p.y
    var feet1 = feet0 + vy * dt
    val margin = r * 0.5f
    var landedOn: Platform? = null
    if (vy <= 0f) {
      for (pl in platforms) {
        if (!pl.containsXZ(p, margin)) continue
        if (feet0 >= pl.top - 0.004f && feet1 <= pl.top) {
          if (landedOn == null || pl.top > landedOn.top) landedOn = pl
        }
      }
      if (landedOn != null) {
        if (!grounded) {
          lastLandImpact = -vy
          squash = 1f - clampf(-vy * 0.16f, 0.12f, 0.4f)
          events += RunnerEvent.LAND
        }
        feet1 = landedOn.top
        vy = 0f
        grounded = true
        jumpsUsed = 0
        coyote = 0f
        groundPlatformId = landedOn.id
        if (landedOn.kind != PlatformKind.STONE || landedOn.area > 0.02f) {
          lastSafe = landedOn.closestTopPoint(p, 0.03f)
        }
      }
    } else {
      for (pl in platforms) {
        if (!pl.containsXZ(p, margin)) continue
        val head0 = feet0 + hgt
        val head1 = feet1 + hgt
        if (head0 <= pl.bottom + 0.002f && head1 > pl.bottom) {
          feet1 = pl.bottom - hgt
          vy = 0f
          events += RunnerEvent.BONK
          break
        }
      }
    }
    p = p.withY(feet1)

    // --- Still on the ground? ---------------------------------------------
    if (grounded && landedOn == null) {
      val support =
          platforms.firstOrNull { pl -> pl.containsXZ(p, margin) && abs(p.y - pl.top) < 0.01f }
      if (support == null) {
        grounded = false
        coyote = Tuning.COYOTE_TIME
      } else {
        groundPlatformId = support.id
      }
    }
    if (!grounded && coyote <= 0f && jumpsUsed == 0) jumpsUsed = 1

    pos = p
    vel = V3(vx, vy, vz)

    // --- The floor is lava ------------------------------------------------
    if (pos.y <= floorY + 0.015f) {
      events += RunnerEvent.FELL_TO_FLOOR
      respawn(lastSafe)
    }
  }

  /** Centre of the character's body (for pickups). */
  fun bodyCenter() = pos + V3(0f, Tuning.CHAR_HEIGHT * 0.5f, 0f)

  companion object {
    fun approach(v: Float, target: Float, maxDelta: Float): Float =
        if (v < target) min(v + maxDelta, target) else maxOf(v - maxDelta, target)
  }
}

/** Contact tests shared by solo and both multiplayer peers. */
object Contacts {
  fun touchesCoin(r: Runner, c: Coin): Boolean =
      r.bodyCenter().dist(c.pos) < Tuning.COIN_RADIUS + 0.055f

  enum class EnemyContact {
    NONE,
    STOMP,
    HURT,
  }

  fun enemyContact(r: Runner, enemyFeet: V3): EnemyContact {
    val er = Tuning.ENEMY_RADIUS
    if (r.pos.distFlat(enemyFeet) > Tuning.CHAR_RADIUS + er) return EnemyContact.NONE
    val feet = r.pos.y
    if (feet > enemyFeet.y + 2f * er || feet + Tuning.CHAR_HEIGHT < enemyFeet.y) return EnemyContact.NONE
    return if (r.vel.y < 0f && feet > enemyFeet.y + er * 0.9f) EnemyContact.STOMP
    else if (r.invulnerable > 0f) EnemyContact.NONE
    else EnemyContact.HURT
  }

  fun atFlag(r: Runner, flag: V3): Boolean =
      r.pos.distFlat(flag) < Tuning.FLAG_RADIUS + Tuning.CHAR_RADIUS && abs(r.pos.y - flag.y) < 0.2f
}
