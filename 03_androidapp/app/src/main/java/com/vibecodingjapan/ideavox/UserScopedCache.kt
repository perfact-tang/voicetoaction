package com.vibecodingjapan.ideavox

object UserScopedCache {
  fun switchUser(snapshot: LocalSnapshot, nextUid: String?): LocalSnapshot {
    if (snapshot.activeUid == nextUid) return snapshot
    val visiblePending = snapshot.pendingSharedNotes.filter { it.ownerUid == null || it.ownerUid == nextUid }
    return snapshot.copy(
      activeUid = nextUid,
      notes = emptyList(),
      noteDraft = visiblePending.maxByOrNull { it.createdAt }?.body.orEmpty(),
      recordings = snapshot.recordings.filter { it.storageUri == null && it.filePath.isNotBlank() },
    )
  }
}
