package com.vibecodingjapan.ideavox

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Enabled only by the debug-only diagnostic Activity. No recording/upload actions while active. */
object BluetoothTouchDiagnostics {
  @Volatile var active = false
    private set
  @Volatile var ownsCommunicationRoute = false
  @Volatile var phase = "未开始"
    private set
  private var beganAt = 0L
  private val lock = Any()
  private val mutableEvents = MutableStateFlow<List<String>>(emptyList())
  val events = mutableEvents.asStateFlow()

  fun begin(context: Context): Boolean {
    if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return false
    synchronized(lock) {
      if (active) return true
      beganAt = SystemClock.elapsedRealtime()
      mutableEvents.value = emptyList()
      active = true
      phase = "准备"
    }
    record("SESSION begin — touch events are observations only; audio samples are discarded")
    return true
  }

  fun setPhase(value: String) {
    phase = value
    record("PHASE $value")
  }

  fun record(message: String) {
    if (!active) return
    synchronized(lock) {
      if (!active) return
      val wall = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT).format(Date())
      val line = "$wall +${SystemClock.elapsedRealtime() - beganAt}ms [$phase] $message"
      mutableEvents.value = (mutableEvents.value + line).takeLast(1000)
      Log.i("IdeavoxBtProbe", line)
    }
  }

  fun finish() {
    record("SESSION end")
    ownsCommunicationRoute = false
    active = false
  }
}
