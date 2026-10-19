package com.vibecodingjapan.ideavox

import android.Manifest
import android.content.pm.PackageManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.media.AudioDeviceInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.OutcomeReceiver
import android.telecom.CallAudioState
import android.telecom.CallEndpoint
import android.telecom.CallEndpointException
import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.DisconnectCause
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import android.util.Log
import androidx.core.content.ContextCompat

object BluetoothRecordingControlModeResolver {
  fun effective(
    mode: BluetoothRecordingControlMode,
    bluetoothInputType: Int? = null,
  ): BluetoothRecordingControlMode =
    when (mode) {
      BluetoothRecordingControlMode.AUTO ->
        if (bluetoothInputType == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
          BluetoothRecordingControlMode.TELECOM
        } else {
          BluetoothRecordingControlMode.MEDIA
        }
      BluetoothRecordingControlMode.MEDIA,
      BluetoothRecordingControlMode.TELECOM,
      -> mode
    }

  fun shouldUseTelecom(
    mode: BluetoothRecordingControlMode,
    bluetoothInputType: Int? = null,
  ): Boolean =
    effective(mode, bluetoothInputType) == BluetoothRecordingControlMode.TELECOM

  fun description(mode: BluetoothRecordingControlMode): String =
    when (mode) {
      BluetoothRecordingControlMode.AUTO ->
        "自动：新型 BLE 双向音频使用媒体按键；经典 SCO/HFP 麦克风模式同时启用通话挂断兜底。"
      BluetoothRecordingControlMode.MEDIA ->
        "媒体按键模式：播放、暂停或下一曲按键都作为“录音 / 确认”操作。"
      BluetoothRecordingControlMode.TELECOM ->
        "通话控制模式：将耳机的通话按键统一作为“录音 / 确认”操作，不再暂停录音。"
    }

}

object BluetoothRecordingTelecom {
  private const val TAG = "IdeavoxBtTelecom"
  private const val ACCOUNT_ID = "ideavox-bluetooth-recording"
  internal const val EXTRA_SESSION_ID = "ideavox.bluetooth.CALL_SESSION_ID"
  private val mainHandler = Handler(Looper.getMainLooper())
  private val lifecycle = BluetoothCallLifecycle()
  @Volatile private var activeConnection: BluetoothRecordingConnection? = null
  private var allowConnectionWhilePreparing = false
  @Volatile private var scoAudioConnected = false
  private var audioObserverRegistered = false
  @Volatile private var diagnosticLegacyAlphaAddress = false
  @Volatile private var diagnosticVoipAudioMode = false
  @Volatile private var activeCallAddress: String? = null
  @Volatile private var preferredBluetoothName: String? = null
  @Volatile private var preferredBluetoothAddress: String? = null

  private fun onMain(action: () -> Unit) {
    if (Looper.myLooper() == Looper.getMainLooper()) action() else mainHandler.post { action() }
  }

  /**
   * Debug-only comparison switch. The default is always the numeric address.
   * `enabled = true` keeps the numeric address; `enabled = false` selects the
   * old alphabetic identifier so the HFP CLCC difference can be compared.
   */
  internal fun configureDebugNumericAddress(context: Context, enabled: Boolean): Boolean =
    configureDebugCallAddress(context, legacyAlpha = !enabled)

  internal fun configureDebugCallAddress(context: Context, legacyAlpha: Boolean): Boolean {
    if (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0 ||
      lifecycle.current != null || RecordingRuntime.state.value.status != RecordingStatus.IDLE
    ) return false
    diagnosticLegacyAlphaAddress = legacyAlpha
    Log.i(
      TAG,
      "debug call address legacyAlpha=$legacyAlpha " +
        "address=${BluetoothCallIdentity.address(legacyAlpha)} " +
        "hfpDialable=${BluetoothCallIdentity.hfpDialable(BluetoothCallIdentity.address(legacyAlpha))}; no carrier call",
    )
    return true
  }

