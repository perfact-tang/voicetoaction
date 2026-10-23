#!/usr/bin/env node
/**
 * 预下载 SenseVoice-Small 模型（约 240MB）+ silero-VAD（约 0.6MB）。
 *
 *   npm run setup:sensevoice
 *
 * 不预下载也能用：第一次转录时 Python 端会自动下载。这里只是把首次延迟
 * 挪到安装阶段，避免第一条录音卡在下载上。
 */
import { spawnSync } from "node:child_process";
import { existsSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const projectRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const windows = process.platform === "win32";
const venvPython = join(projectRoot, ".venv", windows ? "Scripts/python.exe" : "bin/python");
const script = join(projectRoot, "scripts", "sensevoice_transcribe.py");
const modelDir = process.env.SENSEVOICE_MODEL_DIR || join(projectRoot, "models", "sensevoice");

if (!existsSync(venvPython)) {
  console.error(`找不到 ${venvPython}，请先运行 npm run setup:sensevoice 安装 Python 依赖。`);
  process.exit(2);
}

const result = spawnSync(
  venvPython,
  [script, "--download-model", "--model-dir", modelDir],
  { stdio: "inherit" }
);
process.exit(result.status ?? 1);
