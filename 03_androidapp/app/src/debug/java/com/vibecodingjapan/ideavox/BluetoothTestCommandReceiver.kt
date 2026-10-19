package com.vibecodingjapan.ideavox

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.util.Log

/** Debug manifest only; DUMP permission restricts commands to authorized shell/system callers. */
class BluetoothTestCommandReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    when (val command = intent.getStringExtra("command")) {
      "enable-volume-stop" -> appStore.saveBluetoothVolumeStopEnabled(intent.getBooleanExtra("enabled", false))
      "numeric-call-address" -> Log.i(TAG, "numeric test accepted=${BluetoothRecordingTelecom.configureDebugNumericAddress(context, intent.getBooleanExtra("enabled", true))}")
      "legacy-call-address" -> Log.i(TAG, "legacy alpha test accepted=${BluetoothRecordingTelecom.configureDebugCallAddress(context, intent.getBooleanExtra("enabled", true))}")
      "voip-audio-mode" -> Log.i(TAG, "voip audio mode test accepted=${BluetoothRecordingTelecom.configureDebugVoipAudioMode(context, intent.getBooleanExtra("enabled", true))}")
      "start" -> Log.i(TAG, "debug start accepted=${BluetoothRecordingWorkflow.startDebugBluetoothRecording(context)}")
      "stop" -> {
        val id = RecordingRuntime.state.value.activeRecordingId
        if (id != null) context.startService(
          Intent(context, RecordingService::class.java).setAction(RecordingService.ACTION_STOP)
            .putExtra(RecordingService.EXTRA_EXPECTED_RECORDING_ID, id)
        )
      }
      "volume-up" -> {
        // Debug simulation of the temple forward swipe. The production path
        // arrives through RecordingService's VOLUME_CHANGED receiver.
        val state = RecordingRuntime.state.value
        val id = state.activeRecordingId ?: BluetoothRecordingWorkflow.awaitingUploadRecordingId()
        if (state.status == RecordingStatus.RECORDING || state.status == RecordingStatus.PAUSED) {
          Log.i(TAG, "sim volume-up stop id=$id effect=${BluetoothRecordingWorkflow.handleTelecomEnd(context, id, source = "volume")}")
        } else if (id != null) {
          Log.i(TAG, "sim volume-up upload id=$id effect=${BluetoothRecordingWorkflow.handleUploadConfirmation(context, id, source = "volume")}")
        } else {
          Log.w(TAG, "sim volume-up ignored: no recording and no upload window")
        }
      }
      "raise-call-volume" -> {
        // Exercises the real volume gate through the system broadcast.
        val audio = context.getSystemService(AudioManager::class.java)
        val current = audio.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
        val target = (current + 1).coerceAtMost(audio.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL))
        audio.setStreamVolume(AudioManager.STREAM_VOICE_CALL, target, 0)
        Log.i(TAG, "sim raise call volume $current->$target")
      }
      "lower-call-volume" -> {
        // Exercises the production backward-swipe gate through the broadcast.
        val audio = context.getSystemService(AudioManager::class.java)
        val current = audio.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
        val minVolume = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) audio.getStreamMinVolume(AudioManager.STREAM_VOICE_CALL) else 1
        val target = (current - 1).coerceAtLeast(minVolume)
        audio.setStreamVolume(AudioManager.STREAM_VOICE_CALL, target, 0)
        Log.i(TAG, "sim lower call volume $current->$target")
      }
      "status" -> Unit
      else -> Log.w(TAG, "unknown test command=$command")
    }
    Log.i(
      TAG,
      "debug status=${RecordingRuntime.state.value.status} recording=${RecordingRuntime.state.value.activeRecordingId} " +
        "bluetooth=${RecordingRuntime.activeInputIsBluetooth} volumeStop=${appStore.snapshot.value.bluetoothVolumeStopEnabled} " +
        "call=${BluetoothRecordingTelecom.statusDescription()}",
    )
  }

  companion object { private const val TAG = "IdeavoxBtTest" }
}
