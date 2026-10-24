/**
 * Whiteboard design system.
 *
 * Everything here exists to make a screen look like a marker on paper:
 *   - a warm paper base with a faint grain
 *   - wobbly, sketchy outlines instead of straight rectangles
 *   - strokes that draw themselves in over time (`SketchLine`)
 *   - a hand holding a pen that travels between drawing anchors
 *   - marker-highlighted keywords inside captions
 */

import React from "react";
import {
  AbsoluteFill,
  Easing,
  interpolate,
  random,
  useCurrentFrame,
} from "remotion";

/* ------------------------------------------------------------------ */
/* Palette + type                                                      */
/* ------------------------------------------------------------------ */

export const P = {
  paper: "#f7f2e7",
  paperEdge: "#e8dfcc",
  ink: "#22262e",
  inkSoft: "#4d5462",
  inkFaint: "#98a0ae",
  blue: "#1f6feb",
  blueSoft: "#dceafd",
  marker: "#ffe98a",
  red: "#e04b3a",
  redSoft: "#fbe0dc",
  green: "#1a9d6b",
  greenSoft: "#d9f2e6",
  purple: "#7b5cd6",
  purpleSoft: "#e9e2fb",
  cloud: "#2a7fd4",
} as const;

/**
 * Linux first: Ubuntu/Debian ship Noto Sans CJK (fonts-noto-cjk). The macOS
 * names are kept behind them so the same files also render on a Mac.
 */
export const FONT =
  '"Noto Sans SC", "Noto Sans CJK SC", "Source Han Sans SC", "Noto Sans CJK JP", ' +
  '"WenQuanYi Zen Hei", "PingFang SC", "Hiragino Sans GB", "Microsoft YaHei", sans-serif';

export const MONO =
  'ui-monospace, "DejaVu Sans Mono", "Liberation Mono", "SF Mono", Menlo, Consolas, monospace';

export const W = 1080;
export const H = 1920;

/* ------------------------------------------------------------------ */
/* Deterministic pseudo-randomness (so renders are reproducible)       */
/* ------------------------------------------------------------------ */

export const rnd = (seed: string | number, salt = 0): number =>
  random(`wb-${seed}-${salt}`);

/* ------------------------------------------------------------------ */
/* Timing helpers                                                      */
/* ------------------------------------------------------------------ */

/** Frames for item `i` of `n` to appear, spread across `duration`. */
export const step = (
  durationInFrames: number,
  i: number,
  n: number,
  offset = 0,
): number =>
  Math.round(
    offset +
      (durationInFrames * 0.62) * (n <= 1 ? 0 : i / Math.max(1, n - 1)),
  );

/** Pop-in progress for an element that starts at `at`. */
export const pop = (frame: number, at: number, speed = 8): number =>
  interpolate(frame, [at, at + speed], [0, 1], {
    extrapolateLeft: "clamp",
    extrapolateRight: "clamp",
    easing: Easing.out(Easing.cubic),
  });

export const drawProgress = (
  frame: number,
  at: number,
  duration: number,
): number =>
  interpolate(frame, [at, at + Math.max(1, duration)], [0, 1], {
    extrapolateLeft: "clamp",
    extrapolateRight: "clamp",
    easing: Easing.inOut(Easing.quad),
  });

/* ------------------------------------------------------------------ */
/* Layout primitives                                                   */
/* ------------------------------------------------------------------ */

/**
 * Paper base. `seed` changes the grain + the marginal doodles so every
 * scene feels hand-made rather than stamped from a template.
 */
export const Paper: React.FC<{
  seed: string | number;
  children: React.ReactNode;
  /** Extra scene-level fade in / out. */
  durationInFrames: number;
}> = ({ seed, children, durationInFrames }) => {
  const frame = useCurrentFrame();
  const slide = interpolate(frame, [0, 24], [0.35, 0], {
    extrapolateRight: "clamp",
    easing: Easing.out(Easing.cubic),
  });

  return (
    <AbsoluteFill
      style={{
        backgroundColor: P.paper,
        fontFamily: FONT,
        color: P.ink,
        opacity: interpolate(
          frame,
          [0, 7, durationInFrames - 7, durationInFrames],
          [0, 1, 1, 0],
          { extrapolateLeft: "clamp", extrapolateRight: "clamp" },
        ),
      }}
    >
      <PaperDoodles seed={seed} />
      <AbsoluteFill
        style={{ transform: `translateY(${slide * 100}px)` }}
      >
        {children}
      </AbsoluteFill>
    </AbsoluteFill>
  );
};

