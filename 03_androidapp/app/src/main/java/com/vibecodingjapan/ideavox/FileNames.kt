package com.vibecodingjapan.ideavox

object FileNames {
  fun safeRecordingFileName(name: String, fallbackId: String, extension: String = "wav"): String {
    val sanitized = name.replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_')
    return sanitized.ifBlank { "$fallbackId.$extension" }
  }

  fun safeImportedAudioFileName(name: String, fallbackId: String, extension: String): String {
    val normalizedExtension = extension.lowercase().filter { it.isLetterOrDigit() }.take(10).ifBlank { "audio" }
    val sanitized =
      name
        .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]+"), "_")
        .trim('.', ' ', '_')
        .take(200)
    if (sanitized.isBlank()) return "$fallbackId.$normalizedExtension"
    if (sanitized.substringAfterLast('.', "").equals(normalizedExtension, ignoreCase = true)) return sanitized
    val stem = sanitized.substringBeforeLast('.', sanitized).trimEnd('.', ' ', '_').ifBlank { fallbackId }
    return "$stem.$normalizedExtension"
  }
}
