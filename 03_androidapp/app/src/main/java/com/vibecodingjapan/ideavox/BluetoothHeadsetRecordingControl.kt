package com.vibecodingjapan.ideavox

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.KeyEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

@Composable
fun BluetoothHeadsetRecordingControl(
  recorderState: RecorderUiState,
  monitoringEnabled: Boolean,
) {
  val context = LocalContext.current
  val appContext = context.applicationContext

  LaunchedEffect(monitoringEnabled) {
    if (monitoringEnabled) {
      ContextCompat.startForegroundService(
        appContext,
        Intent(appContext, BluetoothMediaButtonService::class.java)
          .setAction(BluetoothMediaButtonService.ACTION_START_SESSION),
      )
    } else {
      appContext.startService(
        Intent(appContext, BluetoothMediaButtonService::class.java)
          .setAction(BluetoothMediaButtonService.ACTION_STOP_SESSION)
      )
    }
  }

  LaunchedEffect(recorderState.status, monitoringEnabled) {
    if (monitoringEnabled) {
      appContext.startService(
        Intent(appContext, BluetoothMediaButtonService::class.java)
          .setAction(BluetoothMediaButtonService.ACTION_REFRESH_SESSION),
      )
    }
  }
}

class BluetoothMediaButtonService : Service() {
  private lateinit var mediaSession: MediaSession
  private lateinit var mediaButtonReceiver: ComponentName
  private var mediaKeyFocus: BluetoothMediaKeyFocus? = null
  private val silentMediaKeepAlive = SilentMediaKeepAlive()

  override fun onCreate() {
    super.onCreate()
    mediaButtonReceiver = ComponentName(this, BluetoothMediaButtonReceiver::class.java)
    mediaSession = MediaSession(this, "ideavox-bluetooth-media-session")
    mediaKeyFocus = BluetoothMediaKeyFocus(this)

    @Suppress("DEPRECATION")
    mediaSession.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
    mediaSession.setMediaButtonReceiver(
      PendingIntent.getBroadcast(
        this,
        0,
        Intent(Intent.ACTION_MEDIA_BUTTON).setComponent(mediaButtonReceiver),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
      )
    )
    mediaSession.setSessionActivity(
      PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
      )
    )
    mediaSession.setPlaybackToLocal(mediaPlaybackAttributes())
    mediaSession.setMetadata(mediaButtonMetadata(this))
    mediaSession.setCallback(
      object : MediaSession.Callback() {
        override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
          @Suppress("DEPRECATION")
          val event = mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT) as? KeyEvent ?: return false
          return BluetoothHeadsetRecordingActions.handleMediaKeyEvent(this@BluetoothMediaButtonService, event)
        }

        override fun onSkipToNext() {
          BluetoothHeadsetRecordingActions.handleNext(this@BluetoothMediaButtonService)
        }

        override fun onFastForward() {
          BluetoothHeadsetRecordingActions.handleNext(this@BluetoothMediaButtonService)
        }

        override fun onPlay() {
          BluetoothHeadsetRecordingActions.handlePlayPause(this@BluetoothMediaButtonService)
        }

        override fun onPause() {
          BluetoothHeadsetRecordingActions.handlePlayPause(this@BluetoothMediaButtonService)
        }

