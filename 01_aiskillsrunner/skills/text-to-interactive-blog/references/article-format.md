# 文章与图谱数据约定

完整三语示例见 [article.example.json](article.example.json)。用 UTF-8 JSON。字段名称与机器 ID 在所有语言间共用，显示文字按语言写作。

## 顶层

- `version` 为 `1`，`defaultLocale` 必须属于 `locales`。
- `locales` 以标准语言标签为键，例如 `zh`、`ja`、`en`。各值含 `title`、`summary` 和有序 `sections`。中文源稿默认选 `zh`。
- `sources` 可省略。每条含 `id`、来源名 `title`、原始页面的 HTTPS `url`。各语言共用引用 ID。同一个 ID 仅对应一个来源。
- `graph` 含 `nodes` 和可选 `edges`。

ID 用简短的小写 ASCII kebab-case，例如 `context-cost`。章节及内容块的 ID 在整篇内唯一。跨语言保留章节和内容块的 ID、顺序、类型、引用，以及列表项和表格的尺寸。

## 正文

每个章节有 `id`、`title`、`blocks`。各内容块有 `id`、`type` 和下表的字段，可加 `sources` 引用 ID 数组。文字字段写纯文本。

| type | 字段 | 用途 | 输出到 Markdown |
| --- | --- | --- | --- |
| `paragraph` | `text` | 连贯论述，每段一个块 | 段落 |
| `list` | `items` 字符串数组，`ordered` 可选布尔值 | 并列项目或有先后顺序的步骤 | 有序/无序列表 |
| `comparison` | `title`、`columns` 字符串数组、`rows` 二维字符串数组 | 并排比较，首列是行标题 | 加粗标题 + 表格 |
| `details` | `label`、`text` | 读者点开才显示的问答、案例或延伸 | 引用块（`> **label**`） |
| `mermaid` | `caption`、`description`、`code` | `code` 是 Mermaid 源码，`description` 是可独立阅读的解释 | 加粗图题 + 说明 + `mermaid` 代码块 |

各译文中可翻译 Mermaid 图的显示文字，节点 ID、关系及结论须对应。用简单语法和带引号的标签，省去配置、链接回调和 HTML 标签。

## 知识图谱

- 节点字段为 `id`、`labels`（按语言键提供短名称）、`weight`（整数 1 到 5）和 `relation`。相同权重对应相同圆面积。
- 省略 `parent` 的节点为首层，`relation` 为 `theme`。首层取少量主题，再用 `parent` 建立展开顺序。非首层颜色随节点与父节点的关系而变。`supports` 为论据，`explains` 为解释，`illustrates` 为案例，`qualifies` 为限制，`contrasts` 为对照。
- 每个末级节点必须有 `anchor`，值为实际存在的章节或内容块 ID。点击末级节点时跳转到正文 `content-<anchor>` 锚点。
- 额外语义边含 `source`、`target`（图谱节点 ID）和非 `theme` 的 `relation`。用它表达跨分支的实际论证或联系，展开层级仍由 `parent` 决定。

## 生成结果

`scripts/render-markdown.mjs` 输出：

- `zh.md` / `ja.md` / `en.md`：博客可直接渲染的 Markdown。章节与内容块前会写一行
  `<a id="content-<ID>"></a>`，供图谱跳转；博客的 MarkdownView 会把它渲染成不可见锚点。
- `graph.json`：`version`、`defaultLocale`、`relations`、`nodes`（含 `labels` / `weight` / `relation` / `parent` / `anchor`）、`edges`。后端把它写进 Firestore 的 `mkblog/<docid>.graph`，两个博客系统据此渲染交互图谱。
- `article.json`：本次渲染使用的结构化源。

不生成 HTML、CSS、JavaScript，也不下载 Mermaid 运行时。
