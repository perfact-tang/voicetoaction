/**
 * Studio Whiteboard Design System (Remotion).
 *
 * Designed to look like a high-craft editorial sketchbook / motion explainer:
 *   - warm radial-lit cream paper with a lightweight dot-grid pattern (fast to render)
 *   - responsive layout system (`useCanvas()`) that adapts cleanly to 9:16 and 16:9
 *   - spring-physics entrances + subtle ambient motion so frames never feel frozen
 *   - rich SVG-native diagram primitives: PaperCard, StickyNote, PillBadge,
 *     FlowArrow (with optional animated signal pulse), GraphNode/GraphEdge,
 *     DataBar, CountUp, CheckMark, CrossMark, MarkerHighlight, CalloutBanner, Icon
 *   - fountain pen whose nib stays locked to `(x, y)`
 *   - polished caption card with animated highlighter sweep for `**keywords**`
 */

import React from "react";
import {
  AbsoluteFill,
  Easing,
  interpolate,
  random,
  spring,
  useCurrentFrame,
  useVideoConfig,
} from "remotion";
import { Icon, type IconName } from "./icons";

export { Icon, type IconName };

/* ------------------------------------------------------------------ */
/* Palette + type                                                      */
/* ------------------------------------------------------------------ */

export const P = {
  paper: "#faf6ee",
  paperWarm: "#f4ede0",
  paperEdge: "#e4d9c5",
  dotGrid: "#d9ceba",
  ink: "#1c212b",
  inkSoft: "#4b5563",
  inkFaint: "#8e98a8",
  blue: "#2563eb",
  blueSoft: "#dbeafe",
  marker: "#fde047",
  red: "#dc2626",
  redSoft: "#fee2e2",
  green: "#059669",
  greenSoft: "#d1fae5",
  purple: "#7c3aed",
  purpleSoft: "#ede9fe",
  amber: "#d97706",
  amberSoft: "#fef3c7",
  teal: "#0d9488",
  tealSoft: "#ccfbf1",
  cloud: "#2a7fd4",
  white: "#ffffff",
  shadow: "rgba(28, 33, 43, 0.08)",
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

/** Legacy portrait constants kept for backwards compatibility. Prefer `useCanvas()`. */
export const W = 1080;
export const H = 1920;

export const wob = (n: number | string = 1): string => {
  const num = typeof n === "number" ? n : n.length;
  return `url(#sketch-wobble-${(Math.abs(Math.round(num) - 1) % 3) + 1})`;
};

export const clamp01 = (v: number): number => Math.max(0, Math.min(1, v));

/* ------------------------------------------------------------------ */
/* Responsive Canvas Layout (9:16 portrait & 16:9 landscape)           */
/* ------------------------------------------------------------------ */

export type CanvasLayout = {
  width: number;
  height: number;
  fps: number;
  isLandscape: boolean;
  cx: number;
  cy: number;
  safeX: number;
  kickerY: number;
  titleY: number;
  underlineY: number;
  titleSize: number;
  artLeft: number;
  artRight: number;
  artTop: number;
  artBottom: number;
  artW: number;
  artH: number;
  artCy: number;
  captionBottom: number;
  captionMaxW: number;
  captionFontSize: number;
  penScale: number;
};

export const getCanvasLayout = (
  width: number,
  height: number,
  fps = 30,
): CanvasLayout => {
  const isLandscape = width > height;
  if (isLandscape) {
    const safeX = Math.round(width * 0.052); // ~100 on 1920
    const artTop = Math.round(height * 0.165); // ~178 on 1080
    const artBottom = Math.round(height * 0.805); // ~870 on 1080
    return {
      width,
      height,
      fps,
      isLandscape: true,
      cx: width / 2,
      cy: height / 2,
      safeX,
      kickerY: Math.round(height * 0.056), // ~60
      titleY: Math.round(height * 0.104), // ~112
      underlineY: Math.round(height * 0.138), // ~149
      titleSize: Math.round(height * 0.048), // ~52
      artLeft: safeX,
      artRight: width - safeX,
      artTop,
      artBottom,
      artW: width - safeX * 2,
      artH: artBottom - artTop,
      artCy: Math.round((artTop + artBottom) / 2),
      captionBottom: Math.round(height * 0.036), // ~39
      captionMaxW: Math.round(width * 0.78), // ~1498
      captionFontSize: Math.round(height * 0.036), // ~39
      penScale: 0.78,
    };
  }

  // Default 9:16 portrait (1080x1920)
  const safeX = Math.round(width * 0.067); // ~72 on 1080
  const artTop = Math.round(height * 0.145); // ~278 on 1920
  const artBottom = Math.round(height * 0.775); // ~1488 on 1920
  return {
    width,
    height,
    fps,
    isLandscape: false,
    cx: width / 2,
    cy: height / 2,
    safeX,
    kickerY: Math.round(height * 0.062), // ~119
    titleY: Math.round(height * 0.098), // ~188
    underlineY: Math.round(height * 0.123), // ~236
    titleSize: Math.round(width * 0.056), // ~60
    artLeft: safeX,
    artRight: width - safeX,
    artTop,
    artBottom,
    artW: width - safeX * 2,
    artH: artBottom - artTop,
    artCy: Math.round((artTop + artBottom) / 2),
    captionBottom: Math.round(height * 0.058), // ~111
    captionMaxW: width - safeX * 2,
    captionFontSize: Math.round(width * 0.042), // ~45
    penScale: 0.96,
  };
};

export const useCanvas = (): CanvasLayout => {
  const { width, height, fps } = useVideoConfig();
  return getCanvasLayout(width, height, fps);
};

/* ------------------------------------------------------------------ */
/* Deterministic pseudo-randomness & motion helpers                    */
/* ------------------------------------------------------------------ */

export const rnd = (seed: string | number, salt = 0): number =>
  random(`wb-${seed}-${salt}`);

/** Frames for item `i` of `n` to appear, spread across `duration`. */
export const step = (
  durationInFrames: number,
  i: number,
  n: number,
  offset = 0,
): number =>
  Math.round(
    offset +
      durationInFrames * 0.62 * (n <= 1 ? 0 : i / Math.max(1, n - 1)),
  );

/** Smooth ease-out pop-in progress for an element that starts at `at`. */
export const pop = (frame: number, at: number, speed = 8): number =>
  interpolate(frame, [at, at + speed], [0, 1], {
    extrapolateLeft: "clamp",
    extrapolateRight: "clamp",
    easing: Easing.out(Easing.cubic),
  });

/** Spring physics pop-in for cards, icons, badges (0 -> ~1.03 -> 1). */
export const springPop = (
  frame: number,
  fps: number,
  at: number,
  config: { damping?: number; stiffness?: number; mass?: number } = {},
): number => {
  if (frame < at) return 0;
  return spring({
    frame: frame - at,
    fps,
    config: {
      damping: config.damping ?? 15,
      stiffness: config.stiffness ?? 170,
      mass: config.mass ?? 0.75,
    },
  });
};

/** Subtle deterministic ambient float so settled elements stay alive. */
export const floatY = (
  frame: number,
  seed: number | string = 1,
  amp = 3.5,
  period = 54,
): number => {
  const phase = rnd(seed, 91) * Math.PI * 2;
  return Math.sin((frame / period) * Math.PI * 2 + phase) * amp;
};

export const drawProgress = (
  frame: number,
  at: number,
  duration: number,
): number =>
  interpolate(frame, [at, at + Math.max(1, duration)], [0, 1], {
    extrapolateLeft: "clamp",
    extrapolateRight: "clamp",
    easing: Easing.inOut(Easing.cubic),
  });

/* ------------------------------------------------------------------ */
/* Paper backdrop + progress chrome                                    */
/* ------------------------------------------------------------------ */

export const Paper: React.FC<{
  seed: string | number;
  children: React.ReactNode;
  durationInFrames: number;
  sceneIndex?: number;
  totalScenes?: number;
  globalStartFrame?: number;
  totalDurationInFrames?: number;
  accent?: string;
}> = ({
  seed,
  children,
  durationInFrames,
  sceneIndex,
  totalScenes,
  globalStartFrame = 0,
  totalDurationInFrames,
  accent = P.blue,
}) => {
  const frame = useCurrentFrame();
  const canvas = useCanvas();

  const enterOpacity = interpolate(frame, [0, 7], [0, 1], {
    extrapolateLeft: "clamp",
    extrapolateRight: "clamp",
  });
  const exitOpacity = interpolate(
    frame,
    [Math.max(8, durationInFrames - 7), durationInFrames],
    [1, 0],
    { extrapolateLeft: "clamp", extrapolateRight: "clamp" },
  );
  const enterY = interpolate(frame, [0, 16], [18, 0], {
    extrapolateLeft: "clamp",
    extrapolateRight: "clamp",
    easing: Easing.out(Easing.cubic),
  });
  const enterScale = interpolate(frame, [0, 16], [0.992, 1], {
    extrapolateLeft: "clamp",
    extrapolateRight: "clamp",
    easing: Easing.out(Easing.cubic),
  });

  const progress =
    totalDurationInFrames && totalDurationInFrames > 0
      ? clamp01((globalStartFrame + frame) / totalDurationInFrames)
      : clamp01(frame / Math.max(1, durationInFrames));

  return (
    <AbsoluteFill
      style={{
        background: `radial-gradient(ellipse at 50% 35%, ${P.paper} 0%, ${P.paperWarm} 100%)`,
        fontFamily: FONT,
        color: P.ink,
        opacity: enterOpacity * exitOpacity,
      }}
    >
      <PaperBackdrop
        seed={seed}
        width={canvas.width}
        height={canvas.height}
        isLandscape={canvas.isLandscape}
        progress={progress}
        accent={accent}
        sceneIndex={sceneIndex}
        totalScenes={totalScenes}
      />
      <AbsoluteFill
        style={{
          transform: `translateY(${enterY}px) scale(${enterScale})`,
          transformOrigin: "center center",
        }}
      >
        {children}
      </AbsoluteFill>
    </AbsoluteFill>
  );
};

const PaperBackdrop: React.FC<{
  seed: string | number;
  width: number;
  height: number;
  isLandscape: boolean;
  progress: number;
  accent: string;
  sceneIndex?: number;
  totalScenes?: number;
}> = ({
  seed,
  width,
  height,
  isLandscape,
  progress,
  accent,
  sceneIndex,
  totalScenes,
}) => {
  const margin = isLandscape ? 36 : 40;
  const tick = 22;
  const patternId = `dot-grid-${seed}`;
  const r1 = rnd(seed, 11);

  return (
    <AbsoluteFill style={{ pointerEvents: "none" }}>
      <svg width="100%" height="100%" viewBox={`0 0 ${width} ${height}`}>
        <defs>
          <pattern
            id={patternId}
            width="36"
            height="36"
            patternUnits="userSpaceOnUse"
          >
            <circle cx="18" cy="18" r="1.45" fill={P.dotGrid} opacity="0.65" />
          </pattern>
        </defs>

        {/* Subtle sketchbook dot grid */}
        <rect x={0} y={0} width={width} height={height} fill={`url(#${patternId})`} />

        {/* Subtle soft paper inner border */}
        <rect
          x={margin}
          y={margin}
          width={width - margin * 2}
          height={height - margin * 2}
          rx={24}
          fill="none"
          stroke={P.paperEdge}
          strokeWidth={1.6}
          opacity={0.75}
        />

        {/* Corner registration marks */}
        {(
          [
            [margin + 14, margin + 14, 1, 1],
            [width - margin - 14, margin + 14, -1, 1],
            [margin + 14, height - margin - 14, 1, -1],
            [width - margin - 14, height - margin - 14, -1, -1],
          ] as const
        ).map(([x, y, dx, dy], i) => (
          <g
            key={i}
            stroke={P.inkFaint}
            strokeWidth={2}
            strokeLinecap="round"
            opacity={0.45}
          >
            <path d={`M ${x} ${y} L ${x + tick * dx} ${y}`} />
            <path d={`M ${x} ${y} L ${x} ${y + tick * dy}`} />
          </g>
        ))}

        {/* Very subtle warm accent wash in one corner */}
        <circle
          cx={r1 > 0.5 ? width * 0.88 : width * 0.12}
          cy={height * 0.18}
          r={isLandscape ? 240 : 210}
          fill={accent}
          opacity={0.035}
        />

        {/* Top global progress bar */}
        <rect
          x={0}
          y={0}
          width={width}
          height={6}
          fill={P.paperEdge}
          opacity={0.7}
        />
        <rect
          x={0}
          y={0}
          width={Math.max(8, width * progress)}
          height={6}
          rx={3}
          fill={accent}
          opacity={0.88}
        />

        {/* Subtle scene counter pill at top-right */}
        {typeof sceneIndex === "number" && typeof totalScenes === "number" && totalScenes > 1 ? (
          <g transform={`translate(${width - margin - 110} ${margin + 16})`}>
            <rect
              x={0}
              y={0}
              width={92}
              height={34}
              rx={17}
              fill={P.white}
              fillOpacity={0.82}
              stroke={P.paperEdge}
              strokeWidth={1.5}
            />
            <text
              x={46}
              y={17.5}
              textAnchor="middle"
              dominantBaseline="central"
              fontFamily={MONO}
              fontSize={16}
              fontWeight={700}
              fill={P.inkSoft}
            >
              {String(sceneIndex + 1).padStart(2, "0")}/{String(totalScenes).padStart(2, "0")}
            </text>
          </g>
        ) : null}
      </svg>
    </AbsoluteFill>
  );
};

/* ------------------------------------------------------------------ */
/* Title (responsive, with optional chapter kicker + highlighter)      */
/* ------------------------------------------------------------------ */

export const Title: React.FC<{
  text: string;
  kicker?: string;
  accent?: string;
  at?: number;
  seed?: number;
}> = ({ text, kicker, accent = P.blue, at = 0, seed = 1 }) => {
  const frame = useCurrentFrame();
  const canvas = useCanvas();
  const p = springPop(frame, canvas.fps, at, { damping: 16, stiffness: 160 });
  const hiP = drawProgress(frame, at + 5, 14);

  const approxCharW = canvas.titleSize * 0.88;
  const maxWide = canvas.artW * 0.86;
  const wide = Math.min(text.length * approxCharW, maxWide);

  return (
    <g opacity={clamp01(p * 1.4)} transform={`translate(0 ${(1 - clamp01(p)) * 12})`}>
      {kicker ? (
        <g transform={`translate(${canvas.cx} ${canvas.kickerY})`}>
          {(() => {
            const kSize = canvas.isLandscape ? 20 : 22;
            const kW =
              Array.from(kicker).reduce(
                (acc, ch) => acc + (/[\u4e00-\u9fff]/.test(ch) ? kSize * 1.05 : kSize * 0.62),
                0,
              ) + 44;
            return (
              <>
                <rect
                  x={-Math.max(120, kW) / 2}
                  y={-20}
                  width={Math.max(120, kW)}
                  height={40}
                  rx={20}
                  fill={`${accent}18`}
                  stroke={`${accent}55`}
                  strokeWidth={1.8}
                />
                <text
                  x={0}
                  y={0.5}
                  textAnchor="middle"
                  dominantBaseline="central"
                  fontFamily={FONT}
                  fontSize={kSize}
                  fontWeight={700}
                  fill={accent}
                  letterSpacing={1.2}
                >
                  {kicker}
                </text>
              </>
            );
          })()}
        </g>
      ) : null}

      {/* Soft highlighter wash behind bottom half of title */}
      <rect
        x={canvas.cx - wide / 2 - 12}
        y={canvas.titleY - canvas.titleSize * 0.12}
        width={(wide + 24) * hiP}
        height={canvas.titleSize * 0.48}
        rx={10}
        fill={P.marker}
        opacity={0.42}
      />

      <text
        x={canvas.cx}
        y={canvas.titleY}
        textAnchor="middle"
        dominantBaseline="central"
        fontFamily={FONT}
        fontSize={canvas.titleSize}
        fontWeight={800}
        fill={P.ink}
        letterSpacing={1.5}
      >
        {text}
      </text>

      <SketchLine
        d={wobblyLine(
          canvas.cx - wide / 2,
          canvas.underlineY,
          canvas.cx + wide / 2,
          canvas.underlineY,
          3.2,
          seed,
        )}
        at={at + 4}
        durationInFrames={13}
        color={accent}
        strokeWidth={7}
        seed={seed}
        opacity={0.92}
      />
    </g>
  );
};

/* ------------------------------------------------------------------ */
/* SVG-native Studio Whiteboard Cards & Diagrams                       */
/* ------------------------------------------------------------------ */

/**
 * Layered hand-drawn paper card (pure SVG — works inside `art: <g>`).
 * Includes soft offset shadow, crisp fill, optional top accent bar,
 * optional corner badge (e.g. "01"), and spring pop-in animation.
 */
export const PaperCard: React.FC<{
  x: number;
  y: number;
  w: number;
  h: number;
  at?: number;
  accent?: string;
  fill?: string;
  badge?: string;
  radius?: number;
  rotate?: number;
  seed?: number;
  topStrip?: boolean;
  float?: boolean;
  children?: React.ReactNode;
}> = ({
  x,
  y,
  w,
  h,
  at = 0,
  accent = P.blue,
  fill = P.white,
  badge,
  radius = 22,
  rotate = 0,
  seed = 1,
  topStrip = true,
  float = false,
  children,
}) => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  const p = springPop(frame, fps, at, { damping: 15, stiffness: 175 });
  if (p <= 0.01) return null;

  const dy = float ? floatY(frame, seed, 3, 64) : 0;
  const cx = x + w / 2;
  const cy = y + h / 2;
  const scale = 0.88 + p * 0.12;

  return (
    <g
      transform={`translate(${cx} ${cy + dy}) rotate(${rotate}) scale(${scale}) translate(${-cx} ${-cy})`}
      opacity={clamp01(p * 1.4)}
    >
      {/* Offset paper shadow */}
      <rect
        x={x + 7}
        y={y + 9}
        width={w}
        height={h}
        rx={radius}
        fill={P.shadow}
      />
      {/* Main card body */}
      <rect
        x={x}
        y={y}
        width={w}
        height={h}
        rx={radius}
        fill={fill}
        stroke={accent}
        strokeWidth={3.2}
        filter={wob(seed)}
      />
      {/* Optional colored top accent strip */}
      {topStrip ? (
        <path
          d={`M ${x + radius} ${y + 2} H ${x + w - radius} Q ${x + w - 2} ${y + 2} ${x + w - 2} ${y + 14} H ${x + 2} Q ${x + 2} ${y + 2} ${x + radius} ${y + 2} Z`}
          fill={accent}
          opacity={0.85}
        />
      ) : null}
      {/* Optional top-right corner badge */}
      {badge ? (
        <g transform={`translate(${x + w - 18} ${y + 18})`}>
          <rect
            x={-Math.max(46, badge.length * 14 + 18)}
            y={0}
            width={Math.max(46, badge.length * 14 + 18)}
            height={34}
            rx={17}
            fill={`${accent}1c`}
            stroke={`${accent}55`}
            strokeWidth={1.6}
          />
          <text
            x={-Math.max(46, badge.length * 14 + 18) / 2}
            y={17.5}
            textAnchor="middle"
            dominantBaseline="central"
            fontFamily={MONO}
            fontSize={18}
            fontWeight={800}
            fill={accent}
          >
            {badge}
          </text>
        </g>
      ) : null}
      {children}
    </g>
  );
};

