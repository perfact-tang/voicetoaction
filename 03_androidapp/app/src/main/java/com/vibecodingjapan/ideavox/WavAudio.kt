package com.vibecodingjapan.ideavox

import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.concurrent.CancellationException
import kotlin.math.floor

object WavAudio {
  const val SAMPLE_RATE = 44_100
  const val CHANNELS = 1
  const val BITS_PER_SAMPLE = 16
  const val BYTES_PER_SAMPLE = 2
  const val HEADER_SIZE = 44

  data class Format(
    val encoding: Int,
    val channels: Int,
    val sampleRate: Int,
    val byteRate: Int,
    val blockAlign: Int,
    val bitsPerSample: Int,
    val dataOffset: Long,
    val dataSize: Long,
  ) {
    val durationMs: Long
      get() = if (byteRate > 0) (dataSize * 1000L) / byteRate else 0L
  }

  fun createEmptyWav(file: File) {
    file.parentFile?.mkdirs()
    RandomAccessFile(file, "rw").use {
      it.setLength(0)
      writeHeader(it, 0L)
    }
  }

  fun updateHeader(file: RandomAccessFile, pcmBytes: Long) {
    file.seek(0)
    writeHeader(file, pcmBytes)
  }

  fun durationMs(file: File): Long {
    return readFormat(file)?.durationMs
      ?: ((file.length() - HEADER_SIZE).coerceAtLeast(0) * 1000L) / (SAMPLE_RATE * CHANNELS * BYTES_PER_SAMPLE)
  }

  fun isCanonicalPcm16(file: File): Boolean =
    readFormat(file)?.let { format ->
      format.encoding == PCM_ENCODING &&
        format.channels == CHANNELS &&
        format.sampleRate == SAMPLE_RATE &&
        format.bitsPerSample == BITS_PER_SAMPLE &&
        format.blockAlign == BYTES_PER_SAMPLE &&
        format.dataOffset == HEADER_SIZE.toLong() &&
        format.dataSize == (file.length() - HEADER_SIZE).coerceAtLeast(0L)
    } == true

  fun normalizePcm16ToCanonical(
    input: File,
    output: File,
    onProgress: (Int) -> Unit = {},
    shouldCancel: () -> Boolean = { false },
  ): File {
    val format = requireNotNull(readFormat(input)) { "无法读取 WAV 文件头：${input.name}" }
    require(format.encoding == PCM_ENCODING) { "暂不支持此 WAV 编码格式：${input.name}" }
    require(format.bitsPerSample == BITS_PER_SAMPLE) { "暂不支持 ${format.bitsPerSample}-bit WAV：${input.name}" }
    require(format.channels > 0 && format.sampleRate > 0 && format.blockAlign >= format.channels * BYTES_PER_SAMPLE) {
      "WAV 音频参数无效：${input.name}"
    }

    createEmptyWav(output)
    var outputPcmBytes = 0L
    try {
      RandomAccessFile(input, "r").use { source ->
        source.seek(format.dataOffset)
        Pcm16Output(FileOutputStream(output, true)).use { target ->
          val framesPerChunk = 8192
          val buffer = ByteArray(framesPerChunk * format.blockAlign)
          var sourceFrameIndex = 0L
          var outputFrameIndex = 0L
          var consumedBytes = 0L
          while (consumedBytes < format.dataSize) {
            if (shouldCancel()) throw CancellationException("已取消")
            val remaining = format.dataSize - consumedBytes
            val count = minOf(buffer.size.toLong(), remaining).toInt()
            val alignedCount = count - (count % format.blockAlign)
            if (alignedCount <= 0) break
            source.readFully(buffer, 0, alignedCount)
            var offset = 0
            while (offset < alignedCount) {
              var mixed = 0L
              repeat(format.channels) { channel ->
                val sampleOffset = offset + channel * BYTES_PER_SAMPLE
                val low = buffer[sampleOffset].toInt() and 0xff
                val high = buffer[sampleOffset + 1].toInt()
                mixed += ((high shl 8) or low).toShort().toInt()
              }
              val monoSample = (mixed / format.channels).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
              while ((outputFrameIndex * format.sampleRate) / SAMPLE_RATE <= sourceFrameIndex) {
                target.write(monoSample)
                outputPcmBytes += BYTES_PER_SAMPLE
                outputFrameIndex += 1
              }
              sourceFrameIndex += 1
              offset += format.blockAlign
            }
            consumedBytes += alignedCount
            onProgress(((consumedBytes * 100L) / format.dataSize.coerceAtLeast(1L)).toInt().coerceIn(0, 99))
          }
        }
      }
      RandomAccessFile(output, "rw").use { updateHeader(it, outputPcmBytes) }
      onProgress(100)
      return output
    } catch (error: Exception) {
      output.delete()
      throw error
    }
  }

