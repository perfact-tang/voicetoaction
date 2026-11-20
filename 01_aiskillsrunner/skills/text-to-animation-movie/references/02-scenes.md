# 分镜画法：Studio 白板引擎 API 与双画幅布局指南

`src/scenes.tsx` 是每支片子唯一需要改写的视觉文件。所有高级绘图组件、图标、动效工具与双画幅安全区计算全部来自 `src/whiteboard/kit.tsx`，**不要**手写简陋的纯白 `<rect>` + 裸字来凑数。

---

## 1. 一个高质量场景长什么样

每个 `Visual` 接收 `{ durationInFrames, lines, seed, canvas }`，返回 `{ art, keys, accent? }`：
- `art`：纯 SVG 元素（放在 `<g>` 里），使用 `kit.tsx` 的高质感组件构建。
- `keys`：钢笔笔尖轨迹 `PenKey[]`，锚点应指向当前正在出现的卡片右下角或线条终点。
- `accent`：可选的场景主色调（如 `P.blue` / `P.green` / `P.purple` / `P.red` / `P.amber`），会自动同步到顶部全局进度条与底部字幕卡的左侧色条！

```tsx
const vRagPipeline: Visual = ({ durationInFrames, canvas }) => {
  const { isLandscape, cx, artLeft, artTop, artW } = canvas;
  const x0 = isLandscape ? cx - 640 : artLeft + 20;
  const w = isLandscape ? 560 : artW - 40;

  return {
    accent: P.blue,
    art: (
      <g>
        <Title text="RAG 怎么工作？" kicker="02 · 企业文档问答" accent={P.blue} seed={2} />

        <PaperCard x={x0} y={artTop + 60} w={w} h={240} at={10} accent={P.blue} badge="STEP 01" seed={1} float>
          <Icon name="database" x={x0 + 76} y={artTop + 180} size={48} color={P.blue} badgeFill={P.blueSoft} at={14} />
          <Label x={x0 + 150} y={artTop + 146} text="资料切片入库" at={16} size={36} weight={800} />
          <Label x={x0 + 150} y={artTop + 210} text="解析文档 → 切分片段 → 向量数据库" at={20} size={25} color={P.inkSoft} />
        </PaperCard>

        <FlowArrow
          x1={isLandscape ? x0 + w + 14 : cx}
          y1={isLandscape ? artTop + 180 : artTop + 310}
          x2={isLandscape ? x0 + w + 146 : cx}
          y2={isLandscape ? artTop + 180 : artTop + 400}
          at={28}
          color={P.blue}
          label="向量检索"
          pulse
        />
      </g>
    ),
    keys: [
      { at: 10, x: x0 + w - 30, y: artTop + 280 },
      { at: 32, x: isLandscape ? x0 + w + 130 : cx + 30, y: isLandscape ? artTop + 180 : artTop + 390 },
    ],
  };
};
```

---

## 2. 双画幅响应式布局（默认 9:16 竖屏 / 提及 16:9 用横屏）

**永远使用 `canvas`（来自 `useCanvas()`）做坐标定位，不要写死 `1080` 或 `1920`。**

`canvas` 自动给出以下安全区：

### 9:16 竖屏（默认 `1080×1920`）
```
y=0    ┌──────────────────────────┐ 顶部 6px 全局进度条 + 右上角 01/12 胶囊
y=119  │     [ 01 · 章节标签 ]     │ canvas.kickerY
y=188  │       场景主标题          │ canvas.titleY (字号 60 + 荧光笔底纹)
y=236  │       ~~~~~~~~~~~        │ canvas.underlineY
y=278  │ ┌──────────────────────┐ │ canvas.artTop
       │ │  核心插图安全区        │ │ artLeft=72 .. artRight=1008 (artW=936)
       │ │  (纵向堆叠 / 2列网格)  │ │ artH=1210, artCy=883
y=1488 │ └──────────────────────┘ │ canvas.artBottom
y=1620 │   ┌──────────────────┐   │
y=1809 │   │  带色条字幕卡片   │   │ Lines 自动定位在 bottom=111
y=1920 └──────────────────────────┘
```

### 16:9 横屏（`1920×1080`，文章或指示文提及 16:9 时启用）
```
y=0    ┌────────────────────────────────────────┐ 顶部 6px 全局进度条 + 右上角胶囊
y=60   │            [ 01 · 章节标签 ]            │ canvas.kickerY
y=112  │               场景主标题                │ canvas.titleY (字号 52)
y=149  │              ~~~~~~~~~~~~              │ canvas.underlineY
y=178  │ ┌────────────────────────────────────┐ │ canvas.artTop
       │ │        宽屏核心插图安全区           │ │ artLeft=100 .. artRight=1820 (artW=1720)
       │ │     (横向 2-4 栏 / 左右流程图谱)    │ │ artH=692, artCy=524
y=870  │ └────────────────────────────────────┘ │ canvas.artBottom
y=920  │      ┌──────────────────────────┐      │
y=1041 │      │      带色条字幕卡片       │      │ Lines 自动定位在 bottom=39
y=1080 └────────────────────────────────────────┘
```

