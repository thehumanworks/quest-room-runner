package com.thehumanworks.roomrunner.net

import com.thehumanworks.roomrunner.core.Level
import com.thehumanworks.roomrunner.core.Match
import com.thehumanworks.roomrunner.core.MatchEvent
import com.thehumanworks.roomrunner.core.MatchResult
import com.thehumanworks.roomrunner.core.Mode
import com.thehumanworks.roomrunner.core.Phase
import com.thehumanworks.roomrunner.core.SharedFrame

sealed class AuthEvent {
  data class LevelReady(val level: Level) : AuthEvent()
  data class Countdown(val mode: Mode) : AuthEvent()
  data object Go : AuthEvent()
  data class CoinTaken(val coinId: Int, val playerId: Int, val mine: Boolean) : AuthEvent()
  data class Squashed(val enemyId: Int) : AuthEvent()
  data class Finished(val result: MatchResult) : AuthEvent()
  data object PeerJoined : AuthEvent()
  data object PeerLeft : AuthEvent()
}

/**
 * The game loop talks only to this interface, so Solo, Host and Guest share all gameplay code.
 * All positions going in and out are in the LOCAL tracking frame of this headset.
 */
interface GameAuthority {
  val mode: Mode
  val myId: Int
  val phase: Phase
  val time: Float
  val countdown: Float
  val scores: List<Int>
  val level: Level?
  val result: MatchResult?
  val peer: PlayerSnapshot?
  val peerConnected: Boolean
  val status: String

  fun isCoinTaken(id: Int): Boolean
  fun isEnemyAlive(id: Int): Boolean
  fun collectCoin(id: Int)
  fun stomp(enemyId: Int)
  fun setAtFlag(at: Boolean)
  fun update(dt: Float, me: PlayerSnapshot?): List<AuthEvent>
  fun close()
}

private fun mapMatchEvents(evs: List<MatchEvent>, myId: Int, mode: Mode): List<AuthEvent> =
    evs.map {
      when (it) {
        MatchEvent.CountdownStarted -> AuthEvent.Countdown(mode)
        MatchEvent.Go -> AuthEvent.Go
        is MatchEvent.CoinTaken -> AuthEvent.CoinTaken(it.coinId, it.playerId, it.playerId == myId)
        is MatchEvent.EnemySquashed -> AuthEvent.Squashed(it.enemyId)
        is MatchEvent.Finished -> AuthEvent.Finished(it.result)
      }
    }

/** Single player: the match runs right here. */
class LocalAuthority(lvl: Level) : GameAuthority {
  private var match = Match(Mode.SOLO, lvl, 1)
  private var announced = false
  override val mode = Mode.SOLO
  override val myId = 0
  override val phase get() = match.phase
  override val time get() = match.time
  override val countdown get() = match.countdown
  override val scores get() = match.scores.toList()
  override val level: Level = lvl
  override val result get() = match.result
  override val peer: PlayerSnapshot? = null
  override val peerConnected = false
  override val status = "Solo"

  fun restart() = match.start()

  override fun isCoinTaken(id: Int) = match.isCoinTaken(id)
  override fun isEnemyAlive(id: Int) = match.isEnemyAlive(id)
  override fun collectCoin(id: Int) {
    match.claimCoin(0, id)
  }
  override fun stomp(enemyId: Int) {
    match.claimStomp(0, enemyId)
  }
  override fun setAtFlag(at: Boolean) = match.setAtFlag(0, at)

  override fun update(dt: Float, me: PlayerSnapshot?): List<AuthEvent> {
    val out = mutableListOf<AuthEvent>()
    if (!announced) {
      announced = true
      out += AuthEvent.LevelReady(level)
    }
    out += mapMatchEvents(match.tick(dt), 0, mode)
    return out
  }

  override fun close() {}
}

/**
 * Two-player host: owns the level (generated from ITS room scan) and the authoritative [Match].
 * Validates the guest's coin / stomp / flag claims, and streams state at ~20 Hz.
 */
class HostAuthority(private val link: NetLink, frame: SharedFrame, lvl: Level) : GameAuthority {
  var frame: SharedFrame = frame
    private set
  override var level: Level = lvl
    private set
  override var mode: Mode = Mode.RACE
    private set
  override val myId = 0
  private var match: Match? = null
  private var wasConnected = false
  private var announced = false
  private var sendTimer = 0f
  private var gameTimer = 0f
  override var peer: PlayerSnapshot? = null
    private set