  /**
   * Debug-only comparison switch for the call audio mode. The default keeps the
   * existing call-audio mode because it is the configuration that has opened
   * SCO on the test headset; `true` uses the standard self-managed VoIP mode.
   */
  internal fun configureDebugVoipAudioMode(context: Context, enabled: Boolean): Boolean {
    if (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0 ||
      lifecycle.current != null || RecordingRuntime.state.value.status != RecordingStatus.IDLE
    ) return false
    diagnosticVoipAudioMode = enabled
    Log.i(TAG, "debug call audio mode voip=$enabled")
    return true
  }

  internal fun voipAudioMode(): Boolean = diagnosticVoipAudioMode

  internal fun activeCallAddress(): String? = activeCallAddress

  internal fun statusDescription(): String {
    val session = lifecycle.current
    return "session=${session?.id ?: "none"} attached=${session?.attached ?: false} " +
      "endpoint=${session?.endpointReady ?: false} sco=$scoAudioConnected ready=${isReady()} " +
      "address=${activeCallAddress ?: "none"} legacyAlpha=$diagnosticLegacyAlphaAddress " +
      "voipAudioMode=$diagnosticVoipAudioMode"
  }

  internal fun observeBluetoothAudio(context: Context) = onMain {
    if (audioObserverRegistered) return@onMain
    val appContext = context.applicationContext
    ContextCompat.registerReceiver(
      appContext,
      object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
          onBluetoothAudioStateChanged(context, intent.getIntExtra(BluetoothHeadset.EXTRA_STATE, -1) == BluetoothHeadset.STATE_AUDIO_CONNECTED)
        }
      },
      IntentFilter(BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED),
      ContextCompat.RECEIVER_EXPORTED,
    )
    audioObserverRegistered = true
  }

  fun start(
    context: Context,
    allowWhilePreparing: Boolean = false,
    preferredProductName: String? = null,
    preferredAddress: String? = null,
    recordingId: String? = RecordingRuntime.state.value.activeRecordingId,
    requiresSco: Boolean = true,
  ) = onMain {
    val appContext = context.applicationContext
    observeBluetoothAudio(appContext)
    if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.MANAGE_OWN_CALLS) != PackageManager.PERMISSION_GRANTED) {
      Log.w(TAG, "MANAGE_OWN_CALLS unavailable; cannot start headset call control")
      return@onMain
    }
    val current = lifecycle.current
    if (recordingId != null && current?.recordingId == recordingId && !current.endHandled) {
      Log.d(TAG, "keeping call session=${current.id} recording=$recordingId")
      return@onMain
    }
    val telecom = telecomManager(appContext) ?: return@onMain
    endNow("replace")
    val session = lifecycle.begin(recordingId, requiresSco, scoAudioConnected)
    allowConnectionWhilePreparing = allowWhilePreparing
    preferredBluetoothName = preferredProductName
    preferredBluetoothAddress = preferredAddress
    val handle = phoneAccountHandle(appContext)
    val callAddress = BluetoothCallIdentity.address(diagnosticLegacyAlphaAddress)
    runCatching {
      registerPhoneAccount(telecom, handle)
      val callExtras = Bundle().apply { putLong(EXTRA_SESSION_ID, session.id) }
      val extras = Bundle().apply {
        putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle)
        putInt(TelecomManager.EXTRA_START_CALL_WITH_VIDEO_STATE, VideoProfile.STATE_AUDIO_ONLY)
        putBundle(TelecomManager.EXTRA_OUTGOING_CALL_EXTRAS, callExtras)
      }
      // This is always our explicit self-managed voip account. The numeric
      // scheme-specific part survives the HFP CLCC dial-character filter, so
      // the headset sees a nonempty number. It never dials a carrier.
      activeCallAddress = callAddress
      telecom.placeCall(Uri.fromParts("voip", callAddress, null), extras)
      Log.i(
        TAG,
        "requested call session=${session.id} recording=$recordingId requiresSco=$requiresSco " +
          "address=$callAddress hfpDialable=${BluetoothCallIdentity.hfpDialable(callAddress)} " +
          "legacyAlpha=$diagnosticLegacyAlphaAddress voipAudioMode=$diagnosticVoipAudioMode",
      )
    }.onFailure { error ->
      Log.w(TAG, "unable to start call session=${session.id}", error)
      endNow("placeCall failed")
    }
  }

  fun endFromApp(recordingId: String? = null) = onMain {
    if (recordingId == null || lifecycle.current?.recordingId == recordingId) endNow("app")
  }

  internal fun recordingIdFor(sessionId: Long): String? = lifecycle.current?.takeIf { it.id == sessionId }?.recordingId
  internal fun isScoAudioConnected(): Boolean = scoAudioConnected

  private fun endNow(reason: String) {
    val old = activeConnection
    if (lifecycle.current != null) scoAudioConnected = false
    Log.i(TAG, "release call session=${lifecycle.current?.id} reason=$reason")
    // Invalidate pending requests before destroying the connection. Its callbacks
    // may arrive after another recording has already requested a new call.
    lifecycle.clear()
    activeConnection = null
    allowConnectionWhilePreparing = false
    activeCallAddress = null
    preferredBluetoothName = null
    preferredBluetoothAddress = null
    old?.disconnectFromApp()
  }

  internal fun sessionId(request: ConnectionRequest): Long =
    request.extras?.getLong(EXTRA_SESSION_ID, 0L)?.takeIf { it != 0L }
      ?: request.extras?.getBundle(TelecomManager.EXTRA_OUTGOING_CALL_EXTRAS)?.getLong(EXTRA_SESSION_ID, 0L)
      ?: 0L

  internal fun onConnectionCreated(connection: BluetoothRecordingConnection): Boolean {
    if (
      (RecordingRuntime.state.value.status == RecordingStatus.IDLE && !allowConnectionWhilePreparing) ||
      !lifecycle.attach(connection.sessionId)
    ) {
      Log.w(TAG, "reject late/duplicate connection session=${connection.sessionId} current=${lifecycle.current?.id}")
      connection.disconnectFromApp()
      return false
    }
    activeConnection = connection
    Log.i(TAG, "attached call session=${connection.sessionId} recording=${connection.recordingId}")
    return true
  }

  internal fun onConnectionFailed(request: ConnectionRequest) {
    val id = sessionId(request)
    Log.w(TAG, "create connection failed session=$id current=${lifecycle.current?.id}")
    if (lifecycle.current?.id == id) endNow("create failed")
  }

  internal fun onConnectionActivated(connection: BluetoothRecordingConnection) {
    if (activeConnection === connection) Log.i(TAG, "call active session=${connection.sessionId}; waiting for endpoint and audio")
  }

  internal fun onBluetoothRouteChanged(connection: BluetoothRecordingConnection, ready: Boolean, description: String) {
    if (activeConnection !== connection) return
    lifecycle.endpoint(connection.sessionId, ready)
    Log.i(TAG, "call endpoint session=${connection.sessionId} bluetooth=$ready ready=${isReady()} $description")
  }

  internal fun onBluetoothAudioStateChanged(context: Context, connected: Boolean) = onMain {
    val wasConnected = scoAudioConnected
    scoAudioConnected = connected
    lifecycle.audio(connected)
    val session = lifecycle.current
    Log.i(TAG, "HFP audio connected=$connected call=${session?.id} ready=${isReady()}")
    if (wasConnected && !connected && !BluetoothTouchDiagnostics.active) {
      RecordingService.onBluetoothAudioLost(context, session?.recordingId ?: RecordingRuntime.state.value.activeRecordingId)
    }
  }

  internal fun onConnectionDestroyed(connection: BluetoothRecordingConnection) {
    Log.i(TAG, "destroyed call session=${connection.sessionId} current=${lifecycle.current?.id}")
    if (activeConnection === connection) {
      activeConnection = null
      allowConnectionWhilePreparing = false
      activeCallAddress = null
      preferredBluetoothName = null
      preferredBluetoothAddress = null
      lifecycle.finish(connection.sessionId)
    }
  }

  fun isReady(): Boolean = lifecycle.current?.ready == true
  internal fun isCurrent(connection: BluetoothRecordingConnection): Boolean =
    activeConnection === connection && lifecycle.current?.id == connection.sessionId

  fun recordingStarted(recordingId: String) = onMain {
    if (lifecycle.current?.recordingId == recordingId) allowConnectionWhilePreparing = false
  }

  internal fun requestRecordingEnd(context: Context, connection: BluetoothRecordingConnection, reason: String): BluetoothGestureEffect {
    if (!isCurrent(connection)) return BluetoothGestureEffect.Ignore
    val session = lifecycle.claimEnd(connection.sessionId) ?: return BluetoothGestureEffect.Ignore
    Log.i(TAG, "call control session=${session.id} recording=${session.recordingId} reason=$reason")
    return BluetoothRecordingWorkflow.handleTelecomEnd(context, session.recordingId, source = reason)
  }

  internal fun onServiceFocusLost(service: BluetoothRecordingConnectionService) {
    activeConnection?.takeIf { it.owner === service }?.let {
      requestRecordingEnd(service, it, "focus lost")
      it.disconnectFromApp()
    }
  }

  internal fun onServiceDestroyed(service: BluetoothRecordingConnectionService) {
    activeConnection?.takeIf { it.owner === service }?.let {
      requestRecordingEnd(service, it, "service destroyed")
      endNow("service destroyed")
    }
  }

  internal fun hasActiveConnection(): Boolean = lifecycle.current != null

  internal fun preferredBluetoothDevice(devices: Collection<BluetoothDevice>): BluetoothDevice? {
    val expectedAddress = preferredBluetoothAddress?.takeIf { it.isNotBlank() }
    val expectedName = preferredBluetoothName?.trim()?.lowercase()
    return devices.firstOrNull {
      expectedAddress != null && it.address.equals(expectedAddress, ignoreCase = true)
    } ?: devices.firstOrNull {
      expectedName != null && safeBluetoothName(it)?.trim()?.lowercase() == expectedName
    } ?: devices.singleOrNull()
      ?: devices.firstOrNull()
  }

  private fun registerPhoneAccount(telecom: TelecomManager, handle: PhoneAccountHandle) {
    val account =
      PhoneAccount.builder(handle, "ideavox")
        .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
        .setShortDescription("蓝牙耳机录音控制")
        .addSupportedUriScheme("voip")
        .build()
    telecom.registerPhoneAccount(account)
  }

  private fun phoneAccountHandle(context: Context): PhoneAccountHandle =
    PhoneAccountHandle(ComponentName(context, BluetoothRecordingConnectionService::class.java), ACCOUNT_ID)

  private fun telecomManager(context: Context): TelecomManager? =
    ContextCompat.getSystemService(context, TelecomManager::class.java)

}

