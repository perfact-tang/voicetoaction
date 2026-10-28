#!/usr/bin/env node
/**
 * 校验整理后的笔记 JSON：语言、结构、长度、数字保留、编辑话术、重复句。
 *
 * 用法:
 *   node validate-note.mjs <笔记.json> [--language ja] [--source 输入文本.md] [--json]
 *
 * 退出码: 0 通过（可有 warning） 1 有 error 2 参数/读取错误
 */

import fs from "node:fs";
import path from "node:path";
import { pathToFileURL } from "node:url";

const LANG_ALIASES = new Map([
  ["zh", "zh"], ["cn", "zh"], ["zh-cn", "zh"], ["zh_cn", "zh"], ["zh-tw", "zh"],
  ["zh_tw", "zh"], ["chinese", "zh"], ["中文", "zh"], ["简体中文", "zh"], ["汉语", "zh"],
  ["ja", "ja"], ["jp", "ja"], ["ja-jp", "ja"], ["japanese", "ja"], ["日语", "ja"], ["日本語", "ja"],
  ["en", "en"], ["en-us", "en"], ["en_us", "en"], ["english", "en"], ["英语", "en"], ["英文", "en"],
  ["ko", "ko"], ["kr", "ko"], ["ko-kr", "ko"], ["korean", "ko"], ["韩语", "ko"], ["韓国語", "ko"], ["한국어", "ko"],
]);

const LANG_NAMES = { zh: "简体中文", ja: "日语", en: "英语", ko: "韩语" };

function normalizeLanguage(value) {
  const raw = String(value || "").trim();
  if (!raw) return "";
  const low = raw.toLowerCase();
  if (LANG_ALIASES.has(low)) return LANG_ALIASES.get(low);
  if (low.startsWith("zh")) return "zh";
  if (low.startsWith("ja")) return "ja";
  if (low.startsWith("en")) return "en";
  if (low.startsWith("ko") || low.startsWith("kr")) return "ko";
  return "";
}

function charCount(text) {
  return [...String(text || "")].length;
}

function countScript(text, re) {
  re.lastIndex = 0;
  return (String(text || "").match(re) || []).length;
}

export function detectLanguage(text) {
  const source = String(text || "");
  const han = countScript(source, /\p{Script=Han}/gu);
  const kana = countScript(source, /[\p{Script=Hiragana}\p{Script=Katakana}]/gu);
  const hangul = countScript(source, /\p{Script=Hangul}/gu);
  const latin = countScript(source, /\p{Script=Latin}/gu);
  const total = han + kana + hangul + latin;
  if (total === 0) return { code: "unknown", stats: { han, kana, hangul, latin, total } };

  const ratios = {
    han: han / total,
    kana: kana / total,
    hangul: hangul / total,
    latin: latin / total,
  };

  let code = "unknown";
  if (ratios.hangul >= 0.25) code = "ko";
  else if (kana >= 3 && kana / Math.max(1, han + kana) >= 0.03) code = "ja";
  else if (ratios.han >= 0.25) code = "zh";
  else if (ratios.latin >= 0.5) code = "en";
  else if (latin >= 10 && han <= 3) code = "en";
  else if (kana >= 3) code = "ja";
  else if (han >= 5) code = "zh";
  else if (latin >= 5) code = "en";

  return { code, stats: { han, kana, hangul, latin, total }, ratios };
}

function languageError(target, detected, stats) {
  const t = normalizeLanguage(target);
  if (!t) return "";
  const d = detected || "unknown";
  if (t === "zh") {
    if (stats.kana >= 3 || (stats.kana > 0 && stats.kana / Math.max(1, stats.han + stats.kana) > 0.02)) {
      return `目标是简体中文，但正文检测为日语（假名 ${stats.kana} 个）。请改成中文，或确认目标语言是否选错。`;
    }
    if (stats.han === 0 && stats.latin > 0) {
      return "目标是简体中文，但正文几乎没有汉字。";
    }
    return "";
  }
  if (t === "ja") {
    if (stats.kana < 3) {
      return `目标是日语，但正文几乎没有假名（${stats.kana} 个）。可能是中文或未翻译。`;
    }
    if (d === "zh" && stats.kana < 3) {
      return "目标是日语，但正文检测为中文。";
    }
    return "";
  }
  if (t === "en") {
    if (stats.latin === 0 || stats.latin / Math.max(1, stats.total) < 0.35) {
      return `目标是英语，但拉丁字母只占 ${(100 * stats.latin / Math.max(1, stats.total)).toFixed(0)}%。`;
    }
    if (stats.han + stats.hangul > stats.latin) {
      return "目标是英语，但汉字/韩文比拉丁字母还多。";
    }
    return "";
  }
  if (t === "ko") {
    if (stats.hangul === 0 || stats.hangul / Math.max(1, stats.total) < 0.25) {
      return `目标是韩语，但韩文字母只占 ${(100 * stats.hangul / Math.max(1, stats.total)).toFixed(0)}%。`;
    }
    return "";
  }
  return "";
}