        override fun onPlayFromMediaId(mediaId: String?, extras: android.os.Bundle?) {
          BluetoothHeadsetRecordingActions.handlePlayPause(this@BluetoothMediaButtonService)
        }
      }
    )
    mediaSession.setPlaybackState(mediaButtonPlaybackState())
    mediaSession.isActive = true
    mediaKeyFocus?.request()
    silentMediaKeepAlive.start()
    Log.d(TAG, "Bluetooth media button service created and session activated")
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_STOP_SESSION -> {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        return START_NOT_STICKY
      }
      ACTION_MEDIA_BUTTON -> {
        @Suppress("DEPRECATION")
        val event = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT) as? KeyEvent
        if (event != null) {
          BluetoothHeadsetRecordingActions.handleMediaKeyEvent(this, event)
        }
      }
      ACTION_TRANSPORT_NEXT -> {
        BluetoothHeadsetRecordingActions.handleNext(this)
        refreshMediaButtonSession()
      }
      ACTION_TRANSPORT_PLAY_PAUSE -> {
        BluetoothHeadsetRecordingActions.handlePlayPause(this)
        refreshMediaButtonSession()
      }
      ACTION_SUSPEND_MEDIA_ROUTE -> suspendMediaRouteForCommunication()
      ACTION_REARM_SESSION -> rearmMediaButtonSession()
      ACTION_START_SESSION, ACTION_REFRESH_SESSION, null -> refreshMediaButtonSession()
    }
    startForeground(NOTIFICATION_ID, notification())
    return START_STICKY
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onDestroy() {
    silentMediaKeepAlive.stop()
    mediaKeyFocus?.abandon()
    mediaKeyFocus = null
    mediaSession.isActive = false
    mediaSession.setCallback(null)
    mediaSession.release()
    super.onDestroy()
  }

  private fun notification(): Notification {
    val openIntent =
      PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
      )
    val playPauseIntent =
      PendingIntent.getService(
        this,
        1,
        Intent(this, BluetoothMediaButtonService::class.java).setAction(ACTION_TRANSPORT_PLAY_PAUSE),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
      )
    val nextIntent =
      PendingIntent.getService(
        this,
        2,
        Intent(this, BluetoothMediaButtonService::class.java).setAction(ACTION_TRANSPORT_NEXT),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
      )
    return Notification.Builder(this, BLUETOOTH_MEDIA_CHANNEL_ID)
      .setSmallIcon(android.R.drawable.ic_media_play)
      .setContentTitle("ideavox")
      .setContentText("耳机按键控制已开启".localized(this))
      .setContentIntent(openIntent)
      .setCategory(Notification.CATEGORY_TRANSPORT)
      .addAction(Notification.Action.Builder(android.R.drawable.ic_media_play, "录音 / 确认".localized(this), playPauseIntent).build())
      .addAction(Notification.Action.Builder(android.R.drawable.ic_media_next, "录音 / 确认".localized(this), nextIntent).build())
      .setStyle(
        Notification.MediaStyle()
          .setMediaSession(mediaSession.sessionToken)
          .setShowActionsInCompactView(0, 1)
      )
      .setOngoing(true)
      .setVisibility(Notification.VISIBILITY_PUBLIC)
      .setColor(Color.rgb(140, 68, 255))
      .build()
  }

  private fun refreshMediaButtonSession() {
    mediaSession.setMetadata(mediaButtonMetadata(this))
    mediaSession.setPlaybackState(mediaButtonPlaybackState())
    mediaSession.isActive = true
    mediaKeyFocus?.request()
    silentMediaKeepAlive.start()
  }

  private fun rearmMediaButtonSession() {
    Log.d(TAG, "rearming bluetooth media button session")
    silentMediaKeepAlive.restart()
    refreshMediaButtonSession()
  }

  private fun suspendMediaRouteForCommunication() {
    Log.d(TAG, "suspending media keep-alive before bluetooth communication route")
    silentMediaKeepAlive.stop()
    mediaKeyFocus?.abandon()
  }

  companion object {
    private const val NOTIFICATION_ID = 1004
    const val ACTION_START_SESSION = "com.vibecodingjapan.ideavox.bluetooth.START_SESSION"
    const val ACTION_STOP_SESSION = "com.vibecodingjapan.ideavox.bluetooth.STOP_SESSION"
    const val ACTION_REFRESH_SESSION = "com.vibecodingjapan.ideavox.bluetooth.REFRESH_SESSION"
    const val ACTION_REARM_SESSION = "com.vibecodingjapan.ideavox.bluetooth.REARM_SESSION"
    const val ACTION_SUSPEND_MEDIA_ROUTE = "com.vibecodingjapan.ideavox.bluetooth.SUSPEND_MEDIA_ROUTE"
    const val ACTION_MEDIA_BUTTON = "com.vibecodingjapan.ideavox.bluetooth.MEDIA_BUTTON"
    const val ACTION_TRANSPORT_PLAY_PAUSE = "com.vibecodingjapan.ideavox.bluetooth.TRANSPORT_PLAY_PAUSE"
    const val ACTION_TRANSPORT_NEXT = "com.vibecodingjapan.ideavox.bluetooth.TRANSPORT_NEXT"

    fun refresh(context: Context) {
      if (!appStore.snapshot.value.bluetoothHeadsetControlMonitoringEnabled) return
      context.applicationContext.startService(
        Intent(context.applicationContext, BluetoothMediaButtonService::class.java)
          .setAction(ACTION_REFRESH_SESSION)
      )
    }

    fun rearm(context: Context) {
      if (!appStore.snapshot.value.bluetoothHeadsetControlMonitoringEnabled) return
      context.applicationContext.startService(
        Intent(context.applicationContext, BluetoothMediaButtonService::class.java)
          .setAction(ACTION_REARM_SESSION)
      )
    }

    fun suspendMediaRouteForCommunication(context: Context) {
      if (!appStore.snapshot.value.bluetoothHeadsetControlMonitoringEnabled) return
      context.applicationContext.startService(
        Intent(context.applicationContext, BluetoothMediaButtonService::class.java)
          .setAction(ACTION_SUSPEND_MEDIA_ROUTE)
      )
    }
  }
}

