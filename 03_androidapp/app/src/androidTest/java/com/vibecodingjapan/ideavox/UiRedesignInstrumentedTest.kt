package com.vibecodingjapan.ideavox

import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class UiRedesignInstrumentedTest {
  private fun screenshot(name: String) {
    compose.mainClock.advanceTimeBy(500)
    compose.waitForIdle()
    captureScreenshot(name)
  }

  @get:Rule val compose = createAndroidComposeRule<MainActivity>()

  @Test fun notesOpensOnSavedListAndNewNoteKeepsDraftAndSaveTitle() {
    val context = compose.activity.applicationContext
    val draftBefore = appStore.snapshot.value.noteDraft
    val draft = "今天的灵感\n\n把零散的想法先记录下来，留一点空间，给下一步行动。"
    try {
      compose.runOnIdle { appStore.saveDraft("") }
      assertEquals("VoiceToAction", context.getString(R.string.app_name))
      compose.onNodeWithText("VoiceToAction").assertIsDisplayed()
      screenshot("01-recorder.png")
      compose.onNodeWithText("笔记".localized(context)).performClick()
      compose.onNodeWithText("已保存笔记".localized(context)).assertIsDisplayed()
      compose.onNode(hasSetTextAction()).assertDoesNotExist()
      screenshot("02-notes-entry.png")
      val listTitle = compose.onNodeWithText("已保存笔记".localized(context)).fetchSemanticsNode().boundsInRoot
      val newButton = compose.onNodeWithText("新增笔记".localized(context)).assertIsDisplayed()
      check(newButton.fetchSemanticsNode().boundsInRoot.center.x > listTitle.center.x)
      newButton.performClick()
      compose.onNode(hasSetTextAction()).performTextInput(draft)
      compose.runOnIdle { assertEquals(draft, appStore.snapshot.value.noteDraft) }
      compose.onNodeWithText("草稿已保存在本机".localized(context)).assertIsDisplayed()
      compose.onNodeWithText("保存".localized(context)).assertIsEnabled().performClick()
      compose.onNodeWithText("保存笔记".localized(context)).assertIsDisplayed()
      screenshot("05-note-save.png")
      compose.onNodeWithText("取消".localized(context)).performClick()
      compose.onNodeWithContentDescription("返回".localized(context)).performClick()
      compose.onNodeWithText("已保存笔记".localized(context)).assertIsDisplayed()
      compose.onNodeWithText("新增笔记".localized(context)).performClick()
      compose.onNodeWithText(draft).assertIsDisplayed()
      screenshot("04-note-editor.png")
    } finally {
      compose.runOnIdle { appStore.saveDraft(draftBefore) }
    }
  }

  @Test fun settingsShowsDevicesAndSwitchImmediatelyAndKeepsAdvancedOptionsReachable() {
    val context = compose.activity.applicationContext
    val before = appStore.snapshot.value.bluetoothHeadsetControlMonitoringEnabled
    try {
      compose.onNodeWithText("设置".localized(context)).performClick()
      compose.onNodeWithText("录音设备".localized(context)).assertIsDisplayed()
      compose.onNodeWithText("播放设备".localized(context)).assertIsDisplayed()
      compose.onNodeWithText("控制模式".localized(context)).assertDoesNotExist()
      if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
          "pm grant ${context.packageName} android.permission.BLUETOOTH_CONNECT",
        )).use { it.readBytes() }
      }
      compose.onNodeWithText("录音设备".localized(context)).performClick()
      compose.onNode(isPopup()).assertExists()
      InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
      compose.onNodeWithText("播放设备".localized(context)).performClick()
      compose.onNode(isPopup()).assertExists()
      InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
      compose.onNodeWithText("播放键控制监视".localized(context)).assertIsDisplayed().performClick()
      compose.runOnIdle { assertEquals(!before, appStore.snapshot.value.bluetoothHeadsetControlMonitoringEnabled) }
      screenshot("06-settings.png")
      compose.onNodeWithText("播放键控制监视".localized(context)).performClick()
      compose.runOnIdle { assertEquals(before, appStore.snapshot.value.bluetoothHeadsetControlMonitoringEnabled) }
      compose.onNodeWithText("蓝牙与自动上传".localized(context)).performScrollTo().performClick()
      compose.onNodeWithText("控制模式".localized(context)).assertIsDisplayed()
      compose.onNodeWithText("录音中调低通话音量结束".localized(context)).assertIsDisplayed()
      compose.onNodeWithText("默认拨打部门".localized(context)).performScrollTo().assertIsDisplayed()
      compose.onNodeWithText("默认上传语言".localized(context)).performScrollTo().assertIsDisplayed()
      compose.onNodeWithText("默认音频倍速".localized(context)).performScrollTo().assertIsDisplayed()
      screenshot("07-bluetooth-settings.png")
      compose.onNodeWithContentDescription("返回".localized(context)).performClick()
      compose.onNodeWithText("上传设置".localized(context)).performScrollTo().performClick()
      compose.onNodeWithText("输出格式".localized(context)).assertIsDisplayed().performClick()
      compose.onNodeWithText("WAV").assertIsDisplayed()
      screenshot("08-upload-format.png")
      // Dismiss without changing the existing output preference.
      InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
      compose.onNodeWithContentDescription("返回".localized(context)).performClick()
      compose.onNodeWithText("账户与语言".localized(context)).performScrollTo().performClick()
      compose.onNodeWithText("当前账户".localized(context)).assertIsDisplayed()
      compose.onNodeWithText("界面语言".localized(context)).assertIsDisplayed()
    } finally {
      compose.runOnIdle { appStore.saveBluetoothHeadsetControlMonitoringEnabled(before) }
    }
  }
}

