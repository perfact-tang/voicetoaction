package com.vibecodingjapan.ideavox

import android.content.Context
import org.mozilla.javascript.Context as JsContext
import org.mozilla.javascript.Function
import org.mozilla.javascript.NativeArray
import org.mozilla.javascript.Scriptable
import java.io.File
import java.util.concurrent.CancellationException

object Mp3Audio {
  private const val KBPS = 64
  private const val SAMPLES_PER_CHUNK = 1152
  private const val TYPED_ARRAY_POLYFILL =
    """
    function __luyinArray(input) {
      var length = typeof input === 'number' ? input : (input ? input.length : 0);
      var array = new Array(length);
      for (var i = 0; i < length; i++) array[i] = typeof input === 'number' ? 0 : input[i];
      array.subarray = function(start, end) {
        var slice = this.slice(start, end);
        return __luyinArray(slice);
      };
      return array;
    }
    var Int8Array = __luyinArray;
    var Int16Array = __luyinArray;
    var Int32Array = __luyinArray;
    var Float32Array = __luyinArray;
    var Float64Array = __luyinArray;
    var Uint8Array = __luyinArray;
    """

  fun encodePcm16MonoToMp3(
    samples: ShortArray,
    output: File,
    context: Context? = null,
    scriptFile: File? = null,
    onProgress: (Int) -> Unit = {},
    shouldCancel: () -> Boolean = { false },
  ): File {
    output.parentFile?.mkdirs()
    val script = when {
      scriptFile != null -> scriptFile.readText()
      context != null -> context.assets.open("vendor/lame.all.js").use { it.reader().readText() }
      else -> error("MP3 encoder script source is required")
    }
    return encodeWithScript(output, script, totalSamples = samples.size.toLong(), onProgress = onProgress, shouldCancel = shouldCancel) { consume ->
      var offset = 0
      while (offset < samples.size) {
        if (shouldCancel()) throw CancellationException("已取消")
        val count = minOf(SAMPLES_PER_CHUNK, samples.size - offset)
        consume(ShortArray(count) { samples[offset + it] })
        offset += count
      }
    }
  }

  fun encodeWavToMp3(
    input: File,
    output: File,
    context: Context? = null,
    scriptFile: File? = null,
    onProgress: (Int) -> Unit = {},
    shouldCancel: () -> Boolean = { false },
  ): File {
    output.parentFile?.mkdirs()
    val script = when {
      scriptFile != null -> scriptFile.readText()
      context != null -> context.assets.open("vendor/lame.all.js").use { it.reader().readText() }
      else -> error("MP3 encoder script source is required")
    }
    val totalSamples = ((input.length() - WavAudio.HEADER_SIZE).coerceAtLeast(0L) / WavAudio.BYTES_PER_SAMPLE).coerceAtLeast(1L)
    return encodeWithScript(output, script, totalSamples, onProgress, shouldCancel) { consume ->
      WavAudio.forEachPcmChunk(input, SAMPLES_PER_CHUNK) { chunk ->
        if (shouldCancel()) throw CancellationException("已取消")
        consume(chunk)
      }
    }
  }

  private fun encodeWithScript(
    output: File,
    script: String,
    totalSamples: Long,
    onProgress: (Int) -> Unit,
    shouldCancel: () -> Boolean,
    writeInput: ((ShortArray) -> Unit) -> Unit,
  ): File {
    if (output.exists()) output.delete()
    val js = JsContext.enter()
    js.optimizationLevel = -1
    try {
      val scope = js.initStandardObjects()
      js.evaluateString(scope, TYPED_ARRAY_POLYFILL, "luyin-typed-array-polyfill.js", 1, null)
      js.evaluateString(scope, script, "lame.all.js", 1, null)
      val lame = scope.get("lamejs", scope) as Scriptable
      val encoderCtor = lame.get("Mp3Encoder", lame) as Function
      val encoder = encoderCtor.construct(js, scope, arrayOf(1, WavAudio.SAMPLE_RATE, KBPS))
      output.outputStream().use { stream ->
        var processedSamples = 0L
        writeInput { chunk ->
          if (shouldCancel()) throw CancellationException("已取消")
          val encoded = callEncoder(js, scope, encoder, "encodeBuffer", chunk)
          stream.write(encoded)
          processedSamples += chunk.size
          onProgress(((processedSamples * 100) / totalSamples).toInt().coerceIn(0, 99))
        }
        stream.write(callEncoder(js, scope, encoder, "flush"))
      }
      onProgress(100)
      return output
    } finally {
      JsContext.exit()
    }
  }

  private fun callEncoder(
    js: JsContext,
    scope: Scriptable,
    encoder: Scriptable,
    methodName: String,
    samples: ShortArray? = null,
  ): ByteArray {
    val method = encoder.get(methodName, encoder) as Function
    val args = samples?.let { arrayOf(shortArrayToJsArray(js, scope, it)) } ?: emptyArray()
    val result = method.call(js, scope, encoder, args)
    return jsArrayToBytes(result as NativeArray)
  }

  private fun shortArrayToJsArray(js: JsContext, scope: Scriptable, samples: ShortArray): NativeArray {
    val boxed = Array<Any>(samples.size) { index -> samples[index].toInt() }
    return js.newArray(scope, boxed) as NativeArray
  }

  private fun jsArrayToBytes(array: NativeArray): ByteArray =
    ByteArray(array.length.toInt()) { index ->
      val value = array.get(index, array)
      (value as Number).toInt().toByte()
    }
}