class BluetoothRecordingConnectionService : ConnectionService() {
  override fun onCreateOutgoingConnection(
    connectionManagerPhoneAccount: PhoneAccountHandle,
    request: ConnectionRequest,
  ): Connection {
    return BluetoothRecordingConnection(this, BluetoothRecordingTelecom.sessionId(request)).also { connection ->
      if (BluetoothRecordingTelecom.onConnectionCreated(connection)) connection.activate(request.address)
    }
  }

  override fun onCreateOutgoingConnectionFailed(
    connectionManagerPhoneAccount: PhoneAccountHandle,
    request: ConnectionRequest,
  ) {
    Log.w(TAG, "self-managed bluetooth recording call failed")
    BluetoothRecordingTelecom.onConnectionFailed(request)
  }

  private var focusGeneration = 0

  override fun onConnectionServiceFocusGained() {
    focusGeneration += 1
    Log.i(TAG, "call focus gained")
  }

  override fun onConnectionServiceFocusLost() {
    Log.i(TAG, "call focus lost; releasing recording resources")
    val generation = ++focusGeneration
    val recordingId = RecordingRuntime.state.value.activeRecordingId
    BluetoothRecordingTelecom.onServiceFocusLost(this)
    val deadline = SystemClock.elapsedRealtime() + 4_000L
    val handler = Handler(mainLooper)
    val release = object : Runnable {
      override fun run() {
        if (generation != focusGeneration) return
        if (!RecordingRuntime.activeInputIsBluetooth || RecordingRuntime.state.value.activeRecordingId != recordingId) {
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) connectionServiceFocusReleased()
          Log.i(TAG, "call focus resources released")
        } else if (SystemClock.elapsedRealtime() < deadline) {
          handler.postDelayed(this, 25L)
        } else {
          Log.e(TAG, "call focus release timed out while microphone is still active")
        }
      }
    }
    handler.post(release)
  }

  override fun onDestroy() {
    focusGeneration += 1
    BluetoothRecordingTelecom.onServiceDestroyed(this)
    super.onDestroy()
  }

  companion object {
    private const val TAG = "IdeavoxBtTelecomSvc"
  }
}