object BluetoothHeadsetRecordingActions {
  fun handleMediaKeyEvent(context: Context, event: KeyEvent): Boolean {
    if (!appStore.snapshot.value.bluetoothHeadsetControlMonitoringEnabled) return false
    if (event.action == KeyEvent.ACTION_UP) return true
    if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount > 0) return true
    Log.d(TAG, "media key down: keyCode=${event.keyCode}, status=${RecordingRuntime.state.value.status}")
    return when (event.keyCode) {
      KeyEvent.KEYCODE_MEDIA_NEXT,
      KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
      -> {
        handleHeadsetOperation(context, BluetoothHeadsetOperation.NEXT, event.keyCode)
        true
      }
      KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
      KeyEvent.KEYCODE_MEDIA_PLAY,
      KeyEvent.KEYCODE_MEDIA_PAUSE,
      KeyEvent.KEYCODE_HEADSETHOOK,
      -> {
        handleHeadsetOperation(context, BluetoothHeadsetOperation.PLAY_PAUSE, event.keyCode)
        true
      }
      else -> false
    }
  }

  fun handleNext(context: Context) {
    if (!appStore.snapshot.value.bluetoothHeadsetControlMonitoringEnabled) return
    Log.d(TAG, "handle next: status=${RecordingRuntime.state.value.status}")
    handleHeadsetOperation(context, BluetoothHeadsetOperation.NEXT, keyCode = null)
  }

  fun handlePlayPause(context: Context) {
    if (!appStore.snapshot.value.bluetoothHeadsetControlMonitoringEnabled) return
    Log.d(TAG, "handle play/pause: status=${RecordingRuntime.state.value.status}")
    handleHeadsetOperation(context, BluetoothHeadsetOperation.PLAY_PAUSE, keyCode = null)
  }

  private fun handleHeadsetOperation(context: Context, operation: BluetoothHeadsetOperation, keyCode: Int?) {
    Log.d(
      TAG,
      "captured headset trigger operation=$operation keyCode=$keyCode status=${RecordingRuntime.state.value.status}",
    )
    BluetoothRecordingWorkflow.handleTrigger(context)
  }
}

enum class BluetoothHeadsetOperation(val label: String) {
  NEXT("下一曲 / 快进"),
  PLAY_PAUSE("播放 / 暂停"),
}

enum class BluetoothHeadsetRecordingCommand(val label: String) {
  START_BLUETOOTH_RECORDING("开启蓝牙录音"),
  PAUSE_RECORDING("暂停录音"),
  RESUME_RECORDING("继续录音"),
  STOP_RECORDING("结束录音"),
  NONE("不执行录音动作"),
  ;

  val voicePrompt: String
    get() =
      when (this) {
        START_BLUETOOTH_RECORDING -> "录音已开启"
        PAUSE_RECORDING -> "录音已暂停"
        RESUME_RECORDING -> "录音继续"
        STOP_RECORDING -> "录音已结束"
        NONE -> ""
      }
}

data class BluetoothHeadsetEvent(
  val operation: BluetoothHeadsetOperation,
  val keyCode: Int?,
  val status: RecordingStatus,
  val command: BluetoothHeadsetRecordingCommand,
) {
  val statusLabel: String
    get() =
      when (status) {
        RecordingStatus.IDLE -> "未录音"
        RecordingStatus.RECORDING -> "录音中"
        RecordingStatus.PAUSED -> "已暂停"
      }
}

class BluetoothMediaButtonReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action != Intent.ACTION_MEDIA_BUTTON) return
    if (!appStore.snapshot.value.bluetoothHeadsetControlMonitoringEnabled) return
    Log.d(TAG, "MEDIA_BUTTON broadcast received")
    @Suppress("DEPRECATION")
    val event = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT) as? KeyEvent ?: return
    val handled = BluetoothHeadsetRecordingActions.handleMediaKeyEvent(context, event)
    ContextCompat.startForegroundService(
      context.applicationContext,
      Intent(context.applicationContext, BluetoothMediaButtonService::class.java)
        .setAction(BluetoothMediaButtonService.ACTION_REARM_SESSION),
    )
    if (handled) {
      abortBroadcast()
    }
  }
}

