package com.thehumanworks.roomrunner

import android.content.Context
import android.util.Log
import android.view.View
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Query
import com.meta.spatial.core.SystemBase
import com.meta.spatial.core.Vector3
import com.meta.spatial.mruk.MRUKAnchor
import com.meta.spatial.mruk.MRUKLabel
import com.meta.spatial.mruk.MRUKVolume
import com.meta.spatial.mruk.hasLabel
import com.meta.spatial.runtime.ButtonBits
import com.meta.spatial.toolkit.AvatarBody
import com.meta.spatial.toolkit.Controller
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.Visible
import com.meta.spatial.toolkit.getAbsoluteTransform
import com.thehumanworks.roomrunner.core.Contacts
import com.thehumanworks.roomrunner.core.Level
import com.thehumanworks.roomrunner.core.LevelGenerator
import com.thehumanworks.roomrunner.core.MatchResult
import com.thehumanworks.roomrunner.core.Mode
import com.thehumanworks.roomrunner.core.Phase
import com.thehumanworks.roomrunner.core.Platform
import com.thehumanworks.roomrunner.core.Runner
import com.thehumanworks.roomrunner.core.RunnerEvent
import com.thehumanworks.roomrunner.core.RunnerInput
import com.thehumanworks.roomrunner.core.SceneBox
import com.thehumanworks.roomrunner.core.SharedFrame
import com.thehumanworks.roomrunner.core.Tuning
import com.thehumanworks.roomrunner.core.V3
import com.thehumanworks.roomrunner.net.AuthEvent
import com.thehumanworks.roomrunner.net.GameAuthority
import com.thehumanworks.roomrunner.net.GuestAuthority
import com.thehumanworks.roomrunner.net.GuestSession
import com.thehumanworks.roomrunner.net.HostAuthority
import com.thehumanworks.roomrunner.net.HostSession
import com.thehumanworks.roomrunner.net.LocalAuthority
import com.thehumanworks.roomrunner.net.PlayerSnapshot
import com.thehumanworks.roomrunner.view.Confetti
import com.thehumanworks.roomrunner.view.HeadAvatarModel
import com.thehumanworks.roomrunner.view.LevelView
import com.thehumanworks.roomrunner.view.RunnerModel
import com.thehumanworks.roomrunner.view.ShadowModel
import com.thehumanworks.roomrunner.view.sv
import com.thehumanworks.roomrunner.view.v3
import java.util.Locale
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/** Frame-by-frame game loop: input, physics, rules, networking, visuals and panels. */
class GameController(private val act: RoomRunnerActivity) : SystemBase() {
  enum class Screen {
    LOADING,
    MENU,
    SOLO,
    HOST,
    GUEST,
  }

  private var screen = Screen.LOADING
  private var lastNanos = 0L
  private var clock = 0f
  private var loadingTime = 0f
  private var sceneSettle = 0f

  // Level built from THIS headset's room (used for Solo and when hosting).
  private var roomLevel: Level? = null
  private var roomInfo = ""
  private var view: LevelView? = null
  private var viewLevel: Level? = null

  private var authority: GameAuthority? = null
  private var frame = SharedFrame.IDENTITY
  private var calibratedAt = ""

  private var runner = Runner(V3(0f, -10f, 0f))
  private var me: RunnerModel? = null
  private var meShadow: ShadowModel? = null
  private var peerModel: RunnerModel? = null
  private var peerShadow: ShadowModel? = null
  private var peerHead: HeadAvatarModel? = null
  private var peerPos: V3? = null
  private var peerFacing = V3(0f, 0f, 1f)
  private var peerSquash = 1f
  private var confetti: Confetti? = null
  private var lastCountdownBeep = -1
  private var hudCache = ""
  private var hudTimer = 0f
  private var panelDirty = true
  private var lastResult: MatchResult? = null
  private var newBest = false
  private var lastStatus = ""

  private val prefs by lazy { act.getSharedPreferences("roomrunner", Context.MODE_PRIVATE) }