### 双画幅排版铁律
- **9:16 竖屏**优先用：**纵向 2–3 层卡片流程**、**上下对比**、**2×2 四宫格**、**居中图谱 + 底部结论便利贴**。
- **16:9 横屏**优先用：**横向 2–4 列卡片/流程**、**左图右结论 / 左痛点右方案**、**横向知识图谱**。
- 如果本次任务明确确定了画幅（例如纯 9:16 或纯 16:9），只需把该画幅的布局做到极致，同时利用 `canvas.artLeft / artTop / artW / artH / cx` 保持安全区不越界。
- **文字不超框**：中文全角宽 ≈ `size × 1.0`，英文/数字 ≈ `size × 0.58`。卡片宽度必须比内部文字总宽多出至少 60px 余量；长文本可用 `\n` 换行（`<Label>` 原生支持 `\n` 多行居中/左对齐）。
- **钢笔不挡字**：笔身从笔尖向右上 45° 伸出；把 `keys` 锚点放在元素**右下角**最安全，最后一句说完前可让钢笔提前退场（不再加更晚的 key）。

---

## 3. Studio 白板组件速查（全部纯 SVG，可直接放进 `art`）

### 标题与卡片容器
| 组件 | 作用与关键 Props |
|---|---|
| `<Title text kicker accent at seed />` | 顶部章节胶囊 + 荧光笔底纹 + 主标题 + 手绘下划线（自动适配 9:16 / 16:9） |
| `<PaperCard x y w h at accent fill badge radius rotate seed topStrip float>` | **主力容器**：带柔和偏移阴影、顶部彩色饰条、右上角徽章（如 `01`/`STEP 1`）、手绘边框与弹性入场动画；`float` 开启微呼吸漂浮 |
| `<StickyNote x y w h at fill accent rotate seed>` | **便利贴**：带半透明胶带条、微折角与轻倾角，最适合放**具体例子、现实痛点、金句提示** |
| `<CalloutBanner x y w h text at accent icon seed />` | **结论横幅**：带左侧粗色条 + 图标 + 强调底色，最适合放在画面底部总结本场核心判断 |
| `<PillBadge x y text at color fill size icon align />` | **胶囊标签**：给概念、实体、状态打标签（如 `RAG`、`局限`、`优势`、`维护者`） |

### 图标、连线与图谱
| 组件 | 作用与关键 Props |
|---|---|
| `<Icon name x y size color strokeWidth at badgeFill />` | 内置 28 个精致手绘线稿图标，`badgeFill` 可自动包一层柔和圆底。可用名：`doc` `docs` `folder` `search` `database` `brain` `network` `user` `users` `gear` `check` `cross` `warning` `sparkle` `chat` `link` `box` `cart` `chart` `clock` `lightbulb` `book` `code` `server` `shield` `layers` `target` `tag` |
| `<FlowArrow x1 y1 x2 y2 at duration color strokeWidth curve label pulse dashed seed />` | **动态流程箭头**：支持直线或弯曲（`curve`）、中点关系标签胶囊（`label`），**强烈建议加 `pulse`** 让数据流光点沿箭头循环流动，画面立刻「活」起来！ |
| `<GraphNode x y label sublabel icon r at color fill seed pulse />` | **知识图谱/架构节点**：圆形卡片 + 图标 + 微呼吸浮动 + 可选光环脉冲 |
| `<SketchLine d at durationInFrames color strokeWidth seed opacity dash />` | 底层自绘线条，配合 `wobblyLine` / `wobblyEllipse` / `smoothPath` 画自定义路径 |
| `<ArrowHead x y angle at color size />` | 单独箭头 |
| `<Dot x y r at color fill />` | 弹出圆点 |

### 数据、标记与文字
| 组件 | 作用与关键 Props |
|---|---|
| `<Label x y text at size color weight align mono rotate opacity lineHeight />` | **所有画面内文字必须用它**（SVG `<text>`），支持 `\n` 多行 |
| `<DataBar x y w h ratio label valueText at color seed />` | 动画增长的水平数据对比条 |
| `<CountUp x y from to at duration prefix suffix decimals size color align />` | 数字平滑滚动计数器 |
| `<CheckMark x y size at color badge />` | 自绘绿色对勾（默认带柔和绿圆底） |
| `<CrossMark x y size at color badge />` | 自绘红色叉号（默认带柔和红圆底） |
| `<MarkerHighlight x y w h at duration color opacity />` | 动画荧光笔扫过底纹 |

