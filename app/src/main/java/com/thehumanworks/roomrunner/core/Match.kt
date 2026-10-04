package com.thehumanworks.roomrunner.core

enum class Mode(val code: String) {
  SOLO("solo"),
  RACE("race"),
  COOP("coop");

  companion object {
    fun of(code: String) = entries.firstOrNull { it.code == code } ?: SOLO
  }
}

enum class Phase(val code: Int) {
  LOBBY(0),
  COUNTDOWN(1),
  PLAYING(2),
  FINISHED(3);

  companion object {
    fun of(code: Int) = entries.firstOrNull { it.code == code } ?: LOBBY
  }
}

data class MatchResult(
    val mode: Mode,
    val time: Float,
    val scores: List<Int>,
    /** Player id of the winner, -1 for a draw / co-op. */
    val winner: Int,
    val collected: Int,
    val total: Int,
    val flagBy: Int,
)

sealed class MatchEvent {
  data object CountdownStarted : MatchEvent()
  data object Go : MatchEvent()
  data class CoinTaken(val coinId: Int, val playerId: Int) : MatchEvent()
  data class EnemySquashed(val enemyId: Int, val playerId: Int, val until: Float) : MatchEvent()
  data class Finished(val result: MatchResult) : MatchEvent()
}

/**
 * Authoritative game rules. Runs locally in Solo, and on the host in two-player mode (the guest
 * only mirrors it). Rules:
 * - SOLO: reach the flag; time and coins are recorded.
 * - RACE: most coins wins. Reaching the flag first is worth +3 and ends the race; it also ends
 *   when every coin is taken.
 * - COOP: coins are shared; you win when both players stand at the flag together.
 */
class Match(val mode: Mode, val level: Level, val playerCount: Int) {
  var phase = Phase.LOBBY
    private set

  var time = 0f
    private set

  var countdown = 0f
    private set

  val coinOwner = IntArray(level.coins.size) { -1 }
  val scores = IntArray(playerCount)
  val squashedUntil = FloatArray(level.enemies.size) { -1f }
  private val atFlag = BooleanArray(playerCount)
  var result: MatchResult? = null
    private set

  private var flagBy = -1
  private val pending = mutableListOf<MatchEvent>()

  val collected: Int
    get() = coinOwner.count { it >= 0 }

  fun start(countdownSeconds: Float = 3f) {
    phase = Phase.COUNTDOWN
    countdown = countdownSeconds
    time = 0f
    coinOwner.fill(-1)
    scores.fill(0)
    squashedUntil.fill(-1f)
    atFlag.fill(false)
    result = null
    flagBy = -1
    pending += MatchEvent.CountdownStarted
  }

  fun tick(dt: Float): List<MatchEvent> {
    when (phase) {
      Phase.COUNTDOWN -> {
        countdown -= dt
        if (countdown <= 0f) {
          countdown = 0f
          phase = Phase.PLAYING
          pending += MatchEvent.Go
        }
      }
      Phase.PLAYING -> time += dt
      else -> {}
    }
    val out = pending.toList()
    pending.clear()
    return out
  }

  fun isCoinTaken(id: Int) = coinOwner.getOrElse(id) { 0 } >= 0

  fun isEnemyAlive(id: Int) = time >= squashedUntil.getOrElse(id) { 0f }

  fun claimCoin(playerId: Int, coinId: Int): Boolean {
    if (phase != Phase.PLAYING) return false
    if (coinId !in coinOwner.indices || coinOwner[coinId] >= 0) return false
    coinOwner[coinId] = playerId
    scores[playerId]++
    pending += MatchEvent.CoinTaken(coinId, playerId)
    if (mode == Mode.RACE && collected == coinOwner.size) finish()
    return true
  }

  fun claimStomp(playerId: Int, enemyId: Int): Boolean {
    if (phase != Phase.PLAYING || enemyId !in squashedUntil.indices || !isEnemyAlive(enemyId)) {
      return false
    }
    squashedUntil[enemyId] = time + 4f
    pending += MatchEvent.EnemySquashed(enemyId, playerId, squashedUntil[enemyId])
    return true
  }

  fun setAtFlag(playerId: Int, at: Boolean) {
    if (playerId !in atFlag.indices) return
    atFlag[playerId] = at
    if (phase != Phase.PLAYING || !at) return
    when (mode) {
      Mode.SOLO -> {
        flagBy = playerId
        finish()
      }
      Mode.RACE -> {
        flagBy = playerId
        scores[playerId] += 3
        finish()
      }
      Mode.COOP -> if (atFlag.all { it }) {
        flagBy = playerId
        finish()
      }
    }
  }

  private fun finish() {
    if (phase == Phase.FINISHED) return
    phase = Phase.FINISHED
    val winner =
        when (mode) {
          Mode.SOLO -> 0
          Mode.COOP -> -1
          Mode.RACE -> {
            val best = scores.maxOrNull() ?: 0
            val leaders = scores.indices.filter { scores[it] == best }
            if (leaders.size == 1) leaders[0] else if (flagBy in leaders) flagBy else -1
          }
        }
    val r = MatchResult(mode, time, scores.toList(), winner, collected, coinOwner.size, flagBy)
    result = r
    pending += MatchEvent.Finished(r)
  }
}
