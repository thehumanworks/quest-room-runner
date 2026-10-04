package com.thehumanworks.roomrunner

import com.thehumanworks.roomrunner.core.LevelGenerator
import com.thehumanworks.roomrunner.core.Match
import com.thehumanworks.roomrunner.core.MatchEvent
import com.thehumanworks.roomrunner.core.Mode
import com.thehumanworks.roomrunner.core.Phase
import com.thehumanworks.roomrunner.core.SharedFrame
import com.thehumanworks.roomrunner.core.V3
import com.thehumanworks.roomrunner.net.AuthEvent
import com.thehumanworks.roomrunner.net.GuestAuthority
import com.thehumanworks.roomrunner.net.GuestSession
import com.thehumanworks.roomrunner.net.HostAuthority
import com.thehumanworks.roomrunner.net.HostSession
import com.thehumanworks.roomrunner.net.PlayerSnapshot
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MatchAndNetTest {
  private val level =
      LevelGenerator(11).generate(TestRooms.livingRoom(), 0f, V3(0f, 1.6f, 0f), V3(0f, 0f, 1f)).level

  private fun play(m: Match) {
    m.start(0.5f)
    m.tick(0.6f)
    assertEquals(Phase.PLAYING, m.phase)
  }

  @Test
  fun raceRules() {
    val m = Match(Mode.RACE, level, 2)
    assertFalse("no coins before start", m.claimCoin(0, 0))
    play(m)
    assertTrue(m.claimCoin(0, 0))
    assertFalse("double claim rejected", m.claimCoin(1, 0))
    assertTrue(m.claimCoin(1, 1))
    assertTrue(m.claimCoin(1, 2))
    m.setAtFlag(0, true) // player 0 reaches the flag: +3 -> 4 vs 2
    assertEquals(Phase.FINISHED, m.phase)
    assertEquals(0, m.result!!.winner)
    assertEquals(listOf(4, 2), m.result!!.scores)
  }

  @Test
  fun raceEndsWhenAllCoinsTaken() {
    val m = Match(Mode.RACE, level, 2)
    play(m)
    level.coins.forEachIndexed { i, c -> m.claimCoin(i % 2, c.id) }
    assertEquals(Phase.FINISHED, m.phase)
  }

  @Test
  fun coopNeedsBothAtFlag() {
    val m = Match(Mode.COOP, level, 2)
    play(m)
    m.setAtFlag(0, true)
    assertEquals(Phase.PLAYING, m.phase)
    m.setAtFlag(0, false)
    m.setAtFlag(1, true)
    assertEquals(Phase.PLAYING, m.phase)
    m.setAtFlag(0, true)
    assertEquals(Phase.FINISHED, m.phase)
    assertEquals(-1, m.result!!.winner)
  }

  @Test
  fun soloAndStomps() {
    val m = Match(Mode.SOLO, level, 1)
    play(m)
    assertTrue(m.claimStomp(0, 0))
    assertFalse(m.isEnemyAlive(0))
    assertFalse(m.claimStomp(0, 0))
    m.tick(4.1f)
    assertTrue(m.isEnemyAlive(0))
    m.tick(1f)
    m.setAtFlag(0, true)
    val ev = m.tick(0f)
    assertTrue(ev.any { it is MatchEvent.Finished })
    assertTrue(m.result!!.time > 5f)
  }

  /** Full host <-> guest session over real TCP sockets on loopback. */
  @Test
  fun hostGuestLoopback() {
    val hostFrame = SharedFrame(V3(0.5f, 0.75f, 1f), V3(1f, 0f, 0f))
    val guestFrame = SharedFrame(V3(-2f, 0.70f, 3f), V3(0f, 0f, -1f))
    val hostLink = HostSession("host", tcpPort = 47911, discoveryPort = 47912, broadcastTargets = emptyList()).start()
    val guestLink = GuestSession("guest", directHost = InetAddress.getLoopbackAddress(), directPort = 47911).start()
    val host = HostAuthority(hostLink, hostFrame, level)
    val guest = GuestAuthority(guestLink, guestFrame)
    val hostEvents = mutableListOf<AuthEvent>()
    val guestEvents = mutableListOf<AuthEvent>()

    fun pump(seconds: Float, until: () -> Boolean = { false }) {
      var t = 0f
      while (t < seconds && !until()) {
        val hs = PlayerSnapshot(0, level.spawn, V3(0f, 0f, 1f), 1f, true, V3(0f, 1.6f, 0f), V3(0f, 0f, 1f))
        hostEvents += host.update(0.02f, hs)
        val gl = guest.level
        val gs = if (gl != null) PlayerSnapshot(1, gl.spawn2, V3(1f, 0f, 0f), 1f, true, V3(1f, 1.5f, 1f), V3(1f, 0f, 0f)) else null
        guestEvents += guest.update(0.02f, gs)
        Thread.sleep(20)
        t += 0.02f
      }
    }

    pump(5f) { guest.level != null && host.peer != null }
    assertTrue(host.peerConnected && guest.peerConnected)
    val gl = guest.level
    assertNotNull("guest got level", gl)
    gl!!
    assertEquals(level.coins.size, gl.coins.size)
    // Guest's copy, mapped back through the shared frame, matches the host's level physically.
    for ((hc, gc) in level.coins.zip(gl.coins)) {
      val back = hostFrame.pointToLocal(guestFrame.pointToShared(gc.pos))
      assertTrue(back.dist(hc.pos) < 3e-3f)
    }
    // Host sees the guest standing on spawn2 (in host coordinates).
    val peer = host.peer!!
    assertTrue("peer at spawn2: ${peer.feet} vs ${level.spawn2}", peer.feet.dist(level.spawn2) < 3e-3f)

    assertTrue(host.startMatch(Mode.RACE))
    pump(5f) { host.phase == Phase.PLAYING && guest.phase == Phase.PLAYING }
    assertEquals(Phase.PLAYING, guest.phase)
    assertTrue(guestEvents.any { it is AuthEvent.Go })

    // Guest grabs coin 0; host tries the same coin a moment later and must lose.
    guest.collectCoin(0)
    assertTrue("optimistic hide", guest.isCoinTaken(0))
    pump(2f) { host.isCoinTaken(0) }
    host.collectCoin(0)
    host.collectCoin(1)
    pump(2f) { guest.scores == listOf(1, 1) }
    assertEquals(listOf(1, 1), host.scores)
    assertEquals(listOf(1, 1), guest.scores)
    assertTrue(guest.isCoinTaken(1))

    // Guest stomps enemy 0.
    guest.stomp(0)
    pump(2f) { !guest.isEnemyAlive(0) }
    assertFalse(host.isEnemyAlive(0))
    assertFalse(guest.isEnemyAlive(0))

    // Clocks agree closely (enemies are driven by the clock).
    assertTrue("clock drift ${host.time - guest.time}", kotlin.math.abs(host.time - guest.time) < 0.2f)

    // Guest reaches the flag first: wins the race 1+3 vs 1.
    guest.setAtFlag(true)
    pump(3f) { guest.result != null }
    val r = guest.result
    assertNotNull(r)
    assertEquals(1, r!!.winner)
    assertEquals(listOf(1, 4), r.scores)
    assertEquals(Phase.FINISHED, host.phase)

    guest.close()
    pump(2f) { !host.peerConnected }
    hostEvents += host.update(0.02f, null)
    assertFalse(host.peerConnected)
    assertTrue(hostEvents.any { it is AuthEvent.PeerLeft })
    host.close()
  }

  /** UDP discovery: the guest finds the host's beacon (sent to loopback here) and connects. */
  @Test
  fun discoveryOverUdp() {
    val hostLink =
        HostSession("Tomas", tcpPort = 47921, discoveryPort = 47922, broadcastTargets = listOf(InetAddress.getLoopbackAddress())).start()
    val guestLink = GuestSession("Guest", discoveryPort = 47922).start()
    var t = 0
    while ((!hostLink.connected || !guestLink.connected) && t < 100) {
      Thread.sleep(50)
      t++
    }
    assertTrue("host sees guest", hostLink.connected)
    assertTrue("guest connected", guestLink.connected)
    guestLink.close()
    hostLink.close()
  }
}
