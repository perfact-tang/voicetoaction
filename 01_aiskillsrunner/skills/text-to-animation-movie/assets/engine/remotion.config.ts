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
// Software GL: headless Linux servers rarely have a usable GPU.
Config.setChromiumOpenGlRenderer("swangle");