  // ---------------------------------------------------------------------------------------------
  override fun execute() {
    val now = System.nanoTime()
    val dt = if (lastNanos == 0L) 0f else ((now - lastNanos) / 1e9f).coerceIn(0f, 0.05f)
    lastNanos = now
    clock += dt

    val input = readInput()
    val head = input.headPose

    when (screen) {
      Screen.LOADING -> updateLoading(dt, head)
      else -> updateGame(dt, input)
    }
    confetti?.let { if (!it.update(dt)) confetti = null }
    updateHud(dt, head)
  }

  // ---------------------------------------------------------------------------------------------
  // Input
  private class Input(
      val headPose: Pose?,
      val rightPose: Pose?,
      val stickX: Float,
      val stickY: Float,
      val aPressed: Boolean,
      val aHeld: Boolean,
      val bPressed: Boolean,
      val xPressed: Boolean,
      val yPressed: Boolean,
      val menuPressed: Boolean,
  )

  private fun Controller.down(bit: Int) = (buttonState and bit) != 0

  private fun Controller.pressed(bit: Int) = (buttonState and changedButtons and bit) != 0

  private fun readInput(): Input {
    var body: AvatarBody? = null
    try {
      body =
          Query.where { has(AvatarBody.id) }
              .eval()
              .firstOrNull { it.isLocal() && it.getComponent<AvatarBody>().isPlayerControlled }
              ?.getComponent<AvatarBody>()
    } catch (_: Exception) {}
    val left = body?.leftHand?.tryGetComponent<Controller>()
    val right = body?.rightHand?.tryGetComponent<Controller>()
    val headPose = body?.head?.let { safePose(it) }
    val rightPose = body?.rightHand?.let { safePose(it) }
    val b = ButtonBits
    var sx = 0f
    var sy = 0f
    // The Spatial SDK exposes the thumbsticks as 4-way direction bits (8-way when combined).
    for (c in listOfNotNull(left, right)) {
      if (c.down(b.ButtonThumbLR) || c.down(b.ButtonThumbRR)) sx += 1f
      if (c.down(b.ButtonThumbLL) || c.down(b.ButtonThumbRL)) sx -= 1f
      if (c.down(b.ButtonThumbLU) || c.down(b.ButtonThumbRU)) sy += 1f
      if (c.down(b.ButtonThumbLD) || c.down(b.ButtonThumbRD)) sy -= 1f
    }
    sx = sx.coerceIn(-1f, 1f)
    sy = sy.coerceIn(-1f, 1f)
    return Input(
        headPose = headPose,
        rightPose = rightPose,
        stickX = sx,
        stickY = sy,
        aPressed = right?.pressed(b.ButtonA) == true,
        aHeld = right?.down(b.ButtonA) == true,
        bPressed = right?.pressed(b.ButtonB) == true,
        xPressed = left?.pressed(b.ButtonX) == true,
        yPressed = left?.pressed(b.ButtonY) == true,
        menuPressed = left?.pressed(b.ButtonMenu) == true,
    )
  }

  private fun safePose(e: Entity): Pose? =
      try {
        val p = getAbsoluteTransform(e)
        if (p == Pose()) null else p
      } catch (_: Exception) {
        e.tryGetComponent<Transform>()?.transform
      }

  // ---------------------------------------------------------------------------------------------
  // Loading: wait for the room scan (or its absence) and a valid head pose, then build a level.
  private fun updateLoading(dt: Float, head: Pose?) {
    loadingTime += dt
    // Fall back to a standing pose if tracking hasn't reported a head yet after a while.
    val hp = head ?: if (loadingTime > 10f) Pose(Vector3(0f, 1.6f, 0f)) else return
    val state = act.sceneState
    if (state == RoomRunnerActivity.SceneState.PENDING && loadingTime < 8f) return
    // Give MRUK a moment to place anchor entities after loading.
    sceneSettle += dt
    if (state == RoomRunnerActivity.SceneState.LOADED && sceneSettle < 0.6f) return
    buildRoomLevel(hp)
    lastHead = hp
    enterMenu()
  }

