package com.vibecodingjapan.ideavox

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRecord
import android.os.Build
import android.util.Log
import java.util.Locale

object AudioDeviceSelection {
  fun inputDevices(context: Context): List<AudioDeviceInfo> =
    runCatching { audioManager(context).getDevices(AudioManager.GET_DEVICES_INPUTS).sortedBy { label(it) } }.getOrDefault(emptyList())

  fun outputDevices(context: Context): List<AudioDeviceInfo> =
    runCatching { audioManager(context).getDevices(AudioManager.GET_DEVICES_OUTPUTS).sortedBy { label(it) } }.getOrDefault(emptyList())

  fun findInput(context: Context, id: Int?): AudioDeviceInfo? =
    id?.let { selected -> inputDevices(context).firstOrNull { it.id == selected } }

  fun findOutput(context: Context, id: Int?): AudioDeviceInfo? =
    id?.let { selected -> outputDevices(context).firstOrNull { it.id == selected } }

  fun builtInMic(context: Context): AudioDeviceInfo? =
    inputDevices(context).firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }

  fun bluetoothInput(
    context: Context,
    preferredProductName: String? = null,
  ): AudioDeviceInfo? {
    val devices = inputDevices(context).filter(::isBluetoothInput)
    val preferredName = preferredProductName.normalizedDeviceName()
    return devices.firstOrNull { it.productName?.toString().normalizedDeviceName() == preferredName && preferredName != null }
      ?: devices.firstOrNull()
  }

  fun bluetoothOutput(
    context: Context,
    preferredProductName: String? = null,
  ): AudioDeviceInfo? {
    val devices = outputDevices(context).filter(::isBluetoothOutput)
    val preferredName = preferredProductName.normalizedDeviceName()
    return devices.firstOrNull {
      it.productName?.toString().normalizedDeviceName() == preferredName && preferredName != null
    } ?: devices.firstOrNull()
  }

  fun waitForBluetoothInput(
    context: Context,
    preferredProductName: String?,
    timeoutMs: Long = BLUETOOTH_DEVICE_DISCOVERY_WAIT_MS,
  ): AudioDeviceInfo? {
    val deadline = System.currentTimeMillis() + timeoutMs
    do {
      val input = bluetoothInput(context, preferredProductName)
      if (input != null) {
        Log.i(TAG, "Bluetooth input discovered id=${input.id}, type=${input.type}, name=${input.productName}")
        return input
      }
      Thread.sleep(ROUTE_POLL_INTERVAL_MS)
    } while (System.currentTimeMillis() < deadline)
    Log.w(TAG, "Timed out waiting for bluetooth input preferredName=$preferredProductName")
    return null
  }

  fun waitForBluetoothCommunicationReady(
    context: Context,
    inputDevice: AudioDeviceInfo,
    timeoutMs: Long = BLUETOOTH_COMMUNICATION_DEVICE_WAIT_MS,
  ): AudioDeviceInfo? {
    val manager = audioManager(context)
    val preferredName = inputDevice.productName?.toString()
    val deadline = System.currentTimeMillis() + timeoutMs
    do {
      val refreshedInput =
        inputDevices(context)
          .filter(::isBluetoothInput)
          .firstOrNull { sameBluetoothFamily(it, inputDevice) }
          ?: bluetoothInput(context, preferredName)
      if (refreshedInput != null) {
        val active =
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { manager.communicationDevice }.getOrNull()
          } else {
            null
          }
        val available =
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { manager.availableCommunicationDevices }.getOrDefault(emptyList())
          } else {
            emptyList()
          }
        if (
          Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            active?.let { sameBluetoothFamily(it, refreshedInput) } == true ||
            available.any { sameBluetoothFamily(it, refreshedInput) }
        ) {
          Log.i(
            TAG,
            "Bluetooth communication device ready input=${refreshedInput.id}/${refreshedInput.type}/${refreshedInput.productName}, " +
              "active=${active?.id}/${active?.type}/${active?.productName}",
          )
          return refreshedInput
        }
      }
      Thread.sleep(ROUTE_POLL_INTERVAL_MS)
    } while (System.currentTimeMillis() < deadline)
    Log.w(
      TAG,
      "Timed out waiting for bluetooth communication device input=${inputDevice.id}/${inputDevice.type}/${inputDevice.productName}",
    )
    return null
  }

  fun label(device: AudioDeviceInfo): String {
    val name = device.productName?.toString().orEmpty().ifBlank { typeLabel(device.type) }
    return "$name · ${typeLabel(device.type)}"
  }

  fun isBluetoothInput(device: AudioDeviceInfo): Boolean =
    device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
      (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && device.type == AudioDeviceInfo.TYPE_BLE_HEADSET)

  fun isBluetoothOutput(device: AudioDeviceInfo): Boolean =
    when (device.type) {
      AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
      AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
      AudioDeviceInfo.TYPE_HEARING_AID,
      -> true
      else ->
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
          (device.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
            device.type == AudioDeviceInfo.TYPE_BLE_SPEAKER)
    }

  fun prepareInputRoute(
    context: Context,
    device: AudioDeviceInfo?,
  ): AudioInputRoute? {
    if (device == null || !isBluetoothInput(device)) return null
    val manager = audioManager(context)
    val route =
      AudioInputRoute(
        previousMode = manager.mode,
        previousBluetoothScoOn = runCatching { manager.isBluetoothScoOn }.getOrDefault(false),
        communicationDeviceSet = false,
      )
    val communicationDeviceSet = reassertBluetoothInputRoute(manager, device)
    return route.copy(communicationDeviceSet = communicationDeviceSet)
  }

  fun reassertInputRoute(context: Context, device: AudioDeviceInfo?): Boolean {
    if (device == null || !isBluetoothInput(device)) return false
    return reassertBluetoothInputRoute(audioManager(context), device)
  }

  private fun reassertBluetoothInputRoute(manager: AudioManager, device: AudioDeviceInfo): Boolean {
    manager.mode = AudioManager.MODE_IN_COMMUNICATION
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      val activeCommunicationDevice = runCatching { manager.communicationDevice }.getOrNull()
      if (activeCommunicationDevice?.let { sameBluetoothFamily(it, device) } == true) {
        Log.i(
          TAG,
          "Bluetooth communication route already active input=${device.id}/${device.type}/${device.productName}, " +
            "communication=${activeCommunicationDevice.id}/${activeCommunicationDevice.type}/${activeCommunicationDevice.productName}",
        )
        return true
      }
      val communicationDevice = matchingCommunicationDevice(manager, device)
      if (communicationDevice == null) {
        Log.w(TAG, "No communication device matched bluetooth input id=${device.id}, type=${device.type}")
        return false
      }
      repeat(COMMUNICATION_ROUTE_ATTEMPTS) { attempt ->
        val requested = runCatching { manager.setCommunicationDevice(communicationDevice) }
          .onFailure { Log.w(TAG, "setCommunicationDevice failed attempt=${attempt + 1}", it) }
          .getOrDefault(false)
        if (requested && waitForCommunicationDevice(manager, communicationDevice)) {
          Log.i(
            TAG,
            "Bluetooth communication route active input=${device.id}/${device.type}, communication=${communicationDevice.id}/${communicationDevice.type}",
          )
          return true
        }
        Log.w(
          TAG,
          "Bluetooth communication route not active attempt=${attempt + 1}, input=${device.id}/${device.type}",
        )
        runCatching { manager.clearCommunicationDevice() }
        Thread.sleep(ROUTE_RETRY_DELAY_MS)
      }
      if (device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
        Log.w(TAG, "setCommunicationDevice did not activate HFP; trying legacy SCO compatibility route")
        @Suppress("DEPRECATION")
        runCatching {
          manager.startBluetoothSco()
          manager.isBluetoothScoOn = true
        }.onFailure { Log.w(TAG, "Legacy bluetooth SCO request failed", it) }
        if (waitForLegacyBluetoothSco(manager)) {
          Log.i(TAG, "Legacy bluetooth SCO compatibility route requested")
          return true
        }
      }
      return false
    }
    @Suppress("DEPRECATION")
    runCatching {
      manager.startBluetoothSco()
      manager.isBluetoothScoOn = true
    }
    return waitForLegacyBluetoothSco(manager)
  }

  fun waitForBluetoothAudioRecordRoute(
    recorder: AudioRecord,
    timeoutMs: Long = AUDIO_RECORD_ROUTE_WAIT_MS,
  ): AudioDeviceInfo? {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
      val routed = runCatching { recorder.routedDevice }.getOrNull()
      if (routed?.let(::isBluetoothInput) == true) {
        Log.i(TAG, "AudioRecord routed to bluetooth id=${routed.id}, type=${routed.type}")
        return routed
      }
      Thread.sleep(ROUTE_POLL_INTERVAL_MS)
    }
    val routed = runCatching { recorder.routedDevice }.getOrNull()
    Log.w(TAG, "AudioRecord did not route to bluetooth; actual=${routed?.id}/${routed?.type}")
    return null
  }

  fun restoreInputRoute(context: Context, route: AudioInputRoute?) {
    if (route == null) return
    val manager = audioManager(context)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && route.communicationDeviceSet) {
      runCatching { manager.clearCommunicationDevice() }
    } else {
      @Suppress("DEPRECATION")
      runCatching {
        manager.isBluetoothScoOn = route.previousBluetoothScoOn
        manager.stopBluetoothSco()
      }
    }
    manager.mode = route.previousMode
  }

  private fun matchingCommunicationDevice(manager: AudioManager, inputDevice: AudioDeviceInfo): AudioDeviceInfo? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
    val active = runCatching { manager.communicationDevice }.getOrNull()
    if (active?.let { sameBluetoothFamily(it, inputDevice) } == true) return active
    val devices = runCatching { manager.availableCommunicationDevices }.getOrDefault(emptyList())
    val availableMatch =
      devices.firstOrNull { it.id == inputDevice.id }
        ?: devices.firstOrNull { sameBluetoothFamily(it, inputDevice) }
    if (availableMatch != null) return availableMatch

    // Some Pixel/Bluetooth combinations expose the newly selected HFP sink in
    // GET_DEVICES_OUTPUTS several seconds before availableCommunicationDevices.
    // Requesting that exact same-name SCO sink is safe because AudioRecord's
    // routedDevice is still verified before any samples are accepted.
    val outputMatch =
      runCatching { manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList() }
        .getOrDefault(emptyList())
        .firstOrNull { sameBluetoothFamily(it, inputDevice) }
    if (outputMatch != null) {
      Log.i(
        TAG,
        "Using bluetooth communication sink from output devices while available list is stale " +
          "input=${inputDevice.id}/${inputDevice.type}/${inputDevice.productName}, " +
          "output=${outputMatch.id}/${outputMatch.type}/${outputMatch.productName}",
      )
    }
    return outputMatch
  }

  private fun sameBluetoothFamily(candidate: AudioDeviceInfo, inputDevice: AudioDeviceInfo): Boolean {
    if (!isBluetoothInput(candidate) || !isBluetoothInput(inputDevice)) return false
    if (candidate.id == inputDevice.id) return true
    val candidateName = candidate.productName?.toString().normalizedDeviceName()
    val inputName = inputDevice.productName?.toString().normalizedDeviceName()
    if (candidateName != null && inputName != null) return candidateName == inputName
    return candidate.type == inputDevice.type
  }

  private fun waitForCommunicationDevice(manager: AudioManager, selected: AudioDeviceInfo): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
    val deadline = System.currentTimeMillis() + COMMUNICATION_ROUTE_WAIT_MS
    while (System.currentTimeMillis() < deadline) {
      val active = runCatching { manager.communicationDevice }.getOrNull()
      if (active?.id == selected.id || active?.let { sameBluetoothFamily(it, selected) } == true) return true
      Thread.sleep(ROUTE_POLL_INTERVAL_MS)
    }
    val active = runCatching { manager.communicationDevice }.getOrNull()
    Log.w(
      TAG,
      "Timed out waiting for bluetooth communication route selected=${selected.id}/${selected.type}, active=${active?.id}/${active?.type}",
    )
    return false
  }

  @Suppress("DEPRECATION")
  private fun waitForLegacyBluetoothSco(manager: AudioManager): Boolean {
    val deadline = System.currentTimeMillis() + COMMUNICATION_ROUTE_WAIT_MS
    while (System.currentTimeMillis() < deadline) {
      if (runCatching { manager.isBluetoothScoOn }.getOrDefault(false)) return true
      Thread.sleep(ROUTE_POLL_INTERVAL_MS)
    }
    Log.w(TAG, "Timed out waiting for legacy bluetooth SCO route")
    return false
  }

  private fun audioManager(context: Context): AudioManager =
    context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

  private fun typeLabel(type: Int): String =
    when (type) {
      AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "听筒"
      AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "扬声器"
      AudioDeviceInfo.TYPE_BUILTIN_MIC -> "内置麦克风"
      AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "蓝牙通话"
      AudioDeviceInfo.TYPE_BLE_HEADSET -> "蓝牙耳机"
      AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "蓝牙音频"
      AudioDeviceInfo.TYPE_WIRED_HEADSET -> "有线耳麦"
      AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "有线耳机"
      AudioDeviceInfo.TYPE_USB_DEVICE -> "USB 设备"
      AudioDeviceInfo.TYPE_USB_HEADSET -> "USB 耳机"
      AudioDeviceInfo.TYPE_HDMI -> "HDMI"
      else -> "音频设备"
    }
}

data class AudioInputRoute(
  val previousMode: Int,
  val previousBluetoothScoOn: Boolean,
  val communicationDeviceSet: Boolean,
)

private const val TAG = "IdeavoxAudioRoute"
private const val COMMUNICATION_ROUTE_WAIT_MS = 2200L
private const val AUDIO_RECORD_ROUTE_WAIT_MS = 1200L
private const val ROUTE_POLL_INTERVAL_MS = 50L
private const val ROUTE_RETRY_DELAY_MS = 120L
private const val COMMUNICATION_ROUTE_ATTEMPTS = 2
private const val BLUETOOTH_DEVICE_DISCOVERY_WAIT_MS = 8_000L
private const val BLUETOOTH_COMMUNICATION_DEVICE_WAIT_MS = 8_000L

private fun String?.normalizedDeviceName(): String? =
  this
    ?.trim()
    ?.lowercase(Locale.ROOT)
    ?.takeIf { it.isNotBlank() }
