#!/usr/bin/env node
/**
 * 把整理后的语音笔记写入 Firestore 的 /users/<uid>/notes/<noteid>，并发送 FCM。
 *
 * 设计目标：
 *   - **零依赖**：只用 Node 18+ 内置的 crypto / fetch，skill zip 安装后无需 npm install；
 *   - 写入位置与 text-to-animation-movie 的 Note 完全一致；
 *   - 通知走 users/<uid>/fcmTokens，渠道 luyin_job_result（与 Android App 一致）；
 *   - 失败不丢结果：能写 发布结果.json 就写，方便 skill 在报告里引用。
 *
 * 用法:
 *   node publish-note.mjs --file <笔记.json> [options]
 *
 * 选项:
 *   --file <path>        笔记 JSON（必填）
 *   --noteid <id>        覆盖 Note 文档 id（默认 note.noteid / note.docid / 当前毫秒）
 *   --userid <uid>       覆盖用户 uid
 *   --key <path>         覆盖服务账号 JSON 路径
 *   --language <code>    zh / ja / en / ko（默认 note.language / AISKILLS_LANGUAGE）
 *   --tags <a,b>         覆盖 tags（默认 ["voice","note"]）
 *   --dry-run            只校验并打印，不认证、不写 Firebase、不发通知
 *   --no-notify          写 Note，但不发 FCM
 *   --notify-topic <t>   topic 兜底（默认 AISKILLS_FCM_TOPIC）
 *   --notify-token <t>   指定设备 token（可重复；给了就只发这些）
 *   --result <path>      结果 JSON 路径（默认 <笔记.json 目录>/发布结果.json）
 *   --json               只把结果 JSON 打到 stdout（日志走 stderr）
 *
 * 退出码: 0 成功（含 no-tokens） 1 参数/JSON 错误 2 认证/Firestore 失败
 */

import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import crypto from "node:crypto";
import { pathToFileURL } from "node:url";

const DEFAULT_KEY = path.join(
  os.homedir(),
  "Downloads",
  "vibecodingjapan-firebase-adminsdk-fbsvc-f376a3b494.json",
);
const DEFAULT_UID = "KYTF9y43qgc39vKn0sI3qWpsjRE2";
const DEFAULT_TAGS = ["voice", "note"];
const NOTE_COLOR = "paper";
const MAX_BODY_BYTES = 900_000;
const JOB_CHANNEL_ID = "luyin_job_result";

const NOTIFY_BODY = {
  zh: "语音笔记已保存",
  ja: "音声メモを保存しました",
  en: "Voice note saved",
  ko: "음성 메모가 저장되었습니다",
};

const LANG_ALIASES = new Map([
  ["zh", "zh"], ["cn", "zh"], ["zh-cn", "zh"], ["zh_cn", "zh"], ["zh-tw", "zh"],
  ["zh_tw", "zh"], ["chinese", "zh"], ["中文", "zh"], ["简体中文", "zh"], ["汉语", "zh"],
  ["ja", "ja"], ["jp", "ja"], ["ja-jp", "ja"], ["japanese", "ja"], ["日语", "ja"], ["日本語", "ja"],
  ["en", "en"], ["en-us", "en"], ["en_us", "en"], ["english", "en"], ["英语", "en"], ["英文", "en"],
  ["ko", "ko"], ["kr", "ko"], ["ko-kr", "ko"], ["korean", "ko"], ["韩语", "ko"], ["韓国語", "ko"], ["한국어", "ko"],
]);

const LANG_NAMES = { zh: "简体中文", ja: "日语", en: "英语", ko: "韩语" };

// ---------------------------------------------------------------- 基础工具

class CliError extends Error {
  constructor(message, exitCode = 2) {
    super(message);
    this.name = "CliError";
    this.exitCode = exitCode;
  }
}

function log(args, message) {
  const line = String(message);
  if (args && args.json) process.stderr.write(`${line}\n`);
  else process.stdout.write(`${line}\n`);
}

function warn(args, message) {
  process.stderr.write(`⚠ ${message}\n`);
}

