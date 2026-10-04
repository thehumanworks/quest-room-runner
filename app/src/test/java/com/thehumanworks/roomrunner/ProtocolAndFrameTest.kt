package com.thehumanworks.roomrunner

import com.thehumanworks.roomrunner.core.LevelGenerator
import com.thehumanworks.roomrunner.core.MatchResult
import com.thehumanworks.roomrunner.core.Mode
import com.thehumanworks.roomrunner.core.Phase
import com.thehumanworks.roomrunner.core.SharedFrame
import com.thehumanworks.roomrunner.core.V3
import com.thehumanworks.roomrunner.net.Msg
import com.thehumanworks.roomrunner.net.PlayerSnapshot
import com.thehumanworks.roomrunner.net.Protocol
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolAndFrameTest {
  private fun near(a: V3, b: V3, eps: Float = 2e-3f) =
      assertTrue("$a != $b", a.dist(b) < eps)

  @Test
  fun messagesRoundTrip() {
    val snap = PlayerSnapshot(1, V3(1f, 0.5f, -2f), V3(0.6f, 0f, 0.8f), 1.2f, true, V3(0.2f, 1.6f, 0.1f), V3(0f, -0.2f, 0.98f), true)
    val msgs =
        listOf(
            Msg.Hello("Quest|Two", 1),
            Msg.Welcome(1, 1),
            Msg.Start(Mode.COOP, 3f),
            Msg.Game(Phase.PLAYING, 12.5f, 0f, listOf(3, 4)),
            Msg.CoinClaim(7),
            Msg.CoinTaken(7, 1),
            Msg.StompClaim(1),
            Msg.Squashed(1, 9.5f),
            Msg.AtFlag(true),
            Msg.End(MatchResult(Mode.RACE, 42.25f, listOf(5, 9), 1, 14, 20, 1)),
            Msg.Bye,
        )
    for (m in msgs) {
      val d = Protocol.decode(Protocol.encode(m))
      if (m is Msg.Hello) assertEquals("Quest_Two", (d as Msg.Hello).name) else assertEquals(m, d)
    }
    val st = Protocol.decode(Protocol.encode(Msg.State(snap))) as Msg.State
    near(st.s.feet, snap.feet)
    near(st.s.head, snap.head)
    assertEquals(snap.grounded, st.s.grounded)
    assertEquals(snap.invulnerable, st.s.invulnerable)
  }

  @Test
  fun malformedLinesAreIgnored() {
    assertNull(Protocol.decode(""))
    assertNull(Protocol.decode("ST|1|nope"))
    assertNull(Protocol.decode("WHAT|1"))
    assertNull(Protocol.decode("LEVEL|1|2"))
    assertNull(Protocol.parseDiscovery("HELLO|1|2|3"))
    assertEquals(47778 to "Tomas", Protocol.parseDiscovery(Protocol.discoveryPacket("Tomas", 47778)))
  }

  @Test
  fun levelRoundTrip() {
    val l = LevelGenerator(3).generate(TestRooms.livingRoom(), 0f, V3(0f, 1.6f, 0f), V3(0f, 0f, 1f)).level
    val d = (Protocol.decode(Protocol.encode(Msg.LevelMsg(l))) as Msg.LevelMsg).level
    assertEquals(l.platforms.size, d.platforms.size)
    assertEquals(l.coins.size, d.coins.size)
    assertEquals(l.enemies.size, d.enemies.size)
    assertEquals(l.startPlatformId, d.startPlatformId)
    l.platforms.zip(d.platforms).forEach { (a, b) ->
      near(a.center, b.center)
      assertEquals(a.kind, b.kind)
      assertEquals(a.halfX, b.halfX, 1e-3f)
    }
    l.coins.zip(d.coins).forEach { (a, b) -> near(a.pos, b.pos) }
    near(l.flag, d.flag)
    println("level message size: ${Protocol.encode(Msg.LevelMsg(l)).length} chars")
  }

  /**
   * Two headsets track the same room with different origins/headings. Each calibrates on the same
   * physical spot+direction. A point from A's local frame must land on the same physical spot in
   * B's local frame.
   */
  @Test
  fun colocationFrameMapsBetweenHeadsets() {
    fun tracking(yaw: Float, off: V3): (V3) -> V3 = { p ->
      V3(p.x * cos(yaw) - p.z * sin(yaw) + off.x, p.y + off.y, p.x * sin(yaw) + p.z * cos(yaw) + off.z)
    }
    fun trackingDir(yaw: Float): (V3) -> V3 = { d ->
      V3(d.x * cos(yaw) - d.z * sin(yaw), d.y, d.x * sin(yaw) + d.z * cos(yaw))
    }
    val toA = tracking(0.7f, V3(1.2f, 0.02f, -0.4f))
    val toB = tracking(-2.1f, V3(-0.5f, -0.03f, 2.0f))
    val marker = V3(0.8f, 0.75f, 1.1f) // table corner (physical/world coords)
    val markerDir = V3(1f, 0f, 0.2f).flatNormalized()
    val frameA = SharedFrame(toA(marker), trackingDir(0.7f)(markerDir))
    val frameB = SharedFrame(toB(marker), trackingDir(-2.1f)(markerDir))
    val physical = listOf(V3(0f, 0f, 0f), V3(2f, 1.8f, -1f), V3(-1.3f, 0.4f, 0.7f))
    for (p in physical) {
      val shared = frameA.pointToShared(toA(p))
      near(frameB.pointToLocal(shared), toB(p))
    }
    // Directions and whole levels too.
    val lvlA = LevelGenerator(5).generate(TestRooms.livingRoom().map { it.copy(center = toA(it.center)) }, toA(V3.ZERO).y, toA(V3(0f, 1.6f, 0f)), V3(0f, 0f, 1f)).level
    val lvlB = frameB.levelToLocal(frameA.levelToShared(lvlA))
    val toAInv = tracking(-0.7f, V3.ZERO)
    // Coin positions in B correspond to the same physical spot as in A.
    for ((ca, cb) in lvlA.coins.zip(lvlB.coins)) {
      val physA = toAInv(ca.pos - V3(1.2f, 0.02f, -0.4f))
      near(toB(physA), cb.pos)
    }
    // Platform headings stay perpendicular-consistent: a corner maps to the same physical corner.
    for ((pa, pb) in lvlA.platforms.zip(lvlB.platforms)) {
      val cornerA = pa.toWorld(pa.halfX, 0f, pa.halfZ)
      val cornerB = pb.toWorld(pb.halfX, 0f, pb.halfZ)
      near(frameB.pointToLocal(frameA.pointToShared(cornerA)), cornerB)
    }
    assertEquals(toB(V3.ZERO).y, lvlB.floorY, 1e-3f)
  }
}
