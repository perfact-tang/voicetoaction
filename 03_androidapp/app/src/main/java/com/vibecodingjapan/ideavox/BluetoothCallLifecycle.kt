package com.vibecodingjapan.ideavox

/** Identity and readiness for one call only. Late callbacks must not mutate its successor. */
internal class BluetoothCallLifecycle {
  data class Session(
    val id: Long,
    val recordingId: String?,
    val requiresSco: Boolean,
    val attached: Boolean = false,
    val endpointReady: Boolean = false,
    val audioConnected: Boolean = false,
    val endHandled: Boolean = false,
  ) {
    val ready: Boolean get() = attached && !endHandled && endpointReady && (!requiresSco || audioConnected)
  }

  private var sequence = 0L
  @Volatile var current: Session? = null
    private set

  @Synchronized fun begin(recordingId: String?, requiresSco: Boolean, audioConnected: Boolean): Session =
    Session(++sequence, recordingId, requiresSco, audioConnected = audioConnected).also { current = it }

  @Synchronized fun attach(id: Long): Boolean {
    val session = current?.takeIf { it.id == id && !it.attached && !it.endHandled } ?: return false
    current = session.copy(attached = true)
    return true
  }

  @Synchronized fun endpoint(id: Long, ready: Boolean) {
    current?.takeIf { it.id == id && !it.endHandled }?.let { current = it.copy(endpointReady = ready) }
  }

  @Synchronized fun audio(connected: Boolean) {
    current?.let { current = it.copy(audioConnected = connected) }
  }

  @Synchronized fun claimEnd(id: Long): Session? {
    val session = current?.takeIf { it.id == id && it.attached && !it.endHandled } ?: return null
    current = session.copy(endHandled = true, endpointReady = false)
    return session
  }

  @Synchronized fun finish(id: Long) {
    if (current?.id == id) current = null
  }

  @Synchronized fun clear() { current = null }
}
