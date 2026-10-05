package com.vibecodingjapan.ideavox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedTextContentSanitizerTest {
  @Test
  fun recognizesGoogleDocsWrappedMimeCaseInsensitively() {
    assertTrue(
      SharedTextContentSanitizer.isGoogleDocsWrappedMime(
        "Application/X-Vnd.Google-Docs-Document-Slice-Clip+Wrapped",
      )
    )
    assertFalse(SharedTextContentSanitizer.isGoogleDocsWrappedMime("text/html"))
  }

  @Test
  fun normalizeWhitespace_removesTrailingSpacesAndExcessBlankLines() {
    assertEquals(
      "第一段\n\n第二段",
      SharedTextContentSanitizer.normalizeWhitespace("第一段  \r\n\r\n \r\n第二段\n"),
    )
  }
}
