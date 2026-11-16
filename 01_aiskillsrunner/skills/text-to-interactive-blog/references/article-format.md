# 文章与图谱数据约定

完整三语示例见 [article.example.json](article.example.json)。用 UTF-8 JSON。字段名称与机器 ID 在所有语言间共用，显示文字按语言写作。

## 顶层

- `version` 为 `1`，`defaultLocale` 必须属于 `locales`。
- `locales` 以标准语言标签为键，例如 `zh`、`ja`、`en`。各值含 `title`、`summary` 和有序 `sections`。中文源稿默认选 `zh`。添加中、日、英以外的语言时，在该版本的 `ui` 中翻译 `scripts/article-format.mjs` 里英语标签的全部字段。
- `sources` 可省略。每条含 `id`、来源名 `title`、原始页面的 HTTPS `url`。各语言共用引用 ID。同一个 ID 仅对应一个来源。
- `graph` 含 `nodes` 和可选 `edges`。

ID 用简短的小写 ASCII kebab-case，例如 `context-cost`。章节及内容块的 ID 在整篇内唯一，正文 HTML 锚点为 `#content-<id>`。跨语言保留章节和内容块的 ID、顺序、类型、引用，以及列表项和表格的尺寸。

## 正文

每个章节有 `id`、`title`、`blocks`。各内容块有 `id`、`type` 和下表的字段，可加 `sources` 引用 ID 数组。文字字段写纯文本。

| type | 字段 | 用途 |
| --- | --- | --- |
| `paragraph` | `text` | 连贯论述，每段一个块 |
| `list` | `items` 字符串数组，`ordered` 可选布尔值 | 并列项目或有先后顺序的步骤 |
| `comparison` | `title`、`columns` 字符串数组、`rows` 二维字符串数组 | 并排比较，首列是行标题 |
| `details` | `label`、`text` | 读者点开才显示的问答、案例或延伸 |
| `mermaid` | `caption`、`description`、`code` | `code` 是 Mermaid 源码，`description` 是可独立阅读的解释 |

各译文中可翻译 Mermaid 图的显示文字，节点 ID、关系及结论须对应。用简单语法和带引号的标签，省去配置、链接回调和 HTML 标签。Markdown 保留 Mermaid 围栏，HTML 的图旁也保留可展开的 Mermaid 源码。[Mermaid 的浏览器集成文档](https://mermaid.js.org/config/usage)

## 知识图谱

- 节点字段为 `id`、`labels`（按语言键提供短名称）、`weight`（整数 1 到 5）和 `relation`。相同权重对应相同圆面积。按与主论点的关联程度或理解文章的重要性分配权重。
- 省略 `parent` 的节点为首层，`relation` 为 `theme`。首层取少量主题，再用 `parent` 建立展开顺序。非首层颜色随节点与父节点的关系而变。`supports` 为论据，`explains` 为解释，`illustrates` 为案例，`qualifies` 为限制，`contrasts` 为对照。
- 每个末级节点必须有 `anchor`，值为实际存在的正文 ID，优先定位到段落或组件。双击末级节点时跳转并聚焦，如果它对应折叠内容则展开。切换语言时已有锚点仍指向相应内容。
- 额外语义边含 `source`、`target`（图谱节点 ID）和非 `theme` 的 `relation`。用它表达跨分支的实际论证或联系，展开层级仍由 `parent` 决定。边在两端可见时显示，图例与可展开的关系列表解释含义。

浏览器可打开单文件 HTML，图谱、切换语言和文章组件在离线时也可工作。包含 Mermaid 图时，生成器在构建时获取并校验固定版本的 Mermaid 浏览器包，成功后嵌入 HTML。如构建环境需要使用本地包，可用 `INTERACTIVE_BLOG_MERMAID_BUNDLE` 指向同一版本的 `mermaid.min.js`。构建无法取得该包时，HTML 仍提供图解文字及源码，生成器会提示。图谱在桌面用双击激活，支持拖动及键盘 Enter / 空格，触屏用单击激活。
