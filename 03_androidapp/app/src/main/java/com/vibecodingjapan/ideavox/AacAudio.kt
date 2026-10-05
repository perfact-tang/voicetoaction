package com.vibecodingjapan.ideavox

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
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
    return encodeChunksToM4a(output, totalSamples = samples.size.toLong().coerceAtLeast(1L), onProgress = onProgress, shouldCancel = shouldCancel) { requestedSamples ->
      if (offset >= samples.size) {
        null
      } else {
        val count = minOf(requestedSamples, samples.size - offset)
        ShortArray(count) { index -> samples[offset + index] }.also { offset += count }
      }
    }
  }

  fun encodeWavToM4a(
    input: File,
    output: File,
    onProgress: (Int) -> Unit = {},
    shouldCancel: () -> Boolean = { false },
  ): File =
    WavAudio.PcmChunkReader(input, maxSamples = 8192).use { reader ->
      var pending = ShortArray(0)
      var pendingOffset = 0
      val totalSamples = ((input.length() - WavAudio.HEADER_SIZE).coerceAtLeast(0L) / WavAudio.BYTES_PER_SAMPLE).coerceAtLeast(1L)
      encodeChunksToM4a(output, totalSamples, onProgress, shouldCancel) { requestedSamples ->
        val out = ArrayList<Short>(requestedSamples)
        while (out.size < requestedSamples) {
          if (pendingOffset >= pending.size) {
            pending = reader.next() ?: break
            pendingOffset = 0
          }
          val count = minOf(requestedSamples - out.size, pending.size - pendingOffset)
          repeat(count) { out.add(pending[pendingOffset + it]) }
          pendingOffset += count
        }
        if (out.isEmpty()) null else out.toShortArray()
      }
    }

  private fun encodeChunksToM4a(
    output: File,
    totalSamples: Long,
    onProgress: (Int) -> Unit,
    shouldCancel: () -> Boolean,
    nextChunk: (Int) -> ShortArray?,
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
    val bufferInfo = MediaCodec.BufferInfo()

    try {
      codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
      codec.start()

      var inputDone = false
      var outputDone = false
      while (!outputDone) {
        if (shouldCancel()) throw CancellationException("已取消")
        if (!inputDone) {
          val inputBufferIndex = codec.dequeueInputBuffer(TIMEOUT_US)
          if (inputBufferIndex >= 0) {
            val inputBuffer = codec.getInputBuffer(inputBufferIndex) ?: error("AAC input buffer unavailable")
            inputBuffer.clear()
            val sampleCapacity = inputBuffer.remaining() / WavAudio.BYTES_PER_SAMPLE
            val chunk = nextChunk(sampleCapacity)
            val samplesToWrite = chunk?.size ?: 0
            repeat(samplesToWrite) { index ->
              val sample = chunk!![index].toInt()
              inputBuffer.put((sample and 0xff).toByte())
              inputBuffer.put(((sample shr 8) and 0xff).toByte())
            }
            val presentationTimeUs = ((submittedSamples * 1_000_000L) / WavAudio.SAMPLE_RATE)
            val flags = if (chunk == null) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
            codec.queueInputBuffer(inputBufferIndex, 0, samplesToWrite * WavAudio.BYTES_PER_SAMPLE, presentationTimeUs, flags)
            submittedSamples += samplesToWrite
            onProgress(((submittedSamples * 100) / totalSamples).toInt().coerceIn(0, 99))
            inputDone = flags != 0
          }
        }

        when (val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)) {
          MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
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
      }
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
