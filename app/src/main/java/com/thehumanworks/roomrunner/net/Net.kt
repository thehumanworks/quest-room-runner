package com.thehumanworks.roomrunner.net

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.Writer
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/** Non-blocking message pipe the game loop polls once per frame. */
interface NetLink {
  val connected: Boolean
  val status: String
  fun send(m: Msg)
  fun poll(): List<Msg>
  fun close()
}

/** One TCP connection with a reader thread feeding a queue. */
class Connection(private val socket: Socket, private val onClosed: () -> Unit) {
  private val inbox = ConcurrentLinkedQueue<Msg>()
  private val writer: Writer
  private val open = AtomicBoolean(true)

  init {
    socket.tcpNoDelay = true
    writer = OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8)
    Thread({
          try {
            val r = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            while (open.get()) {
              val line = r.readLine() ?: break
              Protocol.decode(line)?.let { inbox.add(it) }
            }
          } catch (_: Exception) {
          } finally {
            close()
          }
        }, "rr-net-reader")
        .apply { isDaemon = true }
        .start()
  }

  val isOpen
    get() = open.get()

  val remote: InetAddress
    get() = socket.inetAddress

  fun send(m: Msg) {
    if (!open.get()) return
    try {
      synchronized(writer) {
        writer.write(Protocol.encode(m))
        writer.write("\n")
        writer.flush()
      }
    } catch (_: Exception) {
      close()
    }
  }

  fun drain(): List<Msg> {
    val out = ArrayList<Msg>()
    while (true) out.add(inbox.poll() ?: break)
    return out
  }

  fun close() {
    if (open.compareAndSet(true, false)) {
      try {
        socket.close()
      } catch (_: Exception) {}
      onClosed()
    }
  }
}

/**
 * Host side: listens for one guest on [tcpPort] and announces itself with a UDP broadcast every
 * second on [discoveryPort] until someone joins. [broadcastTargets] defaults to the global
 * broadcast address; Android passes the Wi-Fi subnet broadcast address as well.
 */
class HostSession(
    private val name: String,
    private val tcpPort: Int = Protocol.TCP_PORT,
    private val discoveryPort: Int = Protocol.DISCOVERY_PORT,
    private val broadcastTargets: List<InetAddress> = listOf(InetAddress.getByName("255.255.255.255")),
) : NetLink {
  @Volatile private var conn: Connection? = null
  @Volatile private var running = true
  @Volatile override var status = "Waiting for player 2..."
    private set
  private var server: ServerSocket? = null

  override val connected
    get() = conn?.isOpen == true

  fun start(): HostSession {
    val ss = ServerSocket()
    ss.reuseAddress = true
    ss.bind(InetSocketAddress(tcpPort))
    server = ss
    Thread({
          while (running) {
            try {
              val s = ss.accept()
              if (connected) {
                s.close() // only one guest
                continue
              }
              conn = Connection(s) { status = "Player 2 left. Waiting..." }
              status = "Player 2 connected (${s.inetAddress.hostAddress})"
            } catch (_: Exception) {
              if (!running) break
            }
          }
        }, "rr-host-accept")
        .apply { isDaemon = true }
        .start()
    Thread({
          val sock =
              try {
                DatagramSocket().apply { broadcast = true }
              } catch (e: Exception) {
                status = "Broadcast unavailable: ${e.message}"
                return@Thread
              }
          val payload = Protocol.discoveryPacket(name, tcpPort).toByteArray()
          while (running) {
            if (!connected) {
              for (t in broadcastTargets) {
                try {
                  sock.send(DatagramPacket(payload, payload.size, t, discoveryPort))
                } catch (_: Exception) {}
              }
            }
            try {
              Thread.sleep(1000)
            } catch (_: InterruptedException) {
              break
            }
          }
          sock.close()
        }, "rr-host-beacon")
        .apply { isDaemon = true }
        .start()
    return this
  }

  override fun send(m: Msg) {
    conn?.send(m)
  }

  override fun poll(): List<Msg> = conn?.drain() ?: emptyList()

  override fun close() {
    running = false
    conn?.send(Msg.Bye)
    conn?.close()
    try {
      server?.close()
    } catch (_: Exception) {}
  }
}

/**
 * Guest side: listens for a host's UDP broadcast and connects to the first one it hears, or
 * connects straight to [directHost] if given (used by tests / manual IP).
 */
class GuestSession(
    private val name: String,
    private val discoveryPort: Int = Protocol.DISCOVERY_PORT,
    private val directHost: InetAddress? = null,
    private val directPort: Int = Protocol.TCP_PORT,
) : NetLink {
  @Volatile private var conn: Connection? = null
  @Volatile private var running = true
  @Volatile override var status = "Looking for a host on this Wi-Fi..."
    private set

  override val connected
    get() = conn?.isOpen == true

  fun start(): GuestSession {
    Thread({
          if (directHost != null) {
            connect(directHost, directPort)
            return@Thread
          }
          var sock: DatagramSocket? = null
          try {
            sock = DatagramSocket(null).apply {
              reuseAddress = true
              broadcast = true
              soTimeout = 500
              bind(InetSocketAddress(discoveryPort))
            }
            val buf = ByteArray(512)
            while (running && !connected) {
              try {
                val pkt = DatagramPacket(buf, buf.size)
                sock.receive(pkt)
                val text = String(pkt.data, 0, pkt.length, Charsets.UTF_8)
                val (port, host) = Protocol.parseDiscovery(text) ?: continue
                status = "Found $host, connecting..."
                connect(pkt.address, port)
              } catch (_: SocketTimeoutException) {}
            }
          } catch (e: Exception) {
            status = "Discovery failed: ${e.message}"
          } finally {
            sock?.close()
          }
        }, "rr-guest-discovery")
        .apply { isDaemon = true }
        .start()
    return this
  }

  private fun connect(addr: InetAddress, port: Int) {
    try {
      val s = Socket()
      s.connect(InetSocketAddress(addr, port), 3000)
      conn = Connection(s) { status = "Disconnected from host" }
      conn?.send(Msg.Hello(name, Protocol.VERSION))
      status = "Connected to host (${addr.hostAddress})"
    } catch (e: Exception) {
      status = "Could not connect: ${e.message}"
    }
  }

  override fun send(m: Msg) {
    conn?.send(m)
  }

  override fun poll(): List<Msg> = conn?.drain() ?: emptyList()

  override fun close() {
    running = false
    conn?.send(Msg.Bye)
    conn?.close()
  }
}
