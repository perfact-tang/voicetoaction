package com.vibecodingjapan.ideavox

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException

object AacAudio {
  private const val MIME_TYPE = MediaFormat.MIMETYPE_AUDIO_AAC
  private const val BIT_RATE = 64_000
  private const val TIMEOUT_US = 10_000L

  fun encodePcm16MonoToM4a(
    samples: ShortArray,
    output: File,
    onProgress: (Int) -> Unit = {},
    shouldCancel: () -> Boolean = { false },
  ): File {
    var offset = 0
    return encodeChunksToM4a(output, totalSamples = samples.size.toLong().coerceAtLeast(1L), onProgress = onProgress, shouldCancel = shouldCancel) { buffer ->
      if (offset >= samples.size) {
        -1
      } else {
        val count = minOf(buffer.remaining() / WavAudio.BYTES_PER_SAMPLE, samples.size - offset)
        repeat(count) { buffer.putShort(samples[offset + it]) }
        offset += count
        count * WavAudio.BYTES_PER_SAMPLE
      }
    }
  }

  fun encodeWavToM4a(
    input: File,
    output: File,
    onProgress: (Int) -> Unit = {},
    shouldCancel: () -> Boolean = { false },
  ): File =
    RandomAccessFile(input, "r").use { reader ->
      require(WavAudio.isCanonicalPcm16(input)) { "AAC input must be canonical PCM16 WAV" }
      reader.seek(WavAudio.HEADER_SIZE.toLong())
      val bytes = ByteArray(64 * 1024)
      val totalSamples = ((input.length() - WavAudio.HEADER_SIZE).coerceAtLeast(0L) / WavAudio.BYTES_PER_SAMPLE).coerceAtLeast(1L)
      encodeChunksToM4a(output, totalSamples, onProgress, shouldCancel) { buffer ->
        val count = minOf(bytes.size.toLong(), buffer.remaining().toLong(), reader.length() - reader.filePointer).toInt()
        val alignedCount = count - count % WavAudio.BYTES_PER_SAMPLE
        if (alignedCount == 0) {
          -1
        } else {
          reader.readFully(bytes, 0, alignedCount)
          buffer.put(bytes, 0, alignedCount)
          alignedCount
        }
      }
    }

  private fun encodeChunksToM4a(
    output: File,
    totalSamples: Long,
    onProgress: (Int) -> Unit,
    shouldCancel: () -> Boolean,
    nextChunk: (ByteBuffer) -> Int,
  ): File {
    output.parentFile?.mkdirs()
    if (output.exists()) output.delete()

    val format =
      MediaFormat.createAudioFormat(MIME_TYPE, WavAudio.SAMPLE_RATE, WavAudio.CHANNELS).apply {
        setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
      }
    val codec = MediaCodec.createEncoderByType(MIME_TYPE)
    val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

    var muxerStarted = false
    var trackIndex = -1
    var submittedSamples = 0L
    var lastProgress = -1
    val startedAt = SystemClock.elapsedRealtime()
    val bufferInfo = MediaCodec.BufferInfo()

    try {
      codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
      codec.start()

      var inputDone = false
      var outputDone = false
      while (!outputDone) {
        if (shouldCancel()) throw CancellationException("已取消")
        var queuedInputs = 0
        // Fill available inputs before draining outputs; only block when neither side can advance.
        while (!inputDone && queuedInputs < 8) {
          val inputBufferIndex = codec.dequeueInputBuffer(0L)
          if (inputBufferIndex < 0) break
          if (shouldCancel()) throw CancellationException("已取消")
          val inputBuffer = codec.getInputBuffer(inputBufferIndex) ?: error("AAC input buffer unavailable")
          inputBuffer.clear()
          inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
          check(inputBuffer.remaining() >= WavAudio.BYTES_PER_SAMPLE) { "AAC input buffer is too small" }
          val bytesRead = nextChunk(inputBuffer)
          val bytesToWrite = bytesRead.coerceAtLeast(0)
          val presentationTimeUs = ((submittedSamples * 1_000_000L) / WavAudio.SAMPLE_RATE)
          val flags = if (bytesRead < 0) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
          codec.queueInputBuffer(inputBufferIndex, 0, bytesToWrite, presentationTimeUs, flags)
          submittedSamples += bytesToWrite / WavAudio.BYTES_PER_SAMPLE
          val progress = ((submittedSamples * 100) / totalSamples).toInt().coerceIn(0, 99)
          if (progress != lastProgress) {
            onProgress(progress)
            lastProgress = progress
          }
          inputDone = flags != 0
          queuedInputs += 1
        }

        var drainedOutputs = 0
        while (!outputDone && drainedOutputs < 16) {
          if (shouldCancel()) throw CancellationException("已取消")
          val timeoutUs = if (queuedInputs == 0 && drainedOutputs == 0) TIMEOUT_US else 0L
          when (val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, timeoutUs)) {
            MediaCodec.INFO_TRY_AGAIN_LATER -> break
            MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
              check(!muxerStarted) { "AAC output format changed after muxer start" }
              trackIndex = muxer.addTrack(codec.outputFormat)
              muxer.start()
              muxerStarted = true
            }
            else -> {
              if (outputBufferIndex >= 0) {
                val outputBuffer = codec.getOutputBuffer(outputBufferIndex) ?: error("AAC output buffer unavailable")
                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                  bufferInfo.size = 0
                }
                if (bufferInfo.size > 0) {
                  check(muxerStarted) { "AAC muxer has not started" }
                  outputBuffer.position(bufferInfo.offset)
                  outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                  muxer.writeSampleData(trackIndex, outputBuffer, bufferInfo)
                }
                outputDone = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                codec.releaseOutputBuffer(outputBufferIndex, false)
              }
            }
          }
          drainedOutputs += 1
        }
      }
      Log.i("AacAudio", "codec=${codec.name}, samples=$submittedSamples, encodeMs=${SystemClock.elapsedRealtime() - startedAt}")
    } finally {
      runCatching { codec.stop() }
      codec.release()
      runCatching { muxer.stop() }
      muxer.release()
    }
    onProgress(100)
    return output
  }
}
