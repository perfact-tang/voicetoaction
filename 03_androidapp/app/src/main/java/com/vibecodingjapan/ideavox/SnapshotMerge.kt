package com.vibecodingjapan.ideavox

object SnapshotMerge {
  fun recordings(local: List<RecordingItem>, remote: List<RecordingItem>): List<RecordingItem> {
    val localOnly = local.filterNot { it.remoteListing }
    return (remote + localOnly).distinctBy { it.id }.sortedByDescending { it.createdAt }
  }

  fun notes(local: List<NoteItem>, remote: List<NoteItem>): List<NoteItem> =
    (remote + local).distinctBy { it.id }.sortedByDescending { it.updatedAt }
}