  private fun buildRoomLevel(head: Pose) {
    val (furniture, floorY, labels) = readFurniture()
    val fwd = head.forward().v3()
    val res = LevelGenerator(System.currentTimeMillis()).generate(furniture, floorY ?: 0f, head.t.v3(), fwd)
    res.log.forEach { Log.i(RoomRunnerActivity.TAG, "levelgen: $it") }
    roomLevel = res.level
    roomInfo =
        if (res.level.fromRoomScan) {
          "Room scan found: ${labels.joinToString(", ")}"
        } else {
          "No usable room scan, so this is a floating course.\n(Run Space Setup on the headset to scan your furniture.)"
        }
    showLevel(res.level, spawnAt = res.level.spawn)
  }

  /** Reads MRUK volumes (tables, couches, beds, storage...) as walkable platforms. */
  private fun readFurniture(): Triple<List<Platform>, Float?, List<String>> {
    val out = mutableListOf<Platform>()
    val labelsSeen = mutableListOf<String>()
    var floorY: Float? = null
    if (act.sceneState != RoomRunnerActivity.SceneState.LOADED) return Triple(out, null, labelsSeen)
    val wanted = listOf(MRUKLabel.TABLE, MRUKLabel.COUCH, MRUKLabel.BED, MRUKLabel.STORAGE, MRUKLabel.OTHER)
    try {
      val rooms = act.mruk.getCurrentRoom()?.let { listOf(it) } ?: act.mruk.rooms
      var id = 0
      for (room in rooms) {
        for (f in room.floors) {
          val y = getAbsoluteTransform(f).t.y
          floorY = if (floorY == null) y else minOf(floorY!!, y)
        }
        for (e in room.anchors) {
          val anchor = e.tryGetComponent<MRUKAnchor>() ?: continue
          val vol = e.tryGetComponent<MRUKVolume>() ?: continue
          val label = wanted.firstOrNull { anchor.hasLabel(it) } ?: continue
          val pose = getAbsoluteTransform(e)
          val corners = SceneBox.localCorners(vol.min.v3(), vol.max.v3()).map { (pose * it.sv()).v3() }
          val p = SceneBox.platformFromCorners(id++, corners, label.name) ?: continue
          out += p
          labelsSeen += label.name.lowercase(Locale.ROOT)
        }
      }
    } catch (e: Exception) {
      Log.e(RoomRunnerActivity.TAG, "reading furniture failed", e)
    }
    val summary = labelsSeen.groupingBy { it }.eachCount().map { (k, v) -> if (v > 1) "$v× $k" else k }
    return Triple(out, floorY, summary)
  }

  private fun showLevel(level: Level, spawnAt: V3) {
    view?.destroy()
    view = LevelView(level)
    viewLevel = level
    runner = Runner(spawnAt)
    runner.respawn(spawnAt)
    ensureModels()
  }

  private fun clearLevelView() {
    view?.destroy()
    view = null
    viewLevel = null
  }

  private fun myColors(id: Int) = if (id == 0) 0xE53935L to 0x1E40AFL else 0x2E7D32L to 0x6A1B9AL

  private fun ensureModels() {
    val myId = authority?.myId ?: 0
    me?.destroy()
    peerModel?.destroy()
    val (c1, b1) = myColors(myId)
    val (c2, b2) = myColors(1 - myId)
    me = RunnerModel(c1, b1)
    peerModel = RunnerModel(c2, b2).also { it.setVisible(false) }
    if (meShadow == null) meShadow = ShadowModel()
    if (peerShadow == null) peerShadow = ShadowModel().also { it.setVisible(false) }
    peerHead?.destroy()
    peerHead = HeadAvatarModel(c2).also { it.setVisible(false) }
  }

  // ---------------------------------------------------------------------------------------------
  // Screens
  private fun enterMenu() {
    authority?.close()
    authority = null
    act.releaseMulticast()
    screen = Screen.MENU
    lastResult = null
    roomLevel?.let { showLevel(it, it.spawn) }
    peerPos = null
    placeMainPanel(force = true)
    panelDirty = true
  }

  private fun startSolo(newLevel: Boolean = false) {
    if (newLevel) regenerate()
    val lvl = roomLevel ?: return
    authority?.close()
    val auth = LocalAuthority(lvl)
    authority = auth
    screen = Screen.SOLO
    showLevel(lvl, lvl.spawn)
    auth.restart()
    lastResult = null
    panelDirty = true
  }

  private fun regenerate() {
    lastHead?.let { buildRoomLevel(it) }
  }