/**
 * Post-it style sticky note with a translucent tape strip at the top.
 * Great for concrete examples, pain points, quotes, and quick tips.
 */
export const StickyNote: React.FC<{
  x: number;
  y: number;
  w: number;
  h: number;
  at?: number;
  fill?: string;
  accent?: string;
  rotate?: number;
  seed?: number;
  children?: React.ReactNode;
}> = ({
  x,
  y,
  w,
  h,
  at = 0,
  fill = "#fff8cf",
  accent = P.amber,
  rotate = -1.6,
  seed = 2,
  children,
}) => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  const p = springPop(frame, fps, at, { damping: 14, stiffness: 165 });
  if (p <= 0.01) return null;

  const cx = x + w / 2;
  const cy = y + h / 2;
  const fold = 26;

  return (
    <g
      transform={`translate(${cx} ${cy}) rotate(${rotate}) scale(${0.86 + p * 0.14}) translate(${-cx} ${-cy})`}
      opacity={clamp01(p * 1.4)}
    >
      {/* Shadow */}
      <rect x={x + 8} y={y + 10} width={w} height={h} rx={10} fill={P.shadow} />
      {/* Note body with subtle folded bottom-right corner */}
      <path
        d={`M ${x} ${y} H ${x + w} V ${y + h - fold} L ${x + w - fold} ${y + h} H ${x} Z`}
        fill={fill}
        stroke={accent}
        strokeWidth={2.4}
        strokeOpacity={0.6}
        filter={wob(seed)}
      />
      {/* Folded corner triangle */}
      <path
        d={`M ${x + w - fold} ${y + h} L ${x + w - fold} ${y + h - fold} L ${x + w} ${y + h - fold} Z`}
        fill={accent}
        opacity={0.18}
      />
      {/* Translucent top tape */}
      <rect
        x={cx - 46}
        y={y - 12}
        width={92}
        height={24}
        rx={4}
        fill="rgba(255,255,255,0.68)"
        stroke="rgba(180,165,130,0.45)"
        strokeWidth={1.2}
      />
      {children}
    </g>
  );
};