function splitSentences(text) {
  return String(text || "")
    .split(/(?<=[。！？!?])|\n+/)
    .map((s) => s.trim())
    .filter((s) => charCount(s) >= 10);
}

function extractNumbers(text) {
  const matches = String(text || "").match(/\d+(?:[.,]\d+)*/g) || [];
  const unique = new Set();
  for (const raw of matches) {
    const token = raw.replace(/[.,]$/, "");
    if (token.length >= 2 || token.includes(".") || token.includes(",")) unique.add(token);
  }
  return [...unique];
}

function hasAny(text, needles) {
  return needles.some((needle) => String(text).includes(needle));
}

export function validateNote({ raw, language, sourceText = "" }) {
  const errors = [];
  const warnings = [];
  const title = String(raw?.title ?? "").trim();
  const body = String(raw?.body ?? "").replace(/\r\n/g, "\n").trim();
  const target = normalizeLanguage(language) || normalizeLanguage(raw?.language) || "";

  if (!raw || typeof raw !== "object" || Array.isArray(raw)) {
    return { ok: false, errors: ["笔记 JSON 必须是对象"], warnings, stats: {} };
  }
  if (!title) errors.push("缺少 title。");
  if (!body) errors.push("缺少 body。");
  if (!target) errors.push("无法确定语言（需要 zh / ja / en / ko）。");
  if (raw.hidden !== false) {
    errors.push(`hidden 必须是 false（当前 ${JSON.stringify(raw.hidden)}），否则 App 的笔记列表查不到。`);
  }
  if (title.length > 200) errors.push(`title 过长（${title.length} 字符，上限 200）。`);
  if (title.includes("\n")) errors.push("title 不能包含换行。");
  if (/[。.!！?？]$/.test(title)) warnings.push("title 末尾带了句末标点，建议去掉。");
  if (!Array.isArray(raw.tags) || raw.tags.length === 0) {
    warnings.push('tags 为空；建议 ["voice", "note"]。');
  }
  if (!raw.noteid && !raw.docid) warnings.push("note JSON 里没有 noteid / docid；发布时用 --noteid 指定。");

  const bodyChars = charCount(body);
  const paragraphs = body ? body.split(/\n{2,}/).map((p) => p.trim()).filter(Boolean) : [];
  if (bodyChars < 20) errors.push(`正文过短（${bodyChars} 字符），不像一段完整的笔记。`);
  if (bodyChars > 300_000) warnings.push(`正文很长（${bodyChars} 字符），确认 Firestore 文档未超过 1 MiB。`);

  const detected = detectLanguage(body);
  if (target) {
    const langError = languageError(target, detected.code, detected.stats);
    if (langError) errors.push(langError);
  }

  if (title && target) {
    const titleDetected = detectLanguage(title);
    const titleError = languageError(target, titleDetected.code, titleDetected.stats);
    if (titleError && charCount(title) >= 10) warnings.push(`标题语言可能不一致：${titleError}`);
  }

  const metaPhrases = [
    "以下是整理", "下面是整理", "这是整理后", "整理如下", "原文如下",
    "希望对你有帮助", "希望对您有帮助", "作为AI", "作为 AI",
    "Here is the cleaned", "Below is the cleaned", "As an AI",
    "以下、整理", "整理しました", "정리한 내용", "다음은 정리",
  ];
  if (hasAny(body, metaPhrases) && /^(以下是|下面是|这是整理|整理如下|原文如下|Here is|Below is|以下、|整理しました|정리한|다음은)/.test(body)) {
    errors.push("正文以编辑话术开头（如「以下是整理后的内容」）；Note 正文只放笔记本身。");
  } else if (hasAny(body, metaPhrases)) {
    warnings.push("正文里出现了编辑话术/元话语，确认它不是原文内容。");
  }

  const placeholders = ["TODO", "TBD", "XXX", "[insert", "（此处省略", "(此处省略", "待补充"];
  if (hasAny(body, placeholders)) errors.push("正文里有占位符（TODO / TBD / 待补充 等）。");

  const sentences = splitSentences(body);
  const duplicateSentences = [];
  for (let i = 1; i < sentences.length; i += 1) {
    if (sentences[i] === sentences[i - 1]) duplicateSentences.push(sentences[i]);
  }
  if (duplicateSentences.length) {
    warnings.push(`有 ${duplicateSentences.length} 处连续重复句，可能是 ASR 重复或复制粘贴。`);
  }

  if (/^#{1,6}\s/m.test(body)) {
    warnings.push("正文包含 Markdown 标题；确认这是说话人自己的结构，而不是你新加的。");
  }
  if (/^\s*[-*]\s+/m.test(body)) {
    warnings.push("正文包含项目符号；确认原文就是逐条列举，否则改回段落。");
  }
  if (body && !/[。！？!?\.\)）"」』]$/.test(body)) {
    warnings.push("正文末尾没有句末标点，确认是否被截断。");
  }
  if (title && body.startsWith(title)) {
    warnings.push("正文开头重复了标题；body 里不要再放标题。");
  }

  let sourceChars = 0;
  let ratio = null;
  if (sourceText && sourceText.trim()) {
    sourceChars = charCount(sourceText);
    const sourceDetected = detectLanguage(sourceText);
    const sameLanguage = !sourceDetected.code || sourceDetected.code === "unknown" || sourceDetected.code === target;
    if (sameLanguage && sourceChars >= 50) {
      ratio = bodyChars / sourceChars;
      if (ratio < 0.6) {
        errors.push(`正文只有原文的 ${(ratio * 100).toFixed(0)}%（${bodyChars}/${sourceChars} 字符），可能过度摘要或漏了内容。`);
      } else if (ratio > 1.4) {
        warnings.push(`正文是原文的 ${(ratio * 100).toFixed(0)}%，可能添加了原文没有的内容。`);
      }
    }

    const missingNumbers = extractNumbers(sourceText).filter((n) => !body.includes(n));
    if (missingNumbers.length) {
      warnings.push(`原文里的数字没有出现在正文：${missingNumbers.slice(0, 8).join(", ")}${missingNumbers.length > 8 ? "…" : ""}`);
    }
  }

  return {
    ok: errors.length === 0,
    language: target,
    languageName: LANG_NAMES[target] || target,
    detectedLanguage: detected.code,
    errors,
    warnings,
    stats: {
      titleChars: charCount(title),
      bodyChars,
      paragraphs: paragraphs.length,
      sourceChars,
      ratio,
      scripts: detected.stats,
    },
  };
}

