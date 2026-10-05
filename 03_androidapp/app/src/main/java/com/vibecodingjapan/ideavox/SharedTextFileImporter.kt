package com.vibecodingjapan.ideavox

import android.content.Context
import android.content.ClipData
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.text.HtmlCompat
import java.io.BufferedInputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

class SharedTextFileImporter(context: Context) {
  private val appContext = context.applicationContext
  private val resolver = context.contentResolver

  fun readClipItem(item: ClipData.Item, reportedMimeType: String?): String {
    item.text?.toString()?.let {
      return SharedTextContentSanitizer.toPlainText(it, reportedMimeType)
    }
    val uri = item.uri ?: error("没有找到分享的文字内容")
    val coerced = runCatching { item.coerceToText(appContext).toString() }.getOrNull()
    if (!coerced.isNullOrBlank() && coerced != uri.toString()) {
      return SharedTextContentSanitizer.toPlainText(coerced, reportedMimeType)
    }
    return SharedTextContentSanitizer.toPlainText(read(uri, reportedMimeType), reportedMimeType)
  }

  fun read(uri: Uri, reportedMimeType: String?): String {
    val displayName = displayName(uri) ?: uri.lastPathSegment
    val providerMimeType = runCatching { resolver.getType(uri) }.getOrNull()
    require(
      SharedTextFileTypes.canRead(reportedMimeType, displayName) ||
        SharedTextFileTypes.canRead(providerMimeType, displayName)
    ) {
      "分享的文件不是支持的文字文件"
    }

    val typedStream =
      if (reportedMimeType.equals(GOOGLE_DOCS_WRAPPED_MIME, ignoreCase = true)) {
        runCatching {
            resolver.openTypedAssetFileDescriptor(uri, "text/plain", null)
              ?.createInputStream()
          }
          .getOrNull()
      } else {
        null
      }
    return (typedStream ?: resolver.openInputStream(uri))?.use { raw ->
      val input = BufferedInputStream(raw)
      input.mark(3)
      val prefix = ByteArray(3)
      val prefixLength = input.read(prefix)
      input.reset()

      val charset =
        when {
          prefixLength >= 3 &&
            prefix[0] == UTF8_BOM[0] &&
            prefix[1] == UTF8_BOM[1] &&
            prefix[2] == UTF8_BOM[2] -> {
            input.skipFully(3)
            StandardCharsets.UTF_8
          }
          prefixLength >= 2 && prefix[0] == UTF16_LE_BOM[0] && prefix[1] == UTF16_LE_BOM[1] -> {
            input.skipFully(2)
            StandardCharsets.UTF_16LE
          }
          prefixLength >= 2 && prefix[0] == UTF16_BE_BOM[0] && prefix[1] == UTF16_BE_BOM[1] -> {
            input.skipFully(2)
            StandardCharsets.UTF_16BE
          }
          else -> StandardCharsets.UTF_8
        }

      InputStreamReader(input, charset).use { reader ->
        val result = StringBuilder()
        val buffer = CharArray(8 * 1024)
        while (true) {
          val count = reader.read(buffer)
          if (count < 0) break
          require(result.length + count <= SharedTextNoteFactory.MAX_BODY_CHARS) {
            "分享的文字内容过长"
          }
          result.append(buffer, 0, count)
        }
        result.toString()
      }
    } ?: error("无法读取分享的文字文件")
  }

  private fun displayName(uri: Uri): String? =
    runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
          if (!cursor.moveToFirst()) return@use null
          val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
          if (index >= 0) cursor.getString(index) else null
        }
      }
      .getOrNull()

  private fun BufferedInputStream.skipFully(byteCount: Long) {
    var remaining = byteCount
    while (remaining > 0) {
      val skipped = skip(remaining)
      check(skipped > 0) { "无法读取分享的文字文件" }
      remaining -= skipped
    }
  }

  private companion object {
    const val GOOGLE_DOCS_WRAPPED_MIME =
      "application/x-vnd.google-docs-document-slice-clip+wrapped"
    val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    val UTF16_LE_BOM = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
    val UTF16_BE_BOM = byteArrayOf(0xFE.toByte(), 0xFF.toByte())
  }
}

object SharedTextFileTypes {
  private val directMimeTypes =
    setOf(
      "application/txt",
      "application/x-txt",
      "application/text",
      "application/x-vnd.google-docs-document-slice-clip+wrapped",
    )
  private val textExtensions = setOf("txt", "text")

  fun isPotentialTextMime(mimeType: String?): Boolean {
    val normalized = mimeType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
    return normalized.startsWith("text/") ||
      normalized in directMimeTypes ||
      normalized == "application/octet-stream"
  }

  fun canRead(mimeType: String?, displayName: String?): Boolean {
    val normalized = mimeType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
    if (normalized.startsWith("text/") || normalized in directMimeTypes) return true
    if (normalized != "application/octet-stream") return false
    return displayName
      ?.substringAfterLast('.', missingDelimiterValue = "")
      ?.lowercase() in textExtensions
  }
}

object SharedTextContentSanitizer {
  private const val GOOGLE_DOCS_WRAPPED_MIME =
    "application/x-vnd.google-docs-document-slice-clip+wrapped"
  private val htmlTagPattern =
    Regex(
      """<(?:html|body|p|span|br|ul|ol|li|div|h[1-6])(?:\s|/?>)""",
      setOf(RegexOption.IGNORE_CASE),
    )
  private val excessiveBlankLines = Regex("""\n[ \t]*\n(?:[ \t]*\n)+""")

  fun toPlainText(value: String, reportedMimeType: String?): String {
    if (!isGoogleDocsWrappedMime(reportedMimeType) || !htmlTagPattern.containsMatchIn(value)) {
      return value
    }
    val parsed =
      HtmlCompat.fromHtml(value, HtmlCompat.FROM_HTML_MODE_COMPACT)
        .toString()
        .replace('\u00A0', ' ')
    return normalizeWhitespace(parsed)
  }

  internal fun isGoogleDocsWrappedMime(mimeType: String?): Boolean =
    mimeType
      ?.substringBefore(';')
      ?.trim()
      ?.equals(GOOGLE_DOCS_WRAPPED_MIME, ignoreCase = true) == true

  internal fun normalizeWhitespace(value: String): String =
    value
      .replace("\r\n", "\n")
      .replace('\r', '\n')
      .lines()
      .joinToString("\n") { it.trimEnd() }
      .replace(excessiveBlankLines, "\n\n")
      .trim()
}