export function normalizeLanguage(value) {
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

export function languageName(code) {
  return LANG_NAMES[normalizeLanguage(code)] || String(code || "未知");
}

function parseArgs(argv) {
  const out = { notifyTokens: [] };
  const take = (i, flag) => {
    if (i + 1 >= argv.length) throw new CliError(`选项 ${flag} 缺少参数值`, 1);
    return argv[i + 1];
  };

  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    const eq = arg.startsWith("--") ? arg.indexOf("=") : -1;
    if (eq !== -1) {
      const flag = arg.slice(0, eq);
      const value = arg.slice(eq + 1);
      switch (flag) {
        case "--file": out.file = value; break;
        case "--noteid": out.noteId = value; break;
        case "--userid": out.userId = value; break;
        case "--key": out.key = value; break;
        case "--language": out.language = value; break;
        case "--tags": out.tags = splitTags(value); break;
        case "--notify-topic": out.notifyTopic = value; break;
        case "--notify-token": out.notifyTokens.push(value); break;
        case "--result": out.result = value; break;
        case "--dry-run": out.dryRun = value !== "false"; break;
        case "--no-notify": out.noNotify = value !== "false"; break;
        case "--json": out.json = value !== "false"; break;
        default: throw new CliError(`未知选项: ${flag}`, 1);
      }
      continue;
    }

    switch (arg) {
      case "--file": out.file = take(i, arg); i += 1; break;
      case "--noteid": out.noteId = take(i, arg); i += 1; break;
      case "--userid": out.userId = take(i, arg); i += 1; break;
      case "--key": out.key = take(i, arg); i += 1; break;
      case "--language": out.language = take(i, arg); i += 1; break;
      case "--tags": out.tags = splitTags(take(i, arg)); i += 1; break;
      case "--notify-topic": out.notifyTopic = take(i, arg); i += 1; break;
      case "--notify-token": out.notifyTokens.push(take(i, arg)); i += 1; break;
      case "--result": out.result = take(i, arg); i += 1; break;
      case "--dry-run": out.dryRun = true; break;
      case "--no-notify": out.noNotify = true; break;
      case "--json": out.json = true; break;
      case "-h":
      case "--help": out.help = true; break;
      default: throw new CliError(`未知参数: ${arg}`, 1);
    }
  }
  return out;
}

function splitTags(value) {
  return String(value || "")
    .split(",")
    .map((s) => s.trim())
    .filter(Boolean);
}

function resolveKeyPath(explicit) {
  const candidate =
    explicit ||
    process.env.AISKILLS_FIREBASE_ACCOUNT ||
    process.env.AISKILLS_NOTE_KEY ||
    process.env.AISKILLS_MOVIE_KEY ||
    DEFAULT_KEY;
  const expanded = String(candidate).replace(/^~(?=$|\/)/, os.homedir());
  return path.resolve(expanded);
}

function resolveUserId(explicit) {
  return String(
    explicit ||
      process.env.AISKILLS_USERID ||
      process.env.AISKILLS_NOTE_UID ||
      process.env.AISKILLS_MOVIE_UID ||
      DEFAULT_UID,
  ).trim();
}

function readJson(filePath) {
  let text;
  try {
    text = fs.readFileSync(filePath, "utf8");
  } catch (error) {
    throw new CliError(`读取笔记 JSON 失败: ${filePath}\n  ${error.message}`, 1);
  }
  try {
    return JSON.parse(text.replace(/^\uFEFF/, ""));
  } catch (error) {
    throw new CliError(`笔记 JSON 无法解析: ${filePath}\n  ${error.message}`, 1);
  }
}

/**
 * 读取并规范化笔记。返回 { note, language, noteId, warnings }。
 * 不联网；所有字段检查都在这里做。
 */
