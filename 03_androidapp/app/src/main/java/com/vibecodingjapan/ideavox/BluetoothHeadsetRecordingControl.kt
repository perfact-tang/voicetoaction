package com.vibecodingjapan.ideavox

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.bluetooth.BluetoothHeadset
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
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
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
  private var communicationSuspended = false
  private val headsetDiagnostics = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
      when (intent.action) {
        BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED,
        BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED,
        -> {
          val state = intent.getIntExtra(BluetoothHeadset.EXTRA_STATE, -1)
          val previous = intent.getIntExtra(BluetoothHeadset.EXTRA_PREVIOUS_STATE, -1)
          Log.i(TAG, "headset state action=${intent.action} state=$state previous=$previous recording=${RecordingRuntime.state.value.status}")
          BluetoothTouchDiagnostics.record("HFP action=${intent.action} state=$state previous=$previous")
        }
        VOLUME_CHANGED_ACTION -> {
          val stream = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1)
          val value = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_VALUE", -1)
          val previous = intent.getIntExtra("android.media.EXTRA_PREV_VOLUME_STREAM_VALUE", -1)
          Log.i(TAG, "volume response stream=$stream value=$value previous=$previous recording=${RecordingRuntime.state.value.status}")
          BluetoothTouchDiagnostics.record("VOLUME stream=$stream value=$value previous=$previous")
        }
      }
    }
  }
  private val audioDevices = object : AudioDeviceCallback() {
    override fun onAudioDevicesAdded(devices: Array<out AudioDeviceInfo>) = logDevices("added", devices)
    override fun onAudioDevicesRemoved(devices: Array<out AudioDeviceInfo>) = logDevices("removed", devices)
  }
  private fun logDevices(action: String, devices: Array<out AudioDeviceInfo>) {
    Log.i(TAG, "audio devices $action: ${devices.joinToString { "${it.id}/${it.type}/${it.productName}" }} status=${RecordingRuntime.state.value.status}")
    BluetoothTouchDiagnostics.record("DEVICES $action ${devices.joinToString { "${it.id}/${it.type}/${it.productName}" }}")
  }

  override fun onCreate() {
    super.onCreate()
    BluetoothRecordingTelecom.observeBluetoothAudio(this)
    ContextCompat.registerReceiver(
      this,
      headsetDiagnostics,
      IntentFilter().apply {
        addAction(BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED)
        addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
        // Optional platform broadcast: diagnostics only. Volume gestures must
        // not be confused with explicit recording/upload confirmation presses.
        addAction(VOLUME_CHANGED_ACTION)
      },
      ContextCompat.RECEIVER_EXPORTED,
    )
    (getSystemService(AUDIO_SERVICE) as AudioManager).registerAudioDeviceCallback(audioDevices, Handler(mainLooper))
    MandarinVoicePrompt.isReady(this) // Warm TTS before the first time-critical trigger.
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
          BluetoothTouchDiagnostics.record("CALLBACK MediaSession.onMediaButtonEvent")
          @Suppress("DEPRECATION")
          val event = mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT) as? KeyEvent ?: return false
          return BluetoothHeadsetRecordingActions.handleMediaKeyEvent(this@BluetoothMediaButtonService, event)
        }

        override fun onSkipToNext() {
          BluetoothTouchDiagnostics.record("CALLBACK MediaSession.onSkipToNext")
          BluetoothHeadsetRecordingActions.handleNext(this@BluetoothMediaButtonService)
        }

        override fun onFastForward() {
          BluetoothTouchDiagnostics.record("CALLBACK MediaSession.onFastForward")
          BluetoothHeadsetRecordingActions.handleNext(this@BluetoothMediaButtonService)
        }

        override fun onPlay() {
          BluetoothTouchDiagnostics.record("CALLBACK MediaSession.onPlay")
          BluetoothHeadsetRecordingActions.handlePlayPause(this@BluetoothMediaButtonService)
        }

        override fun onPause() {
          BluetoothTouchDiagnostics.record("CALLBACK MediaSession.onPause")
          BluetoothHeadsetRecordingActions.handlePlayPause(this@BluetoothMediaButtonService)
        }

        override fun onStop() {
          BluetoothTouchDiagnostics.record("CALLBACK MediaSession.onStop")
          BluetoothHeadsetRecordingActions.handlePlayPause(this@BluetoothMediaButtonService)
        }
        override fun onSkipToPrevious() {
          BluetoothTouchDiagnostics.record("CALLBACK MediaSession.onSkipToPrevious")
          BluetoothHeadsetRecordingActions.handleNext(this@BluetoothMediaButtonService)
        }
        override fun onRewind() {
          BluetoothTouchDiagnostics.record("CALLBACK MediaSession.onRewind")
          BluetoothHeadsetRecordingActions.handleNext(this@BluetoothMediaButtonService)
        }

        override fun onPlayFromMediaId(mediaId: String?, extras: android.os.Bundle?) {
          BluetoothHeadsetRecordingActions.handlePlayPause(this@BluetoothMediaButtonService)
        }
      }
    )
    instance = this
    refreshMediaButtonSession()
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
    if (instance === this) instance = null
    unregisterReceiver(headsetDiagnostics)
    (getSystemService(AUDIO_SERVICE) as AudioManager).unregisterAudioDeviceCallback(audioDevices)
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
    val ownsMedia = !communicationSuspended && !BluetoothRecordingTelecom.hasActiveConnection()
    mediaSession.setMetadata(mediaButtonMetadata(this))
    mediaSession.setPlaybackState(mediaButtonPlaybackState(ownsMedia))
    mediaSession.isActive = ownsMedia
    Log.i(TAG, "media ownership active=$ownsMedia suspended=$communicationSuspended call=${BluetoothRecordingTelecom.hasActiveConnection()} recording=${RecordingRuntime.state.value.status}")
    if (ownsMedia) {
      mediaKeyFocus?.request()
      silentMediaKeepAlive.start()
    } else {
      silentMediaKeepAlive.stop()
      mediaKeyFocus?.abandon()
    }
  }

  private fun rearmMediaButtonSession() {
    Log.d(TAG, "rearming bluetooth media button session")
    if (!RecordingRuntime.activeInputIsBluetooth && !BluetoothRecordingTelecom.hasActiveConnection() && !BluetoothTouchDiagnostics.ownsCommunicationRoute) {
      communicationSuspended = false
    }
    refreshMediaButtonSession()
  }

  private fun suspendMediaRouteForCommunication() {
    communicationSuspended = true
    Log.d(TAG, "suspending media keep-alive before bluetooth communication route")
    mediaSession.setPlaybackState(mediaButtonPlaybackState(active = false))
    mediaSession.isActive = false
    silentMediaKeepAlive.stop()
    mediaKeyFocus?.abandon()
  }

  companion object {
    @Volatile private var instance: BluetoothMediaButtonService? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private const val NOTIFICATION_ID = 1004
    private const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"
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
      val suspend = {
        val service = instance
        if (service != null) service.suspendMediaRouteForCommunication()
        else context.applicationContext.startService(
          Intent(context.applicationContext, BluetoothMediaButtonService::class.java)
            .setAction(ACTION_SUSPEND_MEDIA_ROUTE)
        )
        Unit
      }
      // Run ahead of the following placeCall on the same main looper. Starting
      // the service asynchronously otherwise leaves an overlapping media session.
      if (Looper.myLooper() == Looper.getMainLooper()) suspend() else mainHandler.post(suspend)
    }
  }
}

