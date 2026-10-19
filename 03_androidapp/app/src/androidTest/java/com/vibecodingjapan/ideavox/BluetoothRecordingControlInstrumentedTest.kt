package com.vibecodingjapan.ideavox

import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Runs against real AudioRecord and, when connected, the actual headset route. */
class BluetoothRecordingControlInstrumentedTest {
  @get:Rule val compose = createAndroidComposeRule<MainActivity>()

  @Test fun bluetoothStartKeepsMainLooperResponsiveAndMediaStopSavesWav() {
    val context = compose.activity.applicationContext
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
      "pm grant ${context.packageName} android.permission.RECORD_AUDIO",
    )).use { it.readBytes() }
    val settings = appStore.snapshot.value
    val before = settings.recordings.map { it.id }.toSet()
    val bluetoothAvailable = AudioDeviceSelection.bluetoothInput(context) != null
    try {
      compose.runOnIdle {
        appStore.saveBluetoothHeadsetControlMonitoringEnabled(true)
        appStore.saveBluetoothRecordingControlMode(BluetoothRecordingControlMode.AUTO)
        assertEquals(BluetoothGestureEffect.ArmRecordingStart, BluetoothRecordingWorkflow.handleTrigger(context))
        assertTrue(BluetoothRecordingWorkflow.handleTrigger(context) is BluetoothGestureEffect.StartRecording)
      }
      val responsive = CountDownLatch(1)
      Handler(Looper.getMainLooper()).post { responsive.countDown() }
      assertTrue("Main looper blocked during route setup", responsive.await(500, TimeUnit.MILLISECONDS))
      compose.waitUntil(15_000) { RecordingRuntime.state.value.status == RecordingStatus.RECORDING }
      if (bluetoothAvailable) assertTrue("Expected actual Bluetooth input", RecordingRuntime.activeInputIsBluetooth)
      Thread.sleep(400)
      compose.runOnIdle { BluetoothHeadsetRecordingActions.handlePlayPause(context) }
      compose.waitUntil(8_000) {
        RecordingRuntime.state.value.status == RecordingStatus.IDLE && appStore.snapshot.value.recordings.any { it.id !in before }
      }
      val item = appStore.snapshot.value.recordings.first { it.id !in before }
      assertTrue(File(item.filePath).length() > WavAudio.HEADER_SIZE)
      assertTrue(WavAudio.durationMs(File(item.filePath)) > 0)
      // Expire the upload window without sending data to the server.
      Thread.sleep(BluetoothGestureStateMachine.UPLOAD_CONFIRMATION_WINDOW_MS + 500)
    } finally {
      compose.runOnIdle {
        if (RecordingRuntime.state.value.status != RecordingStatus.IDLE) {
          context.startService(Intent(context, RecordingService::class.java).setAction(RecordingService.ACTION_STOP))
        }
        appStore.saveBluetoothRecordingControlMode(settings.bluetoothRecordingControlMode)
        appStore.saveBluetoothHeadsetControlMonitoringEnabled(settings.bluetoothHeadsetControlMonitoringEnabled)
      }
    }
  }
}
