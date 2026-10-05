package com.vibecodingjapan.ideavox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UserScopedCacheTest {
  @Test
  fun switchUser_clearsPrivateNotesDraftAndCloudRecordings() {
    val snapshot =
      LocalSnapshot(
        activeUid = "user-a",
        notes = listOf(NoteItem("note", "title", "body", 1L)),
        noteDraft = "draft",
        recordings =
          listOf(
            recording("local", filePath = "/local.wav", storageUri = null),
            recording("cloud", filePath = "", storageUri = "gs://bucket/cloud.wav"),
          ),
      )

    val next = UserScopedCache.switchUser(snapshot, "user-b")

    assertEquals("user-b", next.activeUid)
    assertTrue(next.notes.isEmpty())
    assertEquals("", next.noteDraft)
    assertEquals(listOf("local"), next.recordings.map { it.id })
  }

  @Test
  fun switchUser_keepsSnapshotWhenUidIsUnchanged() {
    val snapshot = LocalSnapshot(activeUid = "same", noteDraft = "draft")

    assertEquals(snapshot, UserScopedCache.switchUser(snapshot, "same"))
  }

  @Test
  fun switchUser_displaysOnlyPendingSharedTextAvailableToNextUser() {
    val snapshot =
      LocalSnapshot(
        activeUid = "user-a",
        noteDraft = "private draft",
        pendingSharedNotes =
          listOf(
            PendingSharedNote("a", "A", "user A", 2L, ownerUid = "user-a"),
            PendingSharedNote("open", "Open", "unclaimed", 3L),
          ),
      )

    val next = UserScopedCache.switchUser(snapshot, "user-b")

    assertEquals("unclaimed", next.noteDraft)
    assertEquals(2, next.pendingSharedNotes.size)
  }

  private fun recording(id: String, filePath: String, storageUri: String?): RecordingItem =
    RecordingItem(
      id = id,
      name = "$id.wav",
      filePath = filePath,
      createdAt = 0L,
      durationMs = 0L,
      sizeBytes = 0L,
      storageUri = storageUri,
    )
}
