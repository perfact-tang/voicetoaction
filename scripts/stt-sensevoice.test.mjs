#!/usr/bin/env node
/**
 * SenseVoice 后端的集成自测（Node ↔ Python 契约，不连 Firebase）。
 *
 *   node scripts/stt-sensevoice.test.mjs
 *
 * 需要先跑过 `npm run setup:sensevoice`（.venv 里有 sherpa-onnx，models/sensevoice 有模型）。
 * 条件不满足时直接 skip，不让 CI / 新机器上失败。
 */
import { existsSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { joinSegmentText, probeSenseVoice, runSttScript } from "../monitor.js";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const windows = process.platform === "win32";
const venvPython = join(root, ".venv", windows ? "Scripts/python.exe" : "bin/python");
const modelDir = process.env.SENSEVOICE_MODEL_DIR || join(root, "models", "sensevoice");
const script = join(root, "scripts", "sensevoice_transcribe.py");
const audio = join(modelDir, "test_wavs", "zh.wav");

let failed = 0;
function check(name, condition, detail = "") {
  if (condition) {
    console.log(`  ok   ${name}`);
  } else {
    failed += 1;
    console.error(`  FAIL ${name}${detail ? ` — ${detail}` : ""}`);
  }
}

if (!existsSync(venvPython) || !existsSync(join(modelDir, "model.int8.onnx")) || !existsSync(audio)) {
  console.log("skip: 需要 .venv + models/sensevoice（先运行 npm run setup:sensevoice）");
  process.exit(0);
}

console.log("probeSenseVoice");
const probe = probeSenseVoice(modelDir);
check("可用", probe.ready === true, probe.detail);
check("模型已就绪", probe.modelReady === true, probe.detail);

console.log("runSttScript");
const payload = await runSttScript("SenseVoice", script, [
  "--audio", audio,
  "--language", "zh",
  "--model-dir", modelDir
]);
check("有 segments", Array.isArray(payload.segments) && payload.segments.length > 0);
check("runtime.backend 正确", payload.runtime?.backend === "sherpa-onnx:sensevoice-small");
check("用的是 silero VAD", payload.runtime?.vad === "silero");
check("VAD padding 生效", payload.runtime?.vadPadding > 0);
const text = joinSegmentText(payload);
check("识别出正文", /9点/.test(text) && /下午5点/.test(text), text);
check("没有残留富文本标签", !/<\||\|>/.test(text), text);

if (failed > 0) {
  console.error(`\n${failed} 个用例失败`);
  process.exitCode = 1;
} else {
  console.log("\n全部通过");
}