const PaperDoodles: React.FC<{ seed: string | number }> = ({ seed }) => {
  const r = rnd(seed, 1);
  const r2 = rnd(seed, 2);
  const ring = r > 0.55;
  return (
    <AbsoluteFill style={{ opacity: 0.5 }}>
      <svg width="100%" height="100%" viewBox={`0 0 ${W} ${H}`}>
        {/* corner registration ticks */}
        {(
          [
            [54, 54, 1, 1],
            [W - 54, 54, -1, 1],
            [54, H - 54, 1, -1],
            [W - 54, H - 54, -1, -1],
          ] as const
        ).map(([x, y, dx, dy], i) => (
          <g
            key={i}
            stroke={P.inkFaint}
            strokeWidth={2.4}
            strokeLinecap="round"
            opacity={0.55}
          >
            <path d={`M ${x} ${y} L ${x + 26 * dx} ${y}`} />
            <path d={`M ${x} ${y} L ${x} ${y + 26 * dy}`} />
          </g>
        ))}
        {ring ? (
          <ellipse
            cx={W - 190 - r2 * 60}
            cy={H - 300}
            rx={112}
            ry={104}
            fill="none"
            stroke="#c9a978"
            strokeWidth={7}
            opacity={0.16}
          />
        ) : null}
      </svg>
    </AbsoluteFill>
  );
};

/** Rounded paper card with a wobbly, hand-drawn outline. */
export const SketchBox: React.FC<{
  x: number;
  y: number;
  w: number;
  h: number;
  at?: number;
  color?: string;
  fill?: string;
  rotate?: number;
  radius?: number;
  seed?: string | number;
  strokeWidth?: number;
  children?: React.ReactNode;
  style?: React.CSSProperties;
}> = ({
  x,
  y,
  w,
  h,
  at = 0,
  color = P.ink,
  fill = "transparent",
  rotate = 0,
  radius = 22,
  seed = 1,
  strokeWidth = 3.4,
  children,
  style,
}) => {
  const frame = useCurrentFrame();
  const p = pop(frame, at, 7);
  const r = radius;
  const d =
    `M ${r} 0 H ${w - r} Q ${w} 0 ${w} ${r} V ${h - r} Q ${w} ${h} ${w - r} ${h} ` +
    `H ${r} Q 0 ${h} 0 ${h - r} V ${r} Q 0 0 ${r} 0 Z`;

  return (
    <div
      style={{
        position: "absolute",
        left: x,
        top: y,
        width: w,
        height: h,
        opacity: p,
        transform: `rotate(${rotate}deg) scale(${0.94 + p * 0.06})`,
        transformOrigin: "center center",
        ...style,
      }}
    >
      <svg
        width={w + 16}
        height={h + 16}
        viewBox={`-8 -8 ${w + 16} ${h + 16}`}
        style={{ position: "absolute", left: -8, top: -8 }}
      >
        <path d={d} fill={fill} />
        <path
          d={d}
          fill="none"
          stroke={color}
          strokeWidth={strokeWidth}
          strokeLinejoin="round"
          filter={`url(#sketch-wobble-${seed})`}
        />
      </svg>
      <div style={{ position: "relative", width: "100%", height: "100%" }}>
        {children}
      </div>
    </div>
  );
};

/* ------------------------------------------------------------------ */
/* Strokes that draw themselves                                        */
/* ------------------------------------------------------------------ */

export type Pt = [number, number];