/** Rounded pill badge (pure SVG) for tags, entity types, and callout labels. */
export const PillBadge: React.FC<{
  x: number;
  y: number;
  text: string;
  at?: number;
  color?: string;
  fill?: string;
  size?: number;
  icon?: IconName;
  align?: "center" | "left";
}> = ({
  x,
  y,
  text,
  at = 0,
  color = P.blue,
  fill,
  size = 24,
  icon,
  align = "center",
}) => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  const p = springPop(frame, fps, at, { damping: 14, stiffness: 190 });
  if (p <= 0.01) return null;

  const estW =
    Array.from(text).reduce((acc, ch) => acc + (/[\u4e00-\u9fff]/.test(ch) ? size * 1.02 : size * 0.6), 0) +
    (icon ? size * 1.45 : 0) +
    36;
  const h = size * 1.75;
  const left = align === "center" ? x - estW / 2 : x;
  const bg = fill ?? `${color}18`;

  return (
    <g
      transform={`translate(${x} ${y}) scale(${0.8 + p * 0.2}) translate(${-x} ${-y})`}
      opacity={clamp01(p * 1.4)}
    >
      <rect
        x={left}
        y={y - h / 2}
        width={estW}
        height={h}
        rx={h / 2}
        fill={bg}
        stroke={color}
        strokeWidth={2.2}
        strokeOpacity={0.55}
      />
      {icon ? (
        <Icon
          name={icon}
          x={left + 22 + size * 0.35}
          y={y}
          size={size * 0.92}
          color={color}
          at={0}
        />
      ) : null}
      <text
        x={left + (icon ? 24 + size * 0.95 : estW / 2)}
        y={y + 1}
        textAnchor={icon ? "start" : "middle"}
        dominantBaseline="central"
        fontFamily={FONT}
        fontSize={size}
        fontWeight={700}
        fill={color}
      >
        {text}
      </text>
    </g>
  );
};