export function parseNote(raw, { args = {}, noteId: explicitNoteId, userId } = {}) {
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) {
    throw new CliError("笔记 JSON 必须是对象", 1);
  }
  const warnings = [];
  const title = String(raw.title ?? "").trim();
  const body = String(raw.body ?? "").replace(/\r\n/g, "\n").trim();
  if (!title) throw new CliError("笔记缺少 title", 1);
  if (!body) throw new CliError("笔记缺少 body", 1);
  if (title.length > 200) throw new CliError(`title 过长（${title.length} 字符，上限 200）`, 1);
  const bodyBytes = Buffer.byteLength(body, "utf8");
  if (bodyBytes > MAX_BODY_BYTES) {
    throw new CliError(
      `body 过大（${bodyBytes} 字节，上限 ${MAX_BODY_BYTES}）。请拆分录音或缩短笔记。`,
      1,
    );
  }

  const language =
    normalizeLanguage(args.language) ||
    normalizeLanguage(raw.language) ||
    normalizeLanguage(process.env.AISKILLS_LANGUAGE) ||
    "";
  if (!language) {
    throw new CliError("无法确定笔记语言（需要 zh / ja / en / ko）", 1);
  }

  const noteId = String(
    explicitNoteId || args.noteId || raw.noteid || raw.docid || Date.now(),
  ).trim();
  if (!noteId) throw new CliError("noteid 不能为空", 1);
  if (noteId.includes("/")) throw new CliError("noteid 不能包含 /", 1);
  if (noteId.length > 200) throw new CliError("noteid 过长（上限 200）", 1);

  if (!userId) throw new CliError("userid 不能为空（用 --userid / AISKILLS_USERID）", 1);

  const tags = Array.isArray(raw.tags) && raw.tags.length
    ? raw.tags.map((t) => String(t).trim()).filter(Boolean)
    : DEFAULT_TAGS;
  const color = String(raw.color || NOTE_COLOR);
  const hidden = raw.hidden === true;

  if (hidden) warnings.push("note.hidden 为 true：Note 写入后 App 的笔记列表不会显示它。");
  if (raw.docid && String(raw.docid) !== noteId) {
    warnings.push(`note.docid=${raw.docid} 与 noteid=${noteId} 不一致；以 noteid 为准。`);
  }
  if (String(raw.language || "").trim() && normalizeLanguage(raw.language) !== language) {
    warnings.push(`note.language=${raw.language} 与目标语言 ${language} 不一致；以目标语言为准。`);
  }

  return {
    note: { title, body, tags, color, hidden },
    language,
    noteId,
    userId,
    warnings,
  };
}

// ---------------------------------------------------------------- Google Auth（JWT）

function base64url(input) {
  return Buffer.from(input).toString("base64url");
}

export function readServiceAccount(keyPath) {
  let parsed;
  try {
    parsed = JSON.parse(fs.readFileSync(keyPath, "utf8"));
  } catch (error) {
    throw new CliError(`服务账号密钥无法读取/解析: ${keyPath}\n  ${error.message}`, 2);
  }
  for (const field of ["client_email", "private_key", "project_id"]) {
    if (!parsed[field]) throw new CliError(`服务账号密钥缺少字段: ${field}`, 2);
  }
  return {
    clientEmail: String(parsed.client_email),
    privateKey: String(parsed.private_key).replace(/\\n/g, "\n"),
    projectId: String(parsed.project_id),
  };
}

export function createJwt(serviceAccount, nowSeconds = Math.floor(Date.now() / 1000)) {
  const header = { alg: "RS256", typ: "JWT" };
  const claims = {
    iss: serviceAccount.clientEmail,
    scope: "https://www.googleapis.com/auth/datastore https://www.googleapis.com/auth/firebase.messaging",
    aud: "https://oauth2.googleapis.com/token",
    iat: nowSeconds,
    exp: nowSeconds + 3600,
  };
  const signingInput = `${base64url(JSON.stringify(header))}.${base64url(JSON.stringify(claims))}`;
  const signature = crypto.createSign("RSA-SHA256").update(signingInput).sign(serviceAccount.privateKey);
  return `${signingInput}.${base64url(signature)}`;
}

export async function getAccessToken(serviceAccount) {
  const assertion = createJwt(serviceAccount);
  const response = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion,
    }),
  });
  const text = await response.text();
  if (!response.ok) {
    throw new CliError(`获取 Google access token 失败（HTTP ${response.status}）：${text.slice(0, 500)}`, 2);
  }
  let payload;
  try {
    payload = JSON.parse(text);
  } catch {
    throw new CliError(`access token 响应不是 JSON：${text.slice(0, 200)}`, 2);
  }
  if (!payload.access_token) throw new CliError("access token 响应里没有 access_token", 2);
  return payload.access_token;
}

// ---------------------------------------------------------------- Firestore REST

