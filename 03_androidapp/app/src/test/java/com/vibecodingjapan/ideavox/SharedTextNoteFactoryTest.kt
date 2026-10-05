package com.vibecodingjapan.ideavox

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SharedTextNoteFactoryTest {
  @Test
  fun create_usesRequestedDateTimeTitleAndPreservesBody() {
    val createdAt = Instant.parse("2026-07-23T03:34:00Z").toEpochMilli()

    val note = SharedTextNoteFactory.create("  shared text  ", createdAt, "user-1", ZoneId.of("Asia/Tokyo"))

    assertEquals("2026-07-23 12:34", note.title)
    assertEquals("  shared text  ", note.body)
    assertEquals("user-1", note.ownerUid)
  }

  @Test
  fun create_rejectsBlankSharedText() {
    assertThrows(IllegalArgumentException::class.java) { SharedTextNoteFactory.create("   ") }
  }
}
