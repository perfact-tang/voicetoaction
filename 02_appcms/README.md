# AI Service 管理系统（02_appcms）

Firebase Hosting + Firestore + Storage 的静态管理后台，用于管理两类 AI 服务：

| 类型 (`type`) | 说明 | 关联字段 |
|---|---|---|
| `google_workspace_studio` | Google Workspace Studio，指向 Google Drive | `googleDriveUrl` |
| `deepseek_harness` | 上传自己的 Skill 包（zip），交给 [aiskillsrunner](../../01_aiskillsrunner/) 执行 | `applicationName`（Skill 名称）、`skillActiveVersion`（当前生效版本号）、`skillZipUrl`（当前版本公开下载地址）、`skillStoragePath`、`skillFileName`、`skillFileSize`，以及版本子集合 `skillVersions` |

## 1. 文件上传与下载

### 存储位置

- Bucket：默认 bucket `vibecodingjapan.firebasestorage.app`
- 对象路径：`skills/{uid}/{时间戳}-{安全文件名}.zip`
  - 例如 `skills/KYTF9y43qgc39vKn0sI3qWpsjRE2/1730000000000-my-skill.zip`

### 公开 URL

上传成功后前端写入 Firestore 的 `skillZipUrl` 是一个**无需鉴权即可下载**的公开地址：

```
https://firebasestorage.googleapis.com/v0/b/vibecodingjapan.firebasestorage.app/o/skills%2F{uid}%2F{file}.zip?alt=media
```

该地址由 `02_appcms/storage.rules` 中 `skills/**` 的 `allow get: if true;` 支持：

- `get`（按完整路径下载）：公开，供后台程序直接 `fetch` 下载。
- `list`（枚举目录）：仅本人，避免他人遍历。
- `write` / `delete`：仅本人，且必须是单个 `.zip` 段、大小 ≤ 25 MiB。

> `--reffilepath` 是运行时的本地参考文件，不属于上传内容，因此不存储在 Storage 中。

### 版本与 Storage 对象的关系

每个版本对应一个独立的 Storage 对象，路径不变（`skills/{uid}/{时间戳}-{文件名}.zip`），因此：

- 上传新版本**不会**删除旧对象，旧版本仍可下载、回退。
- 只有「删除某个版本」才会删除该版本自己的对象（best effort）。
- 下一个版本号 = 列表中剩余的最大版本号 + 1。删除最新的版本后，该版本号会被下一个新上传复用。

### 后台程序下载示例

```bash
# skillZipUrl 来自 Firestore: /allalservice/{docId}.skillZipUrl
# 该字段始终是「当前生效版本」的地址，切换版本后无需改动后台程序
aiskillsrunner --skill "my-skill" --installurl "https://firebasestorage.googleapis.com/v0/b/vibecodingjapan.firebasestorage.app/o/skills%2F...%2F...zip?alt=media"
```

Node 侧读取 Firestore 并按字段调用（示例）：

```js
const url = doc.skillZipUrl;            // --installurl
const name = doc.applicationName;       // --skill
// spawn('aiskillsrunner', ['--skill', name, '--installurl', url])
```

## 2. 数据格式与字段

集合 `allalservice`，文档在原有字段基础上新增以下字段（camelCase，与集合原有风格一致）：

| 字段 | 类型 | 说明 |
|---|---|---|
| `info` | string (≤2000) | 一览列表用的简短说明 |
| `sort` | int (0–1000000) | 显示顺序，数字越小越靠前 |
| `skillActiveVersion` | int (0–999999) | 当前生效的版本号；`0` 表示没有生效版本 |
| `skillZipUrl` | string (≤2048) | **当前生效版本**的公开下载地址（`--installurl`） |
| `skillStoragePath` | string (≤1024) | **当前生效版本**的 Storage 对象路径 |
| `skillFileName` | string (≤255) | **当前生效版本**的原始 zip 文件名 |
| `skillFileSize` | int (≤25 MiB) | **当前生效版本**的 zip 字节数 |