export function fsValue(value) {
  if (value === null || value === undefined) return { nullValue: null };
  if (typeof value === "string") return { stringValue: value };
  if (typeof value === "boolean") return { booleanValue: value };
  if (typeof value === "number") {
    return Number.isInteger(value) ? { integerValue: String(value) } : { doubleValue: value };
  }
  if (Array.isArray(value)) return { arrayValue: { values: value.map(fsValue) } };
  if (typeof value === "object") {
    const fields = {};
    for (const [key, v] of Object.entries(value)) fields[key] = fsValue(v);
    return { mapValue: { fields } };
  }
  return { stringValue: String(value) };
}

export function firestoreDocName(projectId, userId, noteId) {
  return `projects/${projectId}/databases/(default)/documents/users/${userId}/notes/${noteId}`;
}

function firestoreBase(projectId) {
  return `https://firestore.googleapis.com/v1/projects/${encodeURIComponent(projectId)}/databases/(default)/documents`;
}

async function readError(response) {
  const text = await response.text();
  try {
    const json = JSON.parse(text);
    return json?.error?.message || text.slice(0, 500);
  } catch {
    return text.slice(0, 500) || `HTTP ${response.status}`;
  }
}

export async function commitNote({ accessToken, projectId, userId, noteId, note, language }) {
  const fields = {
    id: fsValue(noteId),
    title: fsValue(note.title),
    body: fsValue(note.body),
    tags: fsValue(note.tags),
    color: fsValue(note.color),
    hidden: fsValue(note.hidden),
    language: fsValue(language),
  };
  const payload = {
    writes: [
      {
        update: { name: firestoreDocName(projectId, userId, noteId), fields },
        updateMask: { fieldPaths: Object.keys(fields) },
        updateTransforms: [{ fieldPath: "updatedAt", setToServerValue: "REQUEST_TIME" }],
      },
    ],
  };
  const response = await fetch(`${firestoreBase(projectId)}:commit`, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${accessToken}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify(payload),
  });
  if (!response.ok) {
    throw new CliError(`Firestore 写入失败（HTTP ${response.status}）：${await readError(response)}`, 2);
  }
  return response.json();
}

export async function getNote({ accessToken, projectId, userId, noteId }) {
  const response = await fetch(
    `${firestoreBase(projectId)}/users/${encodeURIComponent(userId)}/notes/${encodeURIComponent(noteId)}`,
    { headers: { Authorization: `Bearer ${accessToken}` } },
  );
  if (response.status === 404) return null;
  if (!response.ok) {
    throw new CliError(`Firestore 读回失败（HTTP ${response.status}）：${await readError(response)}`, 2);
  }
  const json = await response.json();
  const fields = json.fields || {};
  return {
    name: json.name,
    title: fields.title?.stringValue ?? "",
    body: fields.body?.stringValue ?? "",
    hidden: fields.hidden?.booleanValue ?? false,
    language: fields.language?.stringValue ?? "",
    updatedAt: fields.updatedAt?.timestampValue ?? "",
  };
}

export async function listFcmTokens({ accessToken, projectId, userId }) {
  const tokens = [];
  let pageToken = "";
  for (;;) {
    const url = new URL(
      `${firestoreBase(projectId)}/users/${encodeURIComponent(userId)}/fcmTokens`,
    );
    url.searchParams.set("pageSize", "300");
    if (pageToken) url.searchParams.set("pageToken", pageToken);
    const response = await fetch(url, { headers: { Authorization: `Bearer ${accessToken}` } });
    if (response.status === 404) return [];
    if (!response.ok) {
      throw new CliError(`读取 FCM tokens 失败（HTTP ${response.status}）：${await readError(response)}`, 2);
    }
    const json = await response.json();
    for (const doc of json.documents || []) {
      const id = String(doc.name || "").split("/").pop();
      const token = doc.fields?.token?.stringValue || id;
      if (token) tokens.push({ id, token });
    }
    pageToken = json.nextPageToken || "";
    if (!pageToken) break;
  }
  const seen = new Set();
  return tokens.filter((entry) => {
    if (seen.has(entry.token)) return false;
    seen.add(entry.token);
    return true;
  });
}

