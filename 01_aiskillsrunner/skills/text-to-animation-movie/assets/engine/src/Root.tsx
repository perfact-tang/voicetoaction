import "./index.css";
import React from "react";
import { Composition } from "remotion";
import { VIDEO } from "./data/videoConfig";
import { TIMING } from "./data/timing";
import { Explainer } from "./Video";

export const RemotionRoot: React.FC = () => {
  return (
    <Composition
      id={VIDEO.id}
      component={Explainer}
      durationInFrames={TIMING.durationInFrames}
      fps={VIDEO.fps}
      width={VIDEO.width}
      height={VIDEO.height}
    />
  );
};
