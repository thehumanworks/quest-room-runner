package com.thehumanworks.roomrunner.core

/** Toy-scale movement tuning. The character is ~13 cm tall; distances are in metres. */
object Tuning {
  const val CHAR_RADIUS = 0.032f
  const val CHAR_HEIGHT = 0.13f
  const val MOVE_SPEED = 0.70f
  const val GROUND_ACCEL = 5.0f
  const val AIR_ACCEL = 3.0f
  const val GRAVITY = 5.5f
  const val JUMP_SPEED = 1.75f // ~0.28 m first jump
  const val DOUBLE_JUMP_SPEED = 1.5f // ~0.20 m extra
  const val MAX_FALL_SPEED = 3.0f
  const val COYOTE_TIME = 0.12f
  const val JUMP_BUFFER = 0.12f
  const val STOMP_BOUNCE = 1.4f

  // Level-generation limits derived from the above (with comfortable margins).
  /** Max horizontal edge-to-edge gap we ask the player to clear with a single jump. */
  const val STEP_GAP = 0.24f
  /** Max rise between consecutive platforms reachable with a single jump. */
  const val STEP_RISE = 0.15f
  const val STONE_HALF = 0.075f
  const val STONE_DEPTH = 0.025f

  const val COIN_RADIUS = 0.022f
  const val ENEMY_RADIUS = 0.032f
  const val FLAG_RADIUS = 0.07f
}