/** Catmull-Rom-ish smoothing so polylines read as hand strokes. */
export const smoothPath = (pts: Pt[], close = false): string => {
  if (pts.length === 0) return "";
  if (pts.length === 1) return `M ${pts[0][0]} ${pts[0][1]}`;
  let d = `M ${pts[0][0]} ${pts[0][1]}`;
  for (let i = 0; i < pts.length - 1; i++) {
    const p0 = pts[i === 0 ? 0 : i - 1];
    const p1 = pts[i];
    const p2 = pts[i + 1];
    const p3 = pts[i + 2] ?? p2;
    const c1x = p1[0] + (p2[0] - p0[0]) / 6;
    const c1y = p1[1] + (p2[1] - p0[1]) / 6;
    const c2x = p2[0] - (p3[0] - p1[0]) / 6;
    const c2y = p2[1] - (p3[1] - p1[1]) / 6;
    d += ` C ${c1x.toFixed(2)} ${c1y.toFixed(2)} ${c2x.toFixed(2)} ${c2y.toFixed(2)} ${p2[0]} ${p2[1]}`;
  }
  return close ? `${d} Z` : d;
};

/** Wobbly freehand line between two points. */
export const wobblyLine = (
  x1: number,
  y1: number,
  x2: number,
  y2: number,
  jitter = 4,
  seed: string | number = 1,
): string => {
  const segments = 6;
  const pts: Pt[] = [];
  for (let i = 0; i <= segments; i++) {
    const t = i / segments;
    const j = i === 0 || i === segments ? 0 : (rnd(`${seed}-${i}`, 3) - 0.5) * jitter * 2;
    const nx = -(y2 - y1);
    const ny = x2 - x1;
    const len = Math.hypot(nx, ny) || 1;
    pts.push([
      x1 + (x2 - x1) * t + (nx / len) * j,
      y1 + (y2 - y1) * t + (ny / len) * j,
    ]);
  }
  return smoothPath(pts);
};

/** Wobbly ellipse path (for circles, loops, orbits). */
export const wobblyEllipse = (
  cx: number,
  cy: number,
  rx: number,
  ry: number,
  jitter = 3,
  seed: string | number = 1,
  steps = 22,
): string => {
  const pts: Pt[] = [];
  for (let i = 0; i <= steps; i++) {
    const a = (i / steps) * Math.PI * 2;
    const j = (rnd(`${seed}-e${i}`, 4) - 0.5) * jitter * 2;
    pts.push([
      cx + Math.cos(a) * (rx + j),
      cy + Math.sin(a) * (ry + j),
    ]);
  }
  return smoothPath(pts, true);
};

export const SketchLine: React.FC<{
  d: string;
  at: number;
  durationInFrames?: number;
  color?: string;
  strokeWidth?: number;
  seed?: string | number;
  opacity?: number;
  dash?: string;
  linecap?: "round" | "butt";
}> = ({
  d,
  at,
  durationInFrames = 1,
  color = P.ink,
  strokeWidth = 4,
  seed = 1,
  opacity = 1,
  dash,
  linecap = "round",
}) => {
  const frame = useCurrentFrame();
  const p = drawProgress(frame, at, durationInFrames);
  return (
    <path
      d={d}
      fill="none"
      stroke={color}
      strokeWidth={strokeWidth}
      strokeLinecap={linecap}
      strokeLinejoin="round"
      strokeDasharray={dash ?? 1}
      strokeDashoffset={dash ? undefined : 1 - p}
      pathLength={dash ? undefined : 1}
      opacity={opacity}
      filter={`url(#sketch-wobble-${seed})`}
      vectorEffect="non-scaling-stroke"
    />
  );
};

/** Arrow head at (x,y) pointing along `angle` (radians). */
export const ArrowHead: React.FC<{
  x: number;
  y: number;
  angle: number;
  at: number;
  color?: string;
  size?: number;
  seed?: string | number;
}> = ({ x, y, angle, at, color = P.ink, size = 20, seed = 1 }) => {
  const frame = useCurrentFrame();
  const p = pop(frame, at, 5);
  if (p <= 0) return null;
  const a1 = angle + Math.PI - 0.5;
  const a2 = angle + Math.PI + 0.5;
  return (
    <g
      transform={`translate(${x} ${y}) scale(${0.6 + p * 0.4})`}
      opacity={p}
      filter={`url(#sketch-wobble-${seed})`}
    >
      <path
        d={smoothPath([
          [Math.cos(a1) * size, Math.sin(a1) * size],
          [0, 0],
          [Math.cos(a2) * size, Math.sin(a2) * size],
        ])}
        fill="none"
        stroke={color}
        strokeWidth={4}
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </g>
  );
};