private const val TAG = "IdeavoxBtKeys"

private fun mediaButtonPlaybackState(): PlaybackState {
  return PlaybackState.Builder()
    .setActions(
      PlaybackState.ACTION_PLAY or
        PlaybackState.ACTION_PAUSE or
        PlaybackState.ACTION_PLAY_PAUSE or
        PlaybackState.ACTION_SKIP_TO_NEXT or
        PlaybackState.ACTION_FAST_FORWARD
    )
    .setState(PlaybackState.STATE_PLAYING, 0L, 1f, System.currentTimeMillis())
    .build()
}

private fun mediaButtonMetadata(context: Context): MediaMetadata =
  MediaMetadata.Builder()
    .putString(MediaMetadata.METADATA_KEY_TITLE, "ideavox 耳机按键控制".localized(context))
    .putString(MediaMetadata.METADATA_KEY_ARTIST, "ideavox")
    .putLong(MediaMetadata.METADATA_KEY_DURATION, -1L)
    .build()

private fun mediaPlaybackAttributes(): AudioAttributes =
  AudioAttributes.Builder()
    .setUsage(AudioAttributes.USAGE_MEDIA)
    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
    .build()

private fun voicePromptAttributes(): AudioAttributes =
  AudioAttributes.Builder()
    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
    .build()

private class SilentMediaKeepAlive {
  private val running = AtomicBoolean(false)
  private val lock = Any()
  @Volatile private var audioTrack: AudioTrack? = null
  @Volatile private var worker: Thread? = null

  fun start() {
    synchronized(lock) {
      if (!running.compareAndSet(false, true)) return
    }
    worker =
      Thread {
        val sampleRate = 8000
        val format =
          AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()
        val bufferSize =
          AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            .coerceAtLeast(2048)
        val track =
          runCatching {
            AudioTrack.Builder()
              .setAudioAttributes(mediaPlaybackAttributes())
              .setAudioFormat(format)
              .setBufferSizeInBytes(bufferSize)
              .setTransferMode(AudioTrack.MODE_STREAM)
              .build()
          }.getOrElse { error ->
            Log.w(TAG, "silent media keep-alive AudioTrack build failed", error)
            running.set(false)
            return@Thread
          }
        audioTrack = track
        if (track.state != AudioTrack.STATE_INITIALIZED) {
          Log.w(TAG, "silent media keep-alive AudioTrack was not initialized")
          running.set(false)
          runCatching { track.release() }
          audioTrack = null
          return@Thread
        }
        val silence = ByteArray(bufferSize)
        runCatching {
          track.setVolume(0f)
          track.play()
          Log.d(TAG, "silent media keep-alive started")
          while (running.get()) {
            BluetoothMediaRouteState.update(runCatching { track.routedDevice }.getOrNull())
            val written = track.write(silence, 0, silence.size)
            if (written < 0) {
              Log.w(TAG, "silent media keep-alive write failed: $written")
              Thread.sleep(40L)
            }
          }
        }.onFailure { error ->
          Log.w(TAG, "silent media keep-alive stopped after error", error)
        }
        runCatching { track.pause() }
        runCatching { track.flush() }
        runCatching { track.release() }
        audioTrack = null
        BluetoothMediaRouteState.update(null)
        Log.d(TAG, "silent media keep-alive stopped")
      }.also { thread ->
        thread.name = "ideavox-silent-media-keep-alive"
        thread.isDaemon = true
        thread.start()
      }
  }

  fun restart() {
    stop()
    start()
  }

  fun stop() {
    val threadToJoin =
      synchronized(lock) {
        if (!running.getAndSet(false)) return
        runCatching { audioTrack?.pause() }
        runCatching { audioTrack?.flush() }
        worker.also { worker = null }
      }
    if (Thread.currentThread() != threadToJoin) {
      runCatching { threadToJoin?.join(220L) }
    }
  }
}

object BluetoothMediaRouteState {
  @Volatile private var routedDeviceId: Int? = null
  @Volatile private var routedDeviceType: Int? = null
  @Volatile private var routedDeviceName: String? = null

  fun update(device: android.media.AudioDeviceInfo?) {
    val nextId = device?.id
    val nextType = device?.type
    if (nextId != routedDeviceId || nextType != routedDeviceType) {
      routedDeviceId = nextId
      routedDeviceType = nextType
      routedDeviceName = device?.productName?.toString()
      Log.i(TAG, "silent media route changed: ${description()}")
    }
  }

