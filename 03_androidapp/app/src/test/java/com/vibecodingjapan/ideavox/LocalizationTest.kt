package com.vibecodingjapan.ideavox

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalizationTest {
  @Test fun automaticUploadCompletionHasSpokenTextInEveryAppLanguage() {
    assertEquals("自动上传成功", localizeText("自动上传成功", AppLanguage.ZH))
    assertEquals("自動アップロードが完了しました", localizeText("自动上传成功", AppLanguage.JA))
    assertEquals("Automatic upload successful", localizeText("自动上传成功", AppLanguage.EN))
    assertEquals("자동 업로드가 완료되었습니다", localizeText("自动上传成功", AppLanguage.KO))
  }

  @Test fun automaticUploadCompletedPromptMatchesTheRequestedWording() {
    assertEquals("自动上传已完成", localizeText("自动上传已完成", AppLanguage.ZH))
    assertEquals("自動アップロードが完了しました", localizeText("自动上传已完成", AppLanguage.JA))
    assertEquals("Automatic upload completed", localizeText("自动上传已完成", AppLanguage.EN))
    assertEquals("자동 업로드가 완료되었습니다", localizeText("自动上传已完成", AppLanguage.KO))
    assertEquals("Recording ended", localizeText("录音已结束", AppLanguage.EN))
  }

  @Test
  fun translatesCoreNavigationIntoEverySupportedLanguage() {
    assertEquals("録音", localizeText("录音", AppLanguage.JA))
    assertEquals("Recorder", localizeText("录音", AppLanguage.EN))
    assertEquals("녹음", localizeText("录音", AppLanguage.KO))
  }

  @Test
  fun translatesInterpolatedInterfaceText() {
    assertEquals(
      "Selected: 3 files",
      localizeText("已选择 3 个文件", AppLanguage.EN),
    )
    assertEquals(
      "フィルター：アップロード済み",
      localizeText("筛选：已上传文件", AppLanguage.JA),
    )
  }

  @Test
  fun translatesVoicePromptsAndCountdownNumbers() {
    assertEquals("Ready", localizeText("我在", AppLanguage.EN))
    assertEquals("Stop", localizeText("结束", AppLanguage.EN))
    assertEquals("我在", localizeText("我在", AppLanguage.ZH))
    assertEquals("开启录音", localizeText("开启录音", AppLanguage.ZH))
    assertEquals("Start recording", localizeText("开启录音", AppLanguage.EN))
    assertEquals("nine", localizeText("九", AppLanguage.EN))
    assertEquals("구", localizeText("九", AppLanguage.KO))
  }

  @Test
  fun keepsUserContentAndChineseSourceUntouched() {
    assertEquals("用户自己的笔记", localizeText("用户自己的笔记", AppLanguage.EN))
    assertEquals("上传成功", localizeText("上传成功", AppLanguage.ZH))
    assertEquals(4, AppLanguage.entries.map { it.languageTag }.toSet().size)
  }
}