/* ------------------------------------------------------------------ */
/* Hand + pen                                                          */
/* ------------------------------------------------------------------ */

export type HandKey = { at: number; x: number; y: number; skip?: boolean };

/**
 * A hand holding a marker, authored so the nib is exactly the local origin.
 *
 * The artwork is deliberately simple and flat: a rounded fist, two chunky
 * fingers and a sleeve. It replaced an earlier "realistic" version whose thin,
 * disconnected finger strokes read as a claw at video size. Everything here is
 * drawn up and to the right of the nib, so the anchor never has to be offset.
 *
 * `mode="pen"` draws only the marker (useful for a calmer, ghost-writing look).
 */
export const Hand: React.FC<{
  keys: HandKey[];
  hidden?: boolean;
  scale?: number;
  opacity?: number;
  mode?: "hand" | "pen";
}> = ({ keys, hidden = false, scale = 1, opacity = 1, mode = "hand" }) => {
  const frame = useCurrentFrame();

  const track = (values: number[]): number => {
    for (let i = 0; i < keys.length; i++) {
      if (frame <= keys[i].at) {
        if (i === 0) return values[0];
        const prev = keys[i - 1];
        const cur = keys[i];
        if (cur.skip || prev.skip) {
          return frame < cur.at ? values[i - 1] : values[i];
        }
        const p = interpolate(frame, [prev.at, cur.at], [0, 1], {
          extrapolateLeft: "clamp",
          extrapolateRight: "clamp",
          easing: Easing.inOut(Easing.quad),
        });
        return values[i - 1] + (values[i] - values[i - 1]) * p;
      }
    }
    return values[values.length - 1];
  };

  if (hidden || keys.length === 0) return null;

  const x = track(keys.map((k) => k.x));
  const y = track(keys.map((k) => k.y));
  const first = keys[0].at;
  const last = keys[keys.length - 1].at;
  const fadeIn = opacity * interpolate(frame, [first - 6, first + 6], [0, 1], {
    extrapolateLeft: "clamp",
    extrapolateRight: "clamp",
  });
  const fadeOut = interpolate(frame, [last + 4, last + 12], [1, 0], {
    extrapolateLeft: "clamp",
    extrapolateRight: "clamp",
  });

  // A very small sway keeps the arm alive without moving the nib off its mark.
  const tilt = -3 + Math.sin(frame / 14) * 1.1;
  const s = 0.72 * scale;

  const pen = (
    <g>
      {/* ink tip + metal collar */}
      <path d="M 0 0 L 26 -16 L 34 -8 L 8 7 Z" fill="#2b313b" />
      <path d="M 0 0 C 5 -3 8 -4 11 -3 L 8 4 Z" fill="#101318" />
      <path d="M 30 -16 L 40 -22 L 46 -10 L 36 -4 Z" fill="#98a1b0" />
      {/* barrel */}
      <path
        d="M 42 -20 L 268 -169 Q 286 -181 296 -165 Q 306 -150 288 -139 L 54 -1 Q 40 8 33 -5 Q 26 -18 42 -20 Z"
        fill="#2f3642"
      />
      <path d="M 72 -42 L 248 -152" stroke="#6a7486" strokeWidth={5} strokeLinecap="round" opacity={0.6} />
    </g>
  );

  return (
    <g transform={`translate(${x} ${y})`} opacity={fadeIn * fadeOut}>
      <g transform={`rotate(${tilt}) scale(${s})`}>
        {pen}
        {mode === "hand" ? (
          <g transform="translate(80 -52)">
            {/* soft cast shadow */}
            <g transform="translate(9 14)" opacity={0.07}>
              <path
                d="M 87 -68 C 140 -105 200 -145 250 -172 C 300 -200 350 -205 385 -170 C 415 -140 430 -90 438 -40 C 448 20 440 70 410 100 C 380 128 340 138 300 130 C 260 122 230 100 200 70 C 160 30 120 -20 87 -68 Z"
                fill="#3a3026"
              />
            </g>
            {/* sleeve, fading out so the arm never has to leave the frame */}
            <path
              d="M 424 50 L 590 292 Q 612 330 578 346 Q 546 362 524 330 L 330 112 Z"
              fill="url(#sleeveFade)"
            />
            {/* back of the hand */}
            <path
              d="M 87 -68 C 130 -100 180 -132 230 -162 C 272 -187 312 -204 344 -192 C 380 -178 404 -138 420 -88 C 436 -38 442 14 424 54 C 406 94 368 118 330 116 C 306 114 286 104 268 90 C 246 100 224 102 206 92 C 186 80 174 58 162 38 C 146 12 128 -18 106 -40 C 96 -50 90 -60 87 -68 Z"
              fill="#eebd93"
            />
            {/* curled middle / ring fingers tucked under the palm */}
            <path
              d="M 168 40 C 142 48 132 72 144 94 C 156 116 186 122 206 108 C 220 98 222 78 214 64 C 202 46 186 36 168 40 Z"
              fill="#eab68a"
            />
            <path
              d="M 208 70 C 188 86 186 112 200 132 C 214 152 244 154 260 138 C 272 126 270 104 260 90 C 246 72 226 62 208 70 Z"
              fill="#eebd93"
            />
            <path d="M 208 78 C 220 90 222 106 214 120" stroke="#d9a172" strokeWidth={4.5} fill="none" strokeLinecap="round" />
            <path d="M 258 102 C 270 114 272 128 264 140" stroke="#d9a172" strokeWidth={4.5} fill="none" strokeLinecap="round" />
            {/* cuff */}
            <path d="M 424 50 L 449 88 L 355 152 L 330 112 Z" fill="#aebfd4" />
            <path d="M 436 70 L 368 118" stroke="#93a7c1" strokeWidth={4} fill="none" strokeLinecap="round" opacity={0.8} />
            {/* index finger, pressing the top of the barrel */}
            <path
              d="M 206 -178 C 174 -150 128 -110 90 -74 C 72 -58 64 -40 78 -32 C 92 -24 110 -34 124 -46 C 160 -76 198 -108 222 -126 C 232 -146 226 -172 206 -178 Z"
              fill="#f2c9a4"
            />
            <ellipse cx={88} cy={-54} rx={12} ry={18} transform="rotate(-42 88 -54)" fill="#f9ddc4" />
            <path d="M 186 -146 C 178 -128 166 -112 150 -98" stroke="#e0ad84" strokeWidth={4.5} fill="none" strokeLinecap="round" />
            {/* thumb, pressing the near side */}
            <path
              d="M 316 -120 C 282 -122 226 -94 182 -72 C 152 -58 122 -48 108 -38 C 92 -28 90 -10 106 0 C 122 10 146 2 164 -10 C 200 -32 244 -48 286 -56 C 310 -62 322 -84 320 -104 C 319 -112 318 -118 316 -120 Z"
              fill="#f2c9a4"
            />
            <ellipse cx={110} cy={-21} rx={12} ry={17} transform="rotate(24 110 -21)" fill="#f9ddc4" />
            <path d="M 280 -68 C 288 -54 286 -38 274 -28" stroke="#e0ad84" strokeWidth={4.5} fill="none" strokeLinecap="round" />
          </g>
        ) : null}
      </g>
    </g>
  );
};

