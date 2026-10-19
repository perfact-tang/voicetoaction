package com.vibecodingjapan.ideavox

import org.junit.Assert.*
import org.junit.Test

class BluetoothCallLifecycleTest {
  @Test fun tenStartHangupUploadCyclesSurviveLateCallbacksFromPreviousCalls() {
    val calls = BluetoothCallLifecycle()
    var phase: BluetoothGesturePhase = BluetoothGesturePhase.Idle
    var oldCall: Long? = null
    repeat(10) { index ->
      val id = "recording-$index"
      val first = BluetoothGestureStateMachine.onTrigger(phase, RecordingStatus.IDLE, null, id, 1000)
      val start = BluetoothGestureStateMachine.onTrigger(first.phase, RecordingStatus.IDLE, null, id, 1100)
      assertEquals(BluetoothGestureEffect.StartRecording(id), start.effect)
      val call = calls.begin(id, true, false)
      calls.attach(call.id)
      calls.endpoint(call.id, true)
      calls.audio(true)
      phase = BluetoothGestureStateMachine.onRecordingStarted(start.phase, id).phase
      oldCall?.let {
        assertNull(calls.claimEnd(it))
        calls.finish(it)
        calls.endpoint(it, false)
      }
      assertTrue(calls.current!!.ready)
      val accepted = calls.claimEnd(call.id)!!
      val stop = BluetoothGestureStateMachine.onTelecomEnd(phase, RecordingStatus.RECORDING, id, accepted.recordingId)
      assertEquals(BluetoothGestureEffect.StopAndArmUpload(id), stop.effect)
      val duplicate = BluetoothGestureStateMachine.onTelecomEnd(stop.phase, RecordingStatus.RECORDING, id, id)
      calls.finish(call.id)
      val saved = BluetoothGestureStateMachine.onRecordingFinalized(duplicate.phase, id, 2000)
      val upload = BluetoothGestureStateMachine.onTrigger(saved.phase, RecordingStatus.IDLE, null, "unused", 2100)
      assertEquals(BluetoothGestureEffect.StartAutomaticUpload(id), upload.effect)
      phase = upload.phase
      assertEquals(BluetoothGesturePhase.Idle, phase)
      oldCall = call.id
    }
  }

  @Test fun twoRecordingCyclesEachAcceptOneHangup() {
    val calls = BluetoothCallLifecycle()
    repeat(2) { index ->
      val recording = "r$index"
      val call = calls.begin(recording, requiresSco = true, audioConnected = false)
      assertTrue(calls.attach(call.id))
      calls.endpoint(call.id, true)
      assertFalse(calls.current!!.ready)
      calls.audio(true)
      assertTrue(calls.current!!.ready)
      assertEquals(recording, calls.claimEnd(call.id)?.recordingId)
      assertNull(calls.claimEnd(call.id))
      assertFalse(calls.current!!.ready)
      calls.finish(call.id)
      assertNull(calls.current)
    }
  }

  @Test fun cancelledPendingConnectionCannotAttachAfterNextCallStarts() {
    val calls = BluetoothCallLifecycle()
    val old = calls.begin("old", true, false)
    calls.clear()
    val next = calls.begin("next", true, false)
    assertFalse(calls.attach(old.id))
    assertTrue(calls.attach(next.id))
    assertFalse(calls.attach(next.id))
  }

  @Test fun oldRouteHangupAndDestroyCannotChangeReadySuccessor() {
    val calls = BluetoothCallLifecycle()
    val old = calls.begin("old", true, true)
    calls.attach(old.id)
    val next = calls.begin("next", true, true)
    calls.attach(next.id)
    calls.endpoint(next.id, true)
    calls.endpoint(old.id, false)
    assertNull(calls.claimEnd(old.id))
    calls.finish(old.id)
    assertEquals("next", calls.current?.recordingId)
    assertTrue(calls.current!!.ready)
  }

  @Test fun audioDisconnectInvalidatesReadinessEvenWhenEndpointMetadataRemainsBluetooth() {
    val calls = BluetoothCallLifecycle()
    val call = calls.begin("r", true, true)
    calls.attach(call.id)
    calls.endpoint(call.id, true)
    assertTrue(calls.current!!.ready)
    calls.audio(false)
    assertFalse(calls.current!!.ready)
    calls.audio(true)
    assertTrue(calls.current!!.ready)
    calls.endpoint(call.id, false)
    assertFalse(calls.current!!.ready)
  }

  @Test fun endpointWithoutScoIsSufficientForBleButDoesNotLeakToNextCall() {
    val calls = BluetoothCallLifecycle()
    val ble = calls.begin("ble", false, false)
    calls.attach(ble.id)
    calls.endpoint(ble.id, true)
    assertTrue(calls.current!!.ready)
    val sco = calls.begin("sco", true, false)
    calls.attach(sco.id)
    assertFalse(calls.current!!.ready)
    calls.endpoint(sco.id, true)
    assertFalse(calls.current!!.ready)
  }

  @Test fun appCleanupPreventsPendingOrDuplicateHangupFromBeingAccepted() {
    val calls = BluetoothCallLifecycle()
    val call = calls.begin("r", true, true)
    calls.attach(call.id)
    calls.clear()
    assertNull(calls.claimEnd(call.id))
    calls.endpoint(call.id, true)
    calls.audio(true)
    assertNull(calls.current)
  }
}
