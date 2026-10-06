/**
 * Scene illustrations.
 *
 * This is the file the skill edits for every new video. Each entry in VISUALS
 * is one illustrated beat: it receives the scene's duration and the local
 * frame range of each narration line, and returns
 *
 *   - `art`: the SVG drawn on paper
 *   - `keys`: where the pen tip travels, as `{ at, x, y }` frames
 *
 * The hand is authored so its nib sits exactly on (x, y), so anchors should
 * point at the ink that is appearing at that frame.
 *
 * Everything comes from the shared whiteboard kit — see references/02-scenes.md
 * for the full API.
 */

import React from "react";
import { Easing, interpolate, useCurrentFrame } from "remotion";
import {
  type HandKey,
  type LineCue,
  Hand,
  Label,
  Lines,
  P,
  Paper,
  SketchLine,
  W,
  wobblyLine,
} from "./whiteboard/kit";

export type SceneProps = {
  durationInFrames: number;
  lines: LineCue[];
  seed: number;
};

type Visual = (p: SceneProps) => { art: React.ReactNode; keys: HandKey[] };

const wob = (n: number) => `url(#sketch-wobble-${((n - 1) % 3) + 1})`;
const clamp01 = (v: number) => Math.max(0, Math.min(1, v));

const Title: React.FC<{ text: string; accent?: string; at?: number; seed?: number }> = ({
  text,
  accent = P.blue,
  at = 0,
  seed = 1,
}) => {
  const frame = useCurrentFrame();
  const p = interpolate(frame, [at, at + 12], [0, 1], {
    extrapolateLeft: "clamp",
    extrapolateRight: "clamp",
    easing: Easing.out(Easing.cubic),
  });
  const wide = Math.min(text.length * 58, 900);
  return (
    <g opacity={p}>
      <text
        x={W / 2}
        y={228}
        textAnchor="middle"
        fontFamily='"Noto Sans SC", "Noto Sans CJK SC", "Source Han Sans SC", "PingFang SC", "Hiragino Sans GB", sans-serif'
        fontSize={70}
        fontWeight={800}
        fill={P.ink}
        letterSpacing={2}
      >
        {text}
      </text>
      <SketchLine
        d={wobblyLine(W / 2 - wide / 2, 268, W / 2 + wide / 2, 268, 4, seed)}
        at={at + 4}
        durationInFrames={12}
        color={accent}
        strokeWidth={9}
        seed={seed}
        opacity={0.9}
      />
    </g>
  );
};

/* ================================================================== */
/* title — placeholder scene, replace with the real illustrations      */
/* ================================================================== */

const vTitle: Visual = ({ durationInFrames }) => {
  const frame = useCurrentFrame();
  const frameAt = 14;
  return {
    art: (
      <g>
        <Title text="标题" accent={P.blue} seed={1} />
        <rect
          x={190}
          y={620}
          width={700}
          height={460}
          rx={28}
          fill="#ffffff"
          stroke={P.blue}
          strokeWidth={4.4}
          opacity={clamp01(interpolate(frame, [frameAt, frameAt + 12], [0, 1], {
            extrapolateLeft: "clamp",
            extrapolateRight: "clamp",
          }))}
          filter={wob(1)}
        />
        <Label x={540} y={800} text="在这里画这一场的插图" at={frameAt + 8} size={44} align="center" color={P.blue} />
        <Label x={540} y={880} text="src/scenes.tsx → VISUALS" at={frameAt + 16} size={30} align="center" color={P.inkSoft} />
      </g>
    ),
    keys: [
      { at: 6, x: 220, y: 660 },
      { at: Math.round(durationInFrames * 0.45), x: 860, y: 1040 },
      { at: Math.round(durationInFrames * 0.75), x: 540, y: 820 },
    ] as HandKey[],
  };
};

/* ------------------------------------------------------------------ */
/* Registry                                                            */
/* ------------------------------------------------------------------ */

const VISUALS: Record<string, Visual> = {
  title: vTitle,
};

export const SCENE_VISUALS = Object.keys(VISUALS);

export const Scenes: React.FC<SceneProps & { visual: string }> = (props) => {
  const visual = VISUALS[props.visual];
  if (!visual) {
    throw new Error(
      `no illustration registered for visual "${props.visual}". ` +
        `Add it to VISUALS in src/scenes.tsx (available: ${SCENE_VISUALS.join(", ")}).`,
    );
  }
  const { art, keys } = visual(props);

  return (
    <Paper seed={props.seed} durationInFrames={props.durationInFrames}>
      <svg
        width={W}
        height={1920}
        viewBox={`0 0 ${W} 1920`}
        style={{ position: "absolute", left: 0, top: 0 }}
      >
        {art}
        <Hand keys={keys} />
      </svg>
      <Lines lines={props.lines} />
    </Paper>
  );
};