/** Renders actual screen composables with local fixture data; no upload, sign-in, or remote skill is executed. */
class UiRedesignSurfaceInstrumentedTest {
  private fun screenshot(name: String) {
    compose.mainClock.advanceTimeBy(500)
    compose.waitForIdle()
    captureScreenshot(name)
  }

  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
  private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
  private val note = NoteItem("ui-note", "会议纪要", "确认本周发布范围，整理录音与下一步行动。\n\n请在周五前完成复核。", 1794015000000L)
  private val target = AICallingItem("会议整理", "提取讨论要点和行动项", serviceId = "ui-skill", serviceType = ServiceTypes.DEEPSEEK_HARNESS)
  private val recording = RecordingItem("ui-recording", "2026-11-07-10-30.wav", "", 1794015000000L, 65000L, 3200000L,
    uploadMode = UploadProcessMode.MERGED, sourceRecordingIds = listOf("one", "two"))

  private fun setFixture(content: @Composable () -> Unit) {
    compose.runOnUiThread {
      compose.activity.enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
    }
    compose.setContent { VoiceToActionTheme { AppSurface { Box(Modifier.fillMaxSize().safeDrawingPadding()) { content() } } } }
  }

  @Test fun callScreensKeepTargetSelectionAudioRouteAndFinishActions() {
    val screen = mutableStateOf("contacts")
    val route = mutableStateOf(CallInputRoute.EARPIECE)
    var sent = false
    setFixture {
      Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
          when (screen.value) {
            "contacts" -> AICallingContactsScreen(listOf(target), false, null, {}, {}, { screen.value = "call" })
            "call" -> AICallingCallScreen(target, RecorderUiState(status = RecordingStatus.RECORDING, elapsedMs = 65000L),
              route.value, { route.value = it }, { screen.value = "summary" })
            else -> AICallingSummaryScreen(target, recording, {}, { sent = true })
          }
        }
        AppBottomBar("recorder", onSelect = {})
      }
    }
    compose.onNodeWithText(target.title).assertIsDisplayed()
    screenshot("09-call-targets.png")
    compose.onNodeWithText(target.title).performClick()
    compose.onNodeWithText("通话录音".localized(context)).assertIsDisplayed()
    compose.onNodeWithText(target.title).assertIsDisplayed()
    compose.onNodeWithText("0:01:05").assertIsDisplayed()
    compose.onNodeWithText(CallInputRoute.SPEAKER.label.localized(context)).performClick()
    compose.runOnIdle { assertEquals(CallInputRoute.SPEAKER, route.value) }
    screenshot("10-active-call.png")
    compose.onNodeWithText("挂断".localized(context)).performClick()
    compose.onNodeWithText("记录完成".localized(context)).assertIsDisplayed()
    screenshot("11-call-summary.png")
    compose.onNodeWithText("确认并触发自动化".localized(context)).performClick()
    compose.runOnIdle { assertEquals(true, sent) }
  }

  @Test fun uploadAndReprocessDialogsKeepTheirActionsAndSelections() {
    val screen = mutableStateOf("mode")
    val mode = mutableStateOf(UploadProcessMode.MERGED)
    var submitted: Triple<List<String>, Float, ReaderLanguage>? = null
    var picked: AICallingItem? = null
    setFixture {
      when (screen.value) {
        "mode" -> UploadModeChoiceDialog(1, {}, { mode.value = it; screen.value = "upload" })
        "upload" -> UploadDialog(listOf(recording.id), listOf(recording), UploadOutputFormat.AAC_M4A, mode.value, {}, { ids, _, speed, language ->
          submitted = Triple(ids, speed, language)
          screen.value = "done"
        })
        "done" -> UploadedRecordingDetails(recording, UploadedProcessingStatus(loading = false, state = "success", hasTranscript = true),
          false, {}, { screen.value = "picker" })
        else -> AICallingPickerDialog(true, listOf(target), false, null, {}, {}, { picked = it })
      }
    }
    compose.onNodeWithText("上传方式".localized(context)).assertIsDisplayed()
    screenshot("12-upload-mode.png")
    compose.onNodeWithText("逐条上传".localized(context)).performClick()
    compose.onNodeWithText("韩语".localized(context)).performScrollTo().assertIsDisplayed()
    compose.onNodeWithText("日语".localized(context)).performScrollTo().performClick()
    compose.onNodeWithText("1.5x").performScrollTo().performClick()
    screenshot("13-upload-parameters.png")
    compose.onNodeWithText("上传".localized(context)).performScrollTo().performClick()
    compose.runOnIdle { assertEquals(Triple(listOf(recording.id), 1.5f, ReaderLanguage.JA), submitted) }
    compose.onNodeWithText("重新选择处理对象".localized(context)).assertIsEnabled()
    screenshot("14-uploaded-detail.png")
    compose.onNodeWithText("重新选择处理对象".localized(context)).performClick()
    screenshot("15-reprocess-targets.png")
    compose.onNodeWithText(target.title).performClick()
    compose.runOnIdle { assertEquals(target, picked) }
  }

  @Test fun noteRowsOpenReaderAndControlsRemainVisible() {
    val open = mutableStateOf(false)
    setFixture {
      if (open.value) ReaderScreen(note.title, note.body, 22f, { open.value = false })
      else Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 14.dp)) {
        Column(Modifier.weight(1f)) {
          PageHeader("已保存笔记") { ActionButton("新增笔记", icon = Icons.Rounded.Add, onClick = {}) }
          androidx.compose.foundation.layout.Spacer(Modifier.padding(top = 12.dp))
          SavedNoteRow(note, onOpen = { open.value = true })
          SavedNoteRow(NoteItem("ui-note-2", "产品灵感", "从一次对话开始，把想法整理成清晰的行动。", 1793928600000L), onOpen = {})
          SavedNoteRow(NoteItem("ui-note-3", "周末计划", "去听一场现场演出，再找一家安静的咖啡馆。", 1793842200000L), onOpen = {})
        }
        AppBottomBar("notes", onSelect = {})
      }
    }
    compose.onNodeWithText(note.title).assertIsDisplayed()
    screenshot("03-saved-notes.png")
    compose.onNodeWithText(note.title).performClick()
    compose.onNodeWithText("朗读".localized(context)).assertIsDisplayed()
    compose.onNodeWithText("播放".localized(context)).assertIsDisplayed()
    compose.onNodeWithText("暂停".localized(context)).assertIsDisplayed()
    compose.onNodeWithText("停止".localized(context)).assertIsDisplayed()
    screenshot("16-reader.png")
  }

  @Test fun loginHasBothFieldsAndDoesNotSubmitAutomatically() {
    setFixture { LoginDialog(onSignedIn = { error("unexpected sign-in") }, onDismiss = {}) }
    compose.onNodeWithText("邮箱登录".localized(context)).assertIsDisplayed()
    compose.onNodeWithText("邮箱".localized(context)).assertIsDisplayed()
    compose.onNodeWithText("密码".localized(context)).assertIsDisplayed()
    screenshot("17-login.png")
  }
}

private fun captureScreenshot(name: String) {
  val instrumentation = InstrumentationRegistry.getInstrumentation()
  val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "vta-redesign-artifacts").apply { mkdirs() }
  // Compose assertions can finish before the window compositor presents the frame.
  instrumentation.waitForIdleSync()
  android.os.SystemClock.sleep(300)
  val bitmap = instrumentation.uiAutomation.takeScreenshot()
  try { File(directory, name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
  finally { bitmap.recycle() }
}
