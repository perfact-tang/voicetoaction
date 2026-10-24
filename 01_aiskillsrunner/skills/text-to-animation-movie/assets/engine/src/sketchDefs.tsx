import React from "react";
import { AbsoluteFill } from "remotion";

/**
 * Shared SVG filters. A low-octave turbulence displacement roughens every
 * stroke so lines read as marker ink rather than vector output. Kept to a
 * handful of octaves on purpose: this filter runs on every animated frame.
 */
export const SketchDefs: React.FC = () => (
  <AbsoluteFill style={{ pointerEvents: "none" }}>
    <svg width="0" height="0" style={{ position: "absolute" }}>
      <defs>
        {[1, 2, 3].map((seed) => (
          <filter
            key={seed}
            id={`sketch-wobble-${seed}`}
            x="-12%"
            y="-12%"
            width="124%"
            height="124%"
            colorInterpolationFilters="sRGB"
          >
            <feTurbulence
              type="fractalNoise"
              baseFrequency="0.014 0.021"
              numOctaves={1}
              seed={seed}
              result="noise"
            />
            <feDisplacementMap
              in="SourceGraphic"
              in2="noise"
              scale={3.4}
              xChannelSelector="R"
              yChannelSelector="G"
            />
          </filter>
        ))}
        <filter id="sketch-soft" x="-20%" y="-20%" width="140%" height="140%">
          <feGaussianBlur stdDeviation="9" />
        </filter>
        {/* Sleeve of the drawing hand; coordinates are the hand's local space. */}
        <linearGradient
          id="sleeveFade"
          gradientUnits="userSpaceOnUse"
          x1={430}
          y1={60}
          x2={620}
          y2={340}
        >
          <stop offset="0" stopColor="#b7c6da" stopOpacity={1} />
          <stop offset="0.55" stopColor="#b7c6da" stopOpacity={0.85} />
          <stop offset="1" stopColor="#b7c6da" stopOpacity={0} />
        </linearGradient>
      </defs>
    </svg>
    {/*
      There used to be a full-screen <feTurbulence> "paper grain" layer here.
      It sat behind the opaque <Paper> background, so it was never visible — but
      Chrome still regenerated the noise for every 1080x1920 frame, which was
      by far the biggest render cost (~0.7s/frame). Removed: the flat paper base
      is what the video has always looked like, and frames are ~5x cheaper.
    */}
  </AbsoluteFill>
);
