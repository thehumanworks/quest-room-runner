# Room Runner

A tiny mixed-reality platformer for **Meta Quest 3**. Your real furniture (tables, couch, bed,
shelves), taken from the headset's Space Setup room scan, becomes the level. A 13 cm character runs
and jumps across it, collecting coins, stomping blobs and reaching the flag. **The floor is lava.**
You can play solo or with two headsets on the same Wi-Fi network (race or co-op).

> **Status: built and unit-tested on Linux. It has NOT been run on a headset yet.** See
> [What's tested and what isn't](#whats-tested-and-what-isnt).

**Download the APK:** https://github.com/thehumanworks/quest-room-runner/releases/latest/download/room-runner.apk

## Why Meta Spatial SDK (and not Unity or Godot)

- **Meta Spatial SDK 0.14.0** (native Kotlin/Android, Gradle) with its **MRUK** feature for the
  scene model. It's Meta's own first-party MR stack: passthrough, the room scan (tables, couches,
  beds and so on as 3D volumes), controllers, panels and haptics. It builds headlessly with plain
  Gradle, so there's no editor, licence or paid asset involved.
- All gameplay (level generation, character physics, match rules, networking) is **plain Kotlin
  with no SDK dependencies**, so it runs and is tested on an ordinary JVM (`./gradlew testDebugUnitTest`).
- Godot or OpenXR would also work, but they add an export pipeline and a separate scene-API
  integration for no real gain at this size.

## Game modes

| Mode | What happens |
|---|---|
| **Solo** | Collect coins, reach the flag. Your best time is saved. |
| **Host 2P → Race** | The same course for both players. Most coins wins. Touching the flag gives +3 and ends the round (it also ends when every coin is taken). |
| **Host 2P → Co-op** | Both players have to stand at the flag together. The team time is saved. |
| **Join 2P** | Finds the host on the LAN automatically and plays on the **host's** room level. |

The host builds the level from **its** room scan and sends the platforms, coins and enemies to the
guest. The host is authoritative: it decides who got each coin, enemy stomps, scores and the end of
the match. Each player sees the other's character (red/blue for P1, green/purple for P2) with a
shadow, plus a small floating head avatar where the other person's head really is.

## Controls

| Input | Action |
|---|---|
| Either thumbstick | Run. Movement is relative to where you're looking (8-way digital, see below). |
| **A** | Jump. Press again in mid-air to double jump. Hold for a higher jump. |
| **B** | Menu: rescan the room. 2P: **calibrate** (see below). |
| **X / Y** | Menu: Host 2P / Join 2P. Host lobby: start Race / Co-op. After a solo run: new level / menu. |
| **A** on the menu | Solo (also "Again" after a solo run) |
| Left **Menu** button | Back to the main menu |
| Laser + trigger on the panel | You can also click the panel buttons. |

Stomp a blob from above to knock it out for 4 s. Touching it from the side knocks you back. If you
fall onto the floor you respawn at your last safe spot.

## Install on both Quest 3 headsets (from a Mac)

Do this once per headset.

1. **Developer Mode:** in the **Meta Horizon** phone app go to *Menu → Devices →* pick the headset
   *→ Headset settings → Developer Mode → On*. You need a (free) developer organisation at
   <https://developers.meta.com/horizon/>. Reboot the headset afterwards.
2. **Room scan:** on the headset go to *Settings → Physical Space → Space Setup* and scan the room,
   making sure the furniture gets marked (tables, couch, and so on). In 2P only the **host's** scan
   is used, but doing both does no harm.
3. **adb on the Mac:**
   ```bash
   brew install android-platform-tools
   curl -L -o room-runner.apk https://github.com/thehumanworks/quest-room-runner/releases/latest/download/room-runner.apk
   ```
4. Plug a headset in with USB-C, put it on and accept **"Allow USB debugging"** (tick *Always allow*). Then:
   ```bash
   adb devices                      # should list one device as "device"
   adb install -r room-runner.apk
   ```
   Repeat for the second headset. With both plugged in at once, use `adb devices` to get the
   serials and run `adb -s <serial> install -r room-runner.apk` for each.
   Alternatively, drag the APK onto the device in **Meta Quest Developer Hub**.
5. On the headset, open **Library → filter "Unknown Sources"** (the dropdown at the top right of
   the Library) and launch **Room Runner**. Allow the **spatial data** permission when asked,
   otherwise you get a floating fallback course instead of your furniture.

Both headsets are signed with the same key, so later updates work with `adb install -r` without uninstalling first.

## Two-player setup

1. Put both headsets on the **same Wi-Fi network**. Guest networks with "client isolation" block
   this.
2. P1 chooses **Host 2P** and P2 chooses **Join 2P**. The guest finds the host through a UDP
   broadcast beacon (port 47777) and connects over TCP (port 47778).
3. **Calibrate (align the two views).** One after the other, each player puts the tip of their
   **right controller** on the same physical spot (a table corner works well), pointing the same
   way, and presses **B**. That spot and direction becomes the shared origin, and all network
   coordinates are relative to it. Once the guest has calibrated, the course should sit exactly on
   the host's real furniture for both players. If it looks shifted, calibrate again. You can do it
   any time, including mid-game.
4. The host presses **X (Race)** or **Y (Co-op)**. There's a 3-2-1 countdown and then you play.

Meta *shared spatial anchors* would make step 3 automatic, but the Spatial SDK 0.14 public API
doesn't expose anchor sharing, so the game uses manual alignment for now.

## How it works

```
app/src/main/java/com/thehumanworks/roomrunner/
  RoomRunnerActivity.kt   Spatial SDK app: passthrough, MRUK scene load, panels, haptics, Wi-Fi/multicast
  GameController.kt       Per-frame system: input, screens/menu, models, HUD, sounds
  core/                   Pure Kotlin (JVM-tested)
    LevelGenerator.kt     Furniture boxes -> reachable course (stepping-stone bridges, coins, enemies, flag)
    Runner.kt             Kinematic platformer controller (coyote time, jump buffer, double jump, stomp)
    Match.kt              Solo / Race / Co-op rules
    SceneBox.kt, SharedFrame.kt, Level.kt, Tuning.kt, V3.kt
  net/                    Pure Kotlin (JVM-tested)
    Protocol.kt           Line-based text protocol + discovery beacon
    Net.kt                HostSession (TCP accept + UDP beacon) / GuestSession (discovery + TCP)
    Authority.kt          Local / Host (authoritative) / Guest (predicted) game authorities
  view/                   Primitive-mesh models, level visuals, runtime-synthesised chiptune SFX
```

- **Level generation:** MRUK volumes labelled TABLE / COUCH / BED / STORAGE / OTHER become
  platforms. Small, tall or hidden pieces are filtered out, and virtual floating platforms fill
  gaps (or make a whole course if there's no scan). A spanning tree links every surface to the
  start with stepping stones the character can definitely reach (spiralling up for tall jumps).
  The flag goes on the highest surface.
- **Sync:** both players send their character and head pose at 20 Hz; the host also streams the
  authoritative match state (phase, clock, scores) at 10 Hz. The guest sends coin, stomp and flag
  claims. The guest picks coins up optimistically
  and the host's verdict wins. Clocks are smoothed so enemy patrols line up on both headsets.

## Building

Requirements: JDK 17 and the Android SDK (platform 34, build-tools 34, NDK 27.0.12077973). The
easiest way to get these is Android Studio.

```bash
./gradlew testDebugUnitTest     # JVM tests
./gradlew assembleDebug         # -> app/build/outputs/apk/debug/app-debug.apk
```

The repo is text-only. The debug keystore is stored as base64 (`keystore/roomrunner-debug.jks.b64`,
created by the first CI run) and decoded on the first local build, and the sound effects are synthesised on the headset at first launch. If
`gradle/wrapper/gradle-wrapper.jar` is missing, run `gradle wrapper --gradle-version 9.4.1`; CI
generates and commits both after its first run. GitHub Actions (`.github/workflows/android.yml`) runs
the tests, builds the APK and publishes it as the latest release on every push to `main`.

**About the keystore:** it's a throwaway *debug* key (password `android`), committed on purpose
so that every build (CI, the Mac, anywhere) has the same signature and `adb install -r` upgrades
work. It protects nothing. Don't use it for a store release.

## What's tested and what isn't

**Tested on the JVM (25 unit tests, all passing):**
- Level generator: in 300 random furniture layouts the flag is always reachable. A realistic living
  room produces a fully connected course.
- Physics bot: a scripted bot plays generated levels with the real `Runner` controller. It clears
  the living room with 0 falls and clears 111 of 120 random rooms. The 9 failures look like bot
  heuristic or overlapping-furniture cases, not proven impossible levels.
- Match rules: solo, race (winner, draw, flag bonus) and co-op (both players at the flag).
- Networking over **real TCP/UDP sockets on loopback**: discovery beacon, hello/level transfer
  through two *different* shared frames, coin arbitration (the same coin can't be scored twice),
  stomp sync, clock sync (drift under 0.2 s), race end and winner on both sides, disconnect handling.
- Protocol round-trips, scene-box → platform conversion, WAV synthesis.

**Not tested (nothing has run on a Quest yet):**
- MRUK volume orientation and position on a real scan (handled defensively, but unverified).
- Whether panel layout, size and laser clicks look right. HUD readability.
- Thumbstick and button mapping on real controllers, haptics strength, SoundPool playback.
- LAN discovery on real Quest Wi-Fi: broadcast or multicast can be blocked by some routers. There
  is no manual IP entry in the UI yet.
- How accurate manual calibration is in practice (expect a few cm of error).
- Frame rate with all the primitive meshes. Passthrough and lighting look.

**Known limitations / skipped:**
- **Shared spatial anchors:** not in the Spatial SDK 0.14 public API, so manual calibration is used.
- **Analog stick:** the Spatial SDK exposes the sticks as direction bits only, so movement is
  8-way at a fixed speed.
- Walls aren't colliders, so the character can run through them (it falls into lava instead).

## Next steps

1. Try it on both headsets and tune it: jump height, speed, panel placement, MRUK orientation fixes.
2. Automatic colocation with shared spatial anchors / colocation discovery once the SDK exposes
   them (or through a small native OpenXR bridge). Add a manual IP entry as a fallback for
   restrictive Wi-Fi.
3. More game: analog movement (via OpenXR input), moving platforms, power-ups, more enemy types,
   glTF characters, and room-mesh colliders so walls count.

## Licence

MIT for this code (see `LICENSE`). The Meta Spatial SDK is a dependency under Meta's own licence terms.