function parseArgs(argv) {
  const out = { language: "", source: "", json: false, file: "" };
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === "--language") out.language = argv[++i] || "";
    else if (arg.startsWith("--language=")) out.language = arg.slice("--language=".length);
    else if (arg === "--source") out.source = argv[++i] || "";
    else if (arg.startsWith("--source=")) out.source = arg.slice("--source=".length);
    else if (arg === "--json") out.json = true;
    else if (arg === "-h" || arg === "--help") out.help = true;
    else if (!arg.startsWith("-") && !out.file) out.file = arg;
    else throw new Error(`未知参数: ${arg}`);
  }
  return out;
}

function readText(filePath) {
  try {
    return fs.readFileSync(filePath, "utf8").replace(/^\uFEFF/, "");
  } catch (error) {
    throw new Error(`读取失败: ${filePath}\n  ${error.message}`);
  }
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  if (args.help || !args.file) {
    process.stdout.write("用法: node validate-note.mjs <笔记.json> [--language ja] [--source 输入文本.md] [--json]\n");
    if (!args.file) process.exit(1);
    return;
  }

  const file = path.resolve(args.file);
  const raw = JSON.parse(readText(file));
  const sourceText = args.source ? readText(path.resolve(args.source)) : "";
  const language = args.language || process.env.AISKILLS_LANGUAGE || raw.language || "";
  const result = validateNote({ raw, language, sourceText });
  result.file = file;
  result.source = args.source ? path.resolve(args.source) : "";

  if (args.json) {
    console.log(JSON.stringify(result));
  } else {
    if (result.ok) console.log("✔ 校验通过");
    else console.log("✖ 校验未通过");
    console.log(`  语言      : ${result.languageName || result.language || "未知"}（检测：${result.detectedLanguage}）`);
    console.log(`  标题      : ${result.stats.titleChars} 字符`);
    console.log(`  正文      : ${result.stats.bodyChars} 字符，${result.stats.paragraphs} 段`);
    if (result.stats.ratio != null) {
      console.log(`  原文比例  : ${(result.stats.ratio * 100).toFixed(0)}%（原文 ${result.stats.sourceChars} 字符）`);
    }
    for (const warning of result.warnings) console.log(`  ⚠ ${warning}`);
    for (const error of result.errors) console.log(`  ✖ ${error}`);
  }

  process.exit(result.ok ? 0 : 1);
}

const isMain =
  process.argv[1] && pathToFileURL(path.resolve(process.argv[1])).href === import.meta.url;
if (isMain) {
  main().catch((error) => {
    process.stderr.write(`✖ ${error.message}\n`);
    process.exit(2);
  });
}