  private fun startHost() {
    val lvl = roomLevel ?: return
    authority?.close()
    val link = HostSession(android.os.Build.MODEL ?: "Quest", broadcastTargets = act.broadcastTargets())
    try {
      link.start()
    } catch (e: Exception) {
      Log.e(RoomRunnerActivity.TAG, "host start failed", e)
    }
    authority = HostAuthority(link, frame, lvl)
    screen = Screen.HOST
    showLevel(lvl, lvl.spawn)
    lastResult = null
    placeMainPanel(force = true)
    panelDirty = true
  }

  private fun startGuest() {
    authority?.close()
    act.acquireMulticast()
    authority = GuestAuthority(GuestSession(android.os.Build.MODEL ?: "Quest").start(), frame)
    screen = Screen.GUEST
    clearLevelView()
    ensureModels()
    runner = Runner(V3(0f, -10f, 0f))
    lastResult = null
    placeMainPanel(force = true)
    panelDirty = true
  }

  private fun calibrate(rightPose: Pose?) {
    val p = rightPose ?: return
    val fwd = p.forward().v3().flatNormalized()
    if (fwd == V3.ZERO) return
    frame = SharedFrame(p.t.v3(), fwd)
    when (val a = authority) {
      is HostAuthority -> a.setFrame(frame)
      is GuestAuthority -> a.setFrame(frame)
      else -> {}
    }
    calibratedAt = String.format(Locale.ROOT, "%tT", System.currentTimeMillis())
    act.sfx.play("beep")
    act.haptic(0.6f, 80)
    panelDirty = true
  }

  /** Panel buttons (laser click) map to the same actions as the controller shortcuts. */
  fun onPanelButton(i: Int) {
    pendingButton = i
  }

  @Volatile private var pendingButton = -1
  private var lastHead: Pose? = null

  private fun handleButtons(input: Input) {
    val btn = pendingButton
    pendingButton = -1
    val act1 = input.xPressed || btn == 1 // X
    val act2 = input.yPressed || btn == 2 // Y
    val act0 = btn == 0
    if (input.menuPressed && screen != Screen.MENU) {
      enterMenu()
      return
    }
    when (screen) {
      Screen.MENU -> {
        when {
          act0 || input.aPressed -> startSolo()
          act1 -> startHost()
          act2 -> startGuest()
          input.bPressed -> {
            act.requestRoomScan()
            screen = Screen.LOADING
            loadingTime = 0f
            sceneSettle = 0f
          }
        }
      }
      Screen.SOLO -> {
        if (authority?.phase == Phase.FINISHED) {
          when {
            act0 || input.aPressed -> startSolo()
            act1 -> startSolo(newLevel = true)
            act2 -> enterMenu()
          }
        }
      }
      Screen.HOST -> {
        val h = authority as? HostAuthority ?: return
        if (input.bPressed) calibrate(input.rightPose)
        if (h.phase == Phase.COUNTDOWN || h.phase == Phase.PLAYING) return
        when {
          act1 -> if (!h.startMatch(Mode.RACE)) act.sfx.play("hurt", 0.4f)
          act2 -> if (!h.startMatch(Mode.COOP)) act.sfx.play("hurt", 0.4f)
          act0 -> enterMenu()
        }
      }
      Screen.GUEST -> {
        if (input.bPressed) calibrate(input.rightPose)
        if (act0) enterMenu()
      }
      Screen.LOADING -> {}
    }
  }