### 动效与颜色
- **调色板 `P`**：
  - `P.blue` / `P.blueSoft`：结构、流程、通用技术
  - `P.green` / `P.greenSoft`：正确做法、优势、正向结果
  - `P.red` / `P.redSoft`：痛点、局限、错误/被否定项
  - `P.purple` / `P.purpleSoft`：抽象概念、知识图谱、本体、AI 模型
  - `P.amber` / `P.amberSoft`：提示、具体案例、关键提醒
  - `P.teal` / `P.tealSoft`：工具调用、系统协同、数据层
  - `P.marker`：荧光黄高亮
- **动效助手**：
  - `springPop(frame, fps, at)`：弹簧物理入场（比线性淡入有质感得多）
  - `floatY(frame, seed, amp, period)`：极轻的确定性呼吸浮动
  - `drawProgress(frame, at, duration)`：画线/增长进度（0→1）
  - `step(durationInFrames, i, n, offset)`：将 N 个元素均匀错开排进场景时长里
- **禁止**：CSS `animation`、`setTimeout`、`Math.random()`（必须保持逐帧确定性；随机用 `rnd(seed, salt)`）。
- **禁止**把 HTML `<div>` 塞进 SVG `<g>`，也**不要**加全屏 `<feTurbulence>` 纸纹滤镜（会拖慢渲染 3–4 倍）。

---

## 4. 高质感视觉配方（避免「白框+文字」简陋感）

要让每一场看起来像精心设计的专业知识动画，遵循**「每场至少 3 层视觉信息」**原则：
1. **顶层定调**：`<Title kicker="02 · 章节名" text="本场核心标题" accent={...} />`
2. **中层结构**：用 `PaperCard` + `Icon(badgeFill)` + `FlowArrow(pulse)` / `GraphNode` / `DataBar` 搭出清晰的空间关系（不要只放一个干瘪的大框）
3. **底层点睛**：配一个 `<StickyNote>`（放真实案例）或 `<CalloutBanner>`（放本场结论）或 `<CheckMark>`/`<CrossMark>` + `<PillBadge>`

| 叙事内容 | 推荐视觉配方 |
|---|---|
| **开场多问题抛钩** | 顶部标题 + 2×2 四张带不同色标与 `Icon` 的 `PaperCard`（错开 8 帧弹性弹出）+ 中间/底部 `StickyNote` 问号核心悬念 |
| **现实痛点共鸣** | 3–4 个散落微倾的 `StickyNote` / 灰色小 `PaperCard`（配 `folder`/`doc`/`clock` 图标）+ `CrossMark` 标红痛点 |
| **原理步骤（2–3步）** | 带 `STEP 01/02/03` 角标的 `PaperCard` + `Icon(badgeFill)` + `FlowArrow(pulse, label)` 串联 |
| **具体案例演示** | 上半区用 `GraphNode` + `FlowArrow(label, pulse)` 演一遍具体链路（如 `项目 A` —使用→ `检索服务` —维护→ `数据团队`），下半区 `CalloutBanner` 总结 |
| **优势 vs 局限转折** | 左右（16:9）或上下（9:16）两张 `PaperCard`：绿框+`CheckMark` 写优势，红框+`CrossMark` 写局限，底部 `PillBadge` 引出下一方案 |
| **概念辨析（A vs B）** | 两张对称 `PaperCard` + 中间醒目 `VS` 圆形徽章 + 底部 `MarkerHighlight` 点破本质区别 |
| **全景选型总结** | 4 张精炼对照卡（每张：图标 + 场景标签 + 推荐方案 `PillBadge`）+ 底部 `CalloutBanner` 务实结论 |

---

## 5. 渲染静帧自检清单

每画完一版 `scenes.tsx`，必须用 `bash scripts/render.sh still <帧号> "$DOC_DIR/check/S##.png"` 渲染静帧并用眼睛检查：
- [ ] 画幅比例正确（未提及 16:9 时为 `1080×1920`，提及 16:9 时为 `1920×1080`）
- [ ] 插图全部落在 `artTop..artBottom` 安全区内，**绝对不与底部字幕卡片重叠**
- [ ] 所有文字完整落在卡片内，无截断、无出画幅
- [ ] 钢笔没有挡住核心标题或正在讲解的文字
- [ ] 视觉元素饱满有层次（图标、色条、连线脉冲、徽章、阴影），没有大片空洞或简陋纯白方框
- [ ] 每个元素 `at` 出现时机与该场台词的讲述顺序对齐
