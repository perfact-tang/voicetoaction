package com.vibecodingjapan.ideavox

import org.junit.Assert.assertEquals
import org.junit.Test

class SnapshotMergeTest {
  @Test
  fun recordings_keepsRemoteListingSeparateFromLocalFile() {
    val local = recording(id = "same", name = "local-renamed.wav", filePath = "/local/file.wav", createdAt = 1L)
    val remote =
      recording(
        id = "remote:same",
        name = "remote-name.wav",
        filePath = "",
        createdAt = 2L,
        state = UploadState.UPLOADED,
        remoteId = "same",
        remoteListing = true,
      )

    val merged = SnapshotMerge.recordings(local = listOf(local), remote = listOf(remote))

    assertEquals(2, merged.size)
    assertEquals("remote-name.wav", merged.first { it.remoteListing }.name)
    assertEquals("local-renamed.wav", merged.first { !it.remoteListing }.name)
    assertEquals("/local/file.wav", merged.first { !it.remoteListing }.filePath)
  }

  @Test
  fun notes_prefersRemoteVersionForSameId() {
    val local = NoteItem("n1", "local", "local body", updatedAt = 1L)
    val remote = NoteItem("n1", "remote", "remote body", updatedAt = 2L)

    val merged = SnapshotMerge.notes(local = listOf(local), remote = listOf(remote))

    assertEquals(1, merged.size)
    assertEquals("remote", merged.first().title)
  }

  private fun recording(
    id: String,
    name: String = "$id.wav",
    filePath: String,
    createdAt: Long,
    state: UploadState = UploadState.LOCAL,
    remoteId: String? = null,
    remoteListing: Boolean = false,
  ): RecordingItem =
    RecordingItem(
      id = id,
      name = name,
      filePath = filePath,
      createdAt = createdAt,
      durationMs = 100L,
      sizeBytes = 200L,
      uploadState = state,
      remoteId = remoteId,
      remoteListing = remoteListing,
    )
}
