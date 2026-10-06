/**
 * Generic explainer composition.
 *
 * Reads the generated timeline and video config, then plays the scene registry
 * against the narration track. Nothing here is video-specific.
 */

import React from "react";
import { AbsoluteFill, Audio, Sequence, staticFile } from "remotion";
import { VIDEO } from "./data/videoConfig";
import { TIMING } from "./data/timing";
import { Scenes, SCENE_VISUALS } from "./scenes";
import { SketchDefs } from "./sketchDefs";

/** Guard: catch a script.json scene whose `visual` has no illustration. */
const assertVisualsExist = () => {
  const known = new Set(SCENE_VISUALS);
  const missing = [...new Set(TIMING.scenes.map((s) => s.visual))].filter(
    (v) => !known.has(v),
  );
  if (missing.length > 0) {
    throw new Error(
      `scene visual(s) not implemented in src/scenes.tsx: ${missing.join(", ")}. ` +
        `Available: ${SCENE_VISUALS.join(", ")}`,
    );
  }
};

export const Explainer: React.FC = () => {
  assertVisualsExist();

  return (
    <AbsoluteFill style={{ backgroundColor: "#f7f2e7" }}>
      <SketchDefs />
      {VIDEO.hasAudio ? <Audio src={staticFile(VIDEO.audioTrack)} /> : null}
      {TIMING.scenes.map((scene) => {
        const lines = TIMING.lines
          .filter((line) => line.sceneId === scene.id)
          .map((line) => ({
            text: line.text,
            startFrame: line.startFrame - scene.startFrame,
            endFrame: line.endFrame - scene.startFrame,
          }));

        return (
          <Sequence
            key={scene.id}
            from={scene.startFrame}
            durationInFrames={scene.durationInFrames}
            name={`${scene.id} · ${scene.visual}`}
          >
            <Scenes
              visual={scene.visual}
              durationInFrames={scene.durationInFrames}
              lines={lines}
              seed={scene.index + 1}
            />
          </Sequence>
        );
      })}
    </AbsoluteFill>
  );
};
