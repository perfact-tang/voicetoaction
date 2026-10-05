package com.vibecodingjapan.ideavox

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

object SharedTextNoteFactory {
  private val titleFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US)

  fun create(
    text: CharSequence,
    createdAt: Long = System.currentTimeMillis(),
    ownerUid: String? = null,
    zoneId: ZoneId = ZoneId.systemDefault(),
  ): PendingSharedNote {
    val body = text.toString()
    require(body.isNotBlank()) { "分享的文字内容为空" }
    require(body.length <= MAX_BODY_CHARS) { "分享的文字内容过长" }
    return PendingSharedNote(
      id = UUID.randomUUID().toString(),
      title = titleFormatter.withZone(zoneId).format(Instant.ofEpochMilli(createdAt)),
      body = body,
      createdAt = createdAt,
      ownerUid = ownerUid,
    )
  }

  internal const val MAX_BODY_CHARS = 500_000
}