  // ---------------------------------------------------------------------------------------------
  private fun updateGame(dt: Float, input: Input) {
    if (input.headPose != null) lastHead = input.headPose
    handleButtons(input)
    if (screen == Screen.LOADING) return
    val auth = authority
    val phase = auth?.phase ?: Phase.LOBBY
    val level = viewLevel

    // --- Move the runner ----------------------------------------------------------------------
    val head = input.headPose
    var mx = 0f
    var mz = 0f
    if (head != null && (input.stickX != 0f || input.stickY != 0f)) {
      val f = head.forward().v3().flatNormalized()
      val r = head.right().v3().flatNormalized()
      val m = f * input.stickY + r * input.stickX
      val l = m.flatLength()
      if (l > 1e-3f) {
        mx = m.x / l
        mz = m.z / l
      }
    }
    val frozen = phase == Phase.COUNTDOWN || level == null
    val jumpPressed = input.aPressed && screen != Screen.MENU
    val rin = if (frozen) RunnerInput() else RunnerInput(mx, mz, jumpPressed, input.aHeld)
    if (level != null) {
      val evs = runner.step(dt, rin, level.platforms, level.floorY)
      for (e in evs) {
        when (e) {
          RunnerEvent.JUMP -> act.sfx.play("jump", 0.5f)
          RunnerEvent.DOUBLE_JUMP -> act.sfx.play("double_jump", 0.55f)
          RunnerEvent.LAND -> {
            if (runner.lastLandImpact > 1.2f) act.haptic(0.25f, 25)
            act.sfx.play("land", 0.4f)
          }
          RunnerEvent.BONK -> act.haptic(0.3f, 30)
          RunnerEvent.FELL_TO_FLOOR -> {
            act.sfx.play("fall", 0.6f)
            act.haptic(0.5f, 120)
          }
        }
      }
    }

    // --- Rules: coins, enemies, flag ----------------------------------------------------------
    val enemyTime = if (phase == Phase.LOBBY) clock else (auth?.time ?: clock)
    if (auth != null && level != null && phase == Phase.PLAYING) {
      for (c in level.coins) {
        if (!auth.isCoinTaken(c.id) && Contacts.touchesCoin(runner, c)) {
          auth.collectCoin(c.id)
          act.sfx.play("coin", 0.7f)
          act.haptic(0.35f, 40)
        }
      }
      for (e in level.enemies) {
        if (!auth.isEnemyAlive(e.id)) continue
        val ep = e.positionAt(enemyTime)
        when (Contacts.enemyContact(runner, ep)) {
          Contacts.EnemyContact.STOMP -> {
            auth.stomp(e.id)
            runner.bounce(Tuning.STOMP_BOUNCE)
            act.sfx.play("stomp", 0.8f)
            act.haptic(0.7f, 60)
          }
          Contacts.EnemyContact.HURT -> {
            runner.knockback(ep)
            act.sfx.play("hurt", 0.7f)
            act.haptic(0.9f, 150)
          }
          Contacts.EnemyContact.NONE -> {}
        }
      }
      auth.setAtFlag(Contacts.atFlag(runner, level.flag))
    }

    // --- Authority / network update -----------------------------------------------------------
    if (auth != null) {
      val snap =
          if (level != null && head != null) {
            PlayerSnapshot(
                auth.myId,
                runner.pos,
                runner.facing,
                runner.squash,
                runner.grounded,
                head.t.v3(),
                head.forward().v3(),
                runner.invulnerable > 0f,
            )
          } else null
      for (ev in auth.update(dt, snap)) handleAuthEvent(ev)
      if (auth.status != lastStatus) {
        lastStatus = auth.status
        panelDirty = true
      }
      // Countdown beeps.
      if (auth.phase == Phase.COUNTDOWN) {
        val n = kotlin.math.ceil(auth.countdown).toInt()
        if (n != lastCountdownBeep && n > 0) {
          lastCountdownBeep = n
          act.sfx.play("beep", 0.6f)
        }
      }
    }

    // --- Visuals ------------------------------------------------------------------------------
    updateVisuals(dt, enemyTime, auth)
    if (panelDirty) refreshPanel()
  }

  private fun handleAuthEvent(ev: AuthEvent) {
    when (ev) {
      is AuthEvent.LevelReady -> {
        if (screen == Screen.GUEST) {
          showLevel(ev.level, ev.level.spawn2)
          act.sfx.play("go", 0.5f)
        }
        panelDirty = true
      }
      is AuthEvent.Countdown -> {
        val lvl = viewLevel
        if (lvl != null) runner.respawn(if (authority?.myId == 1) lvl.spawn2 else lvl.spawn)
        lastCountdownBeep = -1
        lastResult = null
        confetti?.destroy()
        confetti = null
        panelDirty = true
      }
      AuthEvent.Go -> {
        act.sfx.play("go", 0.8f)
        act.haptic(0.5f, 80)
        panelDirty = true
      }
      is AuthEvent.CoinTaken -> if (!ev.mine) act.sfx.play("coin", 0.3f, 0.8f)
      is AuthEvent.Squashed -> act.sfx.play("stomp", 0.4f)
      is AuthEvent.Finished -> onFinished(ev.result)
      AuthEvent.PeerJoined -> {
        act.sfx.play("go", 0.6f)
        act.haptic(0.4f, 60)
        panelDirty = true
      }
      AuthEvent.PeerLeft -> {
        act.sfx.play("hurt", 0.5f)
        peerPos = null
        panelDirty = true
      }
    }
  }

