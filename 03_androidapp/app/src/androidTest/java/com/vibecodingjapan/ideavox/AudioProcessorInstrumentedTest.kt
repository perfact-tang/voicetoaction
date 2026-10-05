package com.vibecodingjapan.ideavox

import android.media.MediaMetadataRetriever
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlin.math.abs
import org.junit.Assert.assertTrue
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
    assertTrue(
      "Expected about $durationMs ms, got $actualDurationMs ms",
      abs(actualDurationMs - durationMs) <= 1_000L,
    )
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
