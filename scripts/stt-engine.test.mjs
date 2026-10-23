#!/usr/bin/env node
/**
 * STT 引擎路由的独立自测（不需要 Python / 模型 / Firebase）。
 *
 *   node scripts/stt-engine.test.mjs
 *
 * 覆盖：
 *   1. normalizeSttEngine：各种写法（含空值、未知值）落到哪个引擎
 *   2. senseVoiceSupportsLanguage：SenseVoice 的 5 语种边界
 *   3. resolveSttEngine：默认值、强制 whisper、后端不可用、语种不支持
 *   4. describeFallback：回退日志文案
 */
import {
  STT_ENGINES,
  describeFallback,
  normalizeSttEngine,
  resolveSttEngine,
  senseVoiceSupportsLanguage
} from "./stt-engines.js";

let failed = 0;

function check(name, condition, detail = "") {
  if (condition) {
    console.log(`  ok   ${name}`);
  } else {
    failed += 1;
    console.error(`  FAIL ${name}${detail ? ` — ${detail}` : ""}`);
  }
}

console.log("normalizeSttEngine");
check("默认（空）→ sensevoice", normalizeSttEngine("") === "sensevoice");
check("undefined → sensevoice", normalizeSttEngine(undefined) === "sensevoice");
check("sensevoice-small → sensevoice", normalizeSttEngine("SenseVoice-Small") === "sensevoice");
check("sv → sensevoice", normalizeSttEngine("sv") === "sensevoice");
check("whisper → faster-whisper", normalizeSttEngine("whisper") === "faster-whisper");
check("faster → faster-whisper", normalizeSttEngine("faster") === "faster-whisper");
check("未知值 → sensevoice", normalizeSttEngine("funasr") === "sensevoice");
check("引擎名都在 STT_ENGINES 里", STT_ENGINES.includes(normalizeSttEngine("faster-whisper")));

console.log("senseVoiceSupportsLanguage");
for (const language of ["zh", "yue", "en", "ja", "ko", "auto", ""]) {
  check(`支持 ${JSON.stringify(language)}`, senseVoiceSupportsLanguage(language) === true);
}
for (const language of ["fr", "de", "es", "ru"]) {
  check(`不支持 ${language}`, senseVoiceSupportsLanguage(language) === false);
}

console.log("resolveSttEngine");
{
  const pick = resolveSttEngine({ engine: "", language: "ja", senseVoiceReady: true });
  check("默认走 SenseVoice", pick.engine === "sensevoice" && pick.fallbackReason === null);
}
{
  const pick = resolveSttEngine({ engine: "faster-whisper", language: "zh", senseVoiceReady: true });
  check("显式 whisper 不被抢占", pick.engine === "faster-whisper" && pick.fallbackReason === null);
}
{
  const pick = resolveSttEngine({ engine: "sensevoice", language: "zh", senseVoiceReady: false });
  check("后端不可用 → 回退", pick.engine === "faster-whisper" && pick.fallbackReason === "sensevoice-not-ready");
}
{
  const pick = resolveSttEngine({ engine: "sensevoice", language: "fr", senseVoiceReady: true });
  check("语种不支持 → 回退", pick.engine === "faster-whisper" && pick.fallbackReason === "language-unsupported");
}
{
  const pick = resolveSttEngine({ engine: "sensevoice", language: "auto", senseVoiceReady: true });
  check("auto 语种交给 SenseVoice 自检", pick.engine === "sensevoice");
}

console.log("describeFallback");
check("not-ready 文案", /不可用/.test(describeFallback("sensevoice-not-ready", "zh")));
check("unsupported 文案带语种", describeFallback("language-unsupported", "fr").includes("fr"));
check("无回退 → null", describeFallback(null, "zh") === null);

if (failed > 0) {
  console.error(`\n${failed} 个用例失败`);
  process.exitCode = 1;
} else {
  console.log("\n全部通过");
}