  fun isBluetoothAudioActive(): Boolean =
    when (routedDeviceType) {
      android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
      android.media.AudioDeviceInfo.TYPE_BLE_HEADSET,
      android.media.AudioDeviceInfo.TYPE_BLE_SPEAKER,
      android.media.AudioDeviceInfo.TYPE_HEARING_AID,
      -> true
      else -> false
    }

  fun description(): String =
    "id=${routedDeviceId ?: "none"}, type=${routedDeviceType ?: "none"}, name=${routedDeviceName ?: "none"}"

  fun deviceName(): String? = routedDeviceName
}

class MandarinVoicePrompt private constructor(context: Context) : TextToSpeech.OnInitListener {
  private val appContext = context.applicationContext
  private val tts = TextToSpeech(appContext, this)
  private val pending = ArrayDeque<VoicePromptRequest>()
  @Volatile private var ready = false

  override fun onInit(status: Int) {
    ready = status == TextToSpeech.SUCCESS
    if (!ready) return
    tts.language = AppLanguageManager.current(appContext).locale
    while (pending.isNotEmpty()) {
      speakNow(pending.removeFirst())
    }
  }

  fun speak(text: String) {
    speak(text, useVoiceCommunication = shouldUseVoiceCommunicationPrompt())
  }

  fun speak(
    text: String,
    useVoiceCommunication: Boolean,
    speechRate: Float = 1.0f,
  ) {
    if (text.isBlank()) return
    val request = VoicePromptRequest(text, useVoiceCommunication, speechRate)
    if (!ready) {
      pending.addLast(request)
      return
    }
    speakNow(request)
  }

  private fun speakNow(request: VoicePromptRequest) {
    val language = AppLanguageManager.current(appContext)
    val localizedText = localizeText(request.text, language)
    tts.language = language.locale
    tts.setSpeechRate(request.speechRate)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      tts.setAudioAttributes(if (request.useVoiceCommunication) voicePromptAttributes() else mediaPlaybackAttributes())
      tts.speak(localizedText, TextToSpeech.QUEUE_FLUSH, null, "ideavox-headset-${System.currentTimeMillis()}")
    } else {
      @Suppress("DEPRECATION")
      tts.speak(localizedText, TextToSpeech.QUEUE_FLUSH, null)
    }
  }

  private fun shouldUseVoiceCommunicationPrompt(): Boolean =
    BluetoothRecordingControlModeResolver.shouldUseTelecom(appStore.snapshot.value.bluetoothRecordingControlMode)

  fun stop() {
    pending.clear()
    runCatching { tts.stop() }
  }

  companion object {
    @Volatile private var instance: MandarinVoicePrompt? = null

    fun speak(context: Context, text: String) {
      prompt(context).speak(text)
    }

    fun speak(context: Context, text: String, useVoiceCommunication: Boolean) {
      prompt(context).speak(text, useVoiceCommunication)
    }

    fun speak(
      context: Context,
      text: String,
      useVoiceCommunication: Boolean,
      speechRate: Float,
    ) {
      prompt(context).speak(text, useVoiceCommunication, speechRate)
    }

    fun stop() {
      instance?.stop()
    }

    fun isReady(context: Context): Boolean = prompt(context).ready

    private fun prompt(context: Context): MandarinVoicePrompt {
      val appContext = context.applicationContext
      return instance ?: synchronized(this) {
        instance ?: MandarinVoicePrompt(appContext).also { instance = it }
      }
    }
  }
}

private data class VoicePromptRequest(
  val text: String,
  val useVoiceCommunication: Boolean,
  val speechRate: Float,
)

private class BluetoothMediaKeyFocus(
  context: Context,
) {
  private val appContext = context.applicationContext
  private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
  private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { change ->
    Log.d(TAG, "audio focus change: $change")
  }
  private val focusRequest =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
          AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        )
        .setOnAudioFocusChangeListener(focusChangeListener)
        .build()
    } else {
      null
    }

  fun request() {
    val result =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        audioManager.requestAudioFocus(focusRequest!!)
      } else {
        @Suppress("DEPRECATION")
        audioManager.requestAudioFocus(
          focusChangeListener,
          AudioManager.STREAM_MUSIC,
          AudioManager.AUDIOFOCUS_GAIN,
        )
      }
    Log.d(TAG, "audio focus request result: $result")
  }

  fun abandon() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
    } else {
      @Suppress("DEPRECATION")
      audioManager.abandonAudioFocus(focusChangeListener)
    }
  }
}
