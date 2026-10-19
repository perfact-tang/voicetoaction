package com.vibecodingjapan.ideavox

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothCallControlPolicyTest {
  @Test fun onlyHangupAndAbortEndTheRecording() {
    assertTrue(BluetoothCallControlPolicy.endsRecording(BluetoothCallControlEvent.DISCONNECT))
    assertTrue(BluetoothCallControlPolicy.endsRecording(BluetoothCallControlEvent.ABORT))
  }

  @Test fun holdUnholdAndAnswerNeverEndTheRecording() {
    assertFalse(BluetoothCallControlPolicy.endsRecording(BluetoothCallControlEvent.HOLD))
    assertFalse(BluetoothCallControlPolicy.endsRecording(BluetoothCallControlEvent.UNHOLD))
    assertFalse(BluetoothCallControlPolicy.endsRecording(BluetoothCallControlEvent.ANSWER))
  }

  @Test fun controlCallDoesNotAdvertiseHold() {
    // CAPABILITY_HOLD / CAPABILITY_SUPPORT_HOLD must stay unset because the
    // connection implements no hold feature.
    assertTrue(BluetoothCallControlPolicy.CONNECTION_CAPABILITIES == 0)
  }
}
