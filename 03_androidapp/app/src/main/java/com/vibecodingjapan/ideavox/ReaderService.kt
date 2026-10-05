package com.vibecodingjapan.ideavox

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Locale

class ReaderService : Service(), TextToSpeech.OnInitListener {
  private var tts: TextToSpeech? = null
  private var pendingText = ""
  private var pendingLanguage = ReaderLanguage.ZH
  private var pendingSpeed = 1.0f
  private var chunks: List<TtsChunk> = emptyList()
  private var chunkIndex = 0
  @Volatile private var ttsReady = false
  @Volatile private var paused = false

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    tts = TextToSpeech(this, this)
    tts?.setOnUtteranceProgressListener(
      object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit

        override fun onDone(utteranceId: String?) {
          if (paused) return
          chunkIndex += 1
          if (chunkIndex < chunks.size) {
            speakCurrentChunk()
          } else {
            ReaderRuntime.playing.value = false
            ReaderRuntime.paused.value = false
            stopForeground(STOP_FOREGROUND_REMOVE)
          }
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
          ReaderRuntime.playing.value = false
        }

        override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
          val chunk = chunks.getOrNull(chunkIndex) ?: return
          ReaderRuntime.currentRange.value = (chunk.start + start) to (chunk.start + end)
        }
      }
    )
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_SPEAK -> {
        pendingText = intent.getStringExtra(EXTRA_TEXT).orEmpty()
        pendingLanguage = ReaderLanguage.valueOf(intent.getStringExtra(EXTRA_LANGUAGE) ?: ReaderLanguage.ZH.name)
        pendingSpeed = normalizeReaderSpeed(intent.getFloatExtra(EXTRA_SPEED, 1.0f))
        val startOffset = intent.getIntExtra(EXTRA_START_OFFSET, 0).coerceIn(0, pendingText.length)
        chunks = splitForTtsChunks(pendingText, startOffset)
        chunkIndex = 0
        paused = false
        ReaderRuntime.textLength.value = pendingText.length
        ReaderRuntime.currentRange.value = startOffset to startOffset
        startForeground(1002, notification("正在朗读"))
        speakIfReady()
      }
      ACTION_RESUME -> resumeReader()
      ACTION_PAUSE -> pauseReader()
      ACTION_STOP -> stopReader()
      ACTION_SET_SPEED -> setSpeed(intent.getFloatExtra(EXTRA_SPEED, pendingSpeed))
    }
    return START_STICKY
  }

  override fun onInit(status: Int) {
    ttsReady = status == TextToSpeech.SUCCESS
    if (ttsReady) speakIfReady()
  }

  private fun speakIfReady() {
    if (!ttsReady) return
    val engine = tts ?: return
    if (chunks.isEmpty()) return
    engine.language = Locale.forLanguageTag(pendingLanguage.tag)
    engine.setSpeechRate(pendingSpeed)
    speakCurrentChunk()
  }

  private fun speakCurrentChunk() {
    val engine = tts ?: return
    val text = chunks.getOrNull(chunkIndex) ?: return
    ReaderRuntime.playing.value = true
    ReaderRuntime.paused.value = false
    ReaderRuntime.currentRange.value = text.start to text.start
    engine.speak(text.text, TextToSpeech.QUEUE_FLUSH, null, "luyin-reader-$chunkIndex")
  }

  private fun resumeReader() {
    if (chunks.isEmpty()) return
    paused = false
    startForeground(1002, notification("正在朗读"))
    speakIfReady()
  }

  private fun stopReader() {
    tts?.stop()
    paused = false
    chunks = emptyList()
    chunkIndex = 0
    ReaderRuntime.playing.value = false
    ReaderRuntime.paused.value = false
    ReaderRuntime.currentRange.value = 0 to 0
    stopForeground(STOP_FOREGROUND_REMOVE)
    stopSelf()
  }

  private fun pauseReader() {
    if (chunks.isEmpty()) return
    paused = true
    tts?.stop()
    val restartOffset = ReaderRuntime.currentRange.value.first.coerceIn(0, pendingText.length)
    chunks = splitForTtsChunks(pendingText, restartOffset)
    chunkIndex = 0
    ReaderRuntime.playing.value = false
    ReaderRuntime.paused.value = true
    startForeground(1002, notification("朗读已暂停"))
  }

  private fun setSpeed(speed: Float) {
    pendingSpeed = normalizeReaderSpeed(speed)
    tts?.setSpeechRate(pendingSpeed)
    if (ReaderRuntime.playing.value && !paused && pendingText.isNotBlank()) {
      val restartOffset = ReaderRuntime.currentRange.value.first.coerceIn(0, pendingText.length)
      chunks = splitForTtsChunks(pendingText, restartOffset)
      chunkIndex = 0
      speakIfReady()
    }
  }

  override fun onDestroy() {
    tts?.shutdown()
    tts = null
    super.onDestroy()
  }

  private fun notification(text: String): Notification {
    val pending =
      PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
      )
    return NotificationCompat.Builder(this, READER_CHANNEL_ID)
      .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
      .setContentTitle("朗读".localized(this))
      .setContentText(text.localized(this))
      .setContentIntent(pending)
      .setOngoing(true)
      .build()
  }

  companion object {
    const val ACTION_SPEAK = "com.vibecodingjapan.ideavox.reader.SPEAK"
    const val ACTION_RESUME = "com.vibecodingjapan.ideavox.reader.RESUME"
    const val ACTION_PAUSE = "com.vibecodingjapan.ideavox.reader.PAUSE"
    const val ACTION_STOP = "com.vibecodingjapan.ideavox.reader.STOP"
    const val ACTION_SET_SPEED = "com.vibecodingjapan.ideavox.reader.SET_SPEED"
    const val EXTRA_TEXT = "text"
    const val EXTRA_LANGUAGE = "language"
    const val EXTRA_SPEED = "speed"
    const val EXTRA_START_OFFSET = "start_offset"
  }
}

private fun normalizeReaderSpeed(speed: Float): Float =
  (kotlin.math.round(speed * 10f) / 10f).coerceIn(0.2f, 3.0f)

object ReaderRuntime {
  val playing = MutableStateFlow(false)
  val paused = MutableStateFlow(false)
  val currentRange = MutableStateFlow(0 to 0)
  val textLength = MutableStateFlow(0)
}

fun splitForTts(text: String): List<String> {
  return splitForTtsChunks(text).map { it.text }
}

private data class TtsChunk(val text: String, val start: Int)

private fun splitForTtsChunks(text: String, startOffset: Int = 0): List<TtsChunk> {
  val max = (TextToSpeech.getMaxSpeechInputLength() - 128).coerceAtLeast(500)
  val safeStart = startOffset.coerceIn(0, text.length)
  val chunks = mutableListOf<TtsChunk>()
  var index = safeStart
  while (index < text.length) {
    val end = (index + max).coerceAtMost(text.length)
    val raw = text.substring(index, end)
    val leading = raw.indexOfFirst { !it.isWhitespace() }
    if (leading >= 0) {
      val trimmedEnd = raw.indexOfLast { !it.isWhitespace() } + 1
      chunks += TtsChunk(raw.substring(leading, trimmedEnd), index + leading)
    }
    index = end
  }
  return chunks
}
