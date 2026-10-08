import { createServer } from "node:http";
import { createReadStream, existsSync, readFileSync, rmSync } from "node:fs";
import { mkdir, stat } from "node:fs/promises";
import { extname, join, normalize, resolve } from "node:path";
import { spawn, spawnSync } from "node:child_process";
import os from "node:os";
import { initMonitor } from "./monitor.js";

const root = process.cwd();
const publicDir = join(root, "public");
const transcribeScript = join(root, "scripts", "faster_whisper_transcribe.py");
const windows = process.platform === "win32";
const venvPython = join(root, ".venv", windows ? "Scripts/python.exe" : "bin/python");

// 先读取 .env（Firebase Admin / Google OAuth 路径、监视用户、PORT/HOST 等）
try {
  process.loadEnvFile(join(root, ".env"));
} catch {
  // .env 不存在时忽略（页面会提示）
}

/**
 * dsh 会把「工作目录/.env」当作 project 层读取，凡是下面这些名字都会**直接报错退出**
 * （它只允许来自真正 export 的环境变量）。启动时就检查出来，免得等到跑 skill 才发现。
 * 规则与 @deepseek-ai/dsh-app-boot 的 BOOTSTRAP_NAMES / BOOTSTRAP_PREFIXES 一致。
 */
const DSH_BOOTSTRAP_PREFIXES = ["DSH_", "XDG_", "DYLD_", "BASH_FUNC_"];
const DSH_BOOTSTRAP_NAMES = new Set([
  "PATH", "HOME", "USERPROFILE", "SHELL", "NODE_OPTIONS", "NODE_PATH", "NODE_EXTRA_CA_CERTS",
  "LD_PRELOAD", "LD_LIBRARY_PATH", "LD_AUDIT", "BASH_ENV", "ENV", "SHELLOPTS", "BASHOPTS",
  "PYTHONSTARTUP", "PYTHONPATH", "PYTHONHOME", "RUBYOPT", "RUBYLIB",
  "EDITOR", "VISUAL", "PAGER", "BROWSER",
  "SSL_CERT_FILE", "SSL_CERT_DIR", "HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "NO_PROXY",
  "REQUESTS_CA_BUNDLE", "CURL_CA_BUNDLE", "NODE_TLS_REJECT_UNAUTHORIZED"
]);

function warnAboutDshBootstrapKeys(envPath) {
  let content;
  try {
    content = readFileSync(envPath, "utf8");
  } catch {
    return;
  }
  const offenders = new Set();
  for (const raw of content.split("\n")) {
    const line = raw.trim();
    if (!line || line.startsWith("#")) continue;
    const eq = line.indexOf("=");
    if (eq <= 0) continue;
    const name = line.slice(0, eq).trim();
    const upper = name.toUpperCase();
    if (DSH_BOOTSTRAP_NAMES.has(upper) || DSH_BOOTSTRAP_PREFIXES.some((prefix) => upper.startsWith(prefix))) {
      offenders.add(name);
    }
  }
  if (!offenders.size) return;
  console.warn(
    `[warn] .env 里有 dsh 会拒收的变量：${[...offenders].join(", ")}\n` +
      `       dsh 把「${envPath}」当作 project 层读取，DSH_* / XDG_* / DYLD_* / BASH_FUNC_* 前缀\n` +
      `       只允许来自真正 export 的环境变量；留在 .env 里会让 skill 执行直接失败。\n` +
      `       请改名（例如 DSH_PERMISSION_MODE → SKILLS_DSH_PERMISSION_MODE）。`
  );
}

warnAboutDshBootstrapKeys(join(root, ".env"));

// 端口 15888；HOST 默认 0.0.0.0（局域网可访问）
const port = Number(process.env.PORT || 15888);
const host = process.env.HOST || "0.0.0.0";

/** 取局域网 IPv4（第一个非 internal 地址），找不到回退 127.0.0.1 */
function lanIpv4() {
  const nets = os.networkInterfaces();
  for (const name of Object.keys(nets)) {
    for (const net of nets[name] || []) {
      if (net.family === "IPv4" && !net.internal) return net.address;
    }
  }
  return "127.0.0.1";
}

