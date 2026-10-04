package com.thehumanworks.roomrunner

import com.thehumanworks.roomrunner.view.SoundSynth
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SoundSynthTest {
  @Test
  fun writesValidWavs() {
    val dir = Files.createTempDirectory("sfx").toFile()
    val files = SoundSynth.writeAll(dir)
    assertEquals(10, files.size)
    for ((name, f) in files) {
      val b = f.readBytes()
      assertEquals("RIFF", String(b, 0, 4))
      assertEquals("WAVE", String(b, 8, 4))
      assertTrue("$name has audio", b.size > 1000)
    }
  }
}
