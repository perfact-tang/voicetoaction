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
 * A hand holding a pen, authored so the pen tip is the local origin. The hand
 * is drawn up and to the right of it, resting on the paper beside the ink, and
 * the whole group is translated onto the animated (x, y) anchor. That way every
 * drawing in every scene is aimed at the mark the pen is supposedly making.
 */
export const Hand: React.FC<{
  keys: HandKey[];
  hidden?: boolean;
  scale?: number;
  opacity?: number;
}> = ({ keys, hidden = false, scale = 1, opacity = 1 }) => {
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

  // subtle idle motion so the arm never looks frozen
  const bob = Math.sin(frame / 9) * 2;
  const tilt = -8 + Math.sin(frame / 13) * 1.6;
  const s = 0.55 * scale;

  /*
   * The artwork below is authored in a local space whose origin is exactly the
   * pen tip: it points down-left and the hand sits up and to the right, resting
   * on the paper beside the ink. Translating to the animated anchor first, then
   * rotating/scaling about the origin, keeps the nib locked on the anchor
   * without relying on `transform-origin` (which SVG ignores).
   */
  return (
    <g transform={`translate(${x} ${y})`} opacity={fadeIn * fadeOut}>
      <g transform={`rotate(${tilt}) scale(${s})`}>
        <g transform={`translate(0 ${bob})`}>
          {/* cuff, trailing off the bottom-right of the frame */}
          <path
            d={smoothPath([
              [402, 224],
              [470, 320],
              [524, 400],
            ])}
            fill="none"
            stroke="#3b4252"
            strokeWidth={112}
            strokeLinecap="round"
          />
          {/* forearm, running down-right from the wrist */}
          <path
            d={smoothPath([
              [286, 24],
              [340, 110],
              [386, 196],
            ])}
            fill="none"
            stroke="#eec49f"
            strokeWidth={92}
            strokeLinecap="round"
          />
          {/* fist wrapped around the barrel */}
          <path
            d={smoothPath([
              [178, -22],
              [96, 8],
              [72, 86],
              [118, 130],
              [200, 102],
            ], true)}
            fill="#eec49f"
            stroke="#d5a377"
            strokeWidth={3.5}
            filter="url(#sketch-wobble-1)"
          />
          {/* thumb, creeping along the barrel toward the nib */}
          <path
            d={smoothPath([
              [86, 30],
              [130, 2],
              [214, -90],
            ])}
            fill="none"
            stroke="#eec49f"
            strokeWidth={42}
            strokeLinecap="round"
          />
          {/* the pen: a single straight stroke ending exactly at the origin,
              so the nib can never drift off the anchor */}
          <path
            d={smoothPath([
              [440, -266],
              [0, 0],
            ])}
            fill="none"
            stroke="#242a34"
            strokeWidth={40}
            strokeLinecap="round"
          />
          {/* barrel highlight */}
          <path
            d={smoothPath([
              [360, -220],
              [70, -42],
            ])}
            fill="none"
            stroke="#525a6b"
            strokeWidth={9}
            strokeLinecap="round"
          />
          {/* clip */}
          <path
            d={smoothPath([
              [448, -272],
              [500, -178],
            ])}
            fill="none"
            stroke="#c9a227"
            strokeWidth={12}
            strokeLinecap="round"
          />
          {/* fingertips curling over the barrel */}
          <path
            d={smoothPath([
              [206, 114],
              [182, 52],
              [150, -50],
            ])}
            fill="none"
            stroke="#eec49f"
            strokeWidth={34}
            strokeLinecap="round"
          />
          <path
            d={smoothPath([
              [166, 124],
              [146, 66],
              [122, -30],
            ])}
            fill="none"
            stroke="#eec49f"
            strokeWidth={32}
            strokeLinecap="round"
          />
          <path
            d={smoothPath([
              [130, 130],
              [112, 72],
              [90, -10],
            ])}
            fill="none"
            stroke="#eec49f"
            strokeWidth={29}
            strokeLinecap="round"
          />
        </g>
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