/** 数字逐位转英文单词（慢速清晰）："15888" → "one five eight eight eight" */
const DIGIT_WORDS = ["zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine"];
function digitsToWords(value) {
  return String(value)
    .split("")
    .map((ch) => DIGIT_WORDS[Number(ch)] ?? ch)
    .join(" ");
}

/** IP 逐位朗读："192.168.0.161" → "one nine two dot one six eight dot zero dot one six one" */
function ipToSpeech(ip) {
  return String(ip)
    .split(".")
    .map((octet) => digitsToWords(octet))
    .join(" dot ");
}

/**
 * 候选 ALSA 输出设备（依次尝试）：.env 的 AUDIO_DEVICE > 自带扬声器(plughw:0,0) > default > sysdefault
 */
function audioDeviceCandidates() {
  const list = [];
  if (process.env.AUDIO_DEVICE) list.push(process.env.AUDIO_DEVICE);
  list.push("plughw:0,0", "default", "sysdefault");
  return [...new Set(list)];
}

/**
 * 英文语音朗读：espeak-ng 生成 WAV → aplay 直接输出到 ALSA 硬件设备
 * （绕过 PulseAudio/PipeWire 用户会话，**未登录也能响**）。
 * 无 espeak-ng/aplay/音频设备时静默；开机早期无会话会失败，由调用方重试。
 */
function speakAnnouncement(ip) {
  try {
    const speech =
      `Media Splitter Monitor started. ` +
      `IP address is ${ipToSpeech(ip)}. ` +
      `Port number is ${digitsToWords(port)}.`;
    const wavPath = join(os.tmpdir(), `mediasplitter-announce-${process.pid}.wav`);
    // 1) 生成语音 WAV
    const gen = spawnSync("espeak-ng", ["-s", "100", "-v", "en-us", "-w", wavPath, speech], {
      timeout: 15_000,
      stdio: "ignore"
    });
    if (gen.status !== 0 || !existsSync(wavPath)) return;
    // 2) aplay 直出 ALSA 设备，按候选顺序尝试，成功即停
    for (const device of audioDeviceCandidates()) {
      const play = spawnSync("aplay", ["-D", device, wavPath], {
        timeout: 30_000,
        stdio: "ignore"
      });
      if (play.status === 0) break;
    }
    rmSync(wavPath, { force: true });
  } catch {
    // ignore（尽力而为）
  }
}

/**
 * 启动播报：控制台以日文打印；语音用英文（数字逐位慢读）。
 * 说明：espeak-ng 内置的 ja 语音不是真正的日语 TTS（发音不准），
 * 改用英文朗读并逐位念数字，保证 IP/端口清晰可辨。
 * 语音在启动后 0s / 60s / 120s 各尝试一次，尽量赶上登录后的音频会话。
 */
function announceStartup() {
  const ip = lanIpv4();
  const lines = [
    "================================================",
    "VoiceToAction AI Monitor 起動しました。",
    `IPアドレスは ${ip} です。`,
    `ポート番号は ${port} です。`,
    `Web ページ: http://${ip}:${port}  （同一 LAN 内の他の PC からも開けます）`,
    "================================================"
  ];
  console.log(lines.join("\n"));

  speakAnnouncement(ip);
  for (const delayMs of [60_000, 120_000]) {
    setTimeout(() => speakAnnouncement(ip), delayMs);
  }
}

function splitList(value) {
  return String(value || "")
    .split(",")
    .map((item) => item.trim())
    .filter(Boolean);
}

