package com.vibecodingjapan.ideavox

import java.io.File
import java.util.concurrent.CancellationException
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioProcessorTest {
  @Test
  fun mp3Encoder_writesMp3Bytes() {
    val dir = createTempDirectory("luyin-mp3-test").toFile()
    val output = File(dir, "voice.mp3")
    val script = File("src/main/assets/vendor/lame.all.js")

    Mp3Audio.encodePcm16MonoToMp3(ShortArray(WavAudio.SAMPLE_RATE / 2), output, scriptFile = script)

    assertTrue(output.length() > 0)
    assertEquals(0xff.toByte(), output.readBytes().first())
  }

  @Test
  fun process_withMp3Format_mergesAndEncodesMp3() {
    val dir = createTempDirectory("luyin-audio-mp3-process-test").toFile()
    File("src/main/assets/vendor/lame.all.js").copyTo(File(dir, "lame.all.js"))
    val first = File(dir, "first.wav")
    val second = File(dir, "second.wav")
    WavAudio.writeWav(first, ShortArray(1152) { index -> (index % 200).toShort() })
    WavAudio.writeWav(second, ShortArray(1152) { index -> (199 - index % 200).toShort() })

    val output = File(dir, "merged.mp3")
    AudioProcessor.process(
      items = listOf(recordingFor(first), recordingFor(second)),
      output = output,
      format = UploadOutputFormat.MP3,
      speed = 1.0f,
      workDir = dir,
    )

    assertTrue(output.length() > 0)
    assertEquals("audio/mpeg", RecordingUploadMetadata.contentTypeFor(output))
  }

  @Test
  fun process_withMp3FormatAtOneX_keepsExpectedDuration() {
    val dir = createTempDirectory("luyin-audio-mp3-duration-test").toFile()
    File("src/main/assets/vendor/lame.all.js").copyTo(File(dir, "lame.all.js"))
    val durationMs = 10_000L
    val source = File(dir, "source.wav")
    val samples = ShortArray((WavAudio.SAMPLE_RATE * durationMs / 1000L).toInt()) { index ->
      (kotlin.math.sin(index / 22.0) * Short.MAX_VALUE * 0.2).toInt().toShort()
    }
    WavAudio.writeWav(source, samples)

    val output = File(dir, "merged.mp3")
    AudioProcessor.process(
      items = listOf(recordingFor(source)),
      output = output,
      format = UploadOutputFormat.MP3,
      speed = 1.0f,
      workDir = dir,
    )

    assertTrue(output.length() > 0)
    assertEquals(durationMs, AudioProcessor.durationMs(listOf(recordingFor(source)), output, 1.0f))
  }

  @Test
  fun process_withoutCompression_mergesWavInSelectedOrder() {
    val dir = createTempDirectory("luyin-audio-test").toFile()
    val first = File(dir, "first.wav")
    val second = File(dir, "second.wav")
    WavAudio.writeWav(first, shortArrayOf(1, 2, 3))
    WavAudio.writeWav(second, shortArrayOf(4, 5))

    val output = File(dir, "merged.wav")
    AudioProcessor.process(
      items =
        listOf(
          recordingFor(first),
          recordingFor(second),
        ),
      output = output,
      format = UploadOutputFormat.WAV,
      speed = 1.0f,
      workDir = dir,
    )

    assertArrayEquals(shortArrayOf(1, 2, 3, 4, 5), WavAudio.readPcmSamples(output))
  }

  @Test
  fun process_atOneX_keepsEverySample() {
    val dir = createTempDirectory("luyin-audio-one-x-test").toFile()
    val source = File(dir, "source.wav")
    val samples = ShortArray(20_000) { index -> (index % Short.MAX_VALUE).toShort() }
    WavAudio.writeWav(source, samples)

    val output = File(dir, "merged.wav")
    AudioProcessor.process(
      items = listOf(recordingFor(source)),
      output = output,
      format = UploadOutputFormat.WAV,
      speed = 1.0f,
      workDir = dir,
    )

    assertEquals(samples.size, WavAudio.readPcmSamples(output).size)
    assertArrayEquals(samples, WavAudio.readPcmSamples(output))
  }

  @Test
  fun process_normalizesImported16kPcmWavWithMetadataChunk() {
    val dir = createTempDirectory("luyin-imported-wav-test").toFile()
    val source = File(dir, "imported.wav")
    writePcm16MonoWav(source, sampleRate = 16_000, samples = ShortArray(16_000) { (it % 500).toShort() })

    val sourceFormat = requireNotNull(WavAudio.readFormat(source))
    assertEquals(16_000, sourceFormat.sampleRate)
    assertTrue(sourceFormat.dataOffset > WavAudio.HEADER_SIZE)
    assertEquals(1_000L, WavAudio.durationMs(source))

    val output = File(dir, "merged.wav")
    AudioProcessor.process(
      items = listOf(recordingFor(source)),
      output = output,
      format = UploadOutputFormat.WAV,
      speed = 1.0f,
      workDir = dir,
    )

    assertTrue(WavAudio.isCanonicalPcm16(output))
    assertEquals(1_000L, WavAudio.durationMs(output))
    assertEquals(WavAudio.SAMPLE_RATE, WavAudio.readPcmSamples(output).size)
  }

  @Test
  fun process_deletesOutputWhenDurationValidationFails() {
    val dir = createTempDirectory("luyin-audio-invalid-duration-test").toFile()
    val source = File(dir, "source.wav")
    WavAudio.writeWav(source, ShortArray(WavAudio.SAMPLE_RATE))

    val output = File(dir, "merged.wav")
    val error =
      assertThrows(IllegalStateException::class.java) {
        AudioProcessor.process(
          items = listOf(recordingFor(source).copy(durationMs = 10_000L)),
          output = output,
          format = UploadOutputFormat.WAV,
          speed = 1.0f,
          workDir = dir,
        )
      }

    assertTrue(error.message.orEmpty().contains("音频时长异常"))
    assertFalse(output.exists())
  }

  @Test
  fun process_deletesOutputWhenCancelled() {
    val dir = createTempDirectory("luyin-audio-cancel-test").toFile()
    val source = File(dir, "source.wav")
    WavAudio.writeWav(source, ShortArray(WavAudio.SAMPLE_RATE * 2) { it.toShort() })

    val output = File(dir, "cancelled.wav")
    val error =
      assertThrows(CancellationException::class.java) {
        AudioProcessor.process(
          items = listOf(recordingFor(source)),
          output = output,
          format = UploadOutputFormat.WAV,
          speed = 1.0f,
          workDir = dir,
          shouldCancel = { true },
        )
      }

    assertEquals("已取消", error.message)
    assertFalse(output.exists())
  }

  @Test
  fun process_withoutCompression_appliesSpeed() {
    val dir = createTempDirectory("luyin-audio-speed-test").toFile()
    val source = File(dir, "source.wav")
    WavAudio.writeWav(source, shortArrayOf(10, 20, 30, 40, 50, 60))

    val output = File(dir, "speed.wav")
    AudioProcessor.process(
      items = listOf(recordingFor(source)),
      output = output,
      format = UploadOutputFormat.WAV,
      speed = 2.0f,
      workDir = dir,
    )

    assertEquals(3, WavAudio.readPcmSamples(output).size)
    assertArrayEquals(shortArrayOf(10, 30, 50), WavAudio.readPcmSamples(output))
  }

  @Test
  fun durationMs_forCompressedOutputUsesSelectedSpeed() {
    val first = RecordingItem("1", "a.wav", "/tmp/a.wav", 0L, 3_000L, 0L)
    val second = RecordingItem("2", "b.wav", "/tmp/b.wav", 0L, 1_500L, 0L)

    assertEquals(3_000L, AudioProcessor.durationMs(listOf(first, second), File("merged.m4a"), 1.5f))
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

  private fun writePcm16MonoWav(file: File, sampleRate: Int, samples: ShortArray) {
    val metadata = "INFO".toByteArray(Charsets.US_ASCII)
    val dataSize = samples.size * 2
    val riffSize = 4 + (8 + 16) + (8 + metadata.size) + (8 + dataSize)
    file.outputStream().buffered().use { output ->
      fun ascii(value: String) = output.write(value.toByteArray(Charsets.US_ASCII))
      fun shortLe(value: Int) = output.write(byteArrayOf((value and 0xff).toByte(), ((value shr 8) and 0xff).toByte()))
      fun intLe(value: Int) =
        output.write(
          byteArrayOf(
            (value and 0xff).toByte(),
            ((value shr 8) and 0xff).toByte(),
            ((value shr 16) and 0xff).toByte(),
            ((value shr 24) and 0xff).toByte(),
          ),
        )
      ascii("RIFF")
      intLe(riffSize)
      ascii("WAVE")
      ascii("fmt ")
      intLe(16)
      shortLe(1)
      shortLe(1)
      intLe(sampleRate)
      intLe(sampleRate * 2)
      shortLe(2)
      shortLe(16)
      ascii("LIST")
      intLe(metadata.size)
      output.write(metadata)
      ascii("data")
      intLe(dataSize)
      samples.forEach { sample -> shortLe(sample.toInt()) }
    }
  }
}
