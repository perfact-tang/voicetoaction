# VoiceToAction：参考图驱动的界面重设计

## 设计依据

这版替换此前的石墨灰平面方案，以用户提供的双屏设计图为视觉依据：

`/Users/uiuxtan/.codex/attachments/e2b9d2f1-6f1e-4cc1-9598-29d9a801049d/image-1.png`

从参考图提取暖桃／玫紫的顶部光晕、近黑底色、半透明玻璃面板、26–30dp 圆角、轻量线性图标、声音波形主卡、虹彩玻璃光球及悬浮底部导航。直接在现有 Kotlin / Jetpack Compose 应用中实现，不另外制作网页替代应用。

参考图里的头像、图片生成、视频生成和营销文案不属于本产品；分别使用 VoiceToAction 品牌、录音、文件列表、AI 电话和真实录音信息组织内容。保留 Android 系统状态栏及系统导航，不模仿 iPhone 设备边框。

## 视觉规范

- 背景 `#080609`，顶部使用生成的柔焦光晕图片，66% 不透明度。
- 玻璃面板使用 `#A62C2630`，浅色边界 12%；在亮色背景上仍保留文字对比度。
- 主要文字 `#FFF9FF`，次要文字 `#D0C3D3`，操作主色 `#D7B8FF`，危险操作 `#FFA6B8`，上传成功 `#B5E6D3`。
- 英文与数字使用本地打包的 Manrope（400 / 500 / 600），中日韩回退系统字体。普通正文 14sp、说明 12sp、标题 20–26sp，只有首页主标语与录音计时使用更大字号。
- 内容边距 24dp，常用间距 10–16dp；按钮保持至少 48dp 的触控区域。主录音卡整块可点击。
- 浮动底栏保留录音／笔记／设置，并保留文字标签。选中项使用光晕图片，图标来自已有 Material Outlined 图标库。
- 没有装饰性循环动画。录音波形继续来自真实收音数据；按钮选中颜色保留轻微过渡。

字体与图标来源：[Google Fonts Manrope](https://github.com/google/fonts/tree/main/ofl/manrope)、[Material 图标设计规范](https://m2.material.io/design/iconography/system-icons.html)。Manrope 的 OFL 许可证同时保存在文档和 APK 的 assets/licenses 中。

## 页面与交互

1. 录音首页：品牌页头、简洁标语、声音波形主录音卡，文件列表与 AI 电话两个辅助入口；最近录音直接打开已有录音详情。录音期间仍可暂停、继续、结束。
2. 通话页：玻璃光球、当前处理对象与实际录音计时；设备路由与挂断固定在内容下方。小屏或大字体时上部内容可滚动，关键控制仍可见。
3. 录音列表：保留“录音”和“上传成功”两个独立列表；日期时间同一行，原始文件不加左侧播放按钮。列表改为轻量玻璃行，继续支持选择、上传、删除、详情、播放、改名与分享。
4. 上传成功详情：仍使用已有转写文本重新选择处理对象，不触发重新上传或 speech to text；界面只调用原有重新处理流程。
5. 笔记：首页仍为已保存列表，右上新增；保存标题、自动本地草稿、登录后继续保存、分页、下拉刷新和阅读均保留。列表显示标题、日期、摘要；编辑区与阅读区使用统一玻璃表面。
6. 设置：首屏直接显示录音／播放设备和播放键控制监视；设备名称比字段说明更突出。蓝牙与自动上传、上传格式、账户与语言仍置于二级菜单。
7. 弹窗：上传方式、参数、处理对象、成功详情、录音详情、登录、保存标题和删除确认统一字体、圆角与按钮。标题控制在 20sp 左右，长内容可滚动。
8. 启动页：与新底色一致，Android 12+ 使用同一玻璃光球。显示名继续为 VoiceToAction，应用 ID 保持原值，避免创建另一份应用数据。

## 资产

均通过内置 Image Gen 生成，使用同一张用户参考图作风格依据，已保存至 `app/src/main/res/drawable-nodpi/`。UI 文案、按钮、图标和录音波形仍由原生组件绘制。

| 文件 | 提示词与用途 |
| --- | --- |
| `ambient_glow.png` | Portrait atmospheric background; peach upper right, mauve/plum upper left, rose/lilac upper center, violet at 30–40%; fade to near-black #080609 by 55%; silky smooth light, no UI/text/objects/noise. 全局背景。 |
| `voice_studio.png` | Wide 2.4:1 rich violet background, translucent cyan and peach/pink spectral audio waveform ridges concentrated lower right, polished glass 3D, upper-left negative space, no UI or text. 首页录音卡。 |
| `voice_orb.png` | Single iridescent liquid-glass spherical sculpture; twisted yin-yang / S-shaped interior; cobalt blue, violet, cyan and warm peach reflections; centered; genuine transparent background; no UI/text. 通话主视觉与品牌图。 |

## 验证

最终视觉比较、截图路径、测试结果和调整记录见项目根目录 `design-qa.md`。

本次对照改版前后，所有 `appStore.save*`、`firebaseRepository.*` 调用入口及录音／朗读／上传服务 ACTION 集合均保留。此次仅重做原生界面和视觉资产；不改服务器、音频转换、蓝牙控制策略和云端协议。服务器端真实上传、Skills 和外接蓝牙硬件不属于本次视觉回归。