const monitorConfig = {
  adminKeyPath: process.env.FIREBASE_ADMIN_KEY_PATH || null,
  oauthClientPath: process.env.GOOGLE_OAUTH_CLIENT_PATH || null,
  driveFolderId: process.env.GOOGLE_DRIVE_FOLDER_ID || null,
  monitoredUsers: splitList(process.env.MONITOR_USER_IDS),
  userIdField: process.env.MONITOR_USER_ID_FIELD || "uid",
  whisperModel: process.env.WHISPER_MODEL || "faster:small",
  // ---- Skills（DeepSeek Harness）----
  // 默认 <root>/monitor-data/skills：只给本后端用，不污染 ~/.agents/skills
  skillsDir: process.env.SKILLS_DIR || join(root, "monitor-data", "skills"),
  // 默认用仓库里的 01_aiskillsrunner/aiskillsrunner.js（含 --force-install），
  // 由后端用同一个 node 执行，不依赖 PATH / 全局安装的版本。
  skillsRunnerBin: process.env.SKILLS_RUNNER_BIN || join(root, "01_aiskillsrunner", "aiskillsrunner.js"),
  skillsRunnerTimeoutMs: process.env.SKILLS_RUNNER_TIMEOUT_MS || null,
  // 键名故意避开 DSH_*：dsh 拒收 .env 里的 DSH_* 变量（会让 skill 直接失败）；
  // 若确实 export 了 DSH_BIN / DSH_PERMISSION_MODE，也会照样尊重。
  // 可选：dsh 可执行文件（默认由 aiskillsrunner 从 PATH 查找）
  skillsDshBin: process.env.SKILLS_DSH_BIN || process.env.DSH_BIN || process.env.AISKILLSRUNNER_DSH || null,
  sttOutputDir: process.env.STT_OUTPUT_DIR || join(root, "monitor-data", "stt"),
  dshPermissionMode:
    process.env.SKILLS_DSH_PERMISSION_MODE || process.env.DSH_PERMISSION_MODE || "danger-full-access",
  // skill 退出码为 0 时是否再校验产物（默认开；设 false 可退回「只看退出码」的旧行为）
  verifySkillUpload: process.env.SKILL_VERIFY_UPLOAD !== "false",
  // 需要做产物校验的 skill 名单（逗号分隔；未设置 = 用 monitor.js 的默认值；* = 全部）
  verifySkillNames: process.env.SKILL_VERIFY_NAMES || null,
  // 产物校验不过时最多再续跑几个回合（0 = 关闭；未设置 = 默认 2）。
  // headless 是单回合语义：agent 提前结束回合 = 进程退出，唯一能接着做的就是再起一个回合。
  skillResumeAttempts: process.env.SKILL_RESUME_ATTEMPTS ?? null,
  // ---- FCM 推送 ----
  fcmEnabled: process.env.FCM_ENABLED !== "false"
};

await mkdir(publicDir, { recursive: true });

const mimeTypes = {
  ".html": "text/html; charset=utf-8",
  ".css": "text/css; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".json": "application/json; charset=utf-8",
  ".svg": "image/svg+xml",
  ".png": "image/png",
  ".jpg": "image/jpeg",
  ".jpeg": "image/jpeg"
};

function sendJson(res, status, payload) {
  res.writeHead(status, { "Content-Type": "application/json; charset=utf-8" });
  res.end(JSON.stringify(payload));
}

function resolvePublicPath(urlPath) {
  const requested = urlPath === "/" ? "/index.html" : urlPath;
  const decoded = decodeURIComponent(requested.split("?")[0]);
  const filePath = normalize(join(publicDir, decoded));
  if (!filePath.startsWith(publicDir)) return null;
  return filePath;
}

function collectBody(req) {
  return new Promise((resolveBody, reject) => {
    let body = "";
    req.setEncoding("utf8");
    req.on("data", (chunk) => {
      body += chunk;
      if (body.length > 1_000_000) {
        reject(new Error("Request body is too large."));
        req.destroy();
      }
    });
    req.on("end", () => resolveBody(body));
    req.on("error", reject);
  });
}

/* ---------- faster-whisper 后端探测 ---------- */

function pythonProbe() {
  if (!existsSync(venvPython)) {
    return { ready: false, reason: "no-venv", detail: ".venv 不存在，请先运行 npm run setup:faster-whisper" };
  }
  const result = spawnSync(
    venvPython,
    [transcribeScript, "--probe"],
    { encoding: "utf8", timeout: 30_000 }
  );
  if (result.status !== 0) {
    return {
      ready: false,
      reason: "probe-failed",
      detail: (result.stderr || result.stdout || "faster-whisper 导入失败").trim()
    };
  }
  return { ready: true, reason: "ok", detail: "faster-whisper 可用" };
}

/* ---------- Monitor（SSE 广播） ---------- */

const monitorSseClients = new Set();

