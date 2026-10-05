package com.vibecodingjapan.ideavox

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.bluetooth.BluetoothDevice
import android.media.AudioDeviceInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
  private val lock = Any()
  @Volatile private var activeConnection: BluetoothRecordingConnection? = null
  @Volatile private var endingFromApp = false
  @Volatile private var allowConnectionWhilePreparing = false
  @Volatile private var activeConnectionReady = false
  @Volatile private var preferredBluetoothName: String? = null
  @Volatile private var preferredBluetoothAddress: String? = null

  fun start(
    context: Context,
    allowWhilePreparing: Boolean = false,
    preferredProductName: String? = null,
    preferredAddress: String? = null,
  ) {
    val appContext = context.applicationContext
    val telecom = telecomManager(appContext) ?: return
    val handle = phoneAccountHandle(appContext)
    runCatching {
      registerPhoneAccount(telecom, handle)
      endingFromApp = false
      allowConnectionWhilePreparing = allowWhilePreparing
      activeConnectionReady = false
      preferredBluetoothName = preferredProductName
      preferredBluetoothAddress = preferredAddress
      val extras =
        Bundle().apply {
          putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle)
          putInt(TelecomManager.EXTRA_START_CALL_WITH_VIDEO_STATE, VideoProfile.STATE_AUDIO_ONLY)
        }
      telecom.placeCall(Uri.fromParts("voip", ACCOUNT_ID, null), extras)
      Log.d(TAG, "requested self-managed bluetooth recording call")
    }.onFailure { error ->
      Log.w(TAG, "unable to start self-managed bluetooth recording call", error)
    }
  }

  fun endFromApp() {
    val connection = activeConnection
    endingFromApp = true
    allowConnectionWhilePreparing = false
    activeConnectionReady = false
    if (connection == null) {
      endingFromApp = false
      return
    }
    connection.disconnectFromApp()
  }

  internal fun onConnectionCreated(connection: BluetoothRecordingConnection) {
    synchronized(lock) {
      activeConnection?.disconnectFromApp()
      activeConnection = connection
      endingFromApp = false
      activeConnectionReady = false
    }
    if (
      RecordingRuntime.state.value.status == RecordingStatus.IDLE &&
        !allowConnectionWhilePreparing
    ) {
      connection.disconnectFromApp()
    }
  }

  internal fun onConnectionActivated(connection: BluetoothRecordingConnection) {
    if (activeConnection === connection) {
      Log.d(TAG, "telecom bluetooth recording control is active; waiting for bluetooth call endpoint")
    }
  }

  internal fun onBluetoothRouteReady(connection: BluetoothRecordingConnection, description: String) {
    if (activeConnection === connection) {
      activeConnectionReady = true
      Log.i(TAG, "telecom bluetooth call endpoint is active: $description")
    }
  }

  internal fun onConnectionDestroyed(connection: BluetoothRecordingConnection) {
    synchronized(lock) {
      if (activeConnection === connection) {
        activeConnection = null
        endingFromApp = false
        allowConnectionWhilePreparing = false
        activeConnectionReady = false
        preferredBluetoothName = null
        preferredBluetoothAddress = null
      }
    }
  }

  fun isReady(): Boolean = activeConnectionReady

  fun recordingStarted() {
    allowConnectionWhilePreparing = false
  }

  internal fun pauseRecording(context: Context): BluetoothGestureEffect {
    Log.d(TAG, "telecom hold event mapped to bluetooth workflow trigger")
    return BluetoothRecordingWorkflow.handleTrigger(context, fromTelecom = true)
  }

  internal fun resumeRecording(context: Context): BluetoothGestureEffect {
    Log.d(TAG, "telecom unhold event mapped to bluetooth workflow trigger")
    return BluetoothRecordingWorkflow.handleTrigger(context, fromTelecom = true)
  }

  internal fun stopRecording(context: Context): BluetoothGestureEffect {
    if (endingFromApp) return BluetoothGestureEffect.Ignore
    Log.d(TAG, "telecom disconnect event mapped to bluetooth workflow trigger")
    return BluetoothRecordingWorkflow.handleTrigger(context, fromTelecom = true)
  }

  internal fun hasActiveConnection(): Boolean = activeConnection != null

  internal fun preferredBluetoothDevice(devices: Collection<BluetoothDevice>): BluetoothDevice? {
    val expectedAddress = preferredBluetoothAddress?.takeIf { it.isNotBlank() }
    val expectedName = preferredBluetoothName?.trim()?.lowercase()
    return devices.firstOrNull {
      expectedAddress != null && it.address.equals(expectedAddress, ignoreCase = true)
    } ?: devices.firstOrNull {
      expectedName != null && it.name?.trim()?.lowercase() == expectedName
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
    return BluetoothRecordingConnection(this).also { connection ->
      BluetoothRecordingTelecom.onConnectionCreated(connection)
      connection.activate(request.address)
    }
  }

  override fun onCreateOutgoingConnectionFailed(
    connectionManagerPhoneAccount: PhoneAccountHandle,
    request: ConnectionRequest,
  ) {
    Log.w(TAG, "self-managed bluetooth recording call failed")
    BluetoothRecordingTelecom.endFromApp()
  }

  companion object {
    private const val TAG = "IdeavoxBtTelecomSvc"
  }
}