/* ------------------------------------------------------------------ */
/* Caption driven by the real narration timing                         */
/* ------------------------------------------------------------------ */

export type LineCue = { text: string; startFrame: number; endFrame: number };

export const Lines: React.FC<{
  lines: LineCue[];
  accent?: string;
  size?: number;
  bottom?: number;
}> = ({ lines, accent = P.blue, size = 52, bottom = 150 }) => {
  const frame = useCurrentFrame();
  if (lines.length === 0) return null;

  let active = 0;
  for (let i = lines.length - 1; i >= 0; i--) {
    if (frame >= lines[i].startFrame - 6) {
      active = i;
      break;
    }
  }

  const line = lines[active];
  const local = frame - line.startFrame;
  const p = interpolate(local, [0, 9], [0, 1], {
    extrapolateLeft: "clamp",
    extrapolateRight: "clamp",
    easing: Easing.out(Easing.cubic),
  });

  return (
    <div
      style={{
        position: "absolute",
        left: 84,
        right: 84,
        bottom,
        display: "flex",
        justifyContent: "center",
      }}
    >
      <div
        style={{
          position: "relative",
          maxWidth: 912,
          textAlign: "center",
          fontSize: size,
          lineHeight: 1.42,
          fontWeight: 600,
          letterSpacing: 0.4,
          color: P.ink,
          padding: "30px 40px",
          background: "rgba(255,255,255,0.74)",
          borderRadius: 26,
          boxShadow: "0 18px 46px rgba(34,38,46,0.10)",
          border: `2.5px solid ${P.paperEdge}`,
          transform: `translateY(${(1 - p) * 18}px)`,
          opacity: p,
        }}
      >
        <span key={active}>
          {renderSegments(line.text, frame, accent, line.startFrame)}
        </span>
      </div>
    </div>
  );
};