/** Animated highlighter sweep behind key text or diagrams. */
export const MarkerHighlight: React.FC<{
  x: number;
  y: number;
  w: number;
  h?: number;
  at: number;
  duration?: number;
  color?: string;
  opacity?: number;
}> = ({
  x,
  y,
  w,
  h = 28,
  at,
  duration = 14,
  color = P.marker,
  opacity = 0.55,
}) => {
  const frame = useCurrentFrame();
  const p = drawProgress(frame, at, duration);
  if (p <= 0.01) return null;
  return (
    <rect
      x={x}
      y={y - h / 2}
      width={w * p}
      height={h}
      rx={h * 0.32}
      fill={color}
      opacity={opacity}
    />
  );
};

/**
 * Animated hand-drawn connector arrow with an optional moving signal pulse.
 * Supports straight or gently curved paths + optional mid-point pill label.
 */
export const FlowArrow: React.FC<{
  x1: number;
  y1: number;
  x2: number;
  y2: number;
  at: number;
  duration?: number;
  color?: string;
  strokeWidth?: number;
  curve?: number;
  label?: string;
  pulse?: boolean;
  dashed?: boolean;
  seed?: number;
}> = ({
  x1,
  y1,
  x2,
  y2,
  at,
  duration = 14,
  color = P.inkSoft,
  strokeWidth = 3.8,
  curve = 0,
  label,
  pulse = false,
  dashed = false,
  seed = 1,
}) => {
  const frame = useCurrentFrame();
  const p = drawProgress(frame, at, duration);
  if (p <= 0.01) return null;

  const mx = (x1 + x2) / 2;
  const my = (y1 + y2) / 2;
  const dx = x2 - x1;
  const dy = y2 - y1;
  const len = Math.hypot(dx, dy) || 1;
  const nx = -dy / len;
  const ny = dx / len;
  const cx = mx + nx * curve;
  const cy = my + ny * curve;

  const d =
    Math.abs(curve) > 1
      ? `M ${x1} ${y1} Q ${cx} ${cy} ${x2} ${y2}`
      : wobblyLine(x1, y1, x2, y2, 2.5, seed);

  // Tangent angle at end of quadratic Bezier
  const endAngle = Math.atan2(y2 - cy, x2 - cx);

  // Moving signal dot along quadratic Bezier after line finishes drawing
  const pulseActive = pulse && frame >= at + duration;
  const t = pulseActive ? ((frame - (at + duration)) % 42) / 42 : 0;
  const px = (1 - t) * (1 - t) * x1 + 2 * (1 - t) * t * cx + t * t * x2;
  const py = (1 - t) * (1 - t) * y1 + 2 * (1 - t) * t * cy + t * t * y2;

  const labelX = 0.25 * x1 + 0.5 * cx + 0.25 * x2;
  const labelY = 0.25 * y1 + 0.5 * cy + 0.25 * y2;

  return (
    <g>
      <SketchLine
        d={d}
        at={at}
        durationInFrames={duration}
        color={color}
        strokeWidth={strokeWidth}
        seed={seed}
        dash={dashed ? "10 8" : undefined}
      />
      <ArrowHead
        x={x2}
        y={y2}
        angle={endAngle}
        at={at + Math.max(2, duration - 3)}
        color={color}
        size={17}
        seed={seed}
      />
      {pulseActive ? (
        <g>
          <circle cx={px} cy={py} r={9} fill={color} opacity={0.22} />
          <circle cx={px} cy={py} r={4.8} fill={color} />
        </g>
      ) : null}
      {label ? (
        <PillBadge
          x={labelX}
          y={labelY}
          text={label}
          at={at + Math.round(duration * 0.6)}
          color={color}
          fill={P.white}
          size={20}
        />
      ) : null}
    </g>
  );
};

