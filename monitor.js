/**
 * Monitor 运行时（Firebase Admin 版，自动监听）
 *
 * 全部 Firebase 交互由服务端通过 firebase-admin（服务账号）完成：
 *  - Firestore collectionGroup("recordings") 自动监听（启动即开始，无需手动）
 *  - 只处理 .env 中 MONITOR_USER_IDS 指定的用户（文档字段 MONITOR_USER_ID_FIELD）的录音
 *  - GCS 音频下载（admin.storage）、Firestore 回写（state: success/failed）
 * 流水线：监听新记录 → 入队 → 下载 GCS 音频 → FFmpeg 转 16k wav → faster-whisper 转录
 *        → sidecar 创建 Google Doc → 回写 Firestore。
 *
 * 本进程（node server.js）就是"声音监控程序"本体：Web 页面只是查看器，
 * 页面开不开都不影响监听与处理。
 */
import admin from "firebase-admin";
import { spawn, execFileSync } from "node:child_process";
import { createInterface } from "node:readline";
import { existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { homedir } from "node:os";
import { join, resolve, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import {
  SKILL_SERVICE_TYPE,
  buildSkillResumePrompt,
  inspectSkillOutputs,
  localSkillInstalled,
  recordLocalSkill,
  removeLocalSkill,
  resolveSkillInstall,
  runAiskillsrunner,
  runSkillWithResume
} from "./monitor-skills.js";
import { sendJobNotification } from "./monitor-notify.js";

const projectRoot = resolve(dirname(fileURLToPath(import.meta.url)));
const dataDir = join(projectRoot, "monitor-data");
/** Skills 安装目录（默认）：只给本后端用，不污染 ~/.agents/skills。 */
const defaultSkillsDir = join(dataDir, "skills");
/** Skills 流程的本地 STT 文本目录。 */
const sttDir = join(dataDir, "stt");
/** 产物校验：博客文章所在的 Firestore 集合（可用 BLOG_COLLECTION 覆盖）。 */
const blogCollection = process.env.BLOG_COLLECTION || "mkblog";
/**
 * 产物校验：skill 的临时输出根目录（`~/Documents/tmpaiskill/<DocID>/`）。
 * 与 skill 里的 AISKILLS_BLOG_TMPDIR 约定保持一致。
 */
const skillTmpRoot = process.env.AISKILLS_BLOG_TMPDIR || join(homedir(), "Documents", "tmpaiskill");
/** 默认做产物校验的 skill（SKILL_VERIFY_NAMES 未设置时用；设为 * 表示对所有 skill 生效）。 */
const DEFAULT_VERIFY_SKILL_NAMES = ["audio-to-multilingual-blog"];
/** 判定「输出目录已完成」所需的 4 个文件（与 audio-to-multilingual-blog 的约定一致）。 */
const SKILL_OUTPUT_FILES = ["中文.md", "日文.md", "英文.md", "文章信息.json"];
const jobsPath = join(dataDir, "jobs.json");
const statePath = join(dataDir, "state.json");
const driveTokenPath = join(dataDir, "drive", "oauth-token.json");
const sidecarScript = join(projectRoot, "monitor-sidecar", "index.js");
const venvPython = join(projectRoot, ".venv", process.platform === "win32" ? "Scripts/python.exe" : "bin/python");
const transcribeScript = join(projectRoot, "scripts", "faster_whisper_transcribe.py");
const modelDir = join(projectRoot, "models", "faster-whisper");

const FASTER_MODELS = ["tiny", "base", "small", "medium", "large-v3", "distil-large-v3"];
/**
 * Firestore 文档字段 kind 取该值时，storageUri 指向的是一份 Markdown 文本文件
 * （而不是录音）：跳过 FFmpeg / STT，直接把文本交给后续 Action 处理。
 */
const MARKDOWN_KIND = "markdown";
const ACTIVE_STAGES = [
  "queued",
  "downloading",
  "reading_text",
  "transcribing",
  "creating_doc",
  "updating_firestore",
  "installing_skill",
  "running_skill"
];
const RUNNING_STAGES = ["reading_text", "transcribing", "creating_doc", "installing_skill", "running_skill"];
const MAX_LOGS = 200;
/** 单条 skill 输出回写 Firestore 的最大长度，避免文档超过 1MB。 */
const MAX_SKILL_OUTPUT_CHARS = 30000;

/* ---------------- 工具 ---------------- */

function nowMs() {
  return Date.now();
}

function sanitizeFileStem(value, fallback = "item") {
  const safe = String(value || "")
    .replace(/[^a-zA-Z0-9._-]+/g, "_")
    .replace(/^_+|_+$/g, "")
    .slice(0, 100);
  return safe || fallback;
}

function readJsonFile(path, fallback) {
  try {
    return JSON.parse(readFileSync(path, "utf8"));
  } catch {
    return fallback;
  }
}

function writeJsonFile(path, value) {
  mkdirSync(dirname(path), { recursive: true });
  writeFileSync(path, JSON.stringify(value, null, 2), "utf8");
}

function normalizeLanguage(value) {
  switch (String(value || "").trim().toLowerCase()) {
    case "zh":
    case "cn":
    case "zh-cn":
    case "zh_tw":
    case "zh-tw":
      return "zh";
    case "ja":
    case "jp":
      return "ja";
    case "kr":
    case "ko":
      return "ko";
    case "en":
      return "en";
    default:
      return "auto";
  }
}

function normalizeWhisperModel(value) {
  let model = String(value || "faster:small").trim();
  if (model.startsWith("faster:")) model = model.slice("faster:".length);
  else model = model.split(":").pop();
  if (model === "distill-large-v3") model = "distil-large-v3";
  return FASTER_MODELS.includes(model) ? `faster:${model}` : "faster:small";
}

function isGsUrl(value) {
  return /^gs:\/\/[^/]+\/.+/.test(String(value || "").trim());
}

/**
 * 该任务的 storageUri 是否指向 Markdown 文本（Firestore 字段 kind === "Markdown"）。
 * 兼容大小写与常见写法（markdown / MARKDOWN / md / text/markdown）。
 */
function isMarkdownKind(value) {
  const kind = String(value || "").trim().toLowerCase();
  return kind === MARKDOWN_KIND || kind === "md" || kind === "text/markdown";
}

function isMarkdownJob(job) {
  return isMarkdownKind(job && job.kind);
}

/** Skills 执行超时（毫秒），非法值回退 1.5 小时；下限 10 秒。
 *  视频渲染类 skill（text-to-animation-movie）要装依赖 + 渲染上千帧，
 *  30 分钟会在渲染中途被杀，所以默认放到 90 分钟。 */
function normalizeSkillsTimeout(value) {
  const parsed = Number(value);
  return Number.isFinite(parsed) && parsed >= 10_000 ? Math.trunc(parsed) : 90 * 60 * 1000;
}

/** 「a,b」/ 数组 → 去空去重的字符串数组（产物校验的 skill 名单用）。 */
function normalizeSkillNameList(value) {
  const items = Array.isArray(value) ? value : String(value || "").split(",");
  return [...new Set(items.map((item) => String(item || "").trim()).filter(Boolean))];
}

/**
 * 产物校验不过时，最多再「续跑」几个回合（headless 是单回合语义，续跑 = 再起一次
 * `dsh --profile headless`）。默认 2，0 = 关闭续跑，上限 5 防止无限烧配额。
 */
function normalizeSkillResumeAttempts(value) {
  const parsed = Number(value);
  if (!Number.isFinite(parsed) || parsed < 0) return 2;
  return Math.min(5, Math.trunc(parsed));
}

function parseGsUrl(value) {
  const match = String(value || "").match(/^gs:\/\/([^/]+)\/(.+)$/);
  if (!match) throw new Error(`recordFile 必须是 gs:// URL: ${value}`);
  return { bucket: match[1], object: decodeURIComponent(match[2]) };
}

function cleanFolderId(value) {
  const trimmed = String(value || "").trim();
  return trimmed ? trimmed : null;
}

function timestampForTitle() {
  return String(Math.floor(Date.now() / 1000));
}

function extractExtension(gsUrl) {
  const path = String(gsUrl || "").split("?")[0];
  const last = path.split("/").pop() || "";
  const ext = last.includes(".") ? last.split(".").pop() : "";
  return ext && ext.length <= 8 ? ext : "audio";
}

/* ---------------- 状态 ---------------- */

const state = {
  config: {
    adminKeyPath: null,
    oauthClientPath: null,
    driveFolderId: null,
    monitoredUsers: [],
    userIdField: "uid",
    whisperModel: "faster:small",
    // ---- Skills（DeepSeek Harness）----
    skillsDir: defaultSkillsDir,
    skillsRunnerBin: "aiskillsrunner",
    skillsRunnerTimeoutMs: 90 * 60 * 1000,
    sttOutputDir: sttDir,
    dshPermissionMode: "danger-full-access",
    /** skill 退出码为 0 时，是否再校验产物（见 verifySkillUpload）。 */
    verifySkillUpload: true,
    /** 需要做产物校验的 skill 名单（默认见 DEFAULT_VERIFY_SKILL_NAMES）。 */
    verifySkillNames: [...DEFAULT_VERIFY_SKILL_NAMES],
    /** 产物校验不过时最多再续跑几个回合（0 = 关闭）。 */
    skillResumeAttempts: 2,
    // ---- FCM 推送 ----
    fcmEnabled: true
  },
  admin: null,
  db: null,
  listener: null,
  adminError: null,
  driveAccountEmail: null,
  jobs: [],
  monitoring: true,
  sidecar: null,
  sidecarStdin: null,
  pending: new Map(),
  requestCounter: 0,
  processing: false,
  logs: [],
  started: false
};

function defaultSnapshot() {
  return {
    monitoring: state.monitoring,
    adminReady: Boolean(state.admin),
    adminError: state.adminError,
    config: {
      projectId: state.config.projectId || null,
      monitoredUsers: state.config.monitoredUsers,
      userIdField: state.config.userIdField,
      driveAccountEmail: state.driveAccountEmail,
      driveFolderId: state.config.driveFolderId || null,
      whisperModel: state.config.whisperModel,
      oauthClientConfigured: Boolean(state.config.oauthClientPath && existsSync(state.config.oauthClientPath)),
      skillsDir: state.config.skillsDir,
      skillsRunnerBin: state.config.skillsRunnerBin,
      sttOutputDir: state.config.sttOutputDir,
      dshPermissionMode: state.config.dshPermissionMode,
      fcmEnabled: state.config.fcmEnabled
    },
    jobs: state.jobs,
    logs: state.logs.slice(-MAX_LOGS),
    sidecarReady: Boolean(state.sidecarStdin)
  };
}

function loadJobs() {
  const loaded = readJsonFile(jobsPath, []);
  state.jobs = Array.isArray(loaded) ? loaded : [];
}

function persistJobs() {
  writeJsonFile(jobsPath, state.jobs);
}

function loadDriveEmail() {
  const saved = readJsonFile(statePath, {});
  state.driveAccountEmail = saved.driveAccountEmail || null;
}

function persistDriveEmail() {
  writeJsonFile(statePath, { driveAccountEmail: state.driveAccountEmail });
}

/* ---------------- 日志 ---------------- */

let emitLog = () => {};

function log(text) {
  const line = { t: nowMs(), text: String(text) };
  state.logs.push(line);
  if (state.logs.length > MAX_LOGS) state.logs.splice(0, state.logs.length - MAX_LOGS);
  emitLog(line.text);
}

/* ---------------- Firebase Admin ---------------- */

function initializeAdmin() {
  const keyPath = state.config.adminKeyPath;
  if (!keyPath || !existsSync(keyPath)) {
    state.adminError = `FIREBASE_ADMIN_KEY_PATH 未设置或文件不存在：${keyPath || "(空)"}`;
    log(`[fatal] ${state.adminError}`);
    return false;
  }
  try {
    const serviceAccount = JSON.parse(readFileSync(keyPath, "utf8"));
    admin.initializeApp({
      credential: admin.credential.cert(serviceAccount),
      projectId: serviceAccount.project_id
    });
    state.admin = admin;
    state.db = admin.firestore();
    state.config.projectId = serviceAccount.project_id;
    state.adminError = null;
    log(`Firebase Admin 已初始化（项目 ${serviceAccount.project_id}）`);
    return true;
  } catch (error) {
    state.adminError = `Firebase Admin 初始化失败：${error.message}`;
    log(`[fatal] ${state.adminError}`);
    return false;
  }
}

/* ---------------- 自动监听 ---------------- */

/**
 * 推导记录所属用户 ID：
 *  1) 优先取文档字段（MONITOR_USER_ID_FIELD，默认 uid）；
 *  2) 否则从文档路径推导 users/{uid}/recordings/{id} 中的 uid。
 */
function deriveUserId(documentPath, data) {
  const fieldUid = String((data && data[state.config.userIdField]) || "").trim();
  if (fieldUid) return fieldUid;
  const segments = String(documentPath || "").split("/recordings/")[0].split("/").filter(Boolean);
  return segments.length ? segments[segments.length - 1] : "";
}

function startListener() {
  if (!state.db || state.listener) return;
  let initialized = false;
  let pendingSeen = 0;
  state.listener = state.db
    .collectionGroup("recordings")
    .onSnapshot(
      (snapshot) => {
        for (const change of snapshot.docChanges()) {
          if (change.type !== "added" && change.type !== "modified") continue;
          const data = change.doc.data() || {};
          const path = change.doc.ref.path;
          const userId = deriveUserId(path, data);

          if (state.config.monitoredUsers.length > 0 && !state.config.monitoredUsers.includes(userId)) {
            if (initialized) log(`跳过（非监视用户 ${userId || "(空)"}）：${path}`);
            continue;
          }
          const recordFile = data.storageUri || data.recordFile || "";
          if (data.state === "success") {
            if (initialized) log(`跳过（已处理成功）：${path}`);
            continue;
          }
          if (!isGsUrl(recordFile)) {
            if (initialized) log(`跳过（recordFile 非 gs:// URL）：${path}`);
            continue;
          }
          if (!initialized) pendingSeen += 1;
          enqueueRecord({
            documentPath: path,
            documentId: change.doc.id,
            recordFile,
            // kind === "Markdown" 时 recordFile 是 md 文本文件，后续跳过 STT。
            kind: data.kind || "",
            projectId: data.projectID || state.config.projectId,
            language: data.language || "",
            aicallingid: data.aicallingid || null,
            // CMS 服务身份：serviceType=deepseek_harness 时走 Skills 流程，否则走 Google Drive。
            serviceId: data.serviceId || null,
            serviceType: data.serviceType || null,
            userId
          });
        }
        if (!initialized) {
          initialized = true;
          if (pendingSeen > 0) {
            log(`首次快照：发现 ${pendingSeen} 条待处理记录，已入队。`);
          }
        }
      },
      (error) => {
        log(`Firestore 监听错误：${error.message}`);
      }
    );
  const users = state.config.monitoredUsers.length
    ? state.config.monitoredUsers.join(", ")
    : "（全部用户，未限制）";
  log(`Firestore 监听已自动启动（collectionGroup: recordings；监视用户: ${users}）`);
}

/* ---------------- 任务队列 ---------------- */

function enqueueRecord(record) {
  const now = nowMs();
  const id = `${state.config.projectId}::${String(record.documentPath || "").replace(/\//g, "_")}`;
  let shouldSpawn = false;

  const duplicate = state.jobs.find(
    (job) =>
      job.recordFile === record.recordFile &&
      job.id !== id &&
      (ACTIVE_STAGES.includes(job.stage) || job.stage === "success")
  );
  if (duplicate) {
    log(`跳过重复录音：${record.recordFile} 已在处理/已完成。`);
  } else {
    const existing = state.jobs.find((job) => job.id === id);
    if (existing) {
      if (existing.stage === "failed") {
        Object.assign(existing, {
          documentPath: record.documentPath,
          documentId: record.documentId,
          recordFile: record.recordFile,
          kind: record.kind || "",
          projectId: record.projectId,
          language: record.language,
          userId: record.userId,
          serviceId: record.serviceId || null,
          serviceType: record.serviceType || null,
          requestedGoogleFolderId: cleanFolderId(record.aicallingid),
          updatedAt: now
        });
      } else if (existing.stage === "queued") {
        existing.updatedAt = now;
        shouldSpawn = true;
      }
    } else {
      state.jobs.push({
        id,
        documentPath: record.documentPath,
        documentId: record.documentId,
        recordFile: record.recordFile,
        kind: record.kind || "",
        projectId: record.projectId,
        language: record.language,
        userId: record.userId,
        serviceId: record.serviceId || null,
        serviceType: record.serviceType || null,
        requestedGoogleFolderId: cleanFolderId(record.aicallingid),
        googleFolderId: null,
        skillName: null,
        skillVersion: null,
        stage: "queued",
        progress: 0,
        errorMessage: null,
        googleDocId: null,
        googleDocUrl: null,
        createdAt: now,
        updatedAt: now
      });
      shouldSpawn = true;
    }
  }

  persistJobs();
  emitSnapshot();
  if (shouldSpawn) scheduleQueue();
}

function resumeInterruptedJobs() {
  let changed = false;
  for (const job of state.jobs) {
    if (RUNNING_STAGES.includes(job.stage)) {
      job.stage = "queued";
      job.progress = 0;
      job.errorMessage = "Previous processing was interrupted; queued again.";
      job.updatedAt = nowMs();
      changed = true;
    } else if (job.stage === "failed") {
      // 自愈：启动时自动重试失败任务（永久性错误会快速失败并保留原因）
      job.stage = "queued";
      job.progress = 0;
      job.errorMessage = null;
      job.updatedAt = nowMs();
      changed = true;
    }
  }
  if (changed) {
    persistJobs();
    emitSnapshot();
  }
}

/** Drive 授权成功后，重试所有失败任务（多数失败源于尚未授权）。 */
function requeueFailedJobs() {
  let changed = false;
  for (const job of state.jobs) {
    if (job.stage === "failed") {
      job.stage = "queued";
      job.progress = 0;
      job.errorMessage = null;
      job.updatedAt = nowMs();
      changed = true;
    }
  }
  if (changed) {
    persistJobs();
    emitSnapshot();
    scheduleQueue();
  }
}

function nextQueuedJob() {
  if (state.jobs.some((job) => RUNNING_STAGES.includes(job.stage))) return null;
  return state.jobs.find((job) => job.stage === "queued") || null;
}

function updateJob(jobId, updateFn) {
  const job = state.jobs.find((entry) => entry.id === jobId);
  if (job) {
    updateFn(job);
    job.updatedAt = nowMs();
  }
  persistJobs();
  emitSnapshot();
}

async function processQueue() {
  if (state.processing) return;
  state.processing = true;
  try {
    let job = nextQueuedJob();
    while (job) {
      // 任何未预期的异常都要变成「任务失败」，绝不能让队列悄悄停住
      let result;
      try {
        result = await processJob(job);
      } catch (error) {
        result = { error: `任务异常：${error?.message || String(error)}` };
      }
      if (result?.wait) {
        // 外部条件未就绪（如 Drive 未授权）：任务保持 queued，等待条件满足后重试
        log(`任务等待中（${job.documentId}）：${result.message}`);
        break;
      }
      // processJob 的返回约定：失败 -> 字符串；等待外部条件 -> { wait: true }；成功 -> null。
      // 注意不能只看 result.error —— 失败返回的是字符串，之前这里判断错了，
      // 导致失败任务既不写 failed 也不出队，整个队列被永久堵死。
      const failureMessage = typeof result === "string" ? result : result?.error;
      if (failureMessage) {
        updateJob(job.id, (entry) => {
          entry.stage = "failed";
          entry.progress = 100;
          entry.errorMessage = failureMessage;
        });
        log(`任务失败（${job.documentId}）：${failureMessage}`);
        await updateFirestoreFailure(job, failureMessage);
        await notifyJobResult(job, { ok: false, body: failureMessage });
      }
      job = nextQueuedJob();
    }
  } finally {
    state.processing = false;
  }
}

/** 触发队列处理；吞掉异常并记录，避免未捕获的 rejection 拖垮整个后端。 */
function scheduleQueue() {
  processQueue().catch((error) => {
    log(`队列处理异常：${error?.message || error}`);
  });
}

function jobWorkPath(job) {
  const dir = join(dataDir, "work");
  mkdirSync(dir, { recursive: true });
  return join(dir, `${sanitizeFileStem(job.id, "job")}.${extractExtension(job.recordFile)}`);
}

async function downloadFromStorage(recordFile, outputPath) {
  const { bucket, object } = parseGsUrl(recordFile);
  const file = state.admin.storage().bucket(bucket).file(object);
  await file.download({ destination: outputPath });
}

async function updateFirestoreSuccess(job, fields = {}) {
  const payload = {
    state: "success",
    errorMessage: "",
    updateite: admin.firestore.FieldValue.serverTimestamp()
  };
  // Google Drive 流程
  if (fields.googleDocId !== undefined) payload.googleDocId = fields.googleDocId || "";
  if (fields.googleDocUrl !== undefined) payload.googleDocUrl = fields.googleDocUrl || "";
  if (fields.googleFolderId !== undefined) payload.googleFolderId = fields.googleFolderId || "";
  // Skills 流程
  if (fields.serviceId !== undefined) payload.serviceId = fields.serviceId;
  if (fields.serviceType !== undefined) payload.serviceType = fields.serviceType;
  if (fields.skillName !== undefined) payload.skillName = fields.skillName;
  if (fields.skillVersion !== undefined) payload.skillVersion = fields.skillVersion;
  if (fields.skillOutput !== undefined) payload.skillOutput = fields.skillOutput;
  // 产物校验结果（见 verifySkillUpload）：确认 mkblog 里真的存在这篇文章
  if (fields.skillUploadVerified !== undefined) payload.skillUploadVerified = fields.skillUploadVerified;
  if (fields.blogDocId !== undefined) payload.blogDocId = fields.blogDocId;
  if (fields.sttTextPath !== undefined) payload.sttTextPath = fields.sttTextPath;
  if (fields.sttTextUrl !== undefined) payload.sttTextUrl = fields.sttTextUrl;
  if (fields.sttTextLocalPath !== undefined) payload.sttTextLocalPath = fields.sttTextLocalPath;
  await state.db.doc(job.documentPath).set(payload, { merge: true });
}

/* ---------------- 推送与 Skills 辅助 ---------------- */

/** 该任务是否走 Skills（DeepSeek Harness）流程。 */
function isSkillJob(job) {
  return String(job.serviceType || "") === SKILL_SERVICE_TYPE;
}

function jobLabel(job) {
  return job.skillName || job.documentId || "任务";
}

/** 任务结束后推送一条通知给该用户的 App；推送失败不影响任务结果。 */
async function notifyJobResult(job, { ok, body }) {
  if (!state.config.fcmEnabled || !state.admin || !state.db) return;
  const title = ok ? `${jobLabel(job)} 执行完毕` : `${jobLabel(job)} 执行失败`;
  try {
    await sendJobNotification({
      messaging: state.admin.messaging(),
      db: state.db,
      userId: job.userId,
      title,
      body,
      data: {
        type: ok ? "job_success" : "job_failed",
        documentId: job.documentId || "",
        serviceId: job.serviceId || "",
        serviceType: job.serviceType || "",
        skillName: job.skillName || "",
        skillVersion: job.skillVersion == null ? "" : String(job.skillVersion),
        googleDocUrl: job.googleDocUrl || ""
      },
      log
    });
  } catch (error) {
    log(`推送失败：${error.message}`);
  }
}

/* ---------------- Skills 产物校验 ---------------- */

/**
 * 只凭 exit code 判定 skill 成功会漏掉一类真实故障：
 * headless（`dsh --profile headless`）是**单回合**语义 —— agent 一旦结束回合，
 * 进程立即退出，仍在运行的后台子代理会被一并杀掉；上传步骤（Step8）根本没跑到，
 * 退出码却依然是 0。历史上这会把「只写完 中文.md / 英文.md」的半成品记成 success，
 * 既回写假成功、又推「执行完毕」通知，而且启动自愈只重试 failed，永远不会补做。
 *
 * 这里的独立校验：从 skill 输出里找出 DocID（退化为按 mtime 找刚写出的输出目录），
 * 再确认 Firestore 里确实存在该文章；校验不过就按失败处理。
 */
const DOCID_PATTERNS = [
  /tmpaiskill[\\/]+(\d{10,})/g,
  /\/blog\/(\d{10,})/g,
  /\/editblog\/(\d{10,})/g,
  /\bdocid\b[^\d\n]{0,24}?(\d{10,})/gi
];

/** 从 skill 输出文本里提取 DocID 候选（Unix 毫秒，10 位以上）。 */
function extractDocIds(text) {
  const ids = new Set();
  const source = String(text || "");
  for (const pattern of DOCID_PATTERNS) {
    pattern.lastIndex = 0;
    let match;
    while ((match = pattern.exec(source)) !== null) ids.add(match[1]);
  }
  return [...ids];
}

/** 兜底：本次执行窗口内被写过、且 4 个文件齐全的输出目录名，作为 DocID 候选。 */
function recentDocIdDirs(startedAtMs) {
  return inspectSkillOutputs({ root: skillTmpRoot, startedAtMs, files: SKILL_OUTPUT_FILES })
    .filter((output) => output.complete)
    .map((output) => output.docId);
}

/** 返回 true=存在 / false=不存在 / null=查询失败（无法判定，不能算失败）。 */
async function blogDocExists(docId) {
  try {
    const snapshot = await state.db.collection(blogCollection).doc(docId).get();
    return snapshot.exists;
  } catch (error) {
    log(`产物校验查询失败（${blogCollection}/${docId}）：${error.message}`);
    return null;
  }
}

/**
 * 校验 skill 是否真的把文章上传成功。
 * 返回 { docId, verified: true } | { skipped: true, reason } | { error: "失败原因" }。
 */
async function verifySkillUpload({ skillName, stdout, startedAtMs }) {
  if (!state.config.verifySkillUpload || !state.db) return { skipped: true };
  const names = state.config.verifySkillNames || [];
  if (names.length && !names.includes(skillName)) {
    return { skipped: true, reason: `skill「${skillName}」不在产物校验名单（SKILL_VERIFY_NAMES）` };
  }

  // 输出里报了 DocID 就**只认它**（不再退化到 mtime），避免用别的旧目录造成假通过；
  // 只有输出里完全没有 DocID 时，才按「本次新写出的完整目录」兜底。
  const reported = extractDocIds(stdout);
  const groups = reported.length
    ? [{ ids: reported, by: "输出中的 DocID" }]
    : [{ ids: recentDocIdDirs(startedAtMs), by: "输出目录 mtime" }];

  let inconclusive = false;
  const tried = [];
  for (const group of groups) {
    for (const docId of group.ids) {
      tried.push(docId);
      const exists = await blogDocExists(docId);
      if (exists === true) return { docId, verified: true, matchedBy: group.by };
      if (exists === null) inconclusive = true;
    }
  }
  if (inconclusive) {
    return { skipped: true, reason: "Firestore 查询失败，无法校验产物" };
  }
  if (tried.length === 0) {
    return {
      error:
        `产物校验失败：skill 退出码为 0，但输出里没有 DocID、${skillTmpRoot} 下也没有本次生成的` +
        `完整输出目录（需含 ${SKILL_OUTPUT_FILES.join(" / ")}）。疑似 agent 提前结束回合` +
        `（headless 单回合语义），请查 journalctl 里的 [skill:*] 日志。`
    };
  }
  return {
    error:
      `产物校验失败：skill 退出码为 0，但 ${blogCollection} 里不存在候选文章` +
      `（${[...new Set(tried)].slice(0, 3).join(", ")}）。上传步骤很可能没跑到 ——` +
      `headless 下单回合结束=进程退出，仍在运行的后台子代理会被一并杀掉。`
  };
}

/** Skills 流程的本地 STT 文本路径：<sttOutputDir>/<uid>/<documentId>.txt */
function transcriptPath(job) {
  const dir = join(state.config.sttOutputDir, sanitizeFileStem(job.userId, "user"));
  mkdirSync(dir, { recursive: true });
  return join(dir, `${sanitizeFileStem(job.documentId, "record")}.txt`);
}

/** STT 结果按用户要求留在本机，不依赖 Google Drive。 */
function writeTranscriptLocally(job, transcript) {
  const path = transcriptPath(job);
  writeFileSync(path, String(transcript || ""), "utf8");
  return path;
}

/**
 * 把转录文本备份到 Firebase Storage，返回 { gsUrl, httpsUrl, object, bucket }。
 * 桶名直接取自录音的 gs:// 地址，避免 admin.storage().bucket() 默认桶
 * （<projectId>.appspot.com）与项目实际使用的 *.firebasestorage.app 不一致。
 */
async function uploadTranscriptToStorage(job, transcript) {
  const { bucket: bucketName } = parseGsUrl(job.recordFile);
  const bucket = state.admin.storage().bucket(bucketName);
  const object = `stt/${sanitizeFileStem(job.userId, "user")}/${sanitizeFileStem(job.documentId, "record")}.txt`;
  await bucket.file(object).save(String(transcript || ""), {
    contentType: "text/plain; charset=utf-8",
    resumable: false
  });
  const gsUrl = `gs://${bucketName}/${object}`;
  const httpsUrl =
    `https://firebasestorage.googleapis.com/v0/b/${encodeURIComponent(bucketName)}` +
    `/o/${encodeURIComponent(object)}?alt=media`;
  return { gsUrl, httpsUrl, object, bucket: bucketName };
}

/** 读取 CMS 服务文档，取出 skill 名称、生效版本与下载地址。 */
async function loadSkillService(serviceId) {
  const snap = await state.db.collection("allalservice").doc(serviceId).get();
  if (!snap.exists) throw new Error(`allalservice/${serviceId} 不存在`);
  const data = snap.data() || {};
  return {
    skillName: String(data.applicationName || "").trim(),
    version: Number(data.skillActiveVersion) || 0,
    installUrl: String(data.skillZipUrl || "").trim(),
    type: String(data.type || "")
  };
}

async function updateFirestoreFailure(job, errorMessage) {
  try {
    await state.db.doc(job.documentPath).set(
      {
        state: "failed",
        errorMessage: errorMessage || "Processing failed.",
        updateite: admin.firestore.FieldValue.serverTimestamp()
      },
      { merge: true }
    );
  } catch (error) {
    log(`回写 Firestore failed 失败（${job.documentId}）：${error.message}`);
  }
}

async function processJob(job) {
  // Skills（DeepSeek Harness）流程完全不走 Google Drive / OAuth
  if (isSkillJob(job)) return processSkillJob(job);

  // 外部条件未就绪时返回 { wait: true }：任务保持 queued，不写失败状态
  if (!state.config.oauthClientPath || !existsSync(state.config.oauthClientPath)) {
    return { wait: true, message: "GOOGLE_OAUTH_CLIENT_PATH 未设置或文件不存在（需在 .env 配置后重启）。" };
  }
  if (!state.driveAccountEmail) {
    return { wait: true, message: "Google Drive 尚未授权：请打开 Web 页面点击一次「Connect Google」完成授权。" };
  }
  if (!state.config.driveFolderId) {
    return { wait: true, message: "GOOGLE_DRIVE_FOLDER_ID 未设置（需在 .env 配置后重启）。" };
  }

  const localPath = jobWorkPath(job);
  updateJob(job.id, (entry) => {
    entry.stage = "downloading";
    entry.progress = 15;
  });
  try {
    await downloadFromStorage(job.recordFile, localPath);
    log(`已下载：${job.recordFile}`);
  } catch (error) {
    return `Download failed: ${error.message}`;
  }

  const markdown = isMarkdownJob(job);
  updateJob(job.id, (entry) => {
    entry.stage = markdown ? "reading_text" : "transcribing";
    entry.progress = 45;
  });
  let transcript;
  try {
    transcript = await extractJobText(job, localPath);
  } catch (error) {
    return `${markdown ? "Read markdown" : "Transcription"} failed: ${error.message}`;
  }

  updateJob(job.id, (entry) => {
    entry.stage = "creating_doc";
    entry.progress = 75;
  });
  const title = `${sanitizeFileStem(state.config.projectId, "project")}_${sanitizeFileStem(job.documentId, "document")}_${timestampForTitle()}`;
  const defaultFolderId = state.config.driveFolderId;
  const requestedFolderId = cleanFolderId(job.requestedGoogleFolderId);
  let targetFolderId = requestedFolderId || defaultFolderId;
  updateJob(job.id, (entry) => {
    entry.googleFolderId = targetFolderId;
  });

  let docResult;
  try {
    await configureSidecar();
    docResult = await sidecarRequest(
      "create_doc",
      { title, text: transcript, folderId: targetFolderId },
      120_000
    );
  } catch (error) {
    if (requestedFolderId && targetFolderId !== defaultFolderId) {
      updateJob(job.id, (entry) => {
        entry.stage = "creating_doc";
        entry.progress = 78;
        entry.googleFolderId = defaultFolderId;
        entry.errorMessage = `Folder ${targetFolderId} failed. Retrying default folder.`;
      });
      targetFolderId = defaultFolderId;
      try {
        docResult = await sidecarRequest(
          "create_doc",
          { title, text: transcript, folderId: targetFolderId },
          120_000
        );
      } catch (retryError) {
        return `Google Doc creation failed: ${retryError.message}`;
      }
    } else {
      return `Google Doc creation failed: ${error.message}`;
    }
  }

  const googleDocId = docResult.googleDocId || "";
  const googleDocUrl = docResult.googleDocUrl || "";
  const googleFolderId = docResult.googleFolderId || targetFolderId;

  updateJob(job.id, (entry) => {
    entry.stage = "updating_firestore";
    entry.progress = 90;
    entry.googleDocId = googleDocId;
    entry.googleDocUrl = googleDocUrl;
    entry.googleFolderId = googleFolderId;
    entry.errorMessage = null;
  });
  try {
    await updateFirestoreSuccess(job, { googleDocId, googleDocUrl, googleFolderId });
  } catch (error) {
    return `Firestore update failed: ${error.message}`;
  }

  rmSync(localPath, { force: true });
  updateJob(job.id, (entry) => {
    entry.stage = "success";
    entry.progress = 100;
    entry.errorMessage = null;
  });
  log(`处理完成：${job.documentId} → ${googleDocUrl}`);
  await notifyJobResult(job, { ok: true, body: "Google Doc 已生成。" });
  return null;
}

/**
 * Skills 流程：本地 STT（txt 留在本机）→ 备份 txt 到 Firebase Storage →
 * 按 CMS 版本安装/更新 skill → 用 aiskillsrunner 执行 → 回写 Firestore → 推送通知。
 */
async function processSkillJob(job) {
  if (!job.serviceId) {
    return "Skills 流程缺少 serviceId：App 上传时需要写入 CMS 服务 ID（serviceId）。";
  }

  const localPath = jobWorkPath(job);
  updateJob(job.id, (entry) => {
    entry.stage = "downloading";
    entry.progress = 10;
  });
  try {
    await downloadFromStorage(job.recordFile, localPath);
    log(`已下载：${job.recordFile}`);
  } catch (error) {
    return `Download failed: ${error.message}`;
  }

  const markdown = isMarkdownJob(job);
  updateJob(job.id, (entry) => {
    entry.stage = markdown ? "reading_text" : "transcribing";
    entry.progress = 30;
  });
  let transcript;
  try {
    transcript = await extractJobText(job, localPath);
  } catch (error) {
    return `${markdown ? "Read markdown" : "Transcription"} failed: ${error.message}`;
  }
  // 音频用完即删；STT 文本按 Skills 流程要求留在本机
  rmSync(localPath, { force: true });

  let textPath;
  try {
    textPath = writeTranscriptLocally(job, transcript);
    log(`STT 结果已保存到本机：${textPath}`);
  } catch (error) {
    return `写入本地转录文件失败：${error.message}`;
  }

  let stored = null;
  try {
    stored = await uploadTranscriptToStorage(job, transcript);
    log(`转录文件已备份到 Storage：${stored.gsUrl}`);
  } catch (error) {
    log(`转录文件上传 Storage 失败（本地文件仍保留）：${error.message}`);
  }

  let service;
  try {
    service = await loadSkillService(job.serviceId);
  } catch (error) {
    return `读取 CMS 服务失败：${error.message}`;
  }
  if (!service.skillName) {
    return `CMS 服务 ${job.serviceId} 没有填写 Skill 名称（applicationName / --skill）。`;
  }
  if (!service.installUrl) {
    return `CMS 服务「${service.skillName}」还没有可用的 Skill 包（skillZipUrl 为空），请先在 CMS 上传并设为当前版本。`;
  }

  const install = resolveSkillInstall({
    skillsDir: state.config.skillsDir,
    skillName: service.skillName,
    remoteVersion: service.version,
    installUrl: service.installUrl
  });
  const needsInstall = install.force;

  if (needsInstall) {
    updateJob(job.id, (entry) => {
      entry.stage = "installing_skill";
      entry.progress = 60;
      entry.skillName = service.skillName;
      entry.skillVersion = service.version;
      entry.errorMessage = `本地版本需要更新：${install.reason}`;
    });
    log(`准备${install.action === "install" ? "安装" : "重新安装"} skill「${service.skillName}」：${install.reason}`);
    // 需要重装时（CMS 版本更新 / 首次安装 / 本地没有版本记录 / 换了 zip 地址）
    // 都先删掉本地旧文件，避免 cpSync 残留旧内容
    const removed = removeLocalSkill(state.config.skillsDir, service.skillName);
    if (removed.length) log(`已删除本地旧版本：${removed.join(", ")}`);
  } else {
    updateJob(job.id, (entry) => {
      entry.skillName = service.skillName;
      entry.skillVersion = service.version;
      entry.errorMessage = null;
    });
    log(`skill「${service.skillName}」本地 V${service.version} 与 CMS 一致，跳过安装。`);
  }

  updateJob(job.id, (entry) => {
    entry.stage = "running_skill";
    entry.progress = 75;
    entry.skillName = service.skillName;
    entry.skillVersion = service.version;
  });
  const skillRunStartedAtMs = Date.now();
  const localSkillDir = join(state.config.skillsDir, service.skillName);
  const maxSkillAttempts = 1 + state.config.skillResumeAttempts;

  // headless 单回合语义：回合提前结束 = 进程退出，续跑只能「再起一个回合」。
  // 这里的循环负责：执行 → 校验产物 → 不完整就带着「还缺什么」续跑。
  const loopResult = await runSkillWithResume({
    maxAttempts: maxSkillAttempts,
    inspect: () =>
      inspectSkillOutputs({
        root: skillTmpRoot,
        startedAtMs: skillRunStartedAtMs,
        files: SKILL_OUTPUT_FILES
      }),
    buildResumePrompt: ({ outputs }) =>
      buildSkillResumePrompt({
        skillName: service.skillName,
        skillDir: localSkillDir,
        outputs,
        sttPath: textPath,
        tmpRoot: skillTmpRoot
      }),
    run: async ({ attempt, isResume, attachRef, resumePrompt, outputs }) => {
      if (isResume) {
        log(
          `skill「${service.skillName}」第 ${attempt}/${maxSkillAttempts} 次执行（续跑）：` +
            `上一回合提前结束，${outputs.length ? `带着输出目录 ${outputs[0].dir} 继续` : "尚无输出目录，从头重做"}...`
        );
        updateJob(job.id, (entry) => {
          entry.stage = "running_skill";
          entry.errorMessage = `上一轮产物不完整，正在续跑（第 ${attempt} 次执行）`;
        });
      } else {
        log(`正在执行 skill「${service.skillName}」（aiskillsrunner，userid=${job.userId || "(空)"}）...`);
      }

      const runResult = await runAiskillsrunner({
        runnerBin: state.config.skillsRunnerBin,
        skillsDir: state.config.skillsDir,
        skillName: service.skillName,
        prompt: resumePrompt,
        refPath: attachRef ? textPath : undefined,
        userId: job.userId,
        firebaseKeyPath: state.config.adminKeyPath,
        installUrl: service.installUrl,
        forceInstall: needsInstall && !isResume,
        dshPermissionMode: state.config.dshPermissionMode,
        dshBin: state.config.skillsDshBin,
        cwd: projectRoot,
        timeoutMs: state.config.skillsRunnerTimeoutMs,
        // 把 aiskillsrunner / dsh 的输出实时写进监控日志，避免「卡住了但看不到任何信息」
        onLine: (stream, text) => {
          const trimmed = String(text || "").trim();
          if (!trimmed) return;
          log(stream === "system" ? trimmed : `[skill:${stream}] ${trimmed.slice(0, 400)}`);
        }
      });
      log(
        `skill「${service.skillName}」进程结束（第 ${attempt} 次）：exit=${runResult.code}` +
          `${runResult.timedOut ? "（超时）" : ""}，耗时 ${Math.round((runResult.durationMs || 0) / 1000)}s`
      );

      const attemptOutput = String(runResult.stdout || "").trim();
      if (attemptOutput) log(`[skill] ${attemptOutput.slice(0, 400)}${attemptOutput.length > 400 ? "…" : ""}`);
      if (runResult.timedOut) {
        return { ok: false, error: `skill「${service.skillName}」执行超时（${state.config.skillsRunnerTimeoutMs} ms）。` };
      }
      if (runResult.code !== 0) {
        const reason = String(runResult.stderr || "")
          .trim()
          .split("\n")
          .filter(Boolean)
          .slice(-6)
          .join(" ")
          .slice(0, 500);
        return {
          ok: false,
          error: `skill「${service.skillName}」执行失败（exit ${runResult.code}）：${reason || "无错误输出"}`
        };
      }
      return { ok: true, runResult, output: attemptOutput };
    },
    // 退出码 0 ≠ 真的做完：独立校验产物，避免 headless 回合提前结束造成的假成功
    verify: ({ output }) =>
      verifySkillUpload({
        skillName: service.skillName,
        stdout: output,
        startedAtMs: skillRunStartedAtMs
      }),
    onEvent: (event) => {
      if (event.type === "retry") {
        log(
          `第 ${event.attempt} 次执行的产物校验未通过，准备续跑（最多再试 ${event.total - event.attempt} 次）：` +
            event.error
        );
      }
    }
  });

  if (loopResult.status === "failed") return loopResult.error;

  const output = loopResult.output;
  const uploadCheck = loopResult.check;
  if (uploadCheck.verified) {
    log(`产物校验通过：${blogCollection}/${uploadCheck.docId} 已存在（匹配依据：${uploadCheck.matchedBy}）`);
  } else if (uploadCheck.skipped) {
    log(`产物校验跳过：${uploadCheck.reason || "SKILL_VERIFY_UPLOAD=false"}`);
  }

  if (!localSkillInstalled(state.config.skillsDir, service.skillName)) {
    return `skill「${service.skillName}」执行结束，但本地目录里没有找到它（安装可能失败）。`;
  }

  // 安装 + 执行都成功后才登记版本，下次即可只做版本比对
  recordLocalSkill(state.config.skillsDir, service.skillName, {
    version: service.version,
    installUrl: service.installUrl,
    serviceId: job.serviceId
  });

  updateJob(job.id, (entry) => {
    entry.stage = "updating_firestore";
    entry.progress = 92;
    entry.errorMessage = null;
  });
  try {
    await updateFirestoreSuccess(job, {
      serviceId: job.serviceId,
      serviceType: SKILL_SERVICE_TYPE,
      skillName: service.skillName,
      skillVersion: service.version,
      skillOutput: output.slice(0, MAX_SKILL_OUTPUT_CHARS),
      ...(uploadCheck.verified ? { skillUploadVerified: true, blogDocId: uploadCheck.docId } : {}),
      sttTextPath: stored?.gsUrl || "",
      sttTextUrl: stored?.httpsUrl || "",
      sttTextLocalPath: textPath
    });
  } catch (error) {
    return `Firestore update failed: ${error.message}`;
  }

  updateJob(job.id, (entry) => {
    entry.stage = "success";
    entry.progress = 100;
    entry.errorMessage = null;
  });
  log(`处理完成：${job.documentId} → skill「${service.skillName}」V${service.version}`);
  await notifyJobResult(job, { ok: true, body: `skill「${service.skillName}」执行完毕。` });
  return null;
}

/* ---------------- FFmpeg + 转录 ---------------- */

/**
 * 取得流水线要处理的文本：
 *  - kind === "Markdown"：本地文件就是 UTF-8 文本（md），直接读取，完全不做 FFmpeg / STT；
 *  - 其他：按音频处理（FFmpeg 转 16k wav → faster-whisper 转录）。
 * 失败时抛错，由调用方按各自流程返回失败原因。
 */
async function extractJobText(job, localPath) {
  if (isMarkdownJob(job)) {
    const text = readFileSync(localPath, "utf8").replace(/^\uFEFF/, "");
    log(`Markdown 输入（kind=${job.kind}）：跳过 STT，直接使用文本（${text.length} 字符）`);
    return text;
  }
  const wavPath = localPath.replace(/\.[^/.]+$/, "") + ".whisper.wav";
  try {
    convertToWav(localPath, wavPath);
    return await transcribeWithFasterWhisper({
      audioPath: wavPath,
      language: normalizeLanguage(job.language),
      model: state.config.whisperModel
    });
  } finally {
    rmSync(wavPath, { force: true });
  }
}

function convertToWav(sourcePath, outputPath) {
  execFileSync(
    "ffmpeg",
    [
      "-hide_banner", "-y",
      "-i", sourcePath,
      "-vn", "-map", "0:a:0",
      "-ac", "1", "-ar", "16000",
      "-c:a", "pcm_s16le",
      outputPath
    ],
    { stdio: "pipe" }
  );
}

function transcribeWithFasterWhisper({ audioPath, language, model }) {
  return new Promise((resolvePromise, rejectPromise) => {
    let modelName = model.replace("faster:", "");
    if (modelName === "distil-large-v3" && language !== "en") modelName = "large-v3";
    const args = [
      transcribeScript,
      "--audio", audioPath,
      "--model", modelName,
      "--language", language,
      "--download-root", modelDir,
      "--device", "auto"
    ];
    const child = spawn(venvPython, args, { stdio: ["ignore", "pipe", "pipe"] });
    let stdout = "";
    let stderr = "";
    child.stdout.on("data", (chunk) => (stdout += chunk.toString()));
    child.stderr.on("data", (chunk) => (stderr += chunk.toString()));
    child.on("error", rejectPromise);
    child.on("close", (code) => {
      if (code !== 0) {
        rejectPromise(new Error(stderr.trim() || `faster-whisper exited with ${code}`));
        return;
      }
      const lines = stdout.trim().split("\n").filter((line) => line.startsWith("{"));
      try {
        const payload = JSON.parse(lines[lines.length - 1]);
        const segments = Array.isArray(payload.segments) ? payload.segments : [];
        resolvePromise(segments.map((segment) => segment.text).join("\n").trim());
      } catch (error) {
        rejectPromise(new Error(`无法解析转录结果: ${error.message}`));
      }
    });
  });
}

/* ---------------- sidecar（Drive） ---------------- */

function ensureSidecar() {
  if (state.sidecarStdin) return;
  const child = spawn("node", [sidecarScript], { cwd: projectRoot, stdio: ["pipe", "pipe", "pipe"] });
  state.sidecar = child;
  state.sidecarStdin = child.stdin;

  const rl = createInterface({ input: child.stdout });
  rl.on("line", (line) => handleSidecarLine(line));
  child.stderr.on("data", (chunk) => {
    const text = chunk.toString().trim();
    if (text) log(`[sidecar] ${text}`);
  });
  child.on("error", (error) => log(`[sidecar] 错误: ${error.message}`));
  child.on("close", (code) => {
    log(`[sidecar] 退出 (code ${code})`);
    for (const [, reject] of state.pending) reject(new Error("Monitor sidecar exited unexpectedly."));
    state.pending.clear();
    state.sidecar = null;
    state.sidecarStdin = null;
  });
}

function handleSidecarLine(line) {
  let message;
  try {
    message = JSON.parse(line);
  } catch {
    log(line);
    return;
  }
  if (message.type === "response") {
    const resolvePending = state.pending.get(message.id);
    if (!resolvePending) return;
    state.pending.delete(message.id);
    if (message.ok) resolvePending.resolve(message.payload || {});
    else {
      const error = message.error || message.payload?.error || "Sidecar command failed.";
      resolvePending.reject(new Error(error));
    }
    return;
  }
  if (message.type !== "event") return;
  const payload = message.payload || {};
  switch (message.event) {
    case "drive_authorized":
      if (payload.email) {
        state.driveAccountEmail = payload.email;
        persistDriveEmail();
        log(`Google Drive 授权成功：${payload.email}`);
        emitSnapshot();
        requeueFailedJobs(); // 重试失败任务
        scheduleQueue();     // 继续处理等待中的任务
      }
      break;
    case "sidecar_error":
      log(`[sidecar] ${JSON.stringify(payload)}`);
      break;
    default:
      break;
  }
}

function sidecarRequest(command, payload = {}, timeoutMs = 30_000) {
  ensureSidecar();
  const id = `req-${++state.requestCounter}`;
  return new Promise((resolvePromise, rejectPromise) => {
    const timer = setTimeout(() => {
      state.pending.delete(id);
      rejectPromise(new Error(`Monitor sidecar command '${command}' timed out.`));
    }, timeoutMs);
    state.pending.set(id, {
      resolve: (value) => {
        clearTimeout(timer);
        resolvePromise(value);
      },
      reject: (error) => {
        clearTimeout(timer);
        rejectPromise(error);
      }
    });
    const request = JSON.stringify({ id, command, payload });
    state.sidecarStdin.write(`${request}\n`, (error) => {
      if (error) {
        clearTimeout(timer);
        state.pending.delete(id);
        rejectPromise(error);
      }
    });
  });
}

function configureSidecar() {
  const oauthClient = readJsonFile(state.config.oauthClientPath, null);
  return sidecarRequest(
    "configure",
    {
      driveOAuthClient: oauthClient,
      driveOAuthTokenPath: driveTokenPath,
      driveFolderId: state.config.driveFolderId
    },
    30_000
  );
}

function openBrowserUrl(url) {
  try {
    const command = process.platform === "win32"
      ? "powershell"
      : process.platform === "darwin"
        ? "open"
        : "xdg-open";
    const args = process.platform === "win32"
      ? ["-NoProfile", "-Command", `Start-Process -FilePath '${url.replace(/'/g, "''")}'`]
      : [url];
    spawn(command, args, { detached: true, stdio: "ignore" }).unref();
  } catch {
    // 打不开浏览器时由前端展示链接
  }
}

async function connectDrive() {
  await configureSidecar();
  const response = await sidecarRequest("start_drive_auth", {}, 30_000);
  const authUrl = response.authUrl;
  if (!authUrl) throw new Error("Drive OAuth authorization URL was not returned.");
  log("Google OAuth 授权链接已生成");
  openBrowserUrl(authUrl);
  return { authUrl };
}

/* ---------------- 对外接口 ---------------- */

let emitSnapshot = () => {};

export function initMonitor({ config, onSnapshot, onLog } = {}) {
  if (typeof onSnapshot === "function") emitSnapshot = () => onSnapshot(defaultSnapshot());
  if (typeof onLog === "function") emitLog = onLog;

  state.config = {
    adminKeyPath: config?.adminKeyPath || null,
    oauthClientPath: config?.oauthClientPath || null,
    driveFolderId: config?.driveFolderId || null,
    monitoredUsers: Array.isArray(config?.monitoredUsers) ? config.monitoredUsers : [],
    userIdField: config?.userIdField || "uid",
    whisperModel: normalizeWhisperModel(config?.whisperModel),
    skillsDir: config?.skillsDir || defaultSkillsDir,
    skillsRunnerBin: config?.skillsRunnerBin || "aiskillsrunner",
    skillsRunnerTimeoutMs: normalizeSkillsTimeout(config?.skillsRunnerTimeoutMs),
    skillsDshBin: config?.skillsDshBin || null,
    sttOutputDir: config?.sttOutputDir || sttDir,
    dshPermissionMode: config?.dshPermissionMode || "danger-full-access",
    verifySkillUpload: config?.verifySkillUpload !== false,
    verifySkillNames: (() => {
      const raw = config?.verifySkillNames;
      if (raw === null || raw === undefined || raw === "") return [...DEFAULT_VERIFY_SKILL_NAMES];
      const names = normalizeSkillNameList(raw);
      return names.includes("*") ? [] : names; // [] = 名单为空 = 对所有 skill 生效
    })(),
    skillResumeAttempts: normalizeSkillResumeAttempts(config?.skillResumeAttempts),
    fcmEnabled: config?.fcmEnabled !== false
  };

  // Skills 安装目录与 STT 输出目录（Skills 流程用；Google 流程不需要）
  mkdirSync(state.config.skillsDir, { recursive: true });
  mkdirSync(state.config.sttOutputDir, { recursive: true });

  loadJobs();
  loadDriveEmail();
  // 确保 Drive token 目录存在（oauth-token.json 的父目录），避免写入时报 ENOENT
  mkdirSync(join(dataDir, "drive"), { recursive: true });

  // 自动启动监听（无论 Web 页面是否打开，服务进程即监控程序本体）
  if (initializeAdmin()) {
    startListener();
    resumeInterruptedJobs();
    scheduleQueue();
    state.started = true;
  } else {
    state.started = false;
  }

  return {
    getState: () => defaultSnapshot(),
    command: async (command, args = {}) => {
      switch (command) {
        case "monitor_connect_drive": {
          const { authUrl } = await connectDrive();
          const snapshot = defaultSnapshot();
          snapshot._authUrl = authUrl;
          return snapshot;
        }
        default:
          throw new Error(`Unknown monitor command: ${command}`);
      }
    },
    close: () => {
      if (state.listener && typeof state.listener === "function") {
        try {
          state.listener();
        } catch {
          // ignore
        }
      }
      if (state.sidecar) {
        try {
          state.sidecar.kill("SIGTERM");
        } catch {
          // ignore
        }
      }
      if (state.sidecarStdin) {
        try {
          state.sidecarStdin.end();
        } catch {
          // ignore
        }
      }
      state.sidecar = null;
      state.sidecarStdin = null;
    }
  };
}
