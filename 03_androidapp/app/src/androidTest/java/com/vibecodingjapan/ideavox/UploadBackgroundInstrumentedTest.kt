package com.vibecodingjapan.ideavox

import android.content.Intent
import android.media.MediaMetadataRetriever
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class UploadBackgroundInstrumentedTest {
  @get:Rule
  val compose = createAndroidComposeRule<MainActivity>()

  @After
  fun reset() {
    compose.runOnIdle {
      if (RecordingRuntime.state.value.status != RecordingStatus.IDLE) {
        compose.activity.startService(Intent(compose.activity, RecordingService::class.java).setAction(RecordingService.ACTION_STOP))
      }
      UploadRuntime.finish()
    }
  }

  @Test
  fun minimizedConversion_allowsRecordingAndNavigationAndSurvivesRecreation() {
    val context = compose.activity.applicationContext
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    android.os.ParcelFileDescriptor.AutoCloseInputStream(
      instrumentation.uiAutomation.executeShellCommand("pm grant ${context.packageName} android.permission.RECORD_AUDIO"),
    ).use { it.readBytes() }
    val dir = File(context.cacheDir, "background-conversion-test").apply { mkdirs() }
    val source = File(dir, "source.wav")
    val output = File(dir, "output.m4a")
    WavAudio.writeWav(source, ShortArray(WavAudio.SAMPLE_RATE * 120) { i -> (kotlin.math.sin(i / 18.0) * 8000).toInt().toShort() })
    val executor = Executors.newSingleThreadExecutor()
    val encodingStarted = CountDownLatch(1)
    val continueEncoding = CountDownLatch(1)
    val gated = AtomicBoolean(false)
    val existingIds = appStore.snapshot.value.recordings.map { it.id }.toSet()
    compose.runOnIdle { UploadRuntime.begin("正在转换为 M4A", source.name) }
    val future = executor.submit<File> {
      AudioProcessor.process(
        items = listOf(RecordingItem("background-source", source.name, source.absolutePath, 0L, 120_000L, source.length())),
        output = output, format = UploadOutputFormat.AAC_M4A, speed = 1.0f, workDir = dir, context = context,
        onProgress = { phase, progress ->
          UploadRuntime.update(phase, source.name, progress)
          // Hold a real codec mid-conversion so the UI test cannot race its completion.
          if (phase == "正在转换为 M4A" && progress > 55 && gated.compareAndSet(false, true)) {
            encodingStarted.countDown()
            check(continueEncoding.await(45, TimeUnit.SECONDS)) { "Timed out waiting for recording test" }
          }
        },
      ).also { UploadRuntime.finish() }
    }
    try {
      compose.waitUntil(10_000) { encodingStarted.count == 0L }
      saveScreenshot("conversion-dialog.png")
      compose.onNodeWithText("最小化，后台运行").performClick()
      compose.onNodeWithText("取消处理").assertDoesNotExist()
      compose.onNodeWithText("现在正在转换中，请不要关闭程序").assertIsDisplayed()
      compose.activityRule.scenario.recreate()
      compose.onNodeWithText("最小化，后台运行").assertDoesNotExist()
      compose.onNodeWithText("普通录音").performClick()
      compose.waitUntil(10_000) { RecordingRuntime.state.value.status == RecordingStatus.RECORDING }
      compose.onNodeWithText("正在录音中").assertIsDisplayed()
      saveScreenshot("conversion-with-recording.png")
      assertTrue(UploadRuntime.state.value.active && UploadRuntime.state.value.minimized)
      compose.onNodeWithText("笔记").performClick()
      compose.onNodeWithText("现在正在转换中，请不要关闭程序").assertIsDisplayed()
      compose.onNodeWithText("录音").performClick()
      compose.onNodeWithText("查看进度").performClick()
      compose.onNodeWithText("最小化，后台运行").assertIsDisplayed()
      compose.onNodeWithText("最小化，后台运行").performClick()
      continueEncoding.countDown()
      future.get(20, TimeUnit.SECONDS)
      // Complete AAC encoding while AudioRecord is still capturing, then save the new recording.
      assertTrue(RecordingRuntime.state.value.status == RecordingStatus.RECORDING)
      compose.onNodeWithText("现在正在转换中，请不要关闭程序").assertDoesNotExist()
      compose.onNodeWithText("结束").performClick()
      compose.waitUntil(10_000) { RecordingRuntime.state.value.status == RecordingStatus.IDLE }
      val recorded = appStore.snapshot.value.recordings.single { it.id !in existingIds }
      assertTrue(File(recorded.filePath).length() > WavAudio.HEADER_SIZE)
      assertTrue(WavAudio.durationMs(File(recorded.filePath)) > 0L)
      val retriever = MediaMetadataRetriever()
      try {
        retriever.setDataSource(output.absolutePath)
        val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
        assertTrue(abs(duration - 120_000L) <= 1000L)
      } finally {
        retriever.release()
      }
      File(recorded.filePath).delete()
      appStore.deleteRecordings(setOf(recorded.id))
    } finally {
      continueEncoding.countDown()
      executor.shutdownNow()
      executor.awaitTermination(5, TimeUnit.SECONDS)
      dir.deleteRecursively()
    }
  }

  private fun saveScreenshot(name: String) {
    compose.waitForIdle()
    val dir = File(compose.activity.getExternalFilesDir(null), "ui-test-artifacts").apply { mkdirs() }
    val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
    try {
      File(dir, name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    } finally {
      bitmap.recycle()
    }
  }

  @Test
  fun failureWhileMinimized_showsDetailsWithoutInterruptingOtherScreens() {
    compose.runOnIdle { UploadRuntime.begin("正在转换为 M4A", "source.wav") }
    compose.onNodeWithText("最小化，后台运行").performClick()
    compose.onNodeWithText("设置").performClick()
    compose.runOnIdle { UploadRuntime.fail("测试失败") }
    compose.onNodeWithText("上传失败，点击查看详情").assertIsDisplayed()
    compose.onNodeWithText("关闭").assertDoesNotExist()
    compose.onNodeWithText("查看详情").performClick()
    compose.onNodeWithText("测试失败").assertIsDisplayed()
    compose.onNodeWithText("关闭").performClick()
    compose.onNodeWithText("上传失败，点击查看详情").assertDoesNotExist()
  }
}
