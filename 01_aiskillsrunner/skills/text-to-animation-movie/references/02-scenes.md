# 分镜画法：白板引擎 API

`src/scenes.tsx` 是唯一需要为每支片子改写的文件。所有绘图原语来自 `src/whiteboard/kit.tsx`，
**不要**自己重新实现纸、线、框、手、字幕。

## 1. 一个场景长什么样

```tsx
const vExample: Visual = ({ durationInFrames }) => {
  const frame = useCurrentFrame();
  return {
    art: (                                  // 画在纸上的 SVG
      <g>
        <Title text="场景标题" accent={P.blue} seed={1} />
        <rect x={150} y={500} width={780} height={300} rx={24}
              fill="#ffffff" stroke={P.blue} strokeWidth={4} filter={wob(1)}
              opacity={clamp01(interpolate(frame, [10, 22], [0, 1],
                { extrapolateLeft: "clamp", extrapolateRight: "clamp" }))} />
        <Label x={540} y={650} text="卡片文字" at={16} size={44} align="center" color={P.blue} />
      </g>
    ),
    keys: [                                  // 笔尖轨迹（帧 → 坐标）
      { at: 6,  x: 170, y: 520 },
      { at: 30, x: 910, y: 780 },
      { at: 60, x: 540, y: 650 },
    ] as HandKey[],
  };
};
```

要点：

- `at` 是**场景内的帧号**（不是全局帧）。场景从 0 开始。
- 每个元素的出现时间要和台词节奏对齐：拿 `durationInFrames` 乘以比例，
  或直接对应该场第几句台词。
- `keys` 里笔尖的坐标就是 `(x, y)` —— 引擎已经把手的内建原点对齐到笔尖，
  所以只要给锚点，笔就一定落在那里。**锚点应该指向该帧正在出现的那一笔。**
- 手在最后一个 `at` 之后淡出；想让手早退场就别给最后一个锚点。

## 2. 布局安全区（1080×1920）

```
y=0    ┌──────────────────────────┐
       │        （留白 100）        │
y=228  │        标题（居中）        │   Title 组件用这个位置
y=268  │        标题下划线          │
y=320  │ ┌──────────────────────┐ │
       │ │      插图区域          │ │   建议主要图形放在 y=400..1300
y=1400 │ └──────────────────────┘ │
y=1620 │                          │
y=1770 │      字幕卡片（居中）      │   Lines 组件固定在这里
y=1920 └──────────────────────────┘
```

- **左右各留 80px**：居中文字的实际半宽 `len × size / 2` 不能超过 460。
  CJK 全角字宽 ≈ 字号 × 1.0，英文/数字 ≈ × 0.55。混合时按字符分别算。
- 插图尽量不要伸到 y > 1300，否则容易和字幕卡片打架。
- 手占的画面大约 260×400px，方向是**向右上方**张开。锚点放在插图左下或下方时，
  手会盖到右上方的元素；把锚点放在元素**右下侧**最安全。

## 3. 原语速查

### 容器与文字

| API | 说明 |
|---|---|
| `<Paper seed durationInFrames>` | 米白纸底 + 纸纹 + 边缘随手痕迹 + 场景淡入淡出。`Scenes` 已经包好了，一般不用手写 |
| `<Title text accent seed at>` | 顶部标题 + 手绘下划线 |
| `<Label x y text at size color align mono rotate opacity>` | **文字必须用它**，它是 SVG `<text>`。`at` 控制弹出时间，`align` 取 `left/center/right` |
| `<Lines lines accent bottom size>` | 底部字幕卡片，由 `Scenes` 自动挂载，不要手写 |

> ⚠️ `<Label>` 必须放在 SVG 里（它的 `x/y` 是 SVG 坐标）。
> **不要**把 HTML `<div>` 放进 `<g>`：浏览器会把它提出来，文字会**静默消失**。

### 手绘线条