  private fun onFinished(r: MatchResult) {
    lastResult = r
    newBest = false
    val key = when (r.mode) {
      Mode.SOLO -> "best_solo"
      Mode.COOP -> "best_coop"
      Mode.RACE -> null
    }
    if (key != null) {
      val best = prefs.getFloat(key, Float.MAX_VALUE)
      if (r.time < best) {
        prefs.edit().putFloat(key, r.time).apply()
        newBest = true
      }
    }
    val won = r.mode != Mode.RACE || r.winner == authority?.myId
    act.sfx.play(if (won) "win" else "hurt", 0.8f)
    act.haptic(0.8f, 250)
    viewLevel?.let {
      confetti?.destroy()
      confetti = Confetti(it.flag)
    }
    placeMainPanel(force = true)
    panelDirty = true
  }

  // ---------------------------------------------------------------------------------------------
  private fun groundBelow(p: V3): Float {
    val lvl = viewLevel ?: return 0f
    var best = lvl.floorY
    for (pl in lvl.platforms) {
      if (pl.top <= p.y + 0.01f && pl.top > best && pl.containsXZ(p)) best = pl.top
    }
    return best
  }

  private fun updateVisuals(dt: Float, enemyTime: Float, auth: GameAuthority?) {
    val v = view
    val lvl = viewLevel
    // Me.
    val m = me
    if (m != null) {
      val visible = lvl != null && !(runner.invulnerable > 0f && ((clock * 12f).toInt() % 2 == 0))
      m.setVisible(visible)
      if (lvl != null) {
        m.animate(runner.walkPhase, runner.grounded && runner.vel.flatLength() > 0.05f)
        m.place(runner.pos, runner.facing, runner.squash)
        val gy = groundBelow(runner.pos)
        val hgt = (runner.pos.y - gy).coerceAtLeast(0f)
        meShadow?.setVisible(true)
        meShadow?.place(runner.pos.withY(gy + 0.002f), V3(0f, 0f, 1f), 1f, (1f - hgt * 1.2f).coerceIn(0.35f, 1f))
      } else meShadow?.setVisible(false)
    }
    // Peer runner + head avatar.
    val peer = auth?.peer
    if (peer != null && lvl != null && auth.peerConnected) {
      val k = 1f - exp(-dt * 15f)
      peerPos = peerPos?.lerp(peer.feet, k) ?: peer.feet
      peerFacing = peerFacing.lerp(peer.facing, k)
      peerSquash += (peer.squash - peerSquash) * k
      val pp = peerPos!!
      peerModel?.setVisible(!(peer.invulnerable && ((clock * 12f).toInt() % 2 == 0)))
      peerModel?.animate(clock * 18f, peer.grounded && pp.distFlat(peer.feet) > 0.004f)
      peerModel?.place(pp, peerFacing, peerSquash)
      val gy = groundBelow(pp)
      peerShadow?.setVisible(true)
      peerShadow?.place(pp.withY(gy + 0.002f), V3(0f, 0f, 1f), 1f, (1f - (pp.y - gy) * 1.2f).coerceIn(0.35f, 1f))
      peerHead?.setVisible(true)
      peerHead?.place(peer.head, peer.headForward)
    } else {
      peerModel?.setVisible(false)
      peerShadow?.setVisible(false)
      peerHead?.setVisible(false)
    }
    if (v == null || lvl == null) return
    // Coins: spin + bob; hide taken ones.
    val spin = V3(sin(clock * 3f), 0f, cos(clock * 3f))
    for (c in lvl.coins) {
      val model = v.coins[c.id] ?: continue
      val taken = auth?.isCoinTaken(c.id) == true
      model.setVisible(!taken)
      if (!taken) model.place(c.pos + V3(0f, 0.008f * sin(clock * 2.5f + c.id), 0f), spin)
    }
    // Enemies.
    for (e in lvl.enemies) {
      val model = v.enemies[e.id] ?: continue
      val alive = auth?.isEnemyAlive(e.id) ?: true
      val p = e.positionAt(enemyTime)
      if (alive) {
        model.setVisible(true)
        model.place(p, e.headingAt(enemyTime), 1f + 0.07f * sin(clock * 14f + e.id))
      } else {
        model.setVisible(true)
        model.place(p, e.headingAt(enemyTime), 0.25f, 1.2f)
      }
    }
    // Flag: gentle wobble so it reads as "alive".
    v.flag.place(lvl.flag, V3(sin(clock * 0.8f), 0f, cos(clock * 0.8f)))
  }