function sseSend(client, event, data) {
  client.write(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`);
}

const monitor = initMonitor({
  config: monitorConfig,
  onSnapshot: (snapshot) => {
    for (const client of monitorSseClients) sseSend(client, "snapshot", snapshot);
  },
  onLog: (text) => {
    console.log(`[monitor] ${text}`);
    for (const client of monitorSseClients) sseSend(client, "log", text);
  }
});

function handleHealth(_req, res) {
  const probe = pythonProbe();
  const state = monitor.getState();
  sendJson(res, 200, {
    ok: true,
    app: "mediasplitter-ai",
    node: process.version,
    platform: process.platform,
    fasterWhisper: probe,
    adminReady: state.adminReady,
    adminError: state.adminError,
    projectId: state.config.projectId,
    monitoredUsers: state.config.monitoredUsers,
    monitoring: state.monitoring
  });
}

function handleMonitorState(_req, res) {
  sendJson(res, 200, monitor.getState());
}

async function handleMonitorCommand(req, res) {
  const payload = JSON.parse(await collectBody(req));
  const command = String(payload.command || "");
  const args = payload.args && typeof payload.args === "object" ? payload.args : {};
  try {
    const snapshot = await monitor.command(command, args);
    sendJson(res, 200, snapshot);
  } catch (error) {
    const message = error instanceof Error ? error.message : "Monitor command failed.";
    sendJson(res, 500, { error: message });
  }
}

function handleMonitorEvents(req, res) {
  res.writeHead(200, {
    "Content-Type": "text/event-stream; charset=utf-8",
    "Cache-Control": "no-cache",
    Connection: "keep-alive",
    "X-Accel-Buffering": "no"
  });
  res.write("retry: 3000\n\n");
  sseSend(res, "snapshot", monitor.getState());
  monitorSseClients.add(res);
  const heartbeat = setInterval(() => {
    res.write(": heartbeat\n\n");
  }, 25_000);
  req.on("close", () => {
    clearInterval(heartbeat);
    monitorSseClients.delete(res);
  });
}

const server = createServer(async (req, res) => {
  try {
    const url = new URL(req.url || "/", `http://${req.headers.host}`);

    if (req.method === "GET" && url.pathname === "/api/health") {
      handleHealth(req, res);
      return;
    }
    if (req.method === "GET" && url.pathname === "/api/monitor/state") {
      handleMonitorState(req, res);
      return;
    }
    if (req.method === "POST" && url.pathname === "/api/monitor/command") {
      await handleMonitorCommand(req, res);
      return;
    }
    if (req.method === "GET" && url.pathname === "/api/monitor/events") {
      handleMonitorEvents(req, res);
      return;
    }
    if (url.pathname.startsWith("/api/")) {
      sendJson(res, 404, { error: "Not found." });
      return;
    }

    if (req.method !== "GET") {
      sendJson(res, 405, { error: "Method not allowed." });
      return;
    }

    const filePath = resolvePublicPath(url.pathname);
    if (!filePath) {
      res.writeHead(403);
      res.end("Forbidden");
      return;
    }

    const fileInfo = await stat(filePath);
    if (!fileInfo.isFile()) {
      res.writeHead(404);
      res.end("Not found");
      return;
    }

    res.writeHead(200, {
      "Content-Type": mimeTypes[extname(filePath).toLowerCase()] || "application/octet-stream"
    });
    createReadStream(filePath).pipe(res);
  } catch (error) {
    const message = error instanceof Error ? error.message : "Unexpected error";
    sendJson(res, 500, { error: message });
  }
});

server.listen(port, host, () => {
  const probe = pythonProbe();
  console.log(`faster-whisper backend: ${probe.ready ? "ready" : "NOT READY — " + probe.detail}`);
  const state = monitor.getState();
  console.log(`Firebase Admin: ${state.adminReady ? "ready (project " + state.config.projectId + ")" : "NOT READY — " + state.adminError}`);
  console.log(`监视用户: ${state.config.monitoredUsers.length ? state.config.monitoredUsers.join(", ") : "（全部）"} | 用户字段: ${state.config.userIdField}`);
  console.log(`Drive: ${state.config.driveAccountEmail || "未授权（请打开页面点一次 Connect Google）"} | 模型: ${state.config.whisperModel}`);
  announceStartup();
});

process.on("exit", () => monitor.close());
process.on("SIGINT", () => {
  monitor.close();
  process.exit(0);
});
process.on("SIGTERM", () => {
  monitor.close();
  process.exit(0);
});