/** Knowledge-graph / architecture node with icon, soft halo, and label. */
export const GraphNode: React.FC<{
  x: number;
  y: number;
  label: string;
  sublabel?: string;
  icon?: IconName;
  r?: number;
  at?: number;
  color?: string;
  fill?: string;
  seed?: number;
  pulse?: boolean;
}> = ({
  x,
  y,
  label,
  sublabel,
  icon,
  r = 56,
  at = 0,
  color = P.blue,
  fill = P.white,
  seed = 1,
  pulse = false,
}) => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  const p = springPop(frame, fps, at, { damping: 14, stiffness: 180 });
  if (p <= 0.01) return null;

  const halo = pulse ? 1 + Math.sin((frame - at) / 10) * 0.08 : 1;
  const dy = floatY(frame, seed, 2.5, 58);

  return (
    <g
      transform={`translate(${x} ${y + dy}) scale(${p})`}
      opacity={clamp01(p * 1.4)}
    >
      {pulse ? (
        <circle
          cx={0}
          cy={0}
          r={r * 1.24 * halo}
          fill={color}
          opacity={0.12}
        />
      ) : null}
      <circle cx={5} cy={7} r={r} fill={P.shadow} />
      <circle
        cx={0}
        cy={0}
        r={r}
        fill={fill}
        stroke={color}
        strokeWidth={3.6}
        filter={wob(seed)}
      />
      {icon ? (
        <Icon name={icon} x={0} y={-8} size={r * 0.66} color={color} at={0} />
      ) : null}
      <text
        x={0}
        y={icon ? r * 0.44 : 0}
        textAnchor="middle"
        dominantBaseline="central"
        fontFamily={FONT}
        fontSize={icon ? 22 : 26}
        fontWeight={800}
        fill={P.ink}
      >
        {label}
      </text>
      {sublabel ? (
        <text
          x={0}
          y={r + 24}
          textAnchor="middle"
          dominantBaseline="central"
          fontFamily={FONT}
          fontSize={20}
          fontWeight={600}
          fill={P.inkSoft}
        >
          {sublabel}
        </text>
      ) : null}
    </g>
  );
};

