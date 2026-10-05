package com.vibecodingjapan.ideavox

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalizationTest {
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
    val english =
      localizeText(
        "已监测到操作，3秒内再按一下，开启录音。三，二，一",
        AppLanguage.EN,
      )
    assertEquals(
      "Action detected. Press again within three seconds to start recording. Three, two, one",
      english,
    )
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
