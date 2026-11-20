/**
 * Scene illustrations.
 *
 * This is the file the skill edits for every new video. Each entry in VISUALS
 * is one illustrated beat: it receives the scene's duration, local narration
 * line cues, and responsive `canvas` layout (9:16 portrait or 16:9 landscape),
 * and returns:
 *
 *   - `art`:    the SVG drawn on paper
 *   - `keys`:   where the pen tip travels, as `{ at, x, y }` frames
 *   - `accent`: optional theme color for the top progress bar & caption bar
 *
 * Everything comes from the shared Studio Whiteboard kit (`src/whiteboard/kit.tsx`)
 * — see references/02-scenes.md for the full API and visual recipes.
 */

import React from "react";
import {
  type CanvasLayout,
  type LineCue,
  type PenKey,
  CalloutBanner,
  FlowArrow,
  Icon,
  Label,
  Lines,
  P,
  Paper,
  PaperCard,
  Pen,
  PillBadge,
  StickyNote,
  Title,
  useCanvas,
} from "./whiteboard/kit";

export type SceneProps = {
  durationInFrames: number;
  lines: LineCue[];
  seed: number;
  canvas: CanvasLayout;
  sceneIndex?: number;
  totalScenes?: number;
  globalStartFrame?: number;
  totalDurationInFrames?: number;
};

type Visual = (p: SceneProps) => {
  art: React.ReactNode;
  keys: PenKey[];
  accent?: string;
};

/* ================================================================== */
/* title — responsive starter scene, replace with real illustrations   */
/* ================================================================== */

