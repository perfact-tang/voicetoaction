package com.vibecodingjapan.ideavox

/** CMS `/allalservice` 文档的 `type` 取值。 */
object ServiceTypes {
  const val GOOGLE_WORKSPACE_STUDIO = "google_workspace_studio"
  const val DEEPSEEK_HARNESS = "deepseek_harness"
}

/**
 * CMS `/allalservice` 文档 -> Android 呼叫对象的纯映射逻辑（不依赖 Firebase，可直接单测）。
 *
 * 呼叫对象的来源已从 `/users/{uid}/aicalling` 切换为 CMS 的 `/allalservice`
 * （`where userUid == uid`）。后端 monitor.js 仍然把 `/record.aicallingid` 当作
 * Google Drive 目录 ID 使用，因此这里从 CMS 的 `googleDriveUrl` 反解出目录 ID，
 * 后端无需改动即可继续生成 Google Doc。
 *
 * `deepseek_harness` 类型没有 Drive 目录（`aicallingId` 为空），后端会回落到
 * `GOOGLE_DRIVE_FOLDER_ID` 默认目录；服务 ID 与类型会一并写入 `/record`，供后端后续接入
 * aiskillsrunner 使用。
 */
object ServiceMapping {
  private val FOLDER_IN_PATH = Regex("""drive\.google\.com/drive/(?:u/\d+/)?folders/([A-Za-z0-9_-]+)""")
  private val FOLDER_IN_QUERY = Regex("""[?&]id=([A-Za-z0-9_-]+)""")

  /**
   * 从 CMS 的 `googleDriveUrl` 取出 Google Drive 目录 ID。
   * 支持 `.../drive/folders/{id}`、`.../drive/u/0/folders/{id}`、`...?id={id}`；取不到时返回空字符串。
   */
  fun googleDriveFolderId(url: String?): String {
    val value = url?.trim().orEmpty()
    if (value.isEmpty()) return ""
    FOLDER_IN_PATH.find(value)?.let { return it.groupValues[1] }
    FOLDER_IN_QUERY.find(value)?.let { return it.groupValues[1] }
    return ""
  }

  /** 只有 `google_workspace_studio` 会走 Drive 自动化；其余类型由后端回落默认目录。 */
  fun supportsDriveAutomation(type: String?): Boolean = type == ServiceTypes.GOOGLE_WORKSPACE_STUDIO

  /**
   * 按 App 当前语言取 `name<Lang>` / `description<Lang>`：
   * 先试当前语言，再依次回退到其它语言，全部为空时返回空字符串。
   */
  fun localizedField(values: Map<String, Any?>, prefix: String, language: AppLanguage): String {
    val order = listOf(language) + AppLanguage.entries.filterNot { it == language }
    order.forEach { candidate ->
      val suffix = candidate.name.lowercase().replaceFirstChar { it.uppercase() }
      val value = values[prefix + suffix] as? String
      if (!value.isNullOrBlank()) return value
    }
    return ""
  }

  /**
   * 把一份 `/allalservice` 文档映射成呼叫对象。
   * 已移入回收站（`isDeleted == true`）或不是「使用中」（`status != "active"`）的服务返回 null，
   * 即列表里不会出现停用或已删除的服务。
   */
  fun toCallingItem(values: Map<String, Any?>, documentId: String, language: AppLanguage): AICallingItem? {
    if (values["isDeleted"] == true) return null
    if (values["status"] as? String != "active") return null
    val applicationName = values["applicationName"] as? String ?: ""
    val title =
      localizedField(values, "name", language)
        .ifBlank { applicationName }
        .ifBlank { documentId }
    val info = localizedField(values, "description", language).ifBlank { values["info"] as? String ?: "" }
    return AICallingItem(
      title = title,
      info = info,
      serviceId = documentId,
      aicallingId = googleDriveFolderId(values["googleDriveUrl"] as? String),
      sort = (values["sort"] as? Number)?.toLong() ?: 0L,
      serviceType = values["type"] as? String ?: ServiceTypes.GOOGLE_WORKSPACE_STUDIO,
      applicationName = applicationName,
      skillVersion = (values["skillActiveVersion"] as? Number)?.toInt() ?: 0,
    )
  }
}
