/**
 * Crisp hand-drawn style SVG icons on a 24x24 grid.
 *
 * Pure SVG paths — zero external assets, fast Chromium rasterization,
 * works inside any `<svg><g>` scene illustration.
 */

import React from "react";
import { useCurrentFrame, useVideoConfig, spring } from "remotion";

export type IconName =
  | "doc"
  | "docs"
  | "folder"
  | "search"
  | "database"
  | "brain"
  | "network"
  | "user"
  | "users"
  | "gear"
  | "check"
  | "cross"
  | "warning"
  | "sparkle"
  | "chat"
  | "link"
  | "box"
  | "cart"
  | "chart"
  | "clock"
  | "lightbulb"
  | "book"
  | "code"
  | "server"
  | "shield"
  | "layers"
  | "target"
  | "tag";

const ICON_PATHS: Record<IconName, React.ReactNode> = {
  doc: (
    <>
      <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
      <path d="M14 2v6h6" />
      <path d="M8 13h8" />
      <path d="M8 17h5" />
    </>
  ),
  docs: (
    <>
      <rect x="8" y="4" width="12" height="16" rx="2" />
      <path d="M4 8v10a2 2 0 0 0 2 2h9" />
      <path d="M11 9h6" />
      <path d="M11 13h6" />
      <path d="M11 17h3" />
    </>
  ),
  folder: (
    <>
      <path d="M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z" />
    </>
  ),
  search: (
    <>
      <circle cx="10.5" cy="10.5" r="6.5" />
      <path d="M15.5 15.5L21 21" />
    </>
  ),
  database: (
    <>
      <ellipse cx="12" cy="5" rx="8" ry="3" />
      <path d="M4 5v14c0 1.66 3.58 3 8 3s8-1.34 8-3V5" />
      <path d="M4 12c0 1.66 3.58 3 8 3s8-1.34 8-3" />
    </>
  ),
  brain: (
    <>
      <path d="M9.5 4a3.5 3.5 0 0 0-3.3 4.6A3.5 3.5 0 0 0 5 15a3.5 3.5 0 0 0 4.5 4.8V4z" />
      <path d="M14.5 4a3.5 3.5 0 0 1 3.3 4.6A3.5 3.5 0 0 1 19 15a3.5 3.5 0 0 1-4.5 4.8V4z" />
      <path d="M12 4v16" />
    </>
  ),
  network: (
    <>
      <circle cx="12" cy="5" r="2.5" />
      <circle cx="5" cy="18" r="2.5" />
      <circle cx="19" cy="18" r="2.5" />
      <path d="M10.7 7.2L6.3 15.8" />
      <path d="M13.3 7.2l4.4 8.6" />
      <path d="M7.5 18h9" />
    </>
  ),
  user: (
    <>
      <circle cx="12" cy="8" r="4" />
      <path d="M4 20c0-4 3.6-6.5 8-6.5s8 2.5 8 6.5" />
    </>
  ),
  users: (
    <>
      <circle cx="9" cy="8" r="3.2" />
      <path d="M3 19c0-3.2 2.7-5.2 6-5.2s6 2 6 5.2" />
      <circle cx="16.5" cy="9" r="2.5" />
      <path d="M16.5 13.8c2.4 0 4.5 1.4 4.5 4.2" />
    </>
  ),
  gear: (
    <>
      <circle cx="12" cy="12" r="3.2" />
      <path d="M12 2v3M12 19v3M4.9 4.9l2.1 2.1M17 17l2.1 2.1M2 12h3M19 12h3M4.9 19.1L7 17M17 7l2.1-2.1" />
    </>
  ),
  check: (
    <>
      <path d="M4.5 12.5l5 5 10-11" />
    </>
  ),
  cross: (
    <>
      <path d="M6 6l12 12" />
      <path d="M18 6L6 18" />
    </>
  ),
  warning: (
    <>
      <path d="M12 3L2.5 19.5h19L12 3z" />
      <path d="M12 9.5v4.5" />
      <circle cx="12" cy="17" r="0.8" fill="currentColor" />
    </>
  ),
  sparkle: (
    <>
      <path d="M12 2l2.2 6.8L21 11l-6.8 2.2L12 20l-2.2-6.8L3 11l6.8-2.2z" />
    </>
  ),
  chat: (
    <>
      <path d="M4 5h16a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H9l-5 4V7a2 2 0 0 1 2-2z" />
      <path d="M8 10h8" />
      <path d="M8 14h5" />
    </>
  ),
  link: (
    <>
      <path d="M10 13a5 5 0 0 0 7.1 0l2-2a5 5 0 0 0-7.1-7.1l-1.2 1.2" />
      <path d="M14 11a5 5 0 0 0-7.1 0l-2 2a5 5 0 0 0 7.1 7.1l1.2-1.2" />
    </>
  ),
  box: (
    <>
      <path d="M12 2l8.5 4.8v10.4L12 22l-8.5-4.8V6.8L12 2z" />
      <path d="M3.5 6.8L12 11.6l8.5-4.8" />
      <path d="M12 11.6V22" />
    </>
  ),
  cart: (
    <>
      <path d="M3 4h2l2.4 10.5a1.5 1.5 0 0 0 1.5 1.2h8.8a1.5 1.5 0 0 0 1.4-1.1L21 7H6" />
      <circle cx="9.5" cy="19.5" r="1.5" />
      <circle cx="17.5" cy="19.5" r="1.5" />
    </>
  ),
  chart: (
    <>
      <path d="M4 20h16" />
      <rect x="6" y="12" width="3" height="8" rx="0.8" />
      <rect x="10.5" y="7" width="3" height="13" rx="0.8" />
      <rect x="15" y="4" width="3" height="16" rx="0.8" />
    </>
  ),
  clock: (
    <>
      <circle cx="12" cy="12" r="9" />
      <path d="M12 7v5l3.5 2" />
    </>
  ),
  lightbulb: (
    <>
      <path d="M9 18h6" />
      <path d="M10 21h4" />
      <path d="M12 3a6 6 0 0 0-4 10.5c.8.7 1.3 1.7 1.5 2.5h5c.2-.8.7-1.8 1.5-2.5A6 6 0 0 0 12 3z" />
    </>
  ),
  book: (
    <>
      <path d="M4 5a2 2 0 0 1 2-2h12v17H6a2 2 0 0 1-2-2V5z" />
      <path d="M4 17a2 2 0 0 1 2-2h12" />
      <path d="M8 7h6" />
    </>
  ),
  code: (
    <>
      <path d="M8 7l-5 5 5 5" />
      <path d="M16 7l5 5-5 5" />
      <path d="M13.5 5l-3 14" />
    </>
  ),
  server: (
    <>
      <rect x="3" y="4" width="18" height="6.5" rx="1.8" />
      <rect x="3" y="13.5" width="18" height="6.5" rx="1.8" />
      <circle cx="7" cy="7.25" r="0.9" fill="currentColor" />
      <circle cx="7" cy="16.75" r="0.9" fill="currentColor" />
    </>
  ),
  shield: (
    <>
      <path d="M12 3l7.5 3.2v5.6c0 4.7-3.2 8.2-7.5 9.7-4.3-1.5-7.5-5-7.5-9.7V6.2L12 3z" />
      <path d="M9 12l2.2 2.2 4-4.2" />
    </>
  ),
  layers: (
    <>
      <path d="M12 3l9 4.8-9 4.8-9-4.8L12 3z" />
      <path d="M3 12.2l9 4.8 9-4.8" />
      <path d="M3 16.6l9 4.8 9-4.8" />
    </>
  ),
  target: (
    <>
      <circle cx="12" cy="12" r="9" />
      <circle cx="12" cy="12" r="5" />
      <circle cx="12" cy="12" r="1.5" fill="currentColor" />
    </>
  ),
  tag: (
    <>
      <path d="M4 4h6.5l9.5 9.5a1.5 1.5 0 0 1 0 2.1l-4.4 4.4a1.5 1.5 0 0 1-2.1 0L4 10.5V4z" />
      <circle cx="8.5" cy="8.5" r="1.2" fill="currentColor" />
    </>
  ),
};