  fun readFormat(file: File): Format? =
    runCatching {
        RandomAccessFile(file, "r").use { input ->
          if (input.length() < 12L || input.readFourCc() != "RIFF") return@use null
          input.skipBytes(4)
          if (input.readFourCc() != "WAVE") return@use null
          var encoding = 0
          var channels = 0
          var sampleRate = 0
          var byteRate = 0
          var blockAlign = 0
          var bitsPerSample = 0
          var dataOffset = -1L
          var dataSize = 0L
          while (input.filePointer + 8L <= input.length()) {
            val chunkId = input.readFourCc()
            val declaredSize = input.readUnsignedIntLe()
            val chunkStart = input.filePointer
            val availableSize = minOf(declaredSize, (input.length() - chunkStart).coerceAtLeast(0L))
            when (chunkId) {
              "fmt " -> if (availableSize >= 16L) {
                encoding = input.readUnsignedShortLe()
                channels = input.readUnsignedShortLe()
                sampleRate = input.readIntLe()
                byteRate = input.readIntLe()
                blockAlign = input.readUnsignedShortLe()
                bitsPerSample = input.readUnsignedShortLe()
                if (encoding == EXTENSIBLE_ENCODING && availableSize >= 40L) {
                  input.seek(chunkStart + 24L)
                  if (input.readUnsignedShortLe() == PCM_ENCODING) encoding = PCM_ENCODING
                }
              }
              "data" -> {
                dataOffset = chunkStart
                dataSize = availableSize
              }
            }
            val nextChunk = chunkStart + declaredSize + (declaredSize and 1L)
            if (nextChunk < input.filePointer || nextChunk > input.length()) break
            input.seek(nextChunk)
          }
          if (encoding == 0 || dataOffset < 0L) null
          else Format(encoding, channels, sampleRate, byteRate, blockAlign, bitsPerSample, dataOffset, dataSize)
        }
      }
      .getOrNull()

  fun mergeAndSpeed(
    inputs: List<File>,
    output: File,
    speed: Float,
    onProgress: (Int) -> Unit = {},
    shouldCancel: () -> Boolean = { false },
  ): File {
    inputs.forEach(::requireCanonicalPcm16)
    require(speed > 0f) { "speed must be positive" }
    require(speed >= 1.0f) { "upload speed must be 1.0x or faster" }
    val totalPcmBytes = inputs.sumOf { (it.length() - HEADER_SIZE).coerceAtLeast(0L) }.coerceAtLeast(1L)
    output.parentFile?.mkdirs()
    RandomAccessFile(output, "rw").use { file ->
      file.setLength(0)
      writeHeader(file, 0L)
      var inputSampleIndex = 0L
      var outputSampleIndex = 0L
      var pcmBytes = 0L
      var consumedBytes = 0L
      Pcm16Output(FileOutputStream(output, true)).use { target ->
        inputs.forEachPcmSample { sample ->
          if (shouldCancel()) throw CancellationException("已取消")
          val nextSourceIndex = floor(outputSampleIndex * speed).toLong()
          if (inputSampleIndex == nextSourceIndex) {
            target.write(sample.toInt())
            pcmBytes += BYTES_PER_SAMPLE
            outputSampleIndex += 1
          }
          inputSampleIndex += 1
          consumedBytes += BYTES_PER_SAMPLE
          if (consumedBytes % (BYTES_PER_SAMPLE * 8192L) == 0L) {
            onProgress(((consumedBytes * 55) / totalPcmBytes).toInt().coerceIn(1, 55))
          }
        }
      }
      updateHeader(file, pcmBytes)
    }
    onProgress(55)
    return output
  }

