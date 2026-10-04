package com.thehumanworks.roomrunner.net

import com.thehumanworks.roomrunner.core.Coin
import com.thehumanworks.roomrunner.core.Enemy
import com.thehumanworks.roomrunner.core.Level
import com.thehumanworks.roomrunner.core.MatchResult
import com.thehumanworks.roomrunner.core.Mode
import com.thehumanworks.roomrunner.core.Phase
import com.thehumanworks.roomrunner.core.Platform
import com.thehumanworks.roomrunner.core.PlatformKind
import com.thehumanworks.roomrunner.core.V3
import java.util.Locale

/** Snapshot of one player, in the shared frame, sent ~20x per second. */
data class PlayerSnapshot(
    val playerId: Int,
    val feet: V3,
    val facing: V3,
    val squash: Float,
    val grounded: Boolean,
    val head: V3,
    val headForward: V3,
    val invulnerable: Boolean = false,
)

/**
 * Line-based text protocol over one TCP connection (LAN only). Every message is a single line:
 * `TYPE|field|field...`. Lists use `;` between items and `,` between item fields. Human-readable
 * on purpose so it can be debugged with `nc`.
 */
sealed class Msg {
  data class Hello(val name: String, val version: Int) : Msg()
  data class Welcome(val playerId: Int, val version: Int) : Msg()
  data class LevelMsg(val level: Level) : Msg()
  data class Start(val mode: Mode, val countdown: Float) : Msg()
  data class State(val s: PlayerSnapshot) : Msg()
  data class Game(val phase: Phase, val time: Float, val countdown: Float, val scores: List<Int>) : Msg()
  data class CoinClaim(val coinId: Int) : Msg()
  data class CoinTaken(val coinId: Int, val playerId: Int) : Msg()
  data class StompClaim(val enemyId: Int) : Msg()
  data class Squashed(val enemyId: Int, val until: Float) : Msg()
  data class AtFlag(val at: Boolean) : Msg()
  data class End(val result: MatchResult) : Msg()
  data object Bye : Msg()
}

object Protocol {
  const val VERSION = 1
  const val DISCOVERY_PORT = 47777
  const val TCP_PORT = 47778
  const val DISCOVERY_MAGIC = "ROOMRUNNER"

  private fun f(v: Float) = String.format(Locale.ROOT, "%.4f", v)

  private fun v(p: V3) = "${f(p.x)},${f(p.y)},${f(p.z)}"

  private fun pv(s: String): V3 {
    val a = s.split(',')
    return V3(a[0].toFloat(), a[1].toFloat(), a[2].toFloat())
  }

  private fun clean(s: String) = s.replace(Regex("[|;,\\n\\r]"), "_")

  fun encode(m: Msg): String =
      when (m) {
        is Msg.Hello -> "HELLO|${clean(m.name)}|${m.version}"
        is Msg.Welcome -> "WELCOME|${m.playerId}|${m.version}"
        is Msg.LevelMsg -> "LEVEL|" + encodeLevel(m.level)
        is Msg.Start -> "START|${m.mode.code}|${f(m.countdown)}"
        is Msg.State ->
            with(m.s) {
              "ST|$playerId|${v(feet)}|${f(facing.x)},${f(facing.z)}|${f(squash)}|" +
                  "${if (grounded) 1 else 0}|${v(head)}|${v(headForward)}|${if (invulnerable) 1 else 0}"
            }
        is Msg.Game ->
            "G|${m.phase.code}|${f(m.time)}|${f(m.countdown)}|${m.scores.joinToString(",")}"
        is Msg.CoinClaim -> "CC|${m.coinId}"
        is Msg.CoinTaken -> "COIN|${m.coinId}|${m.playerId}"
        is Msg.StompClaim -> "SC|${m.enemyId}"
        is Msg.Squashed -> "SQ|${m.enemyId}|${f(m.until)}"
        is Msg.AtFlag -> "FLAG|${if (m.at) 1 else 0}"
        is Msg.End ->
            with(m.result) {
              "END|${mode.code}|${f(time)}|${scores.joinToString(",")}|$winner|$collected|$total|$flagBy"
            }
        Msg.Bye -> "BYE"
      }

