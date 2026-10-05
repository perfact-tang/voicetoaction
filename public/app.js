/* MediaSplitter AI Monitor — 只读监视仪表盘
 *
 * 后台（node server.js）是"声音监控程序"本体：Firebase Admin 自动监听 Firestore
 * recordings 集合（只处理 .env 中 MONITOR_USER_IDS 指定的用户），自动下载、转录、
 * 生成 Google Doc、回写 Firestore。本页面只是查看器：
 *  - 显示后台状态（Admin / Drive / 监视用户 / 模型）
 *  - 显示处理历史与实时进度（SSE）
 *  - 显示运行日志
 * 页面开不开都不影响后台运行。唯一操作：首次需要一次「Connect Google」Drive 授权。
 */

const state = {
  snapshot: null,
  logs: []
};

const $ = (id) => document.getElementById(id);

const els = {
  backendStatus: $("backendStatus"),
  adminProject: $("adminProject"),
  adminStatus: $("adminStatus"),
  userIdField: $("userIdField"),
  userList: $("userList"),
  driveAccount: $("driveAccount"),
  driveFolder: $("driveFolder"),
  whisperModel: $("whisperModel"),
  connectDriveBtn: $("connectDriveBtn"),
  driveHint: $("driveHint"),
  statusLine: $("statusLine"),
  jobList: $("jobList"),
  logList: $("logList")
};

/* ---------------- 通用 ---------------- */

function setBackendStatus(text, ok) {
  els.backendStatus.textContent = text;
  els.backendStatus.classList.toggle("ok", ok);
  els.backendStatus.classList.toggle("bad", ok === false);
}

function formatTime(ms) {
  if (!ms) return "-";
  try {
    return new Intl.DateTimeFormat(undefined, {
      month: "2-digit",
      day: "2-digit",
      hour: "2-digit",
      minute: "2-digit",
      second: "2-digit"
    }).format(new Date(ms));
  } catch {
    return "-";
  }
}

function esc(value) {
  return String(value ?? "").replace(/[&<>"']/g, (ch) => ({
    "&": "&amp;",
    "<": "&lt;",
    ">": "&gt;",
    '"': "&quot;",
    "'": "&#39;"
  })[ch]);
}

/* ---------------- 渲染 ---------------- */

function renderSnapshot(snapshot) {
  state.snapshot = snapshot;
  const config = snapshot.config || {};

  els.adminProject.textContent = config.projectId || "-";
  els.adminStatus.textContent = snapshot.adminReady
    ? snapshot.monitoring
      ? "自动监听中"
      : "已连接（未监听）"
    : "未就绪";
  els.adminStatus.classList.toggle("bad", !snapshot.adminReady);
  els.userIdField.textContent = config.userIdField || "uid";

  els.userList.innerHTML = "";
  const users = config.monitoredUsers || [];
  if (users.length === 0) {
    const span = document.createElement("span");
    span.textContent = "（全部用户）";
    els.userList.append(span);
  } else {
    for (const uid of users) {
      const chip = document.createElement("span");
      chip.className = "chip";
      chip.textContent = uid;
      els.userList.append(chip);
    }
  }

  els.driveAccount.textContent = config.driveAccountEmail || "未授权";
  els.driveAccount.classList.toggle("bad", !config.driveAccountEmail);
  els.driveFolder.textContent = config.driveFolderId || "（未设置）";
  els.whisperModel.textContent = config.whisperModel || "-";
  els.connectDriveBtn.disabled = !config.oauthClientConfigured;
  els.driveHint.textContent = config.oauthClientConfigured
    ? config.driveAccountEmail
      ? `已连接 ${config.driveAccountEmail}，新录音将写入 Drive 文件夹。`
      : "点击 Connect Google 完成一次性 Drive 授权（需在浏览器中登录）。"
    : "GOOGLE_OAUTH_CLIENT_PATH 未在 .env 中配置或文件不存在。";
  els.driveHint.classList.toggle("bad", !config.oauthClientConfigured || !config.driveAccountEmail);

  if (snapshot.adminError) {
    setBackendStatus("Admin 未就绪", false);
  } else {
    setBackendStatus("后台运行中 · 自动监听", true);
  }

  renderJobs(snapshot.jobs || []);
  renderLogs(snapshot.logs || []);
}

