package com.vibecodingjapan.ideavox

import org.junit.Assert.assertEquals
import org.junit.Test

class FileNamesTest {
  @Test
  fun safeRecordingFileName_replacesUnsafeCharacters() {
    assertEquals("memo.wav", FileNames.safeRecordingFileName("散歩 memo.wav", "fallback"))
  }

  @Test
  fun safeRecordingFileName_usesFallbackWhenNameIsBlankAfterSanitizing() {
    assertEquals("id-1.wav", FileNames.safeRecordingFileName("   ", "id-1"))
  }

  @Test
  fun safeImportedAudioFileName_preservesSafeNameAndAddsDetectedExtension() {
    assertEquals("meeting.m4a", FileNames.safeImportedAudioFileName("meeting", "id-1", "m4a"))
  }

  @Test
  fun safeImportedAudioFileName_replacesMismatchedExtension() {
    assertEquals("voice.mp3", FileNames.safeImportedAudioFileName("voice.tmp", "id-1", "mp3"))
  }

  @Test
  fun safeImportedAudioFileName_preservesUnicodeName() {
    assertEquals("录音.ogg", FileNames.safeImportedAudioFileName("录音", "id-1", "ogg"))
  }
}