  fun merge(
    inputs: List<File>,
    output: File,
    onProgress: (Int) -> Unit = {},
    shouldCancel: () -> Boolean = { false },
  ): File {
    inputs.forEach(::requireCanonicalPcm16)
    val totalPcmBytes = inputs.sumOf { (it.length() - HEADER_SIZE).coerceAtLeast(0L) }.coerceAtLeast(1L)
    output.parentFile?.mkdirs()
    RandomAccessFile(output, "rw").use { file ->
      file.setLength(0)
      writeHeader(file, 0L)
      var pcmBytes = 0L
      inputs.forEach { input ->
        PcmByteChunkReader(input).use { reader ->
          while (true) {
            if (shouldCancel()) throw CancellationException("已取消")
            val chunk = reader.next() ?: break
            file.write(chunk)
            pcmBytes += chunk.size
            onProgress(((pcmBytes * 55) / totalPcmBytes).toInt().coerceIn(1, 55))
          }
        }
      }
      updateHeader(file, pcmBytes)
    }
    onProgress(55)
    return output
  }

  fun writeWav(output: File, samples: ShortArray) {
    output.parentFile?.mkdirs()
    RandomAccessFile(output, "rw").use { file ->
      file.setLength(0)
      writeHeader(file, (samples.size * BYTES_PER_SAMPLE).toLong())
      val bytes = ByteArray(samples.size * BYTES_PER_SAMPLE)
      samples.forEachIndexed { index, sample ->
        bytes[index * 2] = (sample.toInt() and 0xff).toByte()
        bytes[index * 2 + 1] = ((sample.toInt() shr 8) and 0xff).toByte()
      }
      file.write(bytes)
    }
  }

  fun readPcmSamples(file: File): ShortArray {
    val bytes = file.readBytes()
    if (bytes.size <= HEADER_SIZE) return ShortArray(0)
    val pcmBytes = bytes.copyOfRange(HEADER_SIZE, bytes.size)
    val samples = ShortArray(pcmBytes.size / BYTES_PER_SAMPLE)
    samples.indices.forEach { index ->
      val low = pcmBytes[index * 2].toInt() and 0xff
      val high = pcmBytes[index * 2 + 1].toInt()
      samples[index] = ((high shl 8) or low).toShort()
    }
    return samples
  }

  fun forEachPcmChunk(file: File, maxSamples: Int, consume: (ShortArray) -> Unit) {
    PcmChunkReader(file, maxSamples).use { reader ->
      while (true) {
        val chunk = reader.next() ?: break
        consume(chunk)
      }
    }
  }

  class PcmChunkReader(file: File, private val maxSamples: Int) : Closeable {
    private val input = RandomAccessFile(file, "r")
    private val bytes = ByteArray(maxSamples * BYTES_PER_SAMPLE)

    init {
      input.seek(HEADER_SIZE.toLong())
    }

    fun next(): ShortArray? {
      val read = input.read(bytes)
      if (read <= 0) return null
      val evenRead = read - (read % BYTES_PER_SAMPLE)
      if (evenRead <= 0) return null
      val samples = ShortArray(evenRead / BYTES_PER_SAMPLE)
      samples.indices.forEach { index ->
        val low = bytes[index * 2].toInt() and 0xff
        val high = bytes[index * 2 + 1].toInt()
        samples[index] = ((high shl 8) or low).toShort()
      }
      return samples
    }

    override fun close() {
      input.close()
    }
  }

  private class PcmByteChunkReader(file: File) : Closeable {
    private val input = RandomAccessFile(file, "r")
    private val bytes = ByteArray(64 * 1024)

    init {
      input.seek(HEADER_SIZE.toLong())
    }

