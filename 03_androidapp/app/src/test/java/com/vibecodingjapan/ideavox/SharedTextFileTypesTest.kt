package com.vibecodingjapan.ideavox

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedTextFileTypesTest {
  @Test
  fun canRead_acceptsTextMimeWithoutFileName() {
    assertTrue(SharedTextFileTypes.canRead("text/plain", null))
    assertTrue(SharedTextFileTypes.canRead("text/plain; charset=utf-8", null))
  }

  @Test
  fun canRead_acceptsTxtSentAsGenericBinary() {
    assertTrue(SharedTextFileTypes.canRead("application/octet-stream", "shared.TXT"))
  }

  @Test
  fun canRead_acceptsGoogleDocsClipboardWrapper() {
    assertTrue(
      SharedTextFileTypes.canRead(
        "application/x-vnd.google-docs-document-slice-clip+wrapped",
        null,
      )
    )
  }

  @Test
  fun canRead_rejectsOtherGenericBinaryFiles() {
    assertFalse(SharedTextFileTypes.canRead("application/octet-stream", "recording.mp3"))
    assertFalse(SharedTextFileTypes.canRead("application/octet-stream", null))
  }
}