class BluetoothRecordingConnection(
  context: Context,
) : Connection() {
  private val appContext = context.applicationContext
  @Volatile private var destroyed = false

  init {
    connectionProperties = PROPERTY_SELF_MANAGED
    connectionCapabilities = CAPABILITY_HOLD or CAPABILITY_SUPPORT_HOLD
    // Classic HFP headsets such as soundcore V20i may acknowledge a
    // self-managed VoIP call without ever opening bidirectional SCO. Using the
    // standard call audio mode makes BluetoothInCallService publish the active
    // call to HFP and establish AudioOn, while the call remains self-managed.
    setAudioModeIsVoip(false)
    setCallerDisplayName("ideavox 录音".localized(appContext), TelecomManager.PRESENTATION_ALLOWED)
  }

  fun activate(address: Uri?) {
    setAddress(address ?: Uri.fromParts("voip", "ideavox", null), TelecomManager.PRESENTATION_ALLOWED)
    videoState = VideoProfile.STATE_AUDIO_ONLY
    setActive()
    BluetoothRecordingTelecom.onConnectionActivated(this)
    // Telecom owns call routing for a self-managed Connection. Asking it for
    // Bluetooth here is what actually opens HFP/SCO on headsets that reject
    // the separate voice-recognition command.
    @Suppress("DEPRECATION")
    setAudioRoute(CallAudioState.ROUTE_BLUETOOTH)
  }

  @Suppress("DEPRECATION")
  override fun onCallAudioStateChanged(state: CallAudioState) {
    super.onCallAudioStateChanged(state)
    val activeDevice = state.activeBluetoothDevice
    if (state.route and CallAudioState.ROUTE_BLUETOOTH != 0 && activeDevice != null) {
      BluetoothRecordingTelecom.onBluetoothRouteReady(this, activeDevice.name ?: activeDevice.address)
      return
    }
    if (BluetoothRecordingTelecom.isReady()) return
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      val target = BluetoothRecordingTelecom.preferredBluetoothDevice(state.supportedBluetoothDevices)
      if (target != null) {
        Log.d(TAG, "requesting Telecom bluetooth audio for ${target.name}")
        requestBluetoothAudio(target)
      }
    }
  }

  override fun onAvailableCallEndpointsChanged(availableEndpoints: List<CallEndpoint>) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
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
    if (
      Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
        callEndpoint.endpointType == CallEndpoint.TYPE_BLUETOOTH
    ) {
      BluetoothRecordingTelecom.onBluetoothRouteReady(this, callEndpoint.endpointName.toString())
    }
  }

  override fun onHold() {
    val effect = BluetoothRecordingTelecom.pauseRecording(appContext)
    if (effect is BluetoothGestureEffect.StopAndArmUpload) {
      // Keep the HFP call active during the upload confirmation window so the
      // next headset press remains a Telecom event instead of crossing to A2DP.
      setActive()
    } else if (!destroyed) {
      setOnHold()
    }
  }

  override fun onUnhold() {
    BluetoothRecordingTelecom.resumeRecording(appContext)
    if (!destroyed) setActive()
  }

  override fun onDisconnect() {
    BluetoothRecordingTelecom.stopRecording(appContext)
    disconnect(DisconnectCause.LOCAL)
  }

  override fun onAbort() {
    BluetoothRecordingTelecom.stopRecording(appContext)
    disconnect(DisconnectCause.CANCELED)
  }

  fun disconnectFromApp() {
    disconnect(DisconnectCause.LOCAL)
  }

  private fun disconnect(cause: Int) {
    if (destroyed) return
    destroyed = true
    setDisconnected(DisconnectCause(cause))
    destroy()
    BluetoothRecordingTelecom.onConnectionDestroyed(this)
  }

  private companion object {
    const val TAG = "IdeavoxBtConnection"
  }
}
