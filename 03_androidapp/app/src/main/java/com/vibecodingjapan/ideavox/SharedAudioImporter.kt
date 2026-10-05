package com.vibecodingjapan.ideavox

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.File
import java.util.UUID

class SharedAudioImporter(private val context: Context, private val localStore: LocalStore) {
  fun import(uri: Uri, intentMimeType: String?): RecordingItem {
    val resolver = context.contentResolver
    val resolvedMimeType = runCatching { resolver.getType(uri) }.getOrNull()
    require(isAudioMimeType(intentMimeType) || isAudioMimeType(resolvedMimeType)) {
      "分享的文件不是可识别的音频"
    }

    val id = UUID.randomUUID().toString()
    val sharedName = sourceName(uri)
    val extension = audioExtension(sharedName, intentMimeType, resolvedMimeType)
    val requestedName = sharedName ?: "shared-audio-$id.$extension"
    val safeName = FileNames.safeImportedAudioFileName(requestedName, id, extension)
    val target = createUniqueTarget(localStore.recordingsDir, safeName)

    try {
      val input = resolver.openInputStream(uri) ?: error("无法读取分享的音频")
      input.use { source -> target.outputStream().buffered().use { output -> source.copyTo(output) } }
      require(target.length() > 0L) { "分享的音频文件为空" }

      val recording =
        RecordingItem(
          id = id,
          name = target.name,
          filePath = target.absolutePath,
          createdAt = System.currentTimeMillis(),
          durationMs = readDurationMs(target),
          sizeBytes = target.length(),
        )
      localStore.upsertRecording(recording)
      return recording
    } catch (error: Exception) {
      target.delete()
      throw error
    }
  }

  private fun sourceName(uri: Uri): String? =
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
          val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
          if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
      }
      .getOrNull()
      ?.substringAfterLast('/')
      ?.substringAfterLast('\\')
      ?.takeIf { it.isNotBlank() }

  private fun readDurationMs(file: File): Long {
    val retriever = MediaMetadataRetriever()
    return try {
      retriever.setDataSource(file.absolutePath)
      retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
    } catch (_: RuntimeException) {
      0L
    } finally {
      retriever.release()
    }
  }

  private fun createUniqueTarget(directory: File, requestedName: String): File {
    val stem = requestedName.substringBeforeLast('.', missingDelimiterValue = requestedName)
    val extension = requestedName.substringAfterLast('.', missingDelimiterValue = "")
    var index = 1
    while (true) {
      val suffix = if (index == 1) "" else "-$index"
      val name = if (extension.isBlank()) "$stem$suffix" else "$stem$suffix.$extension"
      val candidate = File(directory, name)
      if (candidate.createNewFile()) return candidate
      index += 1
    }
  }

  private fun audioExtension(sourceName: String?, vararg mimeTypes: String?): String {
    val nameExtension =
      sourceName
        ?.substringAfterLast('.', missingDelimiterValue = "")
        ?.lowercase()
        ?.takeIf { it in SUPPORTED_AUDIO_EXTENSIONS }
    if (nameExtension != null) return nameExtension
    return mimeTypes.firstNotNullOfOrNull { mimeType ->
      mimeType
        ?.substringBefore(';')
        ?.lowercase()
        ?.let(MimeTypeMap.getSingleton()::getExtensionFromMimeType)
    } ?: "audio"
  }

  private fun isAudioMimeType(mimeType: String?): Boolean =
    mimeType?.substringBefore(';')?.startsWith("audio/", ignoreCase = true) == true

  private companion object {
    val SUPPORTED_AUDIO_EXTENSIONS =
      setOf("aac", "amr", "flac", "m4a", "mp3", "mp4", "oga", "ogg", "opus", "wav", "wave", "webm", "3gp")
  }
}
