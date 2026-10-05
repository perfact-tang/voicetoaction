package com.vibecodingjapan.ideavox

import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingAvailabilityTest {
  @Test
  fun isLocalRecordingAvailable_requiresExistingLocalFile() {
    val dir = createTempDirectory("luyin-availability").toFile()
    val file = File(dir, "recording.wav").apply { writeText("wav") }

    assertTrue(isLocalRecordingAvailable(recording(file.absolutePath)))
    assertFalse(isLocalRecordingAvailable(recording("")))
    assertFalse(isLocalRecordingAvailable(recording(File(dir, "missing.wav").absolutePath)))
  }

  private fun recording(path: String): RecordingItem =
    RecordingItem(
      id = path,
      name = "recording.wav",
      filePath = path,
      createdAt = 0L,
      durationMs = 0L,
      sizeBytes = 0L,
    )
}
