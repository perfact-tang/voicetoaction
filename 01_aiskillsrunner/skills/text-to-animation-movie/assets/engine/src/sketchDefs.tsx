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
        {/* Fountain-pen gradients (from the pen artwork, renamed to avoid clashes). */}
        <linearGradient id="pen-body" x1="0%" y1="0%" x2="100%" y2="0%">
          <stop offset="0%" stopColor="#1e293b" />
          <stop offset="30%" stopColor="#475569" />
          <stop offset="50%" stopColor="#94a3b8" />
          <stop offset="70%" stopColor="#475569" />
          <stop offset="100%" stopColor="#0f172a" />
        </linearGradient>
        <linearGradient id="pen-tip" x1="0%" y1="0%" x2="100%" y2="100%">
          <stop offset="0%" stopColor="#fef08a" />
          <stop offset="50%" stopColor="#eab308" />
          <stop offset="100%" stopColor="#a16207" />
        </linearGradient>
        <linearGradient id="pen-ring" x1="0%" y1="0%" x2="100%" y2="0%">
          <stop offset="0%" stopColor="#9ca3af" />
          <stop offset="50%" stopColor="#f3f4f6" />
          <stop offset="100%" stopColor="#6b7280" />
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
