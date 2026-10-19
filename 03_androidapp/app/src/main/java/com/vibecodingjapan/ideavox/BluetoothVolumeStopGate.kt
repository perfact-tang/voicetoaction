package com.vibecodingjapan.ideavox

/** Optional volume-up control. Never interprets a second callback as confirmation. */
internal class BluetoothVolumeStopGate {
  private var recordingId: String? = null
  private var startedAt = 0L
  private var handled = false

  @Synchronized fun begin(id: String, now: Long) {
    recordingId = id
    startedAt = now
    handled = false
  }

  @Synchronized fun clear(id: String) {
    if (recordingId == id) recordingId = null
  }

  @Synchronized fun accept(id: String?, stream: Int, previous: Int, value: Int, now: Long): Boolean {
    // STREAM_VOICE_CALL=0. The SCO alias also broadcasts the same adjustment;
    // only accept one canonical stream, after initial route volume synchronization.
    if (id == null || id != recordingId || handled || stream != 0 || previous < 0 || value < 0 || value <= previous) return false
    if (now - startedAt < 500L) return false
    handled = true
    return true
  }

  companion object {
    fun withHeadroom(currentVolume: Int, maxVolume: Int): Int =
      if (maxVolume > 0 && currentVolume >= maxVolume) maxVolume - 1 else currentVolume
  }
}
