package com.vibecodingjapan.ideavox

import java.io.File
import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

class RecordingListsTest {
  private fun item(id: String, path: String = "/recordings/$id.wav") =
    RecordingItem(id, "ideavox-2026-11-07-09-30.wav", path, 1000L, 3000L, 5000L)

  @Test fun legacySeparateOutputsDoNotPolluteOriginals() {
    assertTrue(RecordingLists.isSource(item("original")))
    assertFalse(RecordingLists.isSource(item("converted", "/data/files/processed/old.wav")))
    assertFalse(RecordingLists.isSource(item("merged").copy(kind = RecordingKind.MERGED)))
    assertFalse(RecordingLists.isSource(item("new").copy(sourceRecordingIds = listOf("original"))))
  }

  @Test fun successfulUploadsRemainAvailableWithoutLocalAudio() {
    val uploaded = item("uploaded").copy(uploadState = UploadState.UPLOADED, storageUri = "gs://bucket/audio", uploadedAt = 5000L)
    val missingAudio = uploaded.copy(id = "missing", filePath = "", uploadedAt = 6000L)
    assertEquals(listOf("missing", "uploaded"), RecordingLists.uploaded(listOf(uploaded, missingAudio, item("local"))).map { it.id })
    assertTrue(RecordingLists.originals(listOf(missingAudio)).isEmpty())
  }

  @Test fun originalsContainOnlyExistingSourcesInDateOrder() {
    val file = File.createTempFile("recording-source", ".wav")
    try {
      val older = item("older", file.absolutePath)
      val newer = older.copy(id = "newer", createdAt = 2000L)
      val generated = newer.copy(id = "output", sourceRecordingIds = listOf("older"))
      assertEquals(listOf("newer", "older"), RecordingLists.originals(listOf(older, generated, newer, item("missing"))).map { it.id })
    } finally { file.delete() }
  }

  @Test fun generatedTitlesShowDateAndTimeOnOneLineButCustomNamesSurvive() {
    for (name in listOf("Idea Vox-2026-11-07-09-30.wav", "ideavox-2026-11-07-09-30.wav", "2026-11-07-09-30-20.wav", "merged-12345.wav")) {
      assertTrue(RecordingLists.displayName(item("x").copy(name = name)).matches(Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}")))
    }
    assertEquals("客户访谈", RecordingLists.displayName(item("x").copy(name = "客户访谈.wav")))
  }

  @Test fun oldSnapshotsDecodeAndNewUploadMetadataSurvivesRestart() {
    val json = Json { ignoreUnknownKeys = true }
    val old = json.decodeFromString<RecordingItem>("""{"id":"old","name":"old.wav","filePath":"/recordings/old.wav","createdAt":1,"durationMs":2,"sizeBytes":3}""")
    assertNull(old.uploadMode)
    assertTrue(old.sourceRecordingIds.isEmpty())
    val uploaded = old.copy(sourceRecordingIds = listOf("source"), uploadMode = UploadProcessMode.SEPARATE, uploadedAt = 123L)
    assertEquals(uploaded, json.decodeFromString<RecordingItem>(json.encodeToString(uploaded)))
  }
}
