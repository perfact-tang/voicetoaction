package com.vibecodingjapan.ideavox

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingUploadMetadataTest {
  @Test
  fun contentTypeFor_recognizesAudioExtensions() {
    assertEquals("audio/wav", RecordingUploadMetadata.contentTypeFor(File("a.wav")))
    assertEquals("audio/mpeg", RecordingUploadMetadata.contentTypeFor(File("a.mp3")))
    assertEquals("audio/mp4", RecordingUploadMetadata.contentTypeFor(File("a.m4a")))
    assertEquals("application/octet-stream", RecordingUploadMetadata.contentTypeFor(File("a.bin")))
  }

  @Test
  fun firestoreMap_containsPrivateRecordingMetadata() {
    val recording =
      RecordingItem(
        id = "id-1",
        name = "merged.wav",
        filePath = "/tmp/merged.wav",
        createdAt = 10L,
        durationMs = 20L,
        sizeBytes = 30L,
        kind = RecordingKind.MERGED,
        aicallingId = "call-1",
        aicallingTitle = "秘书部",
        aicallingInfo = "通过你的电话录音，把内容重新整理。",
        serviceId = "svc-1",
        serviceType = ServiceTypes.GOOGLE_WORKSPACE_STUDIO,
      )

    val map = RecordingUploadMetadata.firestoreMap(recording, "gs://bucket/record/id-1", ReaderLanguage.JA)

    assertEquals("id-1", map["id"])
    assertEquals("MERGED", map["kind"])
    assertEquals("UPLOADED", map["uploadState"])
    assertEquals(100, map["uploadProgress"])
    assertEquals("ja", map["language"])
    assertEquals("gs://bucket/record/id-1", map["storageUri"])
    assertEquals("call-1", map["aicallingid"])
    assertEquals("秘书部", map["aicallingTitle"])
    assertEquals("通过你的电话录音，把内容重新整理。", map["aicallingInfo"])
    assertEquals("svc-1", map["serviceId"])
    assertEquals(ServiceTypes.GOOGLE_WORKSPACE_STUDIO, map["serviceType"])
  }

  @Test
  fun firestoreMap_usesSelectedLanguageRecordCode() {
    val recording =
      RecordingItem(
        id = "id-zh",
        name = "zh.wav",
        filePath = "/tmp/zh.wav",
        createdAt = 0L,
        durationMs = 0L,
        sizeBytes = 0L,
      )

    val map = RecordingUploadMetadata.firestoreMap(recording, "gs://bucket/record/id-zh", ReaderLanguage.ZH)

    assertEquals("zh", map["language"])
  }

  @Test
  fun firestoreMap_omitsAICallingMetadataForNormalRecording() {
    val recording =
      RecordingItem(
        id = "id-normal",
        name = "normal.wav",
        filePath = "/tmp/normal.wav",
        createdAt = 0L,
        durationMs = 0L,
        sizeBytes = 0L,
      )

    val map = RecordingUploadMetadata.firestoreMap(recording, "gs://bucket/record/id-normal", ReaderLanguage.ZH)

    assertEquals(false, map.containsKey("aicallingid"))
    assertEquals(false, map.containsKey("aicallingTitle"))
    assertEquals(false, map.containsKey("aicallingInfo"))
    assertEquals(false, map.containsKey("serviceId"))
    assertEquals(false, map.containsKey("serviceType"))
  }
}