export const Icon: React.FC<{
  name: IconName;
  /** Center X in SVG coordinates. */
  x: number;
  /** Center Y in SVG coordinates. */
  y: number;
  size?: number;
  color?: string;
  strokeWidth?: number;
  at?: number;
  badgeFill?: string;
}> = ({
  name,
  x,
  y,
  size = 44,
  color = "#1c212b",
  strokeWidth = 2.1,
  at = 0,
  badgeFill,
}) => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  const p =
    at <= 0
      ? 1
      : spring({
          frame: frame - at,
          fps,
          config: { damping: 14, stiffness: 180, mass: 0.7 },
        });
  if (p <= 0.01) return null;

  const scale = size / 24;
  return (
    <g transform={`translate(${x} ${y}) scale(${p})`} opacity={Math.min(1, p * 1.4)}>
      {badgeFill ? (
        <circle
          cx={0}
          cy={0}
          r={size * 0.78}
          fill={badgeFill}
          stroke={color}
          strokeWidth={2.2}
          strokeOpacity={0.25}
        />
      ) : null}
      <g
        transform={`translate(${-size / 2} ${-size / 2}) scale(${scale})`}
        fill="none"
        stroke={color}
        strokeWidth={strokeWidth}
        strokeLinecap="round"
        strokeLinejoin="round"
        color={color}
      >
        {ICON_PATHS[name]}
      </g>
    </g>
  );
};