function renderJobs(jobs) {
  const sorted = [...jobs].sort((a, b) => (b.createdAt || 0) - (a.createdAt || 0));
  els.jobList.innerHTML = "";
  if (sorted.length === 0) {
    const empty = document.createElement("div");
    empty.className = "empty-state";
    empty.textContent = "暂无处理记录。";
    els.jobList.append(empty);
    return;
  }
  const active = sorted.find((job) => !["success", "failed"].includes(job.stage));
  els.statusLine.textContent = active
    ? `正在处理 ${active.documentId}（${active.stage}）`
    : `共 ${sorted.length} 条记录（${sorted.filter((j) => j.stage === "success").length} 成功 / ${sorted.filter((j) => j.stage === "failed").length} 失败）`;

  for (const job of sorted) {
    const row = document.createElement("div");
    row.className = "job-row";
    const main = document.createElement("div");
    main.className = "job-main";
    const title = document.createElement("strong");
    title.textContent = `${job.documentId}${job.userId ? `（用户 ${job.userId}）` : ""}`;
    const file = document.createElement("span");
    file.textContent = job.recordFile;
    main.append(title, file);
    if (job.errorMessage) {
      const error = document.createElement("span");
      error.textContent = job.errorMessage;
      error.className = "bad-text";
      main.append(error);
    }
    if (job.googleDocUrl) {
      const link = document.createElement("a");
      link.href = job.googleDocUrl;
      link.target = "_blank";
      link.rel = "noreferrer";
      link.textContent = "Google Doc";
      main.append(link);
    }
    const meta = document.createElement("div");
    meta.className = "job-meta";
    const stage = document.createElement("span");
    stage.className = `job-stage stage-${job.stage}`;
    stage.textContent = job.stage;
    const progress = document.createElement("progress");
    progress.className = "job-progress";
    progress.max = 100;
    progress.value = job.progress || 0;
    const time = document.createElement("span");
    time.textContent = formatTime(job.updatedAt);
    meta.append(stage, progress, time);
    row.append(main, meta);
    els.jobList.append(row);
  }
}

function renderLogs(logs) {
  const entries = logs || [];
  const existing = els.logList.children.length;
  if (entries.length < existing) {
    els.logList.innerHTML = "";
  }
  for (let i = existing; i < entries.length; i += 1) {
    const entry = entries[i];
    const line = document.createElement("div");
    line.className = "log-line";
    const time = document.createElement("span");
    time.className = "log-time";
    time.textContent = formatTime(entry.t);
    const text = document.createElement("span");
    text.textContent = entry.text;
    line.append(time, text);
    els.logList.append(line);
  }
  if (entries.length > 0) {
    els.logList.scrollTop = els.logList.scrollHeight;
  }
}

/* ---------------- 数据获取 / SSE ---------------- */

async function refreshState() {
  try {
    const response = await fetch("/api/monitor/state");
    const snapshot = await response.json();
    if (!response.ok) throw new Error(snapshot.error || "加载失败");
    renderSnapshot(snapshot);
  } catch (error) {
    setBackendStatus("无法连接后端：" + error.message, false);
  }
}

function subscribeEvents() {
  if (!window.EventSource) return;
  const source = new EventSource("/api/monitor/events");
  source.addEventListener("snapshot", (event) => {
    try {
      renderSnapshot(JSON.parse(event.data));
    } catch {
      // ignore
    }
  });
  source.addEventListener("log", (event) => {
    let text = event.data;
    try {
      text = JSON.parse(event.data);
    } catch {
      // keep raw
    }
    const line = { t: Date.now(), text: typeof text === "string" ? text : JSON.stringify(text) };
    state.logs.push(line);
    if (state.logs.length > 200) state.logs.splice(0, state.logs.length - 200);
    renderLogs(state.logs);
  });
}

/* ---------------- 唯一操作：Connect Google ---------------- */

els.connectDriveBtn.addEventListener("click", async () => {
  if (!state.snapshot?.config?.oauthClientConfigured) return;
  els.connectDriveBtn.disabled = true;
  try {
    const response = await fetch("/api/monitor/command", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ command: "monitor_connect_drive" })
    });
    const payload = await response.json();
    if (!response.ok) throw new Error(payload.error || "请求失败");
    if (payload._authUrl) {
      els.driveHint.innerHTML =
        `授权链接：<a href="${esc(payload._authUrl)}" target="_blank" rel="noreferrer">打开 Google 授权页面</a>` +
        "（如浏览器未自动打开请手动点击）";
      els.driveHint.classList.remove("bad");
      window.open(payload._authUrl, "_blank", "noopener");
    }
  } catch (error) {
    els.driveHint.textContent = "Connect Google 失败：" + error.message;
    els.driveHint.classList.add("bad");
  } finally {
    els.connectDriveBtn.disabled = !state.snapshot?.config?.oauthClientConfigured;
  }
});

/* ---------------- 初始化 ---------------- */

refreshState();
setInterval(refreshState, 30_000);
subscribeEvents();