const vTitle: Visual = ({ durationInFrames, canvas }) => {
  const { isLandscape, cx, artLeft, artTop, artW, artH } = canvas;

  if (isLandscape) {
    // 16:9 landscape: clean 3-column flow across the wide canvas
    const cardW = 460;
    const cardH = 310;
    const gap = 110;
    const totalW = cardW * 3 + gap * 2;
    const x0 = cx - totalW / 2;
    const y0 = artTop + 80;

    return {
      accent: P.blue,
      art: (
        <g>
          <Title text="把复杂概念讲清楚" kicker="01 · 开场示范" accent={P.blue} seed={1} />

          <PaperCard x={x0} y={y0} w={cardW} h={cardH} at={10} accent={P.blue} badge="01" seed={1} float>
            <Icon name="docs" x={x0 + 68} y={y0 + 76} size={46} color={P.blue} badgeFill={P.blueSoft} at={14} />
            <Label x={x0 + 130} y={y0 + 76} text="先拆结构" at={16} size={34} weight={800} color={P.ink} />
            <Label x={x0 + 36} y={y0 + 178} text={"从痛点和核心问题切入\n而不是照搬原文小标题"} at={20} size={25} color={P.inkSoft} />
            <PillBadge x={x0 + 115} y={y0 + 266} text="问题驱动" at={24} color={P.blue} size={20} />
          </PaperCard>

          <FlowArrow
            x1={x0 + cardW + 12}
            y1={y0 + cardH / 2}
            x2={x0 + cardW + gap - 12}
            y2={y0 + cardH / 2}
            at={28}
            color={P.blue}
            pulse
          />

          <PaperCard x={x0 + cardW + gap} y={y0} w={cardW} h={cardH} at={32} accent={P.purple} badge="02" seed={2} float>
            <Icon name="chat" x={x0 + cardW + gap + 68} y={y0 + 76} size={46} color={P.purple} badgeFill={P.purpleSoft} at={36} />
            <Label x={x0 + cardW + gap + 130} y={y0 + 76} text="说人话 + 举例" at={38} size={34} weight={800} color={P.ink} />
            <Label x={x0 + cardW + gap + 36} y={y0 + 178} text={"先用大白话定性，再举\n一个听得懂的具体场景"} at={42} size={25} color={P.inkSoft} />
            <PillBadge x={x0 + cardW + gap + 115} y={y0 + 266} text="自然口播" at={46} color={P.purple} size={20} />
          </PaperCard>

          <FlowArrow
            x1={x0 + (cardW + gap) * 2 - gap + 12}
            y1={y0 + cardH / 2}
            x2={x0 + (cardW + gap) * 2 - 12}
            y2={y0 + cardH / 2}
            at={50}
            color={P.green}
            pulse
          />

          <PaperCard x={x0 + (cardW + gap) * 2} y={y0} w={cardW} h={cardH} at={54} accent={P.green} badge="03" seed={3} float>
            <Icon name="network" x={x0 + (cardW + gap) * 2 + 68} y={y0 + 76} size={46} color={P.green} badgeFill={P.greenSoft} at={58} />
            <Label x={x0 + (cardW + gap) * 2 + 130} y={y0 + 76} text="画出关系" at={60} size={34} weight={800} color={P.ink} />
            <Label x={x0 + (cardW + gap) * 2 + 36} y={y0 + 178} text={"卡片、流程、图谱与对比\n让听众一眼看懂全局"} at={64} size={25} color={P.inkSoft} />
            <PillBadge x={x0 + (cardW + gap) * 2 + 115} y={y0 + 266} text="结构可视化" at={68} color={P.green} size={20} />
          </PaperCard>

          <CalloutBanner
            x={cx - 520}
            y={y0 + cardH + 74}
            w={1040}
            h={82}
            text="好动画的秘诀：台词像真人在聊，画面把逻辑摆清楚。"
            at={Math.min(76, Math.round(durationInFrames * 0.65))}
            accent={P.amber}
            icon="lightbulb"
          />
        </g>
      ),
      keys: [
        { at: 10, x: x0 + cardW - 20, y: y0 + cardH - 20 },
        { at: 36, x: x0 + cardW * 2 + gap - 20, y: y0 + cardH - 20 },
        { at: 60, x: x0 + totalW - 20, y: y0 + cardH - 20 },
      ],
    };
  }

  // 9:16 portrait (default 1080x1920): clean vertical narrative stack
  const cardW = artW - 20;
  const x0 = artLeft + 10;
  const y1 = artTop + 56;
  const cardH = 225;
  const gapY = 74;
  const y2 = y1 + cardH + gapY;
  const y3 = y2 + cardH + gapY;

  return {
    accent: P.blue,
    art: (
      <g>
        <Title text="把复杂概念讲清楚" kicker="01 · 开场示范" accent={P.blue} seed={1} />

        <PaperCard x={x0} y={y1} w={cardW} h={cardH} at={10} accent={P.blue} badge="STEP 01" seed={1} float>
          <Icon name="docs" x={x0 + 82} y={y1 + cardH / 2} size={52} color={P.blue} badgeFill={P.blueSoft} at={14} />
          <Label x={x0 + 160} y={y1 + 78} text="先拆核心问题" at={16} size={38} weight={800} color={P.ink} />
          <Label x={x0 + 160} y={y1 + 148} text="从真实场景切入，而不是照抄小标题" at={20} size={27} color={P.inkSoft} />
        </PaperCard>

        <FlowArrow x1={cx} y1={y1 + cardH + 8} x2={cx} y2={y2 - 8} at={26} color={P.blue} pulse />

        <PaperCard x={x0} y={y2} w={cardW} h={cardH} at={30} accent={P.purple} badge="STEP 02" seed={2} float>
          <Icon name="chat" x={x0 + 82} y={y2 + cardH / 2} size={52} color={P.purple} badgeFill={P.purpleSoft} at={34} />
          <Label x={x0 + 160} y={y2 + 78} text="说人话 + 举例子" at={36} size={38} weight={800} color={P.ink} />
          <Label x={x0 + 160} y={y2 + 148} text="大白话定性，再落到具体案例里" at={40} size={27} color={P.inkSoft} />
        </PaperCard>

        <FlowArrow x1={cx} y1={y2 + cardH + 8} x2={cx} y2={y3 - 8} at={46} color={P.green} pulse />

        <PaperCard x={x0} y={y3} w={cardW} h={cardH} at={50} accent={P.green} badge="STEP 03" seed={3} float>
          <Icon name="network" x={x0 + 82} y={y3 + cardH / 2} size={52} color={P.green} badgeFill={P.greenSoft} at={54} />
          <Label x={x0 + 160} y={y3 + 78} text="把结构画出来" at={56} size={38} weight={800} color={P.ink} />
          <Label x={x0 + 160} y={y3 + 148} text="卡片、流程与图谱，一眼看懂全局" at={60} size={27} color={P.inkSoft} />
        </PaperCard>

        <StickyNote
          x={x0 + 50}
          y={y3 + cardH + 62}
          w={cardW - 100}
          h={Math.min(148, artTop + artH - (y3 + cardH + 74))}
          at={Math.min(68, Math.round(durationInFrames * 0.65))}
          rotate={-1.1}
        >
          <Icon name="lightbulb" x={x0 + 104} y={y3 + cardH + 132} size={38} color={P.amber} at={70} />
          <Label
            x={x0 + 146}
            y={y3 + cardH + 132}
            text="台词像真人在聊，画面把逻辑摆清楚"
            at={72}
            size={30}
            weight={700}
            color={P.ink}
          />
        </StickyNote>
      </g>
    ),
    keys: [
      { at: 10, x: x0 + cardW - 40, y: y1 + cardH - 20 },
      { at: 32, x: x0 + cardW - 40, y: y2 + cardH - 20 },
      { at: 54, x: x0 + cardW - 40, y: y3 + cardH - 20 },
    ],
  };
};

/* ------------------------------------------------------------------ */
/* Registry                                                            */
/* ------------------------------------------------------------------ */

const VISUALS: Record<string, Visual> = {
  title: vTitle,
};

export const SCENE_VISUALS = Object.keys(VISUALS);

export const Scenes: React.FC<
  Omit<SceneProps, "canvas"> & { visual: string }
> = (props) => {
  const canvas = useCanvas();
  const visual = VISUALS[props.visual];
  if (!visual) {
    throw new Error(
      `no illustration registered for visual "${props.visual}". ` +
        `Add it to VISUALS in src/scenes.tsx (available: ${SCENE_VISUALS.join(", ")}).`,
    );
  }
  const { art, keys, accent = P.blue } = visual({ ...props, canvas });

  return (
    <Paper
      seed={props.seed}
      durationInFrames={props.durationInFrames}
      sceneIndex={props.sceneIndex}
      totalScenes={props.totalScenes}
      globalStartFrame={props.globalStartFrame}
      totalDurationInFrames={props.totalDurationInFrames}
      accent={accent}
    >
      <svg
        width={canvas.width}
        height={canvas.height}
        viewBox={`0 0 ${canvas.width} ${canvas.height}`}
        style={{ position: "absolute", left: 0, top: 0 }}
      >
        {art}
        <Pen keys={keys} />
      </svg>
      <Lines lines={props.lines} accent={accent} />
    </Paper>
  );
};
