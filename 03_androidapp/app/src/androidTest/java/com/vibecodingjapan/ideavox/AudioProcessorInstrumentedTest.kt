package com.vibecodingjapan.ideavox

import android.media.MediaMetadataRetriever
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import java.util.concurrent.CancellationException
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AudioProcessorInstrumentedTest {
  @Test
  fun process_m4aAtOneX_keepsFullDuration() {
    assertM4aAtOneXKeepsDuration(durationMs = 5_000L, filePrefix = "short")
  }

  @Test
  fun process_m4aAtOneX_keepsLongDuration() {
    assertM4aAtOneXKeepsDuration(durationMs = 120_000L, filePrefix = "long")
  }

  @Test
  fun process_m4aMergedAtOneX_keepsBothRecordings() {
    assertMergedM4aDuration(speed = 1.0f)
  }

  @Test
  fun process_m4aMergedAtTwoX_keepsExpectedDuration() {
    assertMergedM4aDuration(speed = 2.0f)
  }

  @Test
  fun process_m4aCancellation_preservesSourceAndRemovesPartialOutput() {
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    val dir = File(context.cacheDir, "audio-processor-cancel").apply { mkdirs() }
    try {
      val source = File(dir, "source.wav")
      val output = File(dir, "cancelled.m4a")
      WavAudio.writeWav(source, ShortArray(WavAudio.SAMPLE_RATE * 5))
      var cancel = false
      assertThrows(CancellationException::class.java) {
        AudioProcessor.process(
          items = listOf(recordingFor(source)), output = output,
          format = UploadOutputFormat.AAC_M4A, speed = 1.0f, workDir = dir, context = context,
          onProgress = { _, progress -> if (progress > 55) cancel = true },
          shouldCancel = { cancel },
        )
      }
      assertTrue(source.exists())
      assertEquals(5_000L, WavAudio.durationMs(source))
      assertFalse(output.exists())
    } finally {
      dir.deleteRecursively()
    }
  }

  private fun assertMergedM4aDuration(speed: Float) {
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    val dir = File(context.cacheDir, "audio-processor-merged-$speed").apply { mkdirs() }
    try {
      val sources = listOf(File(dir, "first.wav"), File(dir, "second.wav"))
      sources.forEach { WavAudio.writeWav(it, ShortArray(WavAudio.SAMPLE_RATE * 5) { i -> (kotlin.math.sin(i / 18.0) * 8000).toInt().toShort() }) }
      val output = File(dir, "merged.m4a")
      AudioProcessor.process(
        items = sources.map(::recordingFor), output = output,
        format = UploadOutputFormat.AAC_M4A, speed = speed, workDir = dir, context = context,
      )
      assertTrue(abs(mediaDurationMs(output) - (10_000L / speed).toLong()) <= 1_000L)
      assertTrue(sources.all { it.exists() })
      assertFalse(dir.listFiles().orEmpty().any { it.name.startsWith("merged-source-") })
    } finally {
      dir.deleteRecursively()
    }
  }

  private fun assertM4aAtOneXKeepsDuration(durationMs: Long, filePrefix: String) {
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    val dir = File(context.cacheDir, "audio-processor-instrumented-$filePrefix").apply { mkdirs() }
    val source = File(dir, "$filePrefix-source.wav")
    val output = File(dir, "$filePrefix-merged.m4a")
    val samples = ShortArray((WavAudio.SAMPLE_RATE * durationMs / 1000L).toInt()) { index ->
      (kotlin.math.sin(index / 18.0) * Short.MAX_VALUE * 0.25).toInt().toShort()
    }
    WavAudio.writeWav(source, samples)

    AudioProcessor.process(
      items = listOf(recordingFor(source)),
      output = output,
      format = UploadOutputFormat.AAC_M4A,
      speed = 1.0f,
      workDir = dir,
      context = context,
    )

    val actualDurationMs = mediaDurationMs(output)
    assertTrue("M4A output should exist", output.length() > 0L)
    assertTrue("Original recording should be retained", source.exists())
    assertFalse(dir.listFiles().orEmpty().any { it.name.startsWith("merged-source-") })
    assertTrue(
      "Expected about $durationMs ms, got $actualDurationMs ms",
      abs(actualDurationMs - durationMs) <= 1_000L,
    )
    dir.deleteRecursively()
  }

  private fun mediaDurationMs(file: File): Long {
    val retriever = MediaMetadataRetriever()
    return try {
      retriever.setDataSource(file.absolutePath)
      retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
    } finally {
      retriever.release()
    }
  }

  private fun recordingFor(file: File): RecordingItem =
    RecordingItem(
      id = file.name,
      name = file.name,
      filePath = file.absolutePath,
      createdAt = 0L,
      durationMs = WavAudio.durationMs(file),
      sizeBytes = file.length(),
    )
}
