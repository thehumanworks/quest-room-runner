package com.thehumanworks.roomrunner.view

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.util.Log

/** Tiny SoundPool wrapper for the chiptune WAVs synthesised by [SoundSynth] on first launch. */
class Sfx(context: Context) {
  private val pool =
      SoundPool.Builder()
          .setMaxStreams(8)
          .setAudioAttributes(
              AudioAttributes.Builder()
                  .setUsage(AudioAttributes.USAGE_GAME)
                  .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                  .build())
          .build()
  private val ids = HashMap<String, Int>()

  init {
    try {
      for ((name, file) in SoundSynth.writeAll(java.io.File(context.cacheDir, "sfx"))) {
        ids[name] = pool.load(file.absolutePath, 1)
      }
    } catch (e: Exception) {
      Log.w("RoomRunner", "sound setup failed: ${e.message}")
    }
  }

  fun play(name: String, volume: Float = 0.8f, rate: Float = 1f) {
    val id = ids[name] ?: return
    pool.play(id, volume, volume, 1, 0, rate)
  }

  fun release() = pool.release()
}