/** Animated horizontal data bar with count-up value label. */
export const DataBar: React.FC<{
  x: number;
  y: number;
  w: number;
  h?: number;
  ratio: number;
  label: string;
  valueText?: string;
  at: number;
  color?: string;
  seed?: number;
}> = ({
  x,
  y,
  w,
  h = 44,
  ratio,
  label,
  valueText,
  at,
  color = P.blue,
  seed = 1,
}) => {
  const frame = useCurrentFrame();
  const p = drawProgress(frame, at, 18);
  const labelP = pop(frame, at - 2, 7);
  const fillW = Math.max(h * 0.5, w * clamp01(ratio) * p);

  return (
    <g opacity={labelP}>
      <text
        x={x}
        y={y - 18}
        fontFamily={FONT}
        fontSize={25}
        fontWeight={700}
        fill={P.ink}
      >
        {label}
      </text>
      {valueText ? (
        <text
          x={x + w}
          y={y - 18}
          textAnchor="end"
          fontFamily={MONO}
          fontSize={26}
          fontWeight={800}
          fill={color}
        >
          {valueText}
        </text>
      ) : null}
      {/* Track */}
      <rect
        x={x}
        y={y}
        width={w}
        height={h}
        rx={h / 2}
        fill={P.paperEdge}
        opacity={0.55}
      />
      {/* Animated fill */}
      {p > 0.01 ? (
        <rect
          x={x}
          y={y}
          width={fillW}
          height={h}
          rx={h / 2}
          fill={color}
          filter={wob(seed)}
        />
      ) : null}
    </g>
  );
};

/** Animated numeric counter (pure SVG text). */
export const CountUp: React.FC<{
  x: number;
  y: number;
  from?: number;
  to: number;
  at: number;
  duration?: number;
  prefix?: string;
  suffix?: string;
  decimals?: number;
  size?: number;
  color?: string;
  align?: "left" | "center" | "right";
}> = ({
  x,
  y,
  from = 0,
  to,
  at,
  duration = 20,
  prefix = "",
  suffix = "",
  decimals = 0,
  size = 56,
  color = P.blue,
  align = "center",
}) => {
  const frame = useCurrentFrame();
  const p = drawProgress(frame, at, duration);
  if (frame < at) return null;
  const val = from + (to - from) * p;
  const formatted = decimals > 0 ? val.toFixed(decimals) : String(Math.round(val));
  return (
    <text
      x={x}
      y={y}
      textAnchor={align === "center" ? "middle" : align === "right" ? "end" : "start"}
      dominantBaseline="central"
      fontFamily={MONO}
      fontSize={size}
      fontWeight={800}
      fill={color}
      opacity={clamp01(p * 2)}
    >
      {`${prefix}${formatted}${suffix}`}
    </text>
  );
};

/** Self-drawing green check mark (optionally inside a soft circular badge). */
export const CheckMark: React.FC<{
  x: number;
  y: number;
  size?: number;
  at: number;
  color?: string;
  badge?: boolean;
}> = ({ x, y, size = 32, at, color = P.green, badge = true }) => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  const bgP = springPop(frame, fps, at, { damping: 14, stiffness: 200 });
  if (bgP <= 0.01) return null;
  const r = size * 0.72;
  const d = `M ${x - size * 0.36} ${y + size * 0.04} L ${x - size * 0.08} ${y + size * 0.32} L ${x + size * 0.4} ${y - size * 0.28}`;
  return (
    <g>
      {badge ? (
        <circle
          cx={x}
          cy={y}
          r={r * bgP}
          fill={P.greenSoft}
          stroke={color}
          strokeWidth={2.4}
          opacity={clamp01(bgP * 1.4)}
        />
      ) : null}
      <SketchLine
        d={d}
        at={at + 3}
        durationInFrames={9}
        color={color}
        strokeWidth={4.5}
        seed={1}
      />
    </g>
  );
};