    fun next(): ByteArray? {
      val read = input.read(bytes)
      if (read <= 0) return null
      val evenRead = read - (read % BYTES_PER_SAMPLE)
      if (evenRead <= 0) return null
      return bytes.copyOf(evenRead)
    }

    override fun close() {
      input.close()
    }
  }

  fun resampleForSpeed(samples: ShortArray, speed: Float): ShortArray {
    if (samples.isEmpty()) return samples
    val outputSize = floor(samples.size / speed).toInt().coerceAtLeast(1)
    return ShortArray(outputSize) { outputIndex ->
      val sourceIndex = floor(outputIndex * speed).toInt().coerceIn(samples.indices)
      samples[sourceIndex]
    }
  }

  private fun writeHeader(file: RandomAccessFile, pcmBytes: Long) {
    val byteRate = SAMPLE_RATE * CHANNELS * BYTES_PER_SAMPLE
    val blockAlign = CHANNELS * BYTES_PER_SAMPLE
    file.writeAscii("RIFF")
    file.writeIntLe((36 + pcmBytes).toInt())
    file.writeAscii("WAVE")
    file.writeAscii("fmt ")
    file.writeIntLe(16)
    file.writeShortLe(1)
    file.writeShortLe(CHANNELS)
    file.writeIntLe(SAMPLE_RATE)
    file.writeIntLe(byteRate)
    file.writeShortLe(blockAlign)
    file.writeShortLe(BITS_PER_SAMPLE)
    file.writeAscii("data")
    file.writeIntLe(pcmBytes.toInt())
  }

  private fun RandomAccessFile.writeAscii(value: String) = write(value.toByteArray(Charsets.US_ASCII))

  private fun RandomAccessFile.writeIntLe(value: Int) {
    write(byteArrayOf((value and 0xff).toByte(), ((value shr 8) and 0xff).toByte(), ((value shr 16) and 0xff).toByte(), ((value shr 24) and 0xff).toByte()))
  }

  private fun RandomAccessFile.writeShortLe(value: Int) {
    write(byteArrayOf((value and 0xff).toByte(), ((value shr 8) and 0xff).toByte()))
  }

  private fun requireCanonicalPcm16(file: File) {
    require(isCanonicalPcm16(file)) { "WAV must be 44.1kHz mono PCM16: ${file.name}" }
  }

  private fun RandomAccessFile.readFourCc(): String {
    val bytes = ByteArray(4)
    readFully(bytes)
    return bytes.toString(Charsets.US_ASCII)
  }

  private fun RandomAccessFile.readUnsignedIntLe(): Long = readIntLe().toLong() and 0xffffffffL

  private fun RandomAccessFile.readIntLe(): Int {
    val b0 = readUnsignedByte()
    val b1 = readUnsignedByte()
    val b2 = readUnsignedByte()
    val b3 = readUnsignedByte()
    return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
  }

  private fun RandomAccessFile.readUnsignedShortLe(): Int {
    val low = readUnsignedByte()
    val high = readUnsignedByte()
    return low or (high shl 8)
  }

  private class Pcm16Output(output: FileOutputStream) : Closeable {
    private val stream = BufferedOutputStream(output, 64 * 1024)
    private val buffer = ByteArray(64 * 1024)
    private var position = 0

    fun write(sample: Int) {
      if (position + 2 > buffer.size) flushBuffer()
      buffer[position] = (sample and 0xff).toByte()
      buffer[position + 1] = ((sample shr 8) and 0xff).toByte()
      position += 2
    }

    private fun flushBuffer() {
      if (position > 0) stream.write(buffer, 0, position)
      position = 0
    }

    override fun close() {
      flushBuffer()
      stream.close()
    }
  }

  private fun List<File>.forEachPcmSample(consume: (Short) -> Unit) {
    forEach { file ->
      forEachPcmChunk(file, maxSamples = 8192) { chunk ->
        chunk.forEach(consume)
      }
    }
  }

  private const val PCM_ENCODING = 1
  private const val EXTENSIBLE_ENCODING = 0xfffe
}