  override val phase get() = match?.phase ?: Phase.LOBBY
  override val time get() = match?.time ?: 0f
  override val countdown get() = match?.countdown ?: 0f
  override val scores get() = match?.scores?.toList() ?: listOf(0, 0)
  override val result get() = match?.result
  override val peerConnected get() = link.connected
  override val status get() = link.status

  fun setFrame(f: SharedFrame) {
    frame = f
    if (link.connected) link.send(Msg.LevelMsg(frame.levelToShared(level)))
  }

  fun replaceLevel(l: Level) {
    level = l
    match = null
    announced = false
    if (link.connected) link.send(Msg.LevelMsg(frame.levelToShared(level)))
  }

  /** Starts (or restarts) a match. Returns false if the guest isn't connected yet. */
  fun startMatch(m: Mode): Boolean {
    if (!link.connected) return false
    mode = m
    val mt = Match(m, level, 2)
    match = mt
    mt.start()
    link.send(Msg.Start(m, mt.countdown))
    return true
  }

  override fun isCoinTaken(id: Int) = match?.isCoinTaken(id) ?: false
  override fun isEnemyAlive(id: Int) = match?.isEnemyAlive(id) ?: true
  override fun collectCoin(id: Int) {
    match?.claimCoin(0, id)
  }
  override fun stomp(enemyId: Int) {
    match?.claimStomp(0, enemyId)
  }
  override fun setAtFlag(at: Boolean) {
    match?.setAtFlag(0, at)
  }

  override fun update(dt: Float, me: PlayerSnapshot?): List<AuthEvent> {
    val out = mutableListOf<AuthEvent>()
    if (!announced) {
      announced = true
      out += AuthEvent.LevelReady(level)
    }
    val c = link.connected
    if (c && !wasConnected) {
      link.send(Msg.Welcome(1, Protocol.VERSION))
      link.send(Msg.LevelMsg(frame.levelToShared(level)))
      out += AuthEvent.PeerJoined
    } else if (!c && wasConnected) {
      peer = null
      out += AuthEvent.PeerLeft
    }
    wasConnected = c

    for (m in link.poll()) {
      val mt = match
      when (m) {
        is Msg.State -> peer = toLocal(m.s)
        is Msg.CoinClaim -> mt?.claimCoin(1, m.coinId)
        is Msg.StompClaim -> mt?.claimStomp(1, m.enemyId)
        is Msg.AtFlag -> mt?.setAtFlag(1, m.at)
        else -> {}
      }
    }

    val mt = match
    if (mt != null) {
      val evs = mt.tick(dt)
      for (e in evs) {
        when (e) {
          is MatchEvent.CoinTaken -> link.send(Msg.CoinTaken(e.coinId, e.playerId))
          is MatchEvent.EnemySquashed -> link.send(Msg.Squashed(e.enemyId, e.until))
          is MatchEvent.Finished -> link.send(Msg.End(e.result))
          else -> {}
        }
      }
      out += mapMatchEvents(evs, 0, mode)
    }

    sendTimer += dt
    gameTimer += dt
    if (sendTimer >= 0.05f && me != null && c) {
      sendTimer = 0f
      link.send(Msg.State(toShared(me)))
    }
    if (gameTimer >= 0.1f && c) {
      gameTimer = 0f
      link.send(Msg.Game(phase, time, countdown, scores))
    }
    return out
  }

  private fun toShared(s: PlayerSnapshot) =
      s.copy(
          feet = frame.pointToShared(s.feet),
          facing = frame.dirToShared(s.facing),
          head = frame.pointToShared(s.head),
          headForward = frame.dirToShared(s.headForward),
      )

  private fun toLocal(s: PlayerSnapshot) =
      s.copy(
          feet = frame.pointToLocal(s.feet),
          facing = frame.dirToLocal(s.facing),
          head = frame.pointToLocal(s.head),
          headForward = frame.dirToLocal(s.headForward),
      )

  override fun close() = link.close()
}

/**
 * Two-player guest: receives the level and match state from the host. Coin pickups are shown
 * immediately (optimistic) and confirmed / corrected by the host's COIN messages.
 */
class GuestAuthority(private val link: NetLink, frame: SharedFrame) : GameAuthority {
  var frame: SharedFrame = frame
    private set
  private var sharedLevel: Level? = null
  override var level: Level? = null
    private set
  override var mode: Mode = Mode.RACE
    private set
  override val myId = 1
  override var phase: Phase = Phase.LOBBY
    private set
  override var time = 0f
    private set
  override var countdown = 0f
    private set
  override var scores: List<Int> = listOf(0, 0)
    private set
  override var result: MatchResult? = null
    private set
  override var peer: PlayerSnapshot? = null
    private set
  override val peerConnected get() = link.connected
  override val status get() = link.status

