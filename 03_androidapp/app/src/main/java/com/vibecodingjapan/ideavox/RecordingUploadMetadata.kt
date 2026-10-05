package com.vibecodingjapan.ideavox

import java.io.File

object RecordingUploadMetadata {
  fun contentTypeFor(file: File): String =
    when (file.extension.lowercase()) {
      "wav" -> "audio/wav"
      "mp3" -> "audio/mpeg"
      "m4a" -> "audio/mp4"
      else -> "application/octet-stream"
    }

  fun firestoreMap(recording: RecordingItem, storageUri: String, language: ReaderLanguage): Map<String, Any?> =
    buildMap {
      putAll(mapOf(
      "id" to recording.id,
      "name" to recording.name,
      "createdAt" to recording.createdAt,
      "durationMs" to recording.durationMs,
      "sizeBytes" to recording.sizeBytes,
      "kind" to recording.kind.name,
      "uploadState" to UploadState.UPLOADED.name,
      "uploadProgress" to 100,
      "storageUri" to storageUri,
      "language" to language.recordCode,
      "updatedAt" to System.currentTimeMillis(),
      "hidden" to false,
      ))
      recording.aicallingId?.takeIf { it.isNotBlank() }?.let { put("aicallingid", it) }
      recording.aicallingTitle?.takeIf { it.isNotBlank() }?.let { put("aicallingTitle", it) }
      recording.aicallingInfo?.takeIf { it.isNotBlank() }?.let { put("aicallingInfo", it) }
      recording.serviceId?.takeIf { it.isNotBlank() }?.let { put("serviceId", it) }
      recording.serviceType?.takeIf { it.isNotBlank() }?.let { put("serviceType", it) }
    }
}