  // ---------------------------------------------------------------------------------------------
  // Panels
  private fun placeMainPanel(force: Boolean) {
    val head = lastHead ?: return
    val panel = act.mainPanel ?: return
    if (!force) return
    val f = head.forward().v3().flatNormalized().let { if (it == V3.ZERO) V3(0f, 0f, 1f) else it }
    val pos = head.t.v3() + f * 0.85f + V3(0f, -0.12f, 0f)
    val q = Quaternion.lookRotation(f.sv(), Vector3(0f, 1f, 0f)) * Quaternion(12f, 0f, 0f)
    panel.setComponent(Transform(Pose(pos.sv(), q)))
  }

  private fun fmt(t: Float) = String.format(Locale.ROOT, "%.1f s", t)

  private fun best(key: String): String {
    val b = prefs.getFloat(key, Float.MAX_VALUE)
    return if (b == Float.MAX_VALUE) "—" else fmt(b)
  }

  private data class PanelSpec(
      val visible: Boolean,
      val title: String = "",
      val body: String = "",
      val b: List<String?> = listOf(null, null, null),
      val footer: String = "",
  )

  private fun panelSpec(): PanelSpec {
    val auth = authority
    val phase = auth?.phase ?: Phase.LOBBY
    val controls = "Move: thumbstick (relative to where you look)   Jump: A (press again in the air to double jump)"
    return when (screen) {
      Screen.LOADING -> PanelSpec(true, "ROOM RUNNER", "Reading your room scan...", listOf(null, null, null))
      Screen.MENU ->
          PanelSpec(
              true,
              "ROOM RUNNER",
              "$roomInfo\n\nCollect coins, stomp blobs, reach the flag.\nThe floor is lava!\n\nBest solo: ${best("best_solo")}   Best co-op: ${best("best_coop")}",
              listOf("Solo (A)", "Host 2P (X)", "Join 2P (Y)"),
              "$controls\nB: rescan room (Space Setup)   Menu button: back to this menu",
          )
      Screen.SOLO -> {
        val r = lastResult
        if (phase == Phase.FINISHED && r != null) {
          PanelSpec(
              true,
              if (newBest) "NEW BEST TIME!" else "COURSE CLEAR!",
              "Time: ${fmt(r.time)}\nCoins: ${r.collected} / ${r.total}\n\nBest: ${best("best_solo")}",
              listOf("Again (A)", "New level (X)", "Menu (Y)"),
              controls,
          )
        } else PanelSpec(false)
      }
      Screen.HOST, Screen.GUEST -> {
        val isHost = screen == Screen.HOST
        val r = lastResult
        if (phase == Phase.COUNTDOWN || phase == Phase.PLAYING) return PanelSpec(false)
        val cal = if (calibratedAt.isEmpty()) "NOT calibrated yet" else "Calibrated at $calibratedAt"
        val result =
            if (r != null) {
              val myId = auth?.myId ?: 0
              val head =
                  when (r.mode) {
                    Mode.COOP -> "TEAM CLEAR in ${fmt(r.time)}! Coins ${r.collected}/${r.total}"
                    Mode.RACE -> when (r.winner) {
                      -1 -> "DRAW! ${r.scores.getOrElse(0) { 0 }} : ${r.scores.getOrElse(1) { 0 }}"
                      myId -> "YOU WIN! ${r.scores.getOrElse(myId) { 0 }} : ${r.scores.getOrElse(1 - myId) { 0 }}"
                      else -> "YOU LOSE! ${r.scores.getOrElse(myId) { 0 }} : ${r.scores.getOrElse(1 - myId) { 0 }}"
                    }
                    Mode.SOLO -> ""
                  }
              "$head\n\n"
            } else ""
        val align =
            "Align once: both put your RIGHT controller on the same spot (e.g. a table corner), pointing the same way, and press B. Then check the course sits on the real furniture."
        val status = auth?.status ?: ""
        if (isHost) {
          PanelSpec(
              true,
              if (r != null) "GAME OVER" else "HOST 2P",
              "$result$status\n$cal\n\n$align\n\nRace = most coins wins (flag +3). Co-op = reach the flag together.",
              listOf("Leave", "Start Race (X)", "Start Co-op (Y)"),
              "Same Wi-Fi network needed. Menu button: leave",
          )
        } else {
          PanelSpec(
              true,
              if (r != null) "GAME OVER" else "JOIN 2P",
              "$result$status\n${if (viewLevel != null) "Course received from host." else "Waiting for the host's course..."}\n$cal\n\n$align\n\nThe host starts the game.",
              listOf("Leave", null, null),
              "Same Wi-Fi network needed. Menu button: leave",
          )
        }
      }
    }
  }