| API | 说明 |
|---|---|
| `wobblyLine(x1,y1,x2,y2,jitter,seed)` | 抖动的直线，返回 path `d` |
| `wobblyEllipse(cx,cy,rx,ry,jitter,seed,steps)` | 抖动的椭圆/圆 |
| `smoothPath(pts: [number,number][], close?)` | 折线 → Catmull-Rom 平滑路径 |
| `<SketchLine d at durationInFrames color strokeWidth seed opacity dash>` | 会**自己画出来**的线。`at` 起笔，`durationInFrames` 画完所需帧数 |
| `<ArrowHead x y angle at color size>` | 箭头，`angle` 是弧度 |
| `<Dot x y r at color fill>` | 圆点，`at` 控制弹出 |
| `<SketchBox x y w h at color fill rotate radius seed>` | 手绘圆角矩形框 |
| `<Hand keys hidden scale opacity>` | 握笔的手；`Scenes` 已自动挂载 |
| `wob(n)` 本地小工具 | 返回 `url(#sketch-wobble-N)`，给 `<rect>` 等原生 SVG 元素加手抖 |

`filter={wob(1)}` 只对**大**元素加（框、卡片、云）；小图标不加也能看，加了会更慢。
本地只定义了 `wob(n)`（把 n 折回 1..3）；需要别的 id 直接写 `url(#sketch-wobble-N)`，N 取 1..3。

### 颜色

`P` 对象：`paper, paperEdge, ink, inkSoft, inkFaint, blue, blueSoft, marker, red, redSoft,
green, greenSoft, purple, purpleSoft, cloud`。

语义约定（沿用现有片子）：

- **蓝色** = 中性/结构/流程
- **绿色** = 正面结论、正确做法、增长
- **红色** = 问题、局限、被划掉的东西
- **紫色** = 概念、抽象对象（Persona、模式、投资人）
- **marker（荧光黄）** = 强调一条底线，配合 `SketchLine` 用

### 尺寸常量

`W = 1080`、`H = 1920`（导入自 kit）。需要时用 `W / 2` 居中，不要写死 540。

## 4. 动画节奏

`useCurrentFrame()` 给的是场景内当前帧。常用写法：

```tsx
// 弹出（带一点回弹）
opacity={clamp01(interpolate(frame, [at, at + 10], [0, 1],
  { extrapolateLeft: "clamp", extrapolateRight: "clamp" }))}

// 进度条 / 生长
const grow = clamp01(interpolate(frame, [at, at + 18], [0, 1],
  { extrapolateLeft: "clamp", extrapolateRight: "clamp",
    easing: Easing.out(Easing.cubic) }));

// 循环运动（要确定性，不能用 CSS animation / requestAnimationFrame）
const angle = -Math.PI / 2 + (frame - start) / period * Math.PI * 2;
```

**禁止**：CSS `animation`、`setTimeout`、随机数（`Math.random()`）。
Remotion 是逐帧确定性渲染，任何非确定性都会让画面在渲染与预览间不一致。
需要随机感就用 `rnd(seed, salt)`（kit 里已提供，基于 Remotion 的 `random`）。

## 5. 常见画法配方

| 想表达 | 配方 |
|---|---|
| 对比（A vs B） | 左右两栏，`SketchBox` + 中间 `<text>VS</text>` 或箭头 |
| 方案/概念罗列 | 三栏卡片，从上到下依次 `at` 错开 8–12 帧出现，卡片右上角编号 `01/02/03` |
| 流程/转化 | 纵向或横向的 3–4 段漏斗，段间用 `SketchLine` + `ArrowHead` 连接 |
| 数据对比 | 横向区间条：底部长条（浅色）+ 区间段（深色），右侧标数值 |
| 循环/内耗 | `wobblyEllipse` 画环，节点放在环上，一枚胶囊沿环运动 |
| 时间线 | 一条 `SketchLine` + `Dot` 锚点 + 下方年份 `Label` |
| 被否定 | `Cross`（红叉，在 `Cross` 是 scenes 本地组件时可自建：两条 `SketchLine`） |
| 结论/正确 | `Tick`（绿勾，同上：一条折线 `SketchLine`） |
| 强调一句话 | 句子下方一条粗 `marker` 色 `SketchLine`，晚 10 帧出现 |

## 6. 自检清单

渲染静帧后逐场确认（`scripts/build_audio.py --dry-run` 里的场景数应该与图数一致）：

- [ ] 每场都有 `visual` 对应到 `VISUALS` 里的键（否则引擎会直接报错，这是好事）
- [ ] 文字没有超出画幅（居中文字半宽 ≤ 460）
- [ ] 文字没有超出所在卡片（卡片宽 ≥ `len × size`）
- [ ] 手没有盖住正在讲的文字
- [ ] 每个 `Label` 的 `at` 不晚于该场结束，也不早于它所在卡片出现的帧
- [ ] 最后一场的收尾画面成立（不依赖已被移除的元素）