  private val coinOwner = HashMap<Int, Int>()
  private val pendingCoins = HashSet<Int>()
  private val squashedUntil = HashMap<Int, Float>()
  private var wasConnected = false
  private var sendTimer = 0f
  private var lastFlag: Boolean? = null
  private var levelDirty = false

  fun setFrame(f: SharedFrame) {
    frame = f
    sharedLevel?.let {
      level = frame.levelToLocal(it)
      levelDirty = true
    }
  }

  override fun isCoinTaken(id: Int) = coinOwner.containsKey(id) || id in pendingCoins
  override fun isEnemyAlive(id: Int) = time >= (squashedUntil[id] ?: -1f)

  override fun collectCoin(id: Int) {
    if (phase != Phase.PLAYING || isCoinTaken(id)) return
    pendingCoins += id
    link.send(Msg.CoinClaim(id))
  }

  override fun stomp(enemyId: Int) {
    if (phase == Phase.PLAYING) link.send(Msg.StompClaim(enemyId))
  }

  override fun setAtFlag(at: Boolean) {
    if (lastFlag == at) return
    lastFlag = at
    link.send(Msg.AtFlag(at))
  }

  override fun update(dt: Float, me: PlayerSnapshot?): List<AuthEvent> {
    val out = mutableListOf<AuthEvent>()
    val c = link.connected
    if (c && !wasConnected) out += AuthEvent.PeerJoined
    if (!c && wasConnected) {
      peer = null
      out += AuthEvent.PeerLeft
    }
    wasConnected = c
    if (phase == Phase.PLAYING) time += dt
    if (phase == Phase.COUNTDOWN) countdown = (countdown - dt).coerceAtLeast(0f)

    for (m in link.poll()) {
      when (m) {
        is Msg.LevelMsg -> {
          sharedLevel = m.level
          level = frame.levelToLocal(m.level)
          levelDirty = true
          phase = Phase.LOBBY
          coinOwner.clear()
          pendingCoins.clear()
          squashedUntil.clear()
        }
        is Msg.Start -> {
          mode = m.mode
          phase = Phase.COUNTDOWN
          countdown = m.countdown
          time = 0f
          result = null
          coinOwner.clear()
          pendingCoins.clear()
          squashedUntil.clear()
          lastFlag = null
          scores = listOf(0, 0)
          out += AuthEvent.Countdown(m.mode)
        }
        is Msg.Game -> {
          val prev = phase
          phase = m.phase
          countdown = m.countdown
          // Smoothly follow the host clock (avoids enemy jitter); snap on big drift.
          time = if (kotlin.math.abs(m.time - time) > 0.25f) m.time else time + (m.time - time) * 0.3f
          scores = m.scores
          if (prev == Phase.COUNTDOWN && phase == Phase.PLAYING) out += AuthEvent.Go
        }
        is Msg.State -> peer = toLocal(m.s)
        is Msg.CoinTaken -> {
          coinOwner[m.coinId] = m.playerId
          val wasMine = pendingCoins.remove(m.coinId)
          // If we optimistically took it but the host gave it to the other player, the coin
          // simply stays gone; the score shown always comes from the host.
          if (!(wasMine && m.playerId == myId)) {
            out += AuthEvent.CoinTaken(m.coinId, m.playerId, m.playerId == myId)
          }
        }
        is Msg.Squashed -> {
          squashedUntil[m.enemyId] = m.until
          out += AuthEvent.Squashed(m.enemyId)
        }
        is Msg.End -> {
          phase = Phase.FINISHED
          result = m.result
          scores = m.result.scores
          out += AuthEvent.Finished(m.result)
        }
        Msg.Bye -> {}
        else -> {}
      }
    }
    if (levelDirty) {
      levelDirty = false
      level?.let { out += AuthEvent.LevelReady(it) }
    }

    sendTimer += dt
    if (sendTimer >= 0.05f && me != null && c) {
      sendTimer = 0f
      link.send(Msg.State(toShared(me)))
    }
    return out
  }

  private fun toShared(s: PlayerSnapshot) =
      s.copy(
          feet = frame.pointToShared(s.feet),
          facing = frame.dirToShared(s.facing),
          head = frame.pointToShared(s.head),
          headForward = frame.dirToShared(s.headForward),
      )

  private fun toLocal(s: PlayerSnapshot) =
      s.copy(
          feet = frame.pointToLocal(s.feet),
          facing = frame.dirToLocal(s.facing),
          head = frame.pointToLocal(s.head),
          headForward = frame.dirToLocal(s.headForward),
      )

  override fun close() = link.close()
}