  private var lastSpec: PanelSpec? = null

  fun refreshPanel() {
    panelDirty = false
    val spec = panelSpec()
    val panel = act.mainPanel
    if (spec == lastSpec && act.mainTitle != null) return
    val wasVisible = lastSpec?.visible ?: false
    lastSpec = spec
    panel?.setComponent(Visible(spec.visible))
    if (spec.visible && !wasVisible) placeMainPanel(force = true)
    act.runOnUiThread {
      act.mainTitle?.text = spec.title
      act.mainBody?.text = spec.body
      act.mainFooter?.text = spec.footer
      act.buttons.forEachIndexed { i, b ->
        val label = spec.b.getOrNull(i)
        b.visibility = if (label == null) View.GONE else View.VISIBLE
        if (label != null) b.text = label
      }
    }
  }

  private fun updateHud(dt: Float, head: Pose?) {
    val hud = act.hudPanel ?: return
    val auth = authority
    val lvl = viewLevel
    val show = auth != null && lvl != null && screen != Screen.MENU && screen != Screen.LOADING
    hud.setComponent(Visible(show))
    if (!show || head == null) return
    // Float above the runner, facing the player.
    val pos = runner.pos + V3(0f, 0.24f, 0f)
    val dir = (pos - head.t.v3()).let { V3(it.x, 0f, it.z) }.flatNormalized().let { if (it == V3.ZERO) V3(0f, 0f, 1f) else it }
    hud.setComponent(Transform(Pose(pos.sv(), Quaternion.lookRotation(dir.sv(), Vector3(0f, 1f, 0f)))))
    hudTimer += dt
    if (hudTimer < 0.1f) return
    hudTimer = 0f
    val s = auth!!.scores
    val text =
        when (auth.phase) {
          Phase.COUNTDOWN -> {
            val n = kotlin.math.ceil(auth.countdown).toInt()
            if (n > 0) "$n" else "GO!"
          }
          Phase.LOBBY -> if (screen == Screen.HOST && !auth.peerConnected) "Waiting for P2..." else "Lobby: practise!"
          else ->
              when (auth.mode) {
                Mode.SOLO -> "TIME ${fmt(auth.time)}   COINS ${s.getOrElse(0) { 0 }}/${lvl!!.coins.size}"
                Mode.RACE -> "RED ${s.getOrElse(0) { 0 }}  :  ${s.getOrElse(1) { 0 }} GREEN   ${fmt(auth.time)}"
                Mode.COOP -> "TEAM COINS ${s.sum()}/${lvl!!.coins.size}   ${fmt(auth.time)}"
              }
        }
    if (text != hudCache) {
      hudCache = text
      act.runOnUiThread { act.hudText?.text = text }
    }
  }

  fun shutdown() {
    authority?.close()
    authority = null
  }
}
