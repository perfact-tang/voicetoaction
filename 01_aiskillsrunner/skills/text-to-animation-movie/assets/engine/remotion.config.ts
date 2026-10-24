/**
 * Remotion CLI configuration.
 *
 * Note: when rendering through the Node.js APIs these settings do not apply —
 * the skill's render step passes the equivalents as CLI flags.
 */

import { Config } from "@remotion/cli/config";

Config.setRspack(true);
Config.setVideoImageFormat("jpeg");
Config.setOverwriteOutput(true);
/*
 * ANGLE on top of the bundled SwiftShader is ~1.7x faster than `swangle` for
 * this SVG-heavy composition, and produces the same picture. Do NOT switch to
 * `egl` / `swiftshader`: on a GPU-less Linux box those backends silently drop
 * filtered strokes (the hand loses fingers) and skip the paper texture.
 * `scripts/render.sh` exposes `GL=...` if a machine needs a different backend.
 */
Config.setChromiumOpenGlRenderer("angle");
