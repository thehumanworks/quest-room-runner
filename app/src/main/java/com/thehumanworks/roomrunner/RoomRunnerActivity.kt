package com.thehumanworks.roomrunner

import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Hand
import com.meta.spatial.core.Pose
import com.meta.spatial.core.SpatialFeature
import com.meta.spatial.core.Vector3
import com.meta.spatial.mruk.MRUKFeature
import com.meta.spatial.mruk.MRUKLoadDeviceResult
import com.meta.spatial.toolkit.AppSystemActivity
import com.meta.spatial.toolkit.PanelRegistration
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.Visible
import com.meta.spatial.toolkit.createPanelEntity
import com.meta.spatial.vr.LocomotionSystem
import com.meta.spatial.vr.VRFeature
import com.thehumanworks.roomrunner.view.Sfx
import java.net.InetAddress

/**
 * Room Runner: a mixed-reality platformer where your real room (from the Quest's Space Setup
 * scan, via MRUK) becomes the level. All the gameplay lives in [GameController] (a Spatial SDK
 * system) and the pure-Kotlin `core` / `net` packages.
 */
class RoomRunnerActivity : AppSystemActivity() {
  lateinit var mruk: MRUKFeature
  lateinit var sfx: Sfx
  private lateinit var game: GameController

  @Volatile var sceneState = SceneState.PENDING
  var multicastLock: WifiManager.MulticastLock? = null

  // Panel views (set when the panels inflate).
  var mainTitle: TextView? = null
  var mainBody: TextView? = null
  var mainFooter: TextView? = null
  var buttons: List<Button> = emptyList()
  var hudText: TextView? = null
  var mainPanel: Entity? = null
  var hudPanel: Entity? = null

  enum class SceneState {
    PENDING,
    LOADED,
    NONE,
  }

  override fun registerFeatures(): List<SpatialFeature> {
    mruk = MRUKFeature(this, systemManager)
    return listOf(VRFeature(this), mruk)
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    systemManager.findSystem<LocomotionSystem>().enableLocomotion(false)
    scene.enablePassthrough(true)
    sfx = Sfx(this)
    game = GameController(this)
    systemManager.registerSystem(game)

    if (checkSelfPermission(PERMISSION_USE_SCENE) != PackageManager.PERMISSION_GRANTED) {
      Log.i(TAG, "Requesting scene permission")
      requestPermissions(arrayOf(PERMISSION_USE_SCENE), REQUEST_SCENE)
    } else {
      loadScene()
    }
  }

  override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    if (requestCode == REQUEST_SCENE) {
      if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
        loadScene()
      } else {
        Log.w(TAG, "Scene permission denied: falling back to virtual platforms")
        sceneState = SceneState.NONE
      }
    }
  }

  fun loadScene() {
    sceneState = SceneState.PENDING
    try {
      mruk.loadSceneFromDevice().whenComplete { result: MRUKLoadDeviceResult?, err: Throwable? ->
        Log.i(TAG, "Scene load result: $result ${err?.message ?: ""}")
        sceneState =
            if (result == MRUKLoadDeviceResult.SUCCESS && mruk.rooms.isNotEmpty()) SceneState.LOADED
            else SceneState.NONE
      }
    } catch (e: Exception) {
      Log.e(TAG, "Scene load failed", e)
      sceneState = SceneState.NONE
    }
  }

  /** Opens the system Space Setup so the user can scan the room, then reloads it. */
  fun requestRoomScan() {
    try {
      mruk.requestSceneCapture().whenComplete { _, _ -> loadScene() }
    } catch (e: Exception) {
      Log.e(TAG, "Scene capture failed", e)
    }
  }

  override fun onSceneReady() {
    super.onSceneReady()
    scene.setLightingEnvironment(
        ambientColor = Vector3(0.55f),
        sunColor = Vector3(1.0f, 0.97f, 0.9f),
        sunDirection = -Vector3(1.0f, 3.0f, -2.0f),
        environmentIntensity = 0.35f,
    )
    mainPanel = Entity.createPanelEntity(R.layout.panel_main, Transform(Pose(Vector3(0f, 1.3f, 0.8f))), Visible(true))
    hudPanel = Entity.createPanelEntity(R.layout.panel_hud, Transform(Pose(Vector3(0f, -5f, 0f))), Visible(false))
  }

  override fun registerPanels(): List<PanelRegistration> =
      listOf(
          PanelRegistration(R.layout.panel_main) {
            config {
              width = 0.62f
              height = 0.42f
              layoutWidthInPx = 1240
              layoutHeightInPx = 840
              layoutDpi = 260
              includeGlass = false
              enableTransparent = true
            }
            panel {
              val root: View = requireNotNull(rootView)
              mainTitle = root.findViewById(R.id.title)
              mainBody = root.findViewById(R.id.body)
              mainFooter = root.findViewById(R.id.footer)
              buttons = listOf(root.findViewById(R.id.btn1), root.findViewById(R.id.btn2), root.findViewById(R.id.btn3))
              buttons.forEachIndexed { i, b -> b.setOnClickListener { game.onPanelButton(i) } }
              game.refreshPanel()
            }
          },
          PanelRegistration(R.layout.panel_hud) {
            config {
              width = 0.34f
              height = 0.085f
              layoutWidthInPx = 680
              layoutHeightInPx = 170
              layoutDpi = 260
              includeGlass = false
              enableTransparent = true
            }
            panel { hudText = rootView?.findViewById(R.id.hud) }
          },
      )

  fun haptic(strength: Float, millis: Long, hand: Hand = Hand.RIGHT) {
    try {
      spatial.applyHapticFeedback(hand, strength, millis * 1_000_000L, 200f)
    } catch (_: Throwable) {}
  }

  /** Broadcast targets for LAN discovery: global broadcast + this Wi-Fi subnet's broadcast. */
  fun broadcastTargets(): List<InetAddress> {
    val out = mutableListOf(InetAddress.getByName("255.255.255.255"))
    try {
      val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
      @Suppress("DEPRECATION") val dhcp = wifi.dhcpInfo
      if (dhcp != null && dhcp.ipAddress != 0) {
        val bc = (dhcp.ipAddress and dhcp.netmask) or dhcp.netmask.inv()
        val bytes = ByteArray(4) { k -> ((bc shr (k * 8)) and 0xFF).toByte() }
        out += InetAddress.getByAddress(bytes)
      }
    } catch (e: Exception) {
      Log.w(TAG, "no subnet broadcast: ${e.message}")
    }
    return out
  }

  fun acquireMulticast() {
    try {
      val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
      multicastLock = wifi.createMulticastLock("roomrunner").apply {
        setReferenceCounted(false)
        acquire()
      }
    } catch (e: Exception) {
      Log.w(TAG, "multicast lock failed: ${e.message}")
    }
  }

  fun releaseMulticast() {
    try {
      multicastLock?.release()
    } catch (_: Exception) {}
    multicastLock = null
  }

  override fun onDestroy() {
    game.shutdown()
    sfx.release()
    releaseMulticast()
    super.onDestroy()
  }

  companion object {
    const val TAG = "RoomRunner"
    const val PERMISSION_USE_SCENE = "com.oculus.permission.USE_SCENE"
    const val REQUEST_SCENE = 1
  }
}
