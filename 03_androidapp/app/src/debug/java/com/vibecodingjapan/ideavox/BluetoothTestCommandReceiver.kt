package com.vibecodingjapan.ideavox

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Debug manifest only; DUMP permission restricts commands to authorized shell/system callers. */
class BluetoothTestCommandReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    when (val command = intent.getStringExtra("command")) {
      "enable-volume-stop" -> appStore.saveBluetoothVolumeStopEnabled(intent.getBooleanExtra("enabled", false))
      "numeric-call-address" -> Log.i(TAG, "numeric test accepted=${BluetoothRecordingTelecom.configureDebugNumericAddress(context, intent.getBooleanExtra("enabled", false))}")
      "start" -> Log.i(TAG, "debug start accepted=${BluetoothRecordingWorkflow.startDebugBluetoothRecording(context)}")
      "stop" -> {
        val id = RecordingRuntime.state.value.activeRecordingId
        if (id != null) context.startService(
          Intent(context, RecordingService::class.java).setAction(RecordingService.ACTION_STOP)
            .putExtra(RecordingService.EXTRA_EXPECTED_RECORDING_ID, id)
        )
      }
      "status" -> Unit
      else -> Log.w(TAG, "unknown test command=$command")
    }
    Log.i(TAG, "debug status=${RecordingRuntime.state.value.status} recording=${RecordingRuntime.state.value.activeRecordingId} bluetooth=${RecordingRuntime.activeInputIsBluetooth} volumeStop=${appStore.snapshot.value.bluetoothVolumeStopEnabled} callReady=${BluetoothRecordingTelecom.isReady()}")
  }

  companion object { private const val TAG = "IdeavoxBtTest" }
}