object BluetoothHeadsetRecordingActions {
  private val keyDeduplicator = BluetoothKeyDeduplicator()
  fun handleMediaKeyEvent(context: Context, event: KeyEvent): Boolean {
    if (!appStore.snapshot.value.bluetoothHeadsetControlMonitoringEnabled && !BluetoothTouchDiagnostics.active) return false
    val supported = event.keyCode in BluetoothMediaKeys.supported
    BluetoothTouchDiagnostics.record("KEY ${KeyEvent.keyCodeToString(event.keyCode)} action=${event.action} repeat=${event.repeatCount} long=${event.isLongPress} device=${event.deviceId} source=${event.source} downAt=${event.downTime} eventAt=${event.eventTime}")
    Log.i(TAG, "key code=${event.keyCode} action=${event.action} repeat=${event.repeatCount} device=${event.deviceId} source=${event.source} eventAt=${event.eventTime} receivedAt=${SystemClock.uptimeMillis()} status=${RecordingRuntime.state.value.status} supported=$supported")
    if (!supported) return false // Do not swallow volume/navigation key-up events.
    if (BluetoothTouchDiagnostics.active) return true // Observe repeats and key-up before any deduplication or action mapping.
    if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount > 0) return true
    if (!keyDeduplicator.accept(event.deviceId, event.keyCode, event.downTime, event.eventTime, SystemClock.uptimeMillis())) return true
    return when (event.keyCode) {
      KeyEvent.KEYCODE_MEDIA_NEXT,
      KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
      KeyEvent.KEYCODE_MEDIA_PREVIOUS,
      KeyEvent.KEYCODE_MEDIA_REWIND,
      -> {
        handleHeadsetOperation(context, BluetoothHeadsetOperation.NEXT, event.keyCode)
        true
      }
      KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
      KeyEvent.KEYCODE_MEDIA_PLAY,
      KeyEvent.KEYCODE_MEDIA_PAUSE,
      KeyEvent.KEYCODE_HEADSETHOOK,
      KeyEvent.KEYCODE_MEDIA_STOP,
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

private fun mediaButtonPlaybackState(active: Boolean): PlaybackState {
  return PlaybackState.Builder()
    .setActions(
      PlaybackState.ACTION_PLAY or
        PlaybackState.ACTION_PAUSE or
        PlaybackState.ACTION_PLAY_PAUSE or
        PlaybackState.ACTION_SKIP_TO_NEXT or
        PlaybackState.ACTION_FAST_FORWARD or
        PlaybackState.ACTION_STOP or
        PlaybackState.ACTION_SKIP_TO_PREVIOUS or
        PlaybackState.ACTION_REWIND
    )
    .setState(if (active) PlaybackState.STATE_PLAYING else PlaybackState.STATE_STOPPED, 0L, if (active) 1f else 0f, SystemClock.elapsedRealtime())
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
  private val lock = Any()
  private var runToken: AtomicBoolean? = null
  private var audioTrack: AudioTrack? = null

  fun start() {
    synchronized(lock) {
      if (runToken?.get() == true) return
      val token = AtomicBoolean(true)
      runToken = token
      Thread({
        var track: AudioTrack? = null
        try {
          val sampleRate = 8000
          val bufferSize = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(2048)
          track = AudioTrack.Builder()
            .setAudioAttributes(mediaPlaybackAttributes())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(bufferSize).setTransferMode(AudioTrack.MODE_STREAM).build()
          check(track.state == AudioTrack.STATE_INITIALIZED)
          synchronized(lock) {
            if (!token.get()) return@Thread
            audioTrack = track
            track.setVolume(0f)
            track.play()
          }
          val silence = ByteArray(bufferSize)
          while (token.get()) {
            synchronized(lock) {
              if (runToken === token) BluetoothMediaRouteState.update(track.routedDevice)
            }
            if (track.write(silence, 0, silence.size) < 0) break
          }
        } catch (error: Exception) {
          Log.w(TAG, "silent media keep-alive failed", error)
        } finally {
          runCatching { track?.pause() }
          runCatching { track?.flush() }
          runCatching { track?.release() }
          synchronized(lock) {
            // An old writer must never clear a newer writer's route or running flag.
            if (runToken === token) {
              runToken = null
              audioTrack = null
              BluetoothMediaRouteState.update(null)
            }
          }
        }
      }, "ideavox-silent-media-keep-alive").apply { isDaemon = true }.start()
    }
  }

  fun stop() {
    synchronized(lock) {
      runToken?.set(false)
      runCatching { audioTrack?.pause() }
      runCatching { audioTrack?.flush() }
      // No thread join on the main thread. Each writer has its own cancellation token.
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
      BluetoothTouchDiagnostics.record("MEDIA route ${description()}")
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
  private val mainHandler = Handler(Looper.getMainLooper())
  private var utteranceSequence = 0L
  private val tts = TextToSpeech(appContext, this)
  private val pending = ArrayDeque<VoicePromptRequest>()
  @Volatile private var ready = false

  override fun onInit(status: Int) {
    mainHandler.post {
      ready = status == TextToSpeech.SUCCESS
      Log.i(TAG, "voice prompt engine ready=$ready")
      if (ready) {
        tts.language = AppLanguageManager.current(appContext).locale
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
          override fun onStart(id: String?) { Log.i(TAG, "voice prompt started id=$id") }
          override fun onDone(id: String?) { Log.i(TAG, "voice prompt completed id=$id") }
          @Deprecated("Legacy TTS callback")
          override fun onError(id: String?) { Log.w(TAG, "voice prompt failed id=$id") }
        })
        while (pending.isNotEmpty()) speakNow(pending.removeFirst())
      }
    }
  }

  fun speak(text: String) {
    speak(text, useVoiceCommunication = shouldUseVoiceCommunicationPrompt())
  }

  fun speak(
    text: String,
    useVoiceCommunication: Boolean,
    speechRate: Float = 1.0f,
    flush: Boolean = true,
  ) {
    if (text.isBlank()) return
    val request = VoicePromptRequest(text, useVoiceCommunication, speechRate, flush)
    if (!ready) {
      if (flush) pending.clear() // Control feedback replaces stale prompts; completion feedback queues.
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
      tts.speak(localizedText, if (request.flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "ideavox-headset-${++utteranceSequence}")
    } else {
      @Suppress("DEPRECATION")
      tts.speak(localizedText, if (request.flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null)
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
    private val handler = Handler(Looper.getMainLooper())
    private fun onMain(action: () -> Unit) {
      if (Looper.myLooper() == Looper.getMainLooper()) action() else handler.post { action() }
    }

    fun enqueue(context: Context, text: String, useVoiceCommunication: Boolean) {
      onMain { prompt(context).speak(text, useVoiceCommunication, flush = false) }
    }

    fun speak(context: Context, text: String) {
      onMain { prompt(context).speak(text) }
    }

    fun speak(context: Context, text: String, useVoiceCommunication: Boolean) {
      onMain { prompt(context).speak(text, useVoiceCommunication) }
    }

    fun speak(
      context: Context,
      text: String,
      useVoiceCommunication: Boolean,
      speechRate: Float,
      flush: Boolean = true,
    ) {
      onMain { prompt(context).speak(text, useVoiceCommunication, speechRate, flush) }
    }

    fun stop() {
      onMain { instance?.stop() }
    }

    fun isReady(context: Context): Boolean {
      onMain { prompt(context) }
      return instance?.ready == true
    }

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
  val flush: Boolean,
)

private class BluetoothMediaKeyFocus(
  context: Context,
) {
  private val appContext = context.applicationContext
  private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
  private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { change ->
    Log.d(TAG, "audio focus change: $change")
    BluetoothTouchDiagnostics.record("FOCUS change=$change")
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
