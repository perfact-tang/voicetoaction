package com.vibecodingjapan.ideavox

import android.view.KeyEvent

internal object BluetoothMediaKeys {
  val supported = setOf(
    KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
    KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_REWIND,
    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY,
    KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_MEDIA_STOP,
  )
}

/** Deduplicate the same physical key delivered through Activity, receiver and session.
 * Distinct quick presses retain their own timestamps and are always accepted.
 */
internal class BluetoothKeyDeduplicator {
  private data class Key(val deviceId: Int, val code: Int, val downAt: Long, val eventAt: Long)
  private val recent = LinkedHashSet<Key>()
  private val timestampFreePresses = mutableMapOf<Pair<Int, Int>, Long>()

  @Synchronized
  fun accept(deviceId: Int, code: Int, downAt: Long, eventAt: Long, receivedAt: Long = eventAt): Boolean {
    // Some AVRCP stacks emit a fresh KeyEvent with both timestamps set to zero
    // on every press. A permanent identity set would disable that button forever.
    if (downAt == 0L && eventAt == 0L) {
      val key = deviceId to code
      val last = timestampFreePresses[key]
      if (last != null && receivedAt - last in 0 until 80) return false
      timestampFreePresses[key] = receivedAt
      return true
    }
    if (!recent.add(Key(deviceId, code, downAt, eventAt))) return false
    if (recent.size > 32) recent.remove(recent.first())
    return true
  }
}