/* ------------------------------------------------------------------ */
/* Small chart / diagram bits                                          */
/* ------------------------------------------------------------------ */

const renderSegments = (
  text: string,
  frame: number,
  accent: string,
  startFrame: number,
): React.ReactNode[] => {
  const parts = text.split(/(\*\*[^*]+\*\*)/g).filter(Boolean);
  return parts.map((part, i) => {
    if (part.startsWith("**") && part.endsWith("**")) {
      const inner = part.slice(2, -2);
      const at = startFrame + 6 + i * 5;
      const p = interpolate(frame, [at, at + 12], [0, 1], {
        extrapolateLeft: "clamp",
        extrapolateRight: "clamp",
        easing: Easing.out(Easing.cubic),
      });
      return (
        <span
          key={i}
          style={{
            position: "relative",
            display: "inline-block",
            padding: "0 3px",
            color: P.ink,
          }}
        >
          <span
            style={{
              position: "absolute",
              left: 0,
              top: "12%",
              height: "80%",
              width: `${p * 100}%`,
              background: `linear-gradient(90deg, ${accent}44, ${accent}22)`,
              borderRadius: 8,
              zIndex: 0,
            }}
          />
          <span style={{ position: "relative", zIndex: 1 }}>{inner}</span>
        </span>
      );
    }
    return <span key={i}>{part}</span>;
  });
};

/* ------------------------------------------------------------------ */
/* Small chart / diagram bits                                          */
/* ------------------------------------------------------------------ */

export const Dot: React.FC<{
  x: number;
  y: number;
  r?: number;
  at: number;
  color?: string;
  fill?: boolean;
}> = ({ x, y, r = 9, at, color = P.ink, fill = true }) => {
  const frame = useCurrentFrame();
  const p = pop(frame, at, 6);
  return (
    <circle
      cx={x}
      cy={y}
      r={r * (0.5 + p * 0.5)}
      fill={fill ? color : "none"}
      stroke={color}
      strokeWidth={fill ? 0 : 3.5}
      opacity={p}
    />
  );
};

/**
 * Animated text label, emitted as a real SVG `<text>` element.
 *
 * It has to be SVG rather than a positioned `<div>`: HTML children placed
 * inside an SVG `<g>` are hoisted out of the SVG by the browser and never
 * appear, which silently swallows every in-artwork label.
 */
export const Label: React.FC<{
  x: number;
  y: number;
  text: string;
  at: number;
  size?: number;
  color?: string;
  weight?: number;
  align?: "left" | "center" | "right";
  mono?: boolean;
  rotate?: number;
  opacity?: number;
}> = ({
  x,
  y,
  text,
  at,
  size = 30,
  color = P.ink,
  weight = 600,
  align = "left",
  mono = false,
  rotate = 0,
  opacity = 1,
}) => {
  const frame = useCurrentFrame();
  const p = pop(frame, at, 8);
  if (p <= 0) return null;
  const dx = (1 - p) * 14 * (align === "right" ? 1 : -1);
  return (
    <text
      x={x + dx}
      y={y}
      opacity={p * opacity}
      fill={color}
      fontSize={size}
      fontWeight={weight}
      fontFamily={mono ? MONO : FONT}
      textAnchor={align === "center" ? "middle" : align === "right" ? "end" : "start"}
      dominantBaseline="central"
      transform={rotate ? `rotate(${rotate} ${x} ${y})` : undefined}
      style={{ whiteSpace: "pre" }}
    >
      {text}
    </text>
  );
};
