package com.vibecodingjapan.ideavox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 呼叫对象来源从 `/users/{uid}/aicalling` 改为 CMS `/allalservice` 之后的核心映射逻辑。
 * 这些测试不依赖 Firebase，可以直接在 JVM 上跑。
 */
class ServiceMappingTest {
  @Test
  fun googleDriveFolderId_readsFolderFromCmsDriveUrl() {
    assertEquals("1AbC-dEf_9", ServiceMapping.googleDriveFolderId("https://drive.google.com/drive/folders/1AbC-dEf_9"))
    assertEquals(
      "1AbC-dEf_9",
      ServiceMapping.googleDriveFolderId("https://drive.google.com/drive/u/0/folders/1AbC-dEf_9?usp=sharing"),
    )
    assertEquals("0BxYz", ServiceMapping.googleDriveFolderId("https://drive.google.com/open?id=0BxYz"))
  }

  @Test
  fun googleDriveFolderId_returnsEmptyForMissingOrUnknownUrls() {
    assertEquals("", ServiceMapping.googleDriveFolderId(null))
    assertEquals("", ServiceMapping.googleDriveFolderId(""))
    assertEquals("", ServiceMapping.googleDriveFolderId("   "))
    assertEquals("", ServiceMapping.googleDriveFolderId("https://example.com/not-a-drive-url"))
    assertEquals("", ServiceMapping.googleDriveFolderId("https://drive.google.com/drive/my-drive"))
  }

  @Test
  fun supportsDriveAutomation_onlyForGoogleWorkspaceStudio() {
    assertTrue(ServiceMapping.supportsDriveAutomation(ServiceTypes.GOOGLE_WORKSPACE_STUDIO))
    assertFalse(ServiceMapping.supportsDriveAutomation(ServiceTypes.DEEPSEEK_HARNESS))
    assertFalse(ServiceMapping.supportsDriveAutomation(null))
    assertFalse(ServiceMapping.supportsDriveAutomation("something_else"))
  }

  @Test
  fun localizedField_prefersAppLanguageThenFallsBack() {
    val values =
      mapOf(
        "nameZh" to "中文名",
        "nameJa" to "日本語名",
        "nameEn" to "",
      )
    assertEquals("日本語名", ServiceMapping.localizedField(values, "name", AppLanguage.JA))
    // 英语为空 -> 回退到其它可用语言，而不是返回空串。
    assertEquals("中文名", ServiceMapping.localizedField(values, "name", AppLanguage.EN))
    assertEquals("", ServiceMapping.localizedField(values, "description", AppLanguage.JA))
  }

  @Test
  fun toCallingItem_mapsGoogleServiceFromCmsDocument() {
    val item =
      ServiceMapping.toCallingItem(
        values =
          mapOf(
            "userUid" to "u1",
            "type" to ServiceTypes.GOOGLE_WORKSPACE_STUDIO,
            "nameZh" to "秘书部",
            "nameJa" to "秘書部",
            "descriptionZh" to "整理电话录音",
            "descriptionJa" to "通話録音を整理",
            "googleDriveUrl" to "https://drive.google.com/drive/folders/folder-9",
            "sort" to 3L,
            "status" to "active",
            "isDeleted" to false,
          ),
        documentId = "svc-google",
        language = AppLanguage.JA,
      )

    requireNotNull(item)
    assertEquals("svc-google", item.serviceId)
    assertEquals("folder-9", item.aicallingId)
    assertEquals("秘書部", item.title)
    assertEquals("通話録音を整理", item.info)
    assertEquals(3L, item.sort)
    assertEquals(ServiceTypes.GOOGLE_WORKSPACE_STUDIO, item.serviceType)
    assertTrue(item.drivesGoogleDoc)
  }

  @Test
  fun toCallingItem_mapsDeepSeekServiceWithoutDriveFolder() {
    val item =
      ServiceMapping.toCallingItem(
        values =
          mapOf(
            "type" to ServiceTypes.DEEPSEEK_HARNESS,
            "nameZh" to "",
            "nameJa" to "",
            "nameEn" to "",
            "descriptionEn" to "",
            "applicationName" to "research-assistant",
            "info" to "用 Skill 处理",
            "googleDriveUrl" to "",
            "sort" to 0L,
            "status" to "active",
            "isDeleted" to false,
            "skillActiveVersion" to 2L,
          ),
        documentId = "svc-skill",
        language = AppLanguage.ZH,
      )

    requireNotNull(item)
    assertEquals("research-assistant", item.title)
    assertEquals("用 Skill 处理", item.info)
    assertEquals("", item.aicallingId)
    assertEquals(2L, item.skillVersion.toLong())
    assertEquals(ServiceTypes.DEEPSEEK_HARNESS, item.serviceType)
    assertFalse(item.drivesGoogleDoc)
  }

  @Test
  fun toCallingItem_skipsInactiveAndTrashedServices() {
    val base =
      mapOf(
        "type" to ServiceTypes.GOOGLE_WORKSPACE_STUDIO,
        "nameZh" to "服务",
        "googleDriveUrl" to "https://drive.google.com/drive/folders/f1",
        "status" to "active",
        "isDeleted" to false,
      )
    assertNull(ServiceMapping.toCallingItem(base + ("status" to "inactive"), "s1", AppLanguage.ZH))
    assertNull(ServiceMapping.toCallingItem(base + ("isDeleted" to true), "s2", AppLanguage.ZH))
    // 缺少 status 字段（旧文档）同样不可拨打。
    assertNull(ServiceMapping.toCallingItem(base - "status", "s3", AppLanguage.ZH))
    requireNotNull(ServiceMapping.toCallingItem(base, "s4", AppLanguage.ZH))
  }

  @Test
  fun toCallingItem_fallsBackToDocumentIdWhenNoNameExists() {
    val item =
      ServiceMapping.toCallingItem(
        values = mapOf("status" to "active", "isDeleted" to false),
        documentId = "svc-anonymous",
        language = AppLanguage.ZH,
      )
    requireNotNull(item)
    assertEquals("svc-anonymous", item.title)
    assertEquals("", item.info)
    assertEquals(ServiceTypes.GOOGLE_WORKSPACE_STUDIO, item.serviceType)
  }
}
