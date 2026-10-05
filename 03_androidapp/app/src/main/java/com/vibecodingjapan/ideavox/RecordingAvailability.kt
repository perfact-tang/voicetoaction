package com.vibecodingjapan.ideavox

import java.io.File

fun isLocalRecordingAvailable(recording: RecordingItem): Boolean =
  recording.filePath.isNotBlank() && File(recording.filePath).exists()