  /** Returns null for malformed / unknown lines (never throws on bad input). */
  fun decode(line: String): Msg? =
      try {
        val p = line.trim().split('|')
        when (p[0]) {
          "HELLO" -> Msg.Hello(p[1], p[2].toInt())
          "WELCOME" -> Msg.Welcome(p[1].toInt(), p[2].toInt())
          "LEVEL" -> Msg.LevelMsg(decodeLevel(p.drop(1)))
          "START" -> Msg.Start(Mode.of(p[1]), p[2].toFloat())
          "ST" -> {
            val fc = p[3].split(',')
            Msg.State(
                PlayerSnapshot(
                    playerId = p[1].toInt(),
                    feet = pv(p[2]),
                    facing = V3(fc[0].toFloat(), 0f, fc[1].toFloat()),
                    squash = p[4].toFloat(),
                    grounded = p[5] == "1",
                    head = pv(p[6]),
                    headForward = pv(p[7]),
                    invulnerable = p.getOrNull(8) == "1",
                ))
          }
          "G" ->
              Msg.Game(
                  Phase.of(p[1].toInt()),
                  p[2].toFloat(),
                  p[3].toFloat(),
                  if (p[4].isEmpty()) emptyList() else p[4].split(',').map { it.toInt() },
              )
          "CC" -> Msg.CoinClaim(p[1].toInt())
          "COIN" -> Msg.CoinTaken(p[1].toInt(), p[2].toInt())
          "SC" -> Msg.StompClaim(p[1].toInt())
          "SQ" -> Msg.Squashed(p[1].toInt(), p[2].toFloat())
          "FLAG" -> Msg.AtFlag(p[1] == "1")
          "END" ->
              Msg.End(
                  MatchResult(
                      mode = Mode.of(p[1]),
                      time = p[2].toFloat(),
                      scores = p[3].split(',').filter { it.isNotEmpty() }.map { it.toInt() },
                      winner = p[4].toInt(),
                      collected = p[5].toInt(),
                      total = p[6].toInt(),
                      flagBy = p[7].toInt(),
                  ))
          "BYE" -> Msg.Bye
          else -> null
        }
      } catch (e: Exception) {
        null
      }

  fun encodeLevel(l: Level): String {
    val plats =
        l.platforms.joinToString(";") {
          listOf(
                  it.id.toString(),
                  it.kind.code.toString(),
                  f(it.center.x),
                  f(it.center.y),
                  f(it.center.z),
                  f(it.halfX),
                  f(it.halfZ),
                  f(it.depth),
                  f(it.cosA),
                  f(it.sinA),
                  clean(it.label),
              )
              .joinToString(",")
        }
    val coins =
        l.coins.joinToString(";") {
          "${it.id},${v(it.pos)},${it.platformId},${if (it.high) 1 else 0}"
        }
    val enemies =
        l.enemies.joinToString(";") {
          "${it.id},${it.platformId},${v(it.a)},${v(it.b)},${f(it.speed)},${f(it.phase)}"
        }
    return listOf(
            f(l.floorY),
            if (l.fromRoomScan) "1" else "0",
            l.startPlatformId.toString(),
            l.flagPlatformId.toString(),
            v(l.spawn),
            v(l.spawn2),
            v(l.flag),
            plats,
            coins,
            enemies,
        )
        .joinToString("|")
  }

  fun decodeLevel(p: List<String>): Level {
    fun items(s: String) = if (s.isEmpty()) emptyList() else s.split(';')
    val plats =
        items(p[7]).map {
          val a = it.split(',')
          Platform(
              id = a[0].toInt(),
              kind = PlatformKind.of(a[1].toInt()),
              center = V3(a[2].toFloat(), a[3].toFloat(), a[4].toFloat()),
              halfX = a[5].toFloat(),
              halfZ = a[6].toFloat(),
              depth = a[7].toFloat(),
              cosA = a[8].toFloat(),
              sinA = a[9].toFloat(),
              label = a.getOrElse(10) { "" },
          )
        }
    val coins =
        items(p.getOrElse(8) { "" }).map {
          val a = it.split(',')
          Coin(
              a[0].toInt(),
              V3(a[1].toFloat(), a[2].toFloat(), a[3].toFloat()),
              a[4].toInt(),
              a[5] == "1",
          )
        }
    val enemies =
        items(p.getOrElse(9) { "" }).map {
          val a = it.split(',')
          Enemy(
              id = a[0].toInt(),
              platformId = a[1].toInt(),
              a = V3(a[2].toFloat(), a[3].toFloat(), a[4].toFloat()),
              b = V3(a[5].toFloat(), a[6].toFloat(), a[7].toFloat()),
              speed = a[8].toFloat(),
              phase = a[9].toFloat(),
          )
        }
    return Level(
        platforms = plats,
        coins = coins,
        enemies = enemies,
        startPlatformId = p[2].toInt(),
        spawn = pv(p[4]),
        spawn2 = pv(p[5]),
        flag = pv(p[6]),
        flagPlatformId = p[3].toInt(),
        floorY = p[0].toFloat(),
        fromRoomScan = p[1] == "1",
    )
  }

  fun discoveryPacket(name: String, tcpPort: Int) = "$DISCOVERY_MAGIC|$VERSION|$tcpPort|${clean(name)}"

  /** Parses a discovery broadcast; returns (tcpPort, hostName) or null. */
  fun parseDiscovery(s: String): Pair<Int, String>? {
    val p = s.trim().split('|')
    if (p.size < 4 || p[0] != DISCOVERY_MAGIC) return null
    if (p[1].toIntOrNull() != VERSION) return null
    val port = p[2].toIntOrNull() ?: return null
    return port to p[3]
  }
}
