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
        {/*
          Very light paper grain used on large fills. `sketch-wobble-*` is for
          strokes; this one is for texture.
        */}
        <filter id="paper-grain">
          <feTurbulence type="fractalNoise" baseFrequency="0.9" numOctaves={2} />
          <feColorMatrix type="saturate" values="0" />
        </filter>
      </defs>
    </svg>
    {/* paper grain overlay */}
    <svg width="100%" height="100%" style={{ position: "absolute", inset: 0 }}>
      <rect
        width="100%"
        height="100%"
        filter="url(#paper-grain)"
        opacity={0.09}
        style={{ mixBlendMode: "multiply" }}
      />
    </svg>
  </AbsoluteFill>
);
