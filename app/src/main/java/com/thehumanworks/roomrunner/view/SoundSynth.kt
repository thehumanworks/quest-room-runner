package com.thehumanworks.roomrunner.view

import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Synthesises the game's tiny chiptune sound effects at first launch (so the repo has no binary
 * audio assets). Writes 16-bit mono WAVs into [dir].
 */
object SoundSynth {
  private const val SR = 22050

  private enum class W { SQUARE, TRI, SINE, NOISE }

  private fun tone(freqs: List<Float>, dur: Float, w: W = W.SQUARE, vol: Float = 0.35f, slide: Pair<Float, Float>? = null, rnd: Random = Random(4)): FloatArray {
    val n = (SR * dur).toInt()
    val out = FloatArray(n)
    var ph = 0.0
    val seg = max(n / freqs.size, 1)
    for (i in 0 until n) {
      var f = freqs[min(i / seg, freqs.size - 1)]
      if (slide != null) f = slide.first + (slide.second - slide.first) * (i.toFloat() / n)
      ph += 2 * PI * f / SR
      val s =
          when (w) {
            W.SQUARE -> if (sin(ph) >= 0) 1f else -1f
            W.TRI -> (2 / PI * asin(sin(ph))).toFloat()
            W.SINE -> sin(ph).toFloat()
            W.NOISE -> rnd.nextFloat() * 2f - 1f
          }
      // Short attack, release over the last 60%.
      val t = i.toFloat() / SR
      val att = min(1f, t / 0.005f)
      val relStart = dur * 0.4f
      val rel = if (t < relStart) 1f else max(0f, 1f - (t - relStart) / (dur * 0.6f))
      out[i] = s * vol * att * rel
    }
    return out
  }

  private operator fun FloatArray.plus(o: FloatArray): FloatArray {
    val r = FloatArray(this.size + o.size)
    System.arraycopy(this, 0, r, 0, this.size)
    System.arraycopy(o, 0, r, this.size, o.size)
    return r
  }

  private fun mix(a: FloatArray, b: FloatArray) = FloatArray(max(a.size, b.size)) { (a.getOrElse(it) { 0f }) + (b.getOrElse(it) { 0f }) }

  fun all(): Map<String, FloatArray> =
      mapOf(
          "coin" to (tone(listOf(988f), 0.07f) + tone(listOf(1319f), 0.22f)),
          "jump" to tone(listOf(0f), 0.16f, W.SQUARE, 0.25f, 330f to 760f),
          "double_jump" to tone(listOf(0f), 0.16f, W.TRI, 0.4f, 520f to 1200f),
          "stomp" to mix(tone(listOf(0f), 0.14f, W.TRI, 0.5f, 300f to 80f), tone(listOf(0f), 0.14f, W.NOISE, 0.15f)),
          "hurt" to tone(listOf(0f), 0.35f, W.SQUARE, 0.25f, 500f to 120f),
          "fall" to tone(listOf(0f), 0.5f, W.TRI, 0.4f, 900f to 150f),
          "beep" to tone(listOf(660f), 0.15f, W.SQUARE, 0.25f),
          "go" to tone(listOf(1320f), 0.35f, W.SQUARE, 0.25f),
          "win" to (tone(listOf(523f, 659f, 784f, 1047f, 784f, 1047f), 0.9f, W.SQUARE, 0.25f) + tone(listOf(1047f), 0.4f, W.TRI, 0.4f)),
          "land" to tone(listOf(0f), 0.05f, W.TRI, 0.3f, 180f to 90f),
      )

  fun wavBytes(samples: FloatArray): ByteArray {
    val data = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
    for (s in samples) data.putShort((s.coerceIn(-1f, 1f) * 32767).toInt().toShort())
    val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
    h.put("RIFF".toByteArray()).putInt(36 + samples.size * 2).put("WAVE".toByteArray())
    h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(SR).putInt(SR * 2).putShort(2).putShort(16)
    h.put("data".toByteArray()).putInt(samples.size * 2)
    return h.array() + data.array()
  }

  /** Writes all sounds into [dir] (skipping ones that already exist); returns name -> file. */
  fun writeAll(dir: File): Map<String, File> {
    dir.mkdirs()
    return all().mapValues { (name, samples) ->
      val f = File(dir, "rr_$name.wav")
      if (!f.exists() || f.length() < 44) FileOutputStream(f).use { it.write(wavBytes(samples)) }
      f
    }
  }
}
