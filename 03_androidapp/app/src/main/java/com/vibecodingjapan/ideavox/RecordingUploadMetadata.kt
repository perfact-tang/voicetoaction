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
      "sourceRecordingIds" to recording.sourceRecordingIds,
      "uploadMode" to recording.uploadMode?.name,
      "uploadedAt" to System.currentTimeMillis(),
      "uploadState" to UploadState.UPLOADED.name,
      // monitor.js 的后端处理状态（瞬时）：上传完成后为 uploaded，处理结束由
      // monitor 改写为 success/failed。不要把它和客户端 UI 状态 uploadState 混用：
      // uploadState 会永久停留在 UPLOADED，用它做后端对账条件会反复重读历史录音。
      "state" to "uploaded",
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
