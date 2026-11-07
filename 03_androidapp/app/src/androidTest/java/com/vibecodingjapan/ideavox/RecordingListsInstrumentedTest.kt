package com.vibecodingjapan.ideavox

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Rule
import org.junit.Test

/** UI regression only: local fixtures; never uploads audio or requests Skills. */
class RecordingListsInstrumentedTest {
  @get:Rule val compose = createAndroidComposeRule<MainActivity>()

  @Test fun originalAndSuccessfulUploadsHaveIndependentListsAndDetails() {
    val context = compose.activity.applicationContext
    val prefix = "list-regression-"
    val source = File(appStore.recordingsDir, "${prefix}source.wav")
    val converted = File(appStore.processedDir, "${prefix}converted.m4a")
    val date = 1794015000000L
    source.writeBytes(byteArrayOf(1))
    converted.writeBytes(byteArrayOf(1))
    try {
      compose.runOnIdle {
        appStore.upsertRecording(RecordingItem("${prefix}source", "Idea Vox-2026-11-07-09-30.wav", source.absolutePath, date, 65000, 3500000))
        appStore.upsertRecording(RecordingItem("${prefix}legacy", "old-output.m4a", converted.absolutePath, date + 1000, 65000, 900000))
        appStore.upsertRecording(RecordingItem("${prefix}merged", "merged-123.m4a", "", date + 2000, 120000, 1500000,
          kind = RecordingKind.MERGED, uploadState = UploadState.UPLOADED, storageUri = "gs://test/merged", sourceRecordingIds = listOf("a", "b"),
          uploadMode = UploadProcessMode.MERGED, aicallingTitle = "会议整理"))
        appStore.upsertRecording(RecordingItem("${prefix}separate", "separate.m4a", "", date + 3000, 65000, 900000,
          kind = RecordingKind.MERGED, uploadState = UploadState.UPLOADED, storageUri = "gs://test/separate", sourceRecordingIds = listOf("c"),
          uploadMode = UploadProcessMode.SEPARATE, aicallingTitle = "笔记整理"))
      }
      val original = appStore.snapshot.value.recordings.single { it.id == "${prefix}source" }
      compose.onNodeWithText(RecordingLists.displayName(original)).assertIsDisplayed()
      screenshot("recorder-with-files.png")
      compose.onNodeWithText(RecordingLists.displayName(original)).performClick()
      compose.onNodeWithText("录音详情".localized(context)).assertIsDisplayed()
      compose.onNodeWithText("关闭".localized(context)).performClick()
      compose.onNodeWithText("显示录音列表".localized(context)).performClick()
      compose.onNodeWithText("仅显示原始录音，点击可查看详情。".localized(context)).assertIsDisplayed()
      compose.onNodeWithText("old-output").assertDoesNotExist()
      compose.onNodeWithText("Idea Vox-2026-11-07-09-30.wav").assertDoesNotExist()
      screenshot("recordings.png")
      compose.onNodeWithText(RecordingLists.displayName(original)).performClick()
      compose.onNodeWithText("录音详情".localized(context)).assertIsDisplayed()
      compose.onNodeWithText("播放".localized(context)).assertIsEnabled()
      compose.onNodeWithContentDescription("分享".localized(context)).assertIsEnabled()
      compose.onNodeWithText("保存名称".localized(context)).assertIsEnabled()
      screenshot("recording-details.png")
      compose.onNodeWithText("删除".localized(context)).performClick()
      compose.onNodeWithText("删除录音".localized(context)).assertIsDisplayed()
      screenshot("delete-confirmation.png")
      compose.onNodeWithText("否".localized(context)).performClick()
      compose.onNodeWithText("关闭".localized(context)).performClick()
      compose.onNodeWithText("选择".localized(context)).performClick()
      compose.onNodeWithText(RecordingLists.displayName(original)).performClick()
      compose.onNodeWithText("上传".localized(context)).assertIsEnabled()
      screenshot("recording-selection.png")
      compose.onNodeWithText("取消".localized(context)).performClick()
      compose.onNodeWithText("上传成功".localized(context)).performClick()
      compose.onNodeWithText("合并上传".localized(context)).assertIsDisplayed()
      compose.onNodeWithText("逐条上传".localized(context)).assertIsDisplayed()
      compose.onNodeWithText("会议整理").assertIsDisplayed()
      screenshot("successful-uploads.png")
      compose.onNodeWithText("会议整理").performClick()
      compose.onNodeWithText("上传详情".localized(context)).assertIsDisplayed()
      compose.onNodeWithText("使用已有转写文本，不重新上传音频或进行语音识别。".localized(context)).assertIsDisplayed()
      screenshot("uploaded-detail.png")
      compose.onNodeWithText("关闭".localized(context)).performClick()
    } finally {
      compose.runOnIdle { appStore.deleteRecordings(appStore.snapshot.value.recordings.filter { it.id.startsWith(prefix) }.map { it.id }.toSet()) }
      source.delete()
      converted.delete()
    }
  }

  private fun screenshot(name: String) {
    val dir = File(compose.activity.getExternalFilesDir(null), "recording-list-artifacts").apply { mkdirs() }
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    instrumentation.waitForIdleSync()
    android.os.SystemClock.sleep(300)
    val bitmap = instrumentation.uiAutomation.takeScreenshot()
    try { File(dir, name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
    finally { bitmap.recycle() }
  }
}