> `skillZipUrl` 等四个镜像字段**始终描述当前生效的那个版本**，由 `skillActiveVersion` 决定。这样 aiskillsrunner 侧
> `doc.skillZipUrl` → `--installurl` 的既有约定完全不变，无需改动后台程序。

`info` / `sort` / `skill*` / `skillActiveVersion` 在规则中都是**可选字段**：旧文档（没有这些字段）仍可正常读取，也可以继续用部分更新（启用/停用、移入回收站、恢复）。当这些字段存在时，`firestore.rules` 会严格校验：

- `skill*` 要么全部为空，要么四个字段同时存在且构成一个合法的 Storage 下载地址。
- 只有 `deepseek_harness` 类型允许带非空 `skill*`。
- `skillFileName` 必须以 `.zip` 结尾。
- `skillActiveVersion > 0` 时必须属于 `deepseek_harness` 且 `skillZipUrl` 非空（指针与镜像字段一致）。

### 版本文档（子集合 `skillVersions`）

每个版本是一份**不可变**的独立文档，文档 ID 就是版本号：

```
/allalservice/{serviceId}/skillVersions/{version}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `version` | int (1–999999) | 版本号，与文档 ID 相同 |
| `zipUrl` | string (≤2048) | 该版本的 Firebase Storage 公开下载地址 |
| `storagePath` | string (≤1024) | 必须是 `skills/{本人 uid}/…zip` |
| `fileName` | string (≤255) | 上传时的原始 zip 文件名（以 `.zip` 结尾） |
| `fileSize` | int ((0, 25 MiB]) | zip 字节数 |
| `createdAt` | timestamp | 创建时间（服务端时间，创建后不可改） |
| `userUid` | string | 所属用户，必须等于 `request.auth.uid` |

规则要点：

- 读 / 删要求父服务存在且属于本人（不会出现「父文档不存在的孤儿版本」）。
- **不允许 update**：版本是历史记录，改一个版本会篡改历史；需要修改请删除后重新上传。
- 写入时 `storagePath` 必须落在本人的 `skills/{uid}/` 下且为单个 `.zip` 段，防止引用他人的对象。

> 版本放在子集合而不是父文档的数组字段里，是因为 Firestore 规则**无法逐个校验数组元素**，
> 而每个版本文档都可以被完整校验；同时父文档不会随版本数量增长而逼近 1 MiB 上限。

Skill 名称复用原有字段 `applicationName`（`deepseek_harness` 类型必填），对应 aiskillsrunner 的 `--skill`，必须与 zip 内 `<名称>/SKILL.md` 或 `<名称>.md` 一致。

### 后台如何消费这些字段

后端 `monitor.js` 监听 `users/{uid}/recordings/{id}`，App 上传时会把 `serviceId` / `serviceType` 一起写进去：

- `google_workspace_studio`（含旧记录）：把 `googleDriveUrl` 反解出的目录 ID 当 Google Doc 输出目录。
- `deepseek_harness`：不使用 Google Drive，改为本地 STT + 用 `applicationName`（`--skill`）和
  `skillZipUrl`（`--installurl`）调 aiskillsrunner；并拿 `skillActiveVersion` 与本地版本比对，
  不一致就删除本地旧版本重新下载安装。详见根目录 `README.md` 的「Skills 流程」。

另外 App 会把 FCM 设备令牌写到 `users/{uid}/fcmTokens/{token}`（规则：仅本人可读写），
后端 task 结束后用它推送「执行完毕」通知。

## 3. 前端展示

- `services.html`：服务列表改为**表格**形式，列为「显示顺序 / 服务名称（含类型徽标、`--skill`）/ 介绍（`description` + `info`）/ 服务数据（Drive URL 或 Skill 包 + 当前版本）/ 状态 / 更新时间 / 操作（编辑 · 启用停用 · 移入回收站）」，Skill 服务在「服务数据」列提供「下载 zip / 复制 URL」。
  - **显示顺序 ↑ ↓**：每行的 ↑ / ↓ 让该服务与相邻服务交换位置；写入时会把整串顺序规范化成 `0,1,2…` 的 `sort` 值（只更新有变化的文档，用一个 batch 原子提交），因此即使历史 `sort` 有重复值或空洞，顺序也不会含糊。首行不能上移、末行不能下移（按钮置灰）。
  - 处于**搜索或筛选**状态时列表并非完整顺序，↑ ↓ 会自动置灰，避免歧义；清除条件后即可调整。
- `skills.html`（新增，导航栏「Skills 一览」）：表格一览所有 `deepseek_harness` 服务，含 `sort`、Skill 名称、标题、`info`、当前版本徽标 + zip 文件、下载 URL（下载/复制）、状态、更新时间、编辑入口；顶部统计 Skill 服务数、已上传数、缺少 zip 数；支持搜索。
- `service-new.html`：`DeepSeek Harness` 类型下填写 Skill 名称、显示顺序、Skill 说明并上传 zip（含进度条）；创建后该 zip 自动记录为 **V1** 并生效。
- `service-edit.html`：新增「**Skill 版本管控**」表格，列出版本、文件名、大小、上传时间、状态与操作：
  - **上传新版本**：选择新 zip → 保存时上传，自动记录为下一个版本号并设为当前生效版本。
  - **设为当前**：把任一历史版本切回生效（同步更新父文档的镜像字段）。
  - **下载**：直接下载该版本的 zip。
  - **删除**：删除该版本文档与对应的 Storage 对象；若删的是当前生效版本，则父文档的指针与镜像字段被清空，需要重新指定。
  - 旧数据（只有镜像字段、没有版本文档）会先显示为「V1（既有）」，在下次保存时自动补记为真正的 V1 文档。

## 4. 部署

```bash
cd 02_appcms
firebase login --reauth          # 凭据过期时需要
firebase deploy --only firestore:rules,storage,hosting
```

> **只部署 hosting 不会让新功能生效**：子集合 `skillVersions` 的读写依赖新的 `firestore.rules`，
> 不部署规则时版本列表会提示「版本列表加载失败：没有执行此操作的权限」。
> 本地用 Hosting 模拟器（`firebase emulators:start --only hosting`，端口 5000）调试时也一样——
> 页面连的仍是**线上 Firestore**，所以规则必须部署到线上。

### 静态资源缓存与版本号

本 CMS 没有构建步骤，页面直接引用 `assets/app.js`，所以浏览器很容易「新 HTML + 旧 JS」，
表现为新 UI 一片空白（`app.js` 里 `console.info('[AI Service CMS] build …')` 可确认实际运行的版本，
`document.body.dataset.build` 也能查看）。因此：

- `firebase.json` 的 hosting 已对所有路径设置 `Cache-Control: no-cache`，浏览器每次都会带
  ETag 重新校验（未改变则 304，开销很小）。
- 所有页面的 `<script src="assets/app.js?v=…">` 带版本号；**修改 `app.js` 后请同步修改这个 `?v=`**
  （以及 `app.js` 顶部的 `BUILD` 常量），这样即使浏览器缓存了旧 HTML 也会重新拉取新脚本。
- 已经打开了旧页面的浏览器需要强制刷新一次（Ctrl+Shift+R）才能拿到新的 HTML。

### ⚠️ Storage 规则是共享的

Firebase Storage 每个 bucket 只有一份规则，本仓库的 Android 应用（`03_androidapp`，使用 `record/**`）与本 CMS（`skills/**`）**共用默认 bucket**。因此：

- 规范文件是 **`02_appcms/storage.rules`**（已包含 `record/**` + `skills/**` 的并集）。
- 部署 `02_appcms` 会覆盖 `03_androidapp/storage.rules` 的效果。
- 修改规则时请同步两处，始终从 `02_appcms` 部署。

## 5. 迁移脚本

两个脚本都通过 `FIREBASE_ADMIN_KEY_PATH`（或 `.env`、或 `keys/` 下的服务账号 JSON）使用 firebase-admin，绕过安全规则，因此**只能在受信任的机器上运行**。默认都是 dry-run，只有加 `--apply` 才真正写入。

### 5.1 让既有的 aicalling 数据显示到 CMS

CMS 只读取 `allalservice`，而历史数据在 `/users/{uid}/aicalling`，字段为
`{ aicallingid, title, info, sort }`，因此 CMS 一览里看不到。
`aicallingid` 是 **Google Drive 文件夹 ID**（`monitor.js` 用它作为生成 Google Doc 的
`folderId`），所以可以映射为 `googleDriveUrl`。

#### 字段映射

| aicalling | allalservice |
|---|---|
| `title` | `name<Lang>`（`--lang` 指定，默认 `ja`） |
| `info` | `description<Lang>` + `info`（`info` 为空时用 `title` 兜底说明） |
| `sort` | `sort` |
| `aicallingid` | `googleDriveUrl = https://drive.google.com/drive/folders/{aicallingid}` |
| （固定） | `type = google_workspace_studio`、`status = active`、`isDeleted = false`、skill 字段为空 |

文档 ID 直接沿用 aicalling 的文档 ID，因此脚本**可重复执行（幂等）**；已存在的文档默认跳过，加 `--overwrite` 才覆盖。脚本会用与 `firestore.rules` 一致的校验先验证，避免写入 CMS 之后无法编辑的文档。

```bash
# 只读预览（默认），不修改任何数据
node 02_appcms/scripts/inspect-aicalling.mjs                 # 先看现有数据
node 02_appcms/scripts/migrate-aicalling-to-services.mjs     # dry-run 预览迁移结果

# 真正写入（--lang 默认 ja）
node 02_appcms/scripts/migrate-aicalling-to-services.mjs --lang ja --apply
```

> 注意：迁移后 `allalservice` 与 `aicalling` 是两份数据。**Android App 的「呼叫对象」现在直接读取
> `/allalservice`（`where userUid == uid`，只显示使用中且未删除的服务）**，因此 CMS 里的改动会立即反映到
> App，不再需要双向同步；`aicalling` 仅作为历史数据保留，可用上面的迁移脚本一次性转入。

### 5.2 把既有 Skill 包补记为 V1（版本管控上线）

在版本管控之前上传的 Skill 包只有父文档上的四个镜像字段，没有 `skillVersions` 子集合。
这些文档在编辑页会显示为「V1（既有）」，并在**下次保存时自动补记**成真正的 V1 文档；
如果想一次把所有历史数据补齐，可以运行：

```bash
node 02_appcms/scripts/migrate-skill-versions.mjs            # dry-run
node 02_appcms/scripts/migrate-skill-versions.mjs --apply    # 真正写入
```

- 只处理 `type=deepseek_harness` 且 `skillZipUrl` 非空的文档。
- 已有版本文档的文档默认跳过；`--overwrite` 会补写 V1（不会删除已有版本）。
- 版本号沿用父文档的 `skillActiveVersion`（缺失时为 1），镜像字段与 Storage 对象都不动。
- 默认保留原始的 `updatedAt` 作为 V1 的创建时间，列表中看到的是真实的上传时间。

## 6. 文件清单

```
02_appcms/
├── firebase.json        # 增加 storage 段
├── firestore.rules      # allalservice 增加 info/sort/skill* 校验 + skillVersions 子集合规则
├── storage.rules        # 新增：record/** + skills/** 并集
├── scripts/
│   ├── inspect-aicalling.mjs              # 只读：查看 aicalling / allalservice 现状
│   ├── migrate-aicalling-to-services.mjs  # 迁移：aicalling -> allalservice（默认 dry-run）
│   └── migrate-skill-versions.mjs         # 迁移：既有 Skill 包补记为 V1（默认 dry-run）
└── public/
    ├── skills.html      # 新增：Skills 一览（含当前版本徽标）
    ├── services.html    # 导航 + 卡片展示 Skill/info/sort/版本号
    ├── trash.html       # 导航
    ├── service-new.html # Skill 上传表单（创建即 V1）
    ├── service-edit.html# Skill 版本管控表格 + 上传新版本
    └── assets/app.js    # 上传、版本记录/回退/删除、公开 URL、一览页逻辑
```