/** Self-drawing red cross mark (optionally inside a soft circular badge). */
export const CrossMark: React.FC<{
  x: number;
  y: number;
  size?: number;
  at: number;
  color?: string;
  badge?: boolean;
}> = ({ x, y, size = 32, at, color = P.red, badge = true }) => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  const bgP = springPop(frame, fps, at, { damping: 14, stiffness: 200 });
  if (bgP <= 0.01) return null;
  const r = size * 0.72;
  const k = size * 0.3;
  return (
    <g>
      {badge ? (
        <circle
          cx={x}
          cy={y}
          r={r * bgP}
          fill={P.redSoft}
          stroke={color}
          strokeWidth={2.4}
          opacity={clamp01(bgP * 1.4)}
        />
      ) : null}
      <SketchLine
        d={`M ${x - k} ${y - k} L ${x + k} ${y + k}`}
        at={at + 2}
        durationInFrames={6}
        color={color}
        strokeWidth={4.5}
        seed={2}
      />
      <SketchLine
        d={`M ${x + k} ${y - k} L ${x - k} ${y + k}`}
        at={at + 6}
        durationInFrames={6}
        color={color}
        strokeWidth={4.5}
        seed={3}
      />
    </g>
  );
};

/** Bottom / inline takeaway callout banner with icon and highlighter accent. */
export const CalloutBanner: React.FC<{
  x: number;
  y: number;
  w: number;
  h?: number;
  text: string;
  at: number;
  accent?: string;
  icon?: IconName;
  seed?: number;
}> = ({
  x,
  y,
  w,
  h = 84,
  text,
  at,
  accent = P.blue,
  icon = "lightbulb",
  seed = 2,
}) => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  const p = springPop(frame, fps, at, { damping: 15, stiffness: 170 });
  if (p <= 0.01) return null;

  return (
    <g
      transform={`translate(${x + w / 2} ${y + h / 2}) scale(${0.92 + p * 0.08}) translate(${-(x + w / 2)} ${-(y + h / 2)})`}
      opacity={clamp01(p * 1.4)}
    >
      <rect x={x + 5} y={y + 6} width={w} height={h} rx={18} fill={P.shadow} />
      <rect
        x={x}
        y={y}
        width={w}
        height={h}
        rx={18}
        fill={`${accent}14`}
        stroke={accent}
        strokeWidth={2.8}
        filter={wob(seed)}
      />
      <rect x={x + 2} y={y + 10} width={8} height={h - 20} rx={4} fill={accent} />
      <Icon
        name={icon}
        x={x + 48}
        y={y + h / 2}
        size={34}
        color={accent}
        at={0}
      />
      <text
        x={x + 84}
        y={y + h / 2 + 1}
        dominantBaseline="central"
        fontFamily={FONT}
        fontSize={28}
        fontWeight={700}
        fill={P.ink}
      >
        {text}
      </text>
    </g>
  );
};

/* ------------------------------------------------------------------ */
/* Legacy SketchBox (HTML wrapper, kept for backwards compatibility)   */
/* ------------------------------------------------------------------ */

