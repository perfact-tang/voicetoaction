package com.vibecodingjapan.ideavox

/**
 * Optional temple-swipe control. Never interprets a second callback as
 * confirmation. [direction] is +1 for a forward/volume-up swipe and -1 for a
 * backward/volume-down swipe.
 */
internal class BluetoothVolumeStopGate(
  private val minDelayMs: Long = 500L,
  private val direction: Int = 1,
) {
  private var recordingId: String? = null
  private var startedAt = 0L
  private var expectedStartVolume: Int? = null
  private var handled = false

  @Synchronized fun begin(id: String, now: Long, startVolume: Int? = null) {
    recordingId = id
    startedAt = now
    expectedStartVolume = startVolume
    handled = false
  }

  @Synchronized fun clear(id: String) {
    if (recordingId == id) recordingId = null
  }

  @Synchronized fun accept(id: String?, stream: Int, previous: Int, value: Int, now: Long): Boolean {
    // STREAM_VOICE_CALL=0. The SCO alias also broadcasts the same adjustment;
    // only accept one canonical stream, after initial route volume synchronization.
    if (id == null || id != recordingId || handled || stream != 0 || previous < 0 || value < 0) return false
    if (direction > 0 && value <= previous) return false
    if (direction < 0 && value >= previous) return false
    expectedStartVolume?.let { if (previous != it) return false }
    // A real temple swipe changes the call volume by exactly one step.
    if (value != previous + direction) return false
    if (now - startedAt < minDelayMs) return false
    handled = true
    return true
  }

  companion object {
    fun withUpHeadroom(currentVolume: Int, maxVolume: Int): Int =
      if (maxVolume >= 3 && currentVolume > maxVolume - 2) maxVolume - 2 else currentVolume

    fun withDownHeadroom(currentVolume: Int, minVolume: Int): Int =
      if (minVolume >= 0 && currentVolume <= minVolume + 1) minVolume + 1 else currentVolume
  }
}