async function deleteFcmToken({ accessToken, projectId, userId, tokenId }) {
  const response = await fetch(
    `${firestoreBase(projectId)}/users/${encodeURIComponent(userId)}/fcmTokens/${encodeURIComponent(tokenId)}`,
    { method: "DELETE", headers: { Authorization: `Bearer ${accessToken}` } },
  );
  if (response.status === 404) return false;
  if (!response.ok) {
    throw new CliError(`清理失效 token 失败（HTTP ${response.status}）：${await readError(response)}`, 2);
  }
  return true;
}

// ---------------------------------------------------------------- FCM v1

export function buildFcmMessage({ token, topic, title, body, language, noteId, userId }) {
  const target = token ? { token } : { topic };
  return {
    message: {
      ...target,
      notification: {
        title: String(title || "").slice(0, 120),
        body: String(body || NOTIFY_BODY.zh).slice(0, 200),
      },
      data: {
        type: "note",
        noteId: String(noteId || ""),
        documentId: String(noteId || ""),
        userId: String(userId || ""),
        language: normalizeLanguage(language) || String(language || ""),
      },
      android: {
        priority: "HIGH",
        notification: {
          channelId: JOB_CHANNEL_ID,
          sound: "default",
        },
      },
    },
  };
}

async function sendFcmMessage({ accessToken, projectId, message }) {
  const response = await fetch(
    `https://fcm.googleapis.com/v1/projects/${encodeURIComponent(projectId)}/messages:send`,
    {
      method: "POST",
      headers: {
        Authorization: `Bearer ${accessToken}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify(message),
    },
  );
  const text = await response.text();
  let json = null;
  try {
    json = text ? JSON.parse(text) : null;
  } catch {
    json = null;
  }
  if (!response.ok) {
    const details = json?.error?.details || [];
    const errorCode = details.find((d) => d && d.errorCode)?.errorCode || "";
    const error = new CliError(
      `FCM 发送失败（HTTP ${response.status}${errorCode ? ` ${errorCode}` : ""}）：${
        json?.error?.message || text.slice(0, 300)
      }`,
      2,
    );
    error.httpStatus = response.status;
    error.fcmCode = errorCode;
    throw error;
  }
  return json;
}

function isInvalidTokenError(error) {
  const code = String(error?.fcmCode || "");
  if (code === "UNREGISTERED") return true;
  if (error?.httpStatus === 404) return true;
  return /registration token|not registered|invalid.*token/i.test(String(error?.message || ""));
}

/**
 * 发送通知。返回 { sent, failed, skipped, error?, cleaned }。
 * 不抛错：通知失败不能影响已经写好的 Note。
 */
export async function notify({
  accessToken,
  projectId,
  userId,
  note,
  language,
  explicitTokens = [],
  topic = "",
  logFn = () => {},
}) {
  const result = { sent: 0, failed: 0, skipped: null, error: "", cleaned: 0 };
  let entries = explicitTokens.map((token) => ({ id: token, token }));

  if (!entries.length) {
    try {
      entries = await listFcmTokens({ accessToken, projectId, userId });
    } catch (error) {
      result.error = error.message;
      logFn(`读取 FCM tokens 失败：${error.message}`);
      entries = [];
    }
  }

  const body = NOTIFY_BODY[normalizeLanguage(language)] || "语音笔记已保存";

  if (entries.length) {
    for (const entry of entries) {
      const message = buildFcmMessage({
        token: entry.token,
        title: note.title,
        body,
        language,
        noteId: note.noteId,
        userId,
      });
      try {
        await sendFcmMessage({ accessToken, projectId, message });
        result.sent += 1;
        logFn(`FCM 已受理（${String(entry.id).slice(0, 12)}…）`);
      } catch (error) {
        result.failed += 1;
        result.error = error.message;
        logFn(`${error.message}（token ${String(entry.id).slice(0, 12)}…）`);
        if (isInvalidTokenError(error)) {
          try {
            const deleted = await deleteFcmToken({ accessToken, projectId, userId, tokenId: entry.id });
            if (deleted) {
              result.cleaned += 1;
              logFn(`已清理失效 token（${String(entry.id).slice(0, 12)}…）`);
            }
          } catch (cleanupError) {
            logFn(`清理失效 token 失败：${cleanupError.message}`);
          }
        }
      }
    }
    if (result.sent > 0) result.error = "";
    return result;
  }

  if (topic) {
    try {
      const message = buildFcmMessage({
        topic,
        title: note.title,
        body,
        language,
        noteId: note.noteId,
        userId,
      });
      await sendFcmMessage({ accessToken, projectId, message });
      result.sent = 1;
      result.error = "";
      logFn(`FCM 已发到 topic "${topic}"`);
    } catch (error) {
      result.failed = 1;
      result.error = error.message;
      logFn(`FCM topic 发送失败：${error.message}`);
    }
    return result;
  }

  result.skipped = "no-tokens";
  logFn("没有可用的设备 token（users/<uid>/fcmTokens 为空），跳过通知。");
  return result;
}

// ---------------------------------------------------------------- 主流程

function printUsage() {
  process.stdout.write(`用法: node publish-note.mjs --file <笔记.json> [选项]

  --file <path>        笔记 JSON（必填）
  --noteid <id>        Note 文档 id（默认 note.noteid / note.docid / 当前毫秒）
  --userid <uid>       用户 uid
  --key <path>         服务账号 JSON
  --language <code>    zh / ja / en / ko
  --tags <a,b>         覆盖 tags（默认 voice,note）
  --dry-run            只校验并打印，不写任何数据
  --no-notify          不发送 FCM
  --notify-topic <t>   topic 兜底
  --notify-token <t>   指定设备 token（可重复）
  --result <path>      结果 JSON 路径
  --json               结果 JSON 打到 stdout
`);
}

function writeResult(resultPath, result) {
  if (!resultPath) return;
  try {
    fs.mkdirSync(path.dirname(resultPath), { recursive: true });
    fs.writeFileSync(resultPath, `${JSON.stringify(result, null, 2)}\n`, "utf8");
  } catch (error) {
    process.stderr.write(`⚠ 无法写入结果文件 ${resultPath}: ${error.message}\n`);
  }
}

function printSummary(args, result) {
  if (args.json) return;
  console.log("");
  console.log(result.ok ? "✔ 完成" : "✖ 失败");
  console.log(`  noteId     : ${result.noteId || ""}`);
  console.log(`  用户       : ${result.userId || ""}`);
  console.log(`  路径       : ${result.notePath || ""}`);
  console.log(`  语言       : ${result.language || ""}`);
  console.log(`  标题       : ${result.title || ""}`);
  console.log(`  正文字符   : ${result.bodyChars ?? ""}`);
  if (result.dryRun) {
    console.log("  模式       : dry-run（未写 Firebase，未发通知）");
  } else {
    console.log(`  Firestore  : ${result.firestore?.verified ? "已写入并读回" : "已写入（读回未确认）"}`);
    const n = result.notify || {};
    if (result.notified) console.log(`  FCM        : 成功 ${n.sent} / 失败 ${n.failed}`);
    else if (n.skipped) console.log(`  FCM        : 跳过（${n.skipped}）`);
    else console.log(`  FCM        : 未发送${n.error ? `（${n.error}）` : ""}`);
  }
  if (result.error) console.log(`  错误       : ${result.error}`);
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  if (args.help) {
    printUsage();
    return;
  }
  if (!args.file) {
    printUsage();
    throw new CliError("--file 是必填项", 1);
  }

  const noteFile = path.resolve(args.file);
  if (!fs.existsSync(noteFile)) throw new CliError(`找不到笔记 JSON: ${noteFile}`, 1);

  const raw = readJson(noteFile);
  const userId = resolveUserId(args.userId);
  const parsed = parseNote(raw, { args, noteId: args.noteId, userId });
  const { note, language, noteId } = parsed;
  const keyPath = resolveKeyPath(args.key);
  const resultPath = args.result
    ? path.resolve(args.result)
    : path.join(path.dirname(noteFile), "发布结果.json");

  const result = {
    ok: false,
    dryRun: Boolean(args.dryRun),
    noteId,
    userId,
    notePath: `users/${userId}/notes/${noteId}`,
    language,
    languageName: languageName(language),
    title: note.title,
    bodyChars: [...note.body].length,
    tags: note.tags,
    firestore: { written: false, verified: false },
    notify: { sent: 0, failed: 0, skipped: null },
    notified: false,
    timestamp: new Date().toISOString(),
    warnings: parsed.warnings,
  };

  for (const warning of parsed.warnings) warn(args, warning);

  if (args.dryRun) {
    result.ok = true;
    result.credentials = keyPath;
    writeResult(resultPath, result);
    if (args.json) console.log(JSON.stringify(result));
    else {
      log(args, "── dry run，不写任何数据 ──");
      log(args, `  笔记文件  : ${noteFile}`);
      log(args, `  密钥      : ${keyPath}（未校验）`);
      log(args, `  用户      : ${userId}`);
      log(args, `  Firestore : ${result.notePath}`);
      log(args, `  语言      : ${language} / ${languageName(language)}`);
      log(args, `  标题      : ${note.title}`);
      log(args, `  正文      : ${result.bodyChars} 字符，${note.body.split(/\n{2,}/).length} 段`);
      log(args, `  tags      : ${note.tags.join(", ")}`);
    }
    return;
  }

  if (!fs.existsSync(keyPath)) {
    throw new CliError(`找不到 Firebase 服务账号密钥: ${keyPath}\n  用 --key 或 AISKILLS_FIREBASE_ACCOUNT 指定`, 2);
  }

  log(args, `→ 用户      : ${userId}`);
  log(args, `→ 密钥      : ${keyPath}`);
  log(args, `→ Firestore : ${result.notePath}`);

  const serviceAccount = readServiceAccount(keyPath);
  result.projectId = serviceAccount.projectId;
  const accessToken = await getAccessToken(serviceAccount);
  log(args, "→ 已获取 Google access token");

  try {
    await commitNote({
      accessToken,
      projectId: serviceAccount.projectId,
      userId,
      noteId,
      note,
      language,
    });
    result.firestore.written = true;
    log(args, `→ 已写入 Note: ${result.notePath}`);
  } catch (error) {
    result.error = error.message;
    writeResult(resultPath, result);
    if (args.json) console.log(JSON.stringify(result));
    else printSummary(args, result);
    throw error;
  }

  try {
    const readBack = await getNote({
      accessToken,
      projectId: serviceAccount.projectId,
      userId,
      noteId,
    });
    if (!readBack) {
      result.warnings.push("写入后读回不到该 Note（可能最终一致延迟）。");
      warn(args, result.warnings[result.warnings.length - 1]);
    } else if (readBack.body !== note.body) {
      result.warnings.push("读回的 body 与写入内容不一致，请检查是否有并发覆盖。");
      warn(args, result.warnings[result.warnings.length - 1]);
    } else {
      result.firestore.verified = true;
      log(args, "→ 读回校验通过");
    }
  } catch (error) {
    result.warnings.push(`读回校验失败：${error.message}`);
    warn(args, result.warnings[result.warnings.length - 1]);
  }

  if (!args.noNotify && process.env.AISKILLS_NOTE_NOTIFY !== "0") {
    const topic = args.notifyTopic || process.env.AISKILLS_FCM_TOPIC || "";
    result.notify = await notify({
      accessToken,
      projectId: serviceAccount.projectId,
      userId,
      note: { ...note, noteId },
      language,
      explicitTokens: args.notifyTokens,
      topic,
      logFn: (message) => log(args, `→ ${message}`),
    });
    result.notified = result.notify.sent > 0;
  } else {
    result.notify.skipped = "disabled";
    log(args, "→ 已跳过 FCM（--no-notify / AISKILLS_NOTE_NOTIFY=0）");
  }

  result.ok = true;
  result.timestamp = new Date().toISOString();
  writeResult(resultPath, result);

  if (args.json) console.log(JSON.stringify(result));
  else printSummary(args, result);
}

const isMain =
  process.argv[1] && pathToFileURL(path.resolve(process.argv[1])).href === import.meta.url;

if (isMain) {
  main().catch((error) => {
    if (error instanceof CliError) {
      process.stderr.write(`✖ ${error.message}\n`);
      process.exit(error.exitCode || 2);
    }
    process.stderr.write(`✖ 未预期的错误: ${error && error.stack ? error.stack : error}\n`);
    process.exit(2);
  });
}