class BluetoothRecordingConnection(
  internal val owner: BluetoothRecordingConnectionService,
  internal val sessionId: Long,
) : Connection() {
  private val appContext = owner.applicationContext
  internal val recordingId: String? = BluetoothRecordingTelecom.recordingIdFor(sessionId)
  @Volatile private var destroyed = false
  @Volatile private var activeRequested = false
  private val mainHandler = Handler(Looper.getMainLooper())
  private val activateFallback = Runnable { promoteToActive("dialing fallback") }

  init {
    connectionProperties = PROPERTY_SELF_MANAGED
    // Hold/answer are not implemented as call features. Advertising HOLD made
    // the headset route some call-button gestures to a hold callback, which
    // the old code handled by ending the recording. Keep only the capabilities
    // that this one-purpose control call actually implements.
    connectionCapabilities = BluetoothCallControlPolicy.CONNECTION_CAPABILITIES
    // Classic HFP headsets such as soundcore V20i may acknowledge a
    // self-managed VoIP call without ever opening bidirectional SCO. The
    // existing call-audio mode is kept as the default; the Debug receiver can
    // switch it to standard VoIP mode for an A/B comparison.
    setAudioModeIsVoip(BluetoothRecordingTelecom.voipAudioMode())
    setCallerDisplayName("ideavox 录音".localized(appContext), TelecomManager.PRESENTATION_ALLOWED)
  }

  fun activate(address: Uri?) {
    val callAddress = address ?: Uri.fromParts("voip", BluetoothCallIdentity.address(false), null)
    setAddress(callAddress, TelecomManager.PRESENTATION_ALLOWED)
    videoState = VideoProfile.STATE_AUDIO_ONLY
    // Publish DIALING first. The HFP stack maps this to an outgoing call-setup
    // update before the call becomes ACTIVE, so the headset sees a normal
    // outgoing call rather than an immediate jump from idle to active.
    setDialing()
    val dialable = BluetoothCallIdentity.hfpDialable(callAddress.schemeSpecificPart)
    Log.i(
      TAG,
      "call dialing session=$sessionId address=$callAddress hfpDialable=$dialable " +
        "voipAudioMode=${BluetoothRecordingTelecom.voipAudioMode()}",
    )
    BluetoothTouchDiagnostics.record(
      "TELECOM dialing address=${callAddress.schemeSpecificPart} hfpDialable=$dialable",
    )
    // Telecom owns call routing for a self-managed Connection. Requesting the
    // Bluetooth route while dialing lets the headset open HFP/SCO before the
    // call is promoted to ACTIVE.
    @Suppress("DEPRECATION")
    setAudioRoute(CallAudioState.ROUTE_BLUETOOTH)
    mainHandler.removeCallbacks(activateFallback)
    mainHandler.postDelayed(activateFallback, CALL_ACTIVE_FALLBACK_MS)
  }

  /**
   * Promote DIALING to ACTIVE as soon as the Bluetooth route/endpoint is
   * usable, with a timed fallback for stacks that do not emit a route callback
   * before the call must become active.
   */
  private fun promoteToActive(reason: String) {
    if (destroyed || activeRequested || !BluetoothRecordingTelecom.isCurrent(this)) return
    if (state != STATE_DIALING) return
    activeRequested = true
    mainHandler.removeCallbacks(activateFallback)
    setActive()
    Log.i(TAG, "call active session=$sessionId reason=$reason")
    BluetoothTouchDiagnostics.record("TELECOM active reason=$reason")
    BluetoothRecordingTelecom.onConnectionActivated(this)
  }

  @Suppress("DEPRECATION")
  override fun onCallAudioStateChanged(state: CallAudioState) {
    super.onCallAudioStateChanged(state)
    if (destroyed || !BluetoothRecordingTelecom.isCurrent(this)) return
    BluetoothTouchDiagnostics.record("TELECOM audio route=${state.route} muted=${state.isMuted} supported=${state.supportedRouteMask}")
    val activeDevice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) state.activeBluetoothDevice else null
    if (state.route and CallAudioState.ROUTE_BLUETOOTH != 0 && (activeDevice != null || Build.VERSION.SDK_INT < Build.VERSION_CODES.P)) {
      promoteToActive("bluetooth audio route")
      BluetoothRecordingTelecom.onBluetoothRouteChanged(this, true, activeDevice?.let(::safeBluetoothName) ?: "Bluetooth")
      return
    }
    BluetoothRecordingTelecom.onBluetoothRouteChanged(this, false, "audio route=${state.route}")
    if (destroyed || !BluetoothRecordingTelecom.isCurrent(this)) return
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      val target = BluetoothRecordingTelecom.preferredBluetoothDevice(state.supportedBluetoothDevices)
      if (target != null) {
        Log.d(TAG, "requesting Telecom bluetooth audio for ${safeBluetoothName(target)}")
        requestBluetoothAudio(target)
      }
    }
  }

  override fun onAvailableCallEndpointsChanged(availableEndpoints: List<CallEndpoint>) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE || destroyed || !BluetoothRecordingTelecom.isCurrent(this)) return
    if (BluetoothRecordingTelecom.isReady()) return
    val bluetoothEndpoint = availableEndpoints.firstOrNull { it.endpointType == CallEndpoint.TYPE_BLUETOOTH }
    if (bluetoothEndpoint == null) {
      Log.w(TAG, "Telecom has not published a bluetooth call endpoint yet")
      return
    }
    Log.d(TAG, "requesting Telecom call endpoint ${bluetoothEndpoint.endpointName}")
    requestCallEndpointChange(
      bluetoothEndpoint,
      appContext.mainExecutor,
      object : OutcomeReceiver<Void, CallEndpointException> {
        override fun onResult(result: Void?) {
          Log.d(TAG, "Telecom accepted bluetooth call endpoint request")
        }

        override fun onError(error: CallEndpointException) {
          Log.w(TAG, "Telecom rejected bluetooth call endpoint request code=${error.code}", error)
        }
      },
    )
  }

  override fun onCallEndpointChanged(callEndpoint: CallEndpoint) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE || destroyed || !BluetoothRecordingTelecom.isCurrent(this)) return
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      BluetoothTouchDiagnostics.record("TELECOM endpoint type=${callEndpoint.endpointType} name=${callEndpoint.endpointName}")
    }
    if (
      Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
        callEndpoint.endpointType == CallEndpoint.TYPE_BLUETOOTH
    ) {
      promoteToActive("bluetooth call endpoint")
      BluetoothRecordingTelecom.onBluetoothRouteChanged(this, true, callEndpoint.endpointName.toString())
    } else {
      BluetoothRecordingTelecom.onBluetoothRouteChanged(this, false, "endpoint type=${callEndpoint.endpointType}")
    }
  }

  override fun onHold() = handleCallControlEvent(BluetoothCallControlEvent.HOLD, "onHold")

  override fun onUnhold() = handleCallControlEvent(BluetoothCallControlEvent.UNHOLD, "onUnhold")

  override fun onAnswer() = handleCallControlEvent(BluetoothCallControlEvent.ANSWER, "onAnswer")

  override fun onDisconnect() = handleCallControlEvent(BluetoothCallControlEvent.DISCONNECT, "onDisconnect")

  override fun onAbort() = handleCallControlEvent(BluetoothCallControlEvent.ABORT, "onAbort")

  /**
   * Only a real HFP hang-up/abort ends the recording. Hold, unhold and answer
   * are not advertised call capabilities; if a headset or the platform still
   * delivers one, keep the control call alive and never treat it as a stop.
   */
  private fun handleCallControlEvent(event: BluetoothCallControlEvent, diagnosticName: String) {
    if (diagnosticEvent(diagnosticName)) {
      if (!destroyed) setActive()
      return
    }
    if (!BluetoothCallControlPolicy.endsRecording(event)) {
      Log.i(TAG, "call control $diagnosticName ignored; hold/answer are not supported")
      BluetoothTouchDiagnostics.record("TELECOM $diagnosticName ignored (not a recording end)")
      // Defensively return to ACTIVE if the platform nevertheless applied a
      // hold; the recording and the headset's call state must stay aligned.
      if (!destroyed && BluetoothRecordingTelecom.isCurrent(this) && state == STATE_HOLDING) setActive()
      return
    }
    Log.i(
      TAG,
      "HFP $diagnosticName session=$sessionId address=${BluetoothRecordingTelecom.activeCallAddress()} " +
        "destroyed=$destroyed",
    )
    BluetoothTouchDiagnostics.record("TELECOM $diagnosticName -> end recording")
    val cause = if (event == BluetoothCallControlEvent.ABORT) DisconnectCause.CANCELED else DisconnectCause.LOCAL
    endRecording(diagnosticName, cause)
  }

  private fun endRecording(reason: String, cause: Int) {
    if (destroyed) return
    BluetoothRecordingTelecom.requestRecordingEnd(appContext, this, reason)
    disconnect(cause)
  }

  fun disconnectFromApp() {
    disconnect(DisconnectCause.LOCAL)
  }

  private fun diagnosticEvent(event: String): Boolean {
    if (!BluetoothTouchDiagnostics.active) return false
    BluetoothTouchDiagnostics.record("TELECOM $event (observed; diagnostic route retained)")
    return true
  }

  override fun onMuteStateChanged(isMuted: Boolean) {
    BluetoothTouchDiagnostics.record("TELECOM onMuteStateChanged=$isMuted")
  }

  override fun onCallEvent(event: String, extras: Bundle?) {
    BluetoothTouchDiagnostics.record("TELECOM onCallEvent=$event")
    super.onCallEvent(event, extras)
  }

  override fun onPlayDtmfTone(c: Char) {
    BluetoothTouchDiagnostics.record("TELECOM onPlayDtmfTone=$c")
  }

  override fun onStopDtmfTone() {
    BluetoothTouchDiagnostics.record("TELECOM onStopDtmfTone")
  }

  override fun onStateChanged(state: Int) {
    Log.i(TAG, "call state=$state session=$sessionId destroyed=$destroyed")
    BluetoothTouchDiagnostics.record("TELECOM state=$state")
  }

  private fun disconnect(cause: Int) {
    if (destroyed) return
    destroyed = true
    mainHandler.removeCallbacks(activateFallback)
    try {
      setDisconnected(DisconnectCause(cause))
      destroy()
    } finally {
      BluetoothRecordingTelecom.onConnectionDestroyed(this)
    }
  }

  private companion object {
    const val TAG = "IdeavoxBtConnection"
    const val CALL_ACTIVE_FALLBACK_MS = 400L
  }
}

private fun safeBluetoothName(device: BluetoothDevice): String? = try {
  device.name
} catch (_: SecurityException) {
  null
}
