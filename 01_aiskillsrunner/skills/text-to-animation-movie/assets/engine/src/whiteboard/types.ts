/**
 * Data shapes shared between the compositions and the scene illustrations.
 *
 * `Runtime*` types describe the generated `timing.ts` each video imports at
 * render time — only what the composition actually reads. The richer
 * `Diagnostic*` shapes (clip durations, measured onsets, character counts)
 * are written to `out/<video>-timing.json` for inspecting the build.
 */

/** One narration line as the composition needs it. */
export type RuntimeLine = {
  id: string;
  index: number;
  sceneId: string;
  text: string;
  startFrame: number;
  endFrame: number;
};

/** One scene as the composition needs it. */
export type RuntimeScene = {
  id: string;
  visual: string;
  index: number;
  lineIds: string[];
  startFrame: number;
  endFrame: number;
  durationInFrames: number;
};

export type Timing = {
  fps: number;
  width: number;
  height: number;
  durationInFrames: number;
  audioTrack: string;
  lines: RuntimeLine[];
  scenes: RuntimeScene[];
};

/* ------------------------------------------------------------------ */
/* Diagnostics                                                         */
/* ------------------------------------------------------------------ */

export type LineTiming = RuntimeLine & {
  /** Length of the clip as rendered by `say`, measured with ffprobe. */
  durationMs: number;
  /** Where the line's voice actually starts in the finished track. */
  onsetMs: number;
  startMs: number;
  endMs: number;
  speechMs: number;
  gapMs: number;
  totalMs: number;
  speechFrames: number;
  gapFrames: number;
  totalFrames: number;
  chars: number;
};

export type SceneTiming = RuntimeScene & {
  firstLineIndex: number;
  lastLineIndex: number;
  lineStartFrame: number;
  speechFrames: number;
  chars: number;
  durationSeconds: number;
  startMs: number;
  lineStartMs: number;
};

export type TimingDiagnostics = {
  id: string;
  title: string;
  fps: number;
  durationInFrames: number;
  durationMs: number;
  audioTrackMs: number;
  speechMs: number;
  voice: string;
  rate: number;
  lines: LineTiming[];
  scenes: SceneTiming[];
};
