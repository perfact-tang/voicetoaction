package com.vibecodingjapan.ideavox

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object RecordingLists {
  fun isSource(item: RecordingItem): Boolean =
    !item.remoteListing && item.kind == RecordingKind.ORIGINAL && item.sourceRecordingIds.isEmpty() &&
      File(item.filePath).parentFile?.name != "processed"

  fun originals(items: List<RecordingItem>): List<RecordingItem> =
    items.filter { isSource(it) && isLocalRecordingAvailable(it) }.sortedByDescending { it.createdAt }

  // 已上传列表不依赖本地音频是否存在；再次处理只需要服务器上的转写。
  fun uploaded(items: List<RecordingItem>): List<RecordingItem> =
    items.filter { !it.remoteListing && it.uploadState == UploadState.UPLOADED && !it.storageUri.isNullOrBlank() }
      .sortedByDescending { it.uploadedAt ?: it.createdAt }

  fun displayName(item: RecordingItem): String {
    val stem = item.name.substringBeforeLast('.', item.name)
    val generated = Regex("(?i)^(?:(?:idea[ -]?vox|merged)[ -]?)?\\d{4}[-_]\\d{2}[-_]\\d{2}.*")
    return if (generated.matches(stem) || stem.startsWith("merged-", true) || !isSource(item)) {
      SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(item.createdAt))
    } else stem.replace(Regex("(?i)^idea[ -]?vox[ -]+"), "")
  }
}