/** Rounded paper card with a wobbly, hand-drawn outline (HTML layer). */
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
  if (p <= 0.005) return null;
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
      opacity={dash ? opacity * p : opacity}
      filter={wob(seed)}
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
}> = ({ x, y, angle, at, color = P.ink, size = 18, seed = 1 }) => {
  const frame = useCurrentFrame();
  const p = pop(frame, at, 5);
  if (p <= 0) return null;
  const a1 = angle + Math.PI - 0.52;
  const a2 = angle + Math.PI + 0.52;
  return (
    <g
      transform={`translate(${x} ${y}) scale(${0.6 + p * 0.4})`}
      opacity={p}
      filter={wob(seed)}
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
/* Fountain Pen (user-supplied artwork, nib locked at (0,0))           */
/* ------------------------------------------------------------------ */

export type PenKey = { at: number; x: number; y: number; skip?: boolean };
/** @deprecated Kept so older scene files that import `HandKey` still compile. */
export type HandKey = PenKey;

export const Pen: React.FC<{
  keys: PenKey[];
  hidden?: boolean;
  scale?: number;
  opacity?: number;
}> = ({ keys, hidden = false, scale, opacity = 1 }) => {
  const frame = useCurrentFrame();
  const canvas = useCanvas();

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

  const tilt = -2 + Math.sin(frame / 14) * 1.2;
  const s = 1.55 * (scale ?? canvas.penScale);

  return (
    <g transform={`translate(${x} ${y})`} opacity={fadeIn * fadeOut}>
      {/* Soft projected shadow on paper */}
      <g transform={`translate(10 14) rotate(${tilt}) scale(${s})`} opacity={0.14}>
        <g transform="translate(60.104 -60.104) rotate(45)">
          <rect x={-12} y={-80} width={24} height={114} rx={6} fill="#0f172a" />
          <path d="M -12 34 L 12 34 L 0 85 Z" fill="#0f172a" />
        </g>
      </g>
      {/* Pen body */}
      <g transform={`rotate(${tilt}) scale(${s})`}>
        <g transform="translate(60.104 -60.104) rotate(45)">
          <rect x={-12} y={-80} width={24} height={110} rx={6} fill="url(#pen-body)" />
          <path
            d="M 0 -80 L 20 -75 L 20 -40 L 12 -40 L 12 -70 L 0 -70 Z"
            fill="#94a3b8"
            stroke="#475569"
            strokeWidth={1}
          />
          <rect x={-13} y={28} width={26} height={6} rx={2} fill="url(#pen-ring)" />
          <path d="M -12 34 L 12 34 L 8 60 L -8 60 Z" fill="url(#pen-tip)" />
          <path d="M -8 60 L 8 60 L 0 85 Z" fill="#d4d4d8" stroke="#71717a" strokeWidth={0.5} />
          <line x1={0} y1={60} x2={0} y2={80} stroke="#3f3f46" strokeWidth={1} />
          <circle cx={0} cy={65} r={1.5} fill="#3f3f46" />
          <circle cx={0} cy={84} r={1.2} fill="#1e293b" />
          <rect x={-6} y={-75} width={4} height={95} rx={2} fill="#ffffff" opacity={0.18} />
        </g>
      </g>
    </g>
  );
};

/** @deprecated New scenes should use `<Pen>`. */
export const Hand = Pen;

/* ------------------------------------------------------------------ */
/* Caption driven by the real narration timing (responsive)            */
/* ------------------------------------------------------------------ */

export type LineCue = { text: string; startFrame: number; endFrame: number };

export const Lines: React.FC<{
  lines: LineCue[];
  accent?: string;
  size?: number;
  bottom?: number;
}> = ({ lines, accent = P.blue, size, bottom }) => {
  const frame = useCurrentFrame();
  const canvas = useCanvas();
  if (lines.length === 0) return null;

  let active = 0;
  for (let i = lines.length - 1; i >= 0; i--) {
    if (frame >= lines[i].startFrame - 5) {
      active = i;
      break;
    }
  }

  const line = lines[active];
  const local = frame - line.startFrame;
  const p = interpolate(local, [0, 8], [0, 1], {
    extrapolateLeft: "clamp",
    extrapolateRight: "clamp",
    easing: Easing.out(Easing.cubic),
  });
  const lineDur = Math.max(1, line.endFrame - line.startFrame);
  const readProgress = clamp01(local / lineDur);

  const fontSize = size ?? canvas.captionFontSize;
  const bottomPos = bottom ?? canvas.captionBottom;
  const pad = canvas.isLandscape ? "20px 38px 24px 42px" : "26px 36px 30px 40px";

  return (
    <div
      style={{
        position: "absolute",
        left: canvas.safeX,
        right: canvas.safeX,
        bottom: bottomPos,
        display: "flex",
        justifyContent: "center",
        pointerEvents: "none",
      }}
    >
      <div
        style={{
          position: "relative",
          maxWidth: canvas.captionMaxW,
          textAlign: "center",
          fontSize,
          lineHeight: 1.44,
          fontWeight: 600,
          letterSpacing: 0.4,
          color: P.ink,
          padding: pad,
          background: "rgba(255, 255, 255, 0.88)",
          borderRadius: 24,
          boxShadow: "0 14px 36px rgba(28, 33, 43, 0.11), 0 2px 6px rgba(28, 33, 43, 0.05)",
          border: `2px solid ${P.paperEdge}`,
          transform: `translateY(${(1 - p) * 14}px)`,
          opacity: p,
          overflow: "hidden",
        }}
      >
        {/* Colored left accent bar */}
        <div
          style={{
            position: "absolute",
            left: 0,
            top: 0,
            bottom: 0,
            width: 8,
            backgroundColor: accent,
          }}
        />
        <span key={active}>
          {renderSegments(line.text, frame, accent, line.startFrame)}
        </span>
        {/* Subtle reading progress bar along bottom edge */}
        <div
          style={{
            position: "absolute",
            left: 8,
            right: 0,
            bottom: 0,
            height: 4,
            background: `${accent}16`,
          }}
        >
          <div
            style={{
              width: `${readProgress * 100}%`,
              height: "100%",
              background: accent,
              opacity: 0.65,
            }}
          />
        </div>
      </div>
    </div>
  );
};

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
      const at = startFrame + 5 + i * 4;
      const p = interpolate(frame, [at, at + 11], [0, 1], {
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
            padding: "0 5px",
            margin: "0 1px",
            color: P.ink,
            fontWeight: 800,
          }}
        >
          <span
            style={{
              position: "absolute",
              left: 0,
              top: "14%",
              height: "78%",
              width: `${p * 100}%`,
              background: `linear-gradient(90deg, ${P.marker}cc, ${accent}33)`,
              borderRadius: 7,
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
  if (p <= 0) return null;
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
 * Supports multi-line strings separated by `\n`.
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
  lineHeight?: number;
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
  lineHeight = 1.35,
}) => {
  const frame = useCurrentFrame();
  const p = pop(frame, at, 8);
  if (p <= 0) return null;
  const dx = (1 - p) * 12 * (align === "right" ? 1 : -1);
  const lines = text.split("\n");
  const stepY = size * lineHeight;
  const startY = y - ((lines.length - 1) * stepY) / 2;

  return (
    <text
      x={x + dx}
      y={startY}
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
      {lines.length === 1
        ? text
        : lines.map((ln, idx) => (
            <tspan key={idx} x={x + dx} dy={idx === 0 ? 0 : stepY}>
              {ln}
            </tspan>
          ))}
    </text>
  );
};
