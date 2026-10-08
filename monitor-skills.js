/**
 * Skills（DeepSeek Harness）本地安装与执行
 *
 * 呼叫对象来自 CMS `/allalservice`。当 `type == "deepseek_harness"` 时，后端不再走
 * Google Drive 流程，而是：
 *   1. 本地 STT（txt 留在本机），并把 txt 备份上传到 Firebase Storage（gs:// 写回 Firestore）；
 *   2. 用本机已安装的 `aiskillsrunner` 执行 skill（`dsh --profile headless`）；
 *   3. 安装到**后端专属目录**（默认 `monitor-data/skills`），不污染全局 `~/.agents/skills`；
 *   4. 在 `<SKILLS_DIR>/.installed.json` 维护本地版本号，与 CMS 的
 *      `allalservice/{serviceId}.skillActiveVersion` 比对；**CMS 版本更新才**删掉重装。
 *
 * aiskillsrunner 由本模块调用，参数与手写命令一致：
 *   DSH_PERMISSION_MODE=... aiskillsrunner --skill <名称> --reffilepath <txt>
 *     --firebasekey <.env 的 FIREBASE_ADMIN_KEY_PATH> --userid <uid>
 *     [--installurl <skillZipUrl>] [--force-install]
 */
import { spawn } from "node:child_process";
import { existsSync, mkdirSync, readdirSync, readFileSync, rmSync, statSync, writeFileSync } from "node:fs";
import path, { join } from "node:path";

/** CMS `allalservice.type` 中代表 Skills 的取值。 */
export const SKILL_SERVICE_TYPE = "deepseek_harness";

const REGISTRY_NAME = ".installed.json";

function registryPath(skillsDir) {
  return join(skillsDir, REGISTRY_NAME);
}

/** 读取本地版本登记表：{ [skillName]: { version, installUrl, installedAt } }。 */
export function readSkillRegistry(skillsDir) {
  try {
    const parsed = JSON.parse(readFileSync(registryPath(skillsDir), "utf8"));
    return parsed && typeof parsed === "object" ? parsed : {};
  } catch {
    return {};
  }
}

function writeSkillRegistry(skillsDir, registry) {
  mkdirSync(skillsDir, { recursive: true });
  writeFileSync(registryPath(skillsDir), JSON.stringify(registry, null, 2), "utf8");
}

/** 本地登记表里该 skill 的记录（没有则 null）。 */
export function localSkillRecord(skillsDir, skillName) {
  const record = readSkillRegistry(skillsDir)[skillName];
  return record && typeof record === "object" ? record : null;
}

/** 登记/更新本地版本号。 */
export function recordLocalSkill(skillsDir, skillName, { version, installUrl, serviceId, source = "aiskillsrunner" }) {
  const registry = readSkillRegistry(skillsDir);
  const previous = registry[skillName] || {};
  registry[skillName] = {
    version: Number(version) || 0,
    installUrl: installUrl || previous.installUrl || "",
    serviceId: serviceId || previous.serviceId || "",
    source,
    installedAt: previous.installedAt || Date.now(),
    updatedAt: Date.now()
  };
  writeSkillRegistry(skillsDir, registry);
  return registry[skillName];
}

/**
 * 本地是否真的存在该 skill。与 aiskillsrunner 的判定保持一致：
 * 目录型 `<name>/SKILL.md` 或扁平型 `<name>.md`。
 */
export function localSkillInstalled(skillsDir, skillName) {
  return existsSync(join(skillsDir, skillName, "SKILL.md")) || existsSync(join(skillsDir, `${skillName}.md`));
}

/** 删除本地安装的 skill（目录型 + 扁平型），用于需要重装时（如 CMS 出了新版本）。 */
export function removeLocalSkill(skillsDir, skillName) {
  const removed = [];
  const dir = join(skillsDir, skillName);
  if (existsSync(dir)) {
    rmSync(dir, { recursive: true, force: true });
    removed.push(dir);
  }
  const flat = join(skillsDir, `${skillName}.md`);
  if (existsSync(flat)) {
    rmSync(flat, { force: true });
    removed.push(flat);
  }
  return removed;
}

/**
 * 判断是否需要重新下载安装。
 *
 * 只按版本号决定：**CMS 版本比本地新**才重装；本地版本不低于 CMS 就跳过
 * （CMS 端版本号被调低时保持本地不动，不做降级）。另外两种「无从比较」的情况仍重装：
 *   - 本地文件缺失，或登记表里没有该 skill 的版本记录；
 *   - 版本相同、但 zip 下载地址变了（同一个版本号换了包）。
 */
export function resolveSkillInstall({ skillsDir, skillName, remoteVersion, installUrl }) {
  const installed = localSkillInstalled(skillsDir, skillName);
  if (!installed) {
    return { action: "install", reason: `本地未安装 skill "${skillName}"`, force: true };
  }
  const record = localSkillRecord(skillsDir, skillName);
  const localVersion = Number(record?.version);
  const target = Number(remoteVersion) || 0;
  if (!Number.isFinite(localVersion)) {
    return { action: "reinstall", reason: `本地没有 skill "${skillName}" 的版本记录（CMS V${target}）`, force: true };
  }
  if (localVersion < target) {
    return { action: "reinstall", reason: `CMS 有新版本：本地 V${localVersion} → V${target}`, force: true };
  }
  if (localVersion > target) {
    return { action: "skip", reason: `本地 V${localVersion} 比 CMS V${target} 新，保持本地不动`, force: false };
  }
  if (record?.installUrl && installUrl && record.installUrl !== installUrl) {
    return { action: "reinstall", reason: `skill "${skillName}" 的下载地址已变化（版本同为 V${target}）`, force: true };
  }
  return { action: "skip", reason: `本地 V${localVersion} 与 CMS 一致`, force: false };
}

/**
 * 调用 aiskillsrunner 执行 skill。
 * `force` 为 true 时追加 `--force-install`，即使全局目录里存在同名 skill 也会装到本地目录。
 *
 * `prompt` 直接透传成 aiskillsrunner 的 `--prompt`，用于两种场景：
 *   - 首轮：aiskillsrunner 会把 `--reffilepath` 的内容一并附上；
 *   - 续跑（见 buildSkillResumePrompt）：只给「还差什么」的指令，不再重发整份 STT。
 *
 * 两个环境细节（后端通常以 root 运行，PATH 里没有 nvm 的 bin 目录）：
 *   - runnerBin 指向 .js 文件时用 `process.execPath` 执行，不依赖 shebang/PATH；
 *   - 子进程 PATH 前置当前 node 所在目录，让 aiskillsrunner 能解析到 `dsh`。
 */
export function runAiskillsrunner({
  runnerBin = "aiskillsrunner",
  skillsDir,
  skillName,
  prompt,
  refPath,
  userId,
  firebaseKeyPath,
  installUrl,
  forceInstall = false,
  dshPermissionMode = "danger-full-access",
  dshBin,
  extraEnv = {},
  cwd,
  timeoutMs = 90 * 60 * 1000,
  /** (stream, line) => void：把子进程输出实时转出去（仪表盘日志 / 心跳） */
  onLine
} = {}) {
  const runnerArgs = ["--skill", skillName];
  if (prompt) runnerArgs.push("--prompt", prompt);
  if (refPath) runnerArgs.push("--reffilepath", refPath);
  if (firebaseKeyPath) runnerArgs.push("--firebasekey", firebaseKeyPath);
  if (userId) runnerArgs.push("--userid", userId);
  if (installUrl) runnerArgs.push("--installurl", installUrl);
  if (forceInstall) runnerArgs.push("--force-install");

  const nodeBinDir = path.dirname(process.execPath);
  const env = { ...process.env, ...extraEnv };
  // 后端以 root 运行时常缺少 nvm 的 bin 目录，这里显式补上
  env.PATH = [nodeBinDir, env.PATH].filter(Boolean).join(path.delimiter);
  // 只装到后端自己的目录，避免污染 ~/.agents/skills
  if (skillsDir) env.AISKILLSRUNNER_SKILLS_DIR = skillsDir;
  if (dshPermissionMode) env.DSH_PERMISSION_MODE = dshPermissionMode;
  if (dshBin) env.AISKILLSRUNNER_DSH = dshBin;

  const isScript = typeof runnerBin === "string" && /\.(js|cjs|mjs)$/i.test(runnerBin);
  const bin = isScript ? process.execPath : runnerBin;
  const args = isScript ? [runnerBin, ...runnerArgs] : runnerArgs;
  const command = [runnerBin, ...runnerArgs].join(" ");
  const line = typeof onLine === "function" ? onLine : null;

  return new Promise((resolvePromise) => {
    let child;
    try {
      child = spawn(bin, args, {
        cwd: cwd || process.cwd(),
        env,
        // detached：子进程自成进程组，超时时可以整组杀（dsh 等孙进程不会变成孤儿占住管道）
        detached: true,
        stdio: ["ignore", "pipe", "pipe"]
      });
    } catch (error) {
      resolvePromise({ code: 127, stdout: "", stderr: error.message, timedOut: false, command, durationMs: 0 });
      return;
    }

    const startedAt = Date.now();
    let stdout = "";
    let stderr = "";
    let settled = false;
    let timedOut = false;
    let exited = false;
    let exitCode = 0;
    let drainTimer = null;
    let heartbeatTimer = null;
    const lineBuffers = { stdout: "", stderr: "" };

    const flushRemainder = () => {
      if (!line) return;
      for (const stream of ["stdout", "stderr"]) {
        const rest = lineBuffers[stream].trim();
        if (rest) line(stream, rest);
        lineBuffers[stream] = "";
      }
    };

    const finish = (code) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      if (drainTimer) clearTimeout(drainTimer);
      if (heartbeatTimer) clearInterval(heartbeatTimer);
      flushRemainder();
      resolvePromise({
        command,
        code,
        stdout,
        stderr,
        timedOut,
        durationMs: Date.now() - startedAt
      });
    };

    const killGroup = (signal) => {
      try {
        // detached 之后 child 是进程组组长：负 PID = 整组（含 dsh 及其子孙）
        process.kill(-child.pid, signal);
      } catch {
        try {
          child.kill(signal);
        } catch {
          // 进程已经退出
        }
      }
    };

    const timer = setTimeout(() => {
      timedOut = true;
      line?.("system", `执行超时（${Math.round(timeoutMs / 1000)}s），正在终止 skill 进程组…`);
      killGroup("SIGTERM");
      setTimeout(() => {
        if (!settled) killGroup("SIGKILL");
      }, 5_000);
      // 必须自己结算：dsh 等孙进程可能占住管道，导致 close 永不触发
      setTimeout(() => finish(124), 8_000);
    }, timeoutMs);

    // 心跳：让仪表盘能看出进程还活着
    heartbeatTimer = setInterval(() => {
      if (settled) return;
      line?.("system", `skill 仍在执行…已运行 ${Math.round((Date.now() - startedAt) / 1000)}s`);
    }, 60_000);

    const onChunk = (stream) => (chunk) => {
      const text = chunk.toString();
      if (stream === "stdout") stdout += text;
      else stderr += text;
      if (!line) return;
      lineBuffers[stream] += text;
      let index = lineBuffers[stream].indexOf("\n");
      while (index >= 0) {
        const current = lineBuffers[stream].slice(0, index).trim();
        lineBuffers[stream] = lineBuffers[stream].slice(index + 1);
        if (current) line(stream, current);
        index = lineBuffers[stream].indexOf("\n");
      }
    };
    child.stdout?.on("data", onChunk("stdout"));
    child.stderr?.on("data", onChunk("stderr"));

    child.on("error", (error) => {
      stderr += `\n${error.message}`;
      finish(127);
    });

    // 关键：用 exit 而不是 close 结算。
    // aiskillsrunner 以 stderr:'inherit' 启动 dsh，dsh 会继承我们的管道；
    // 只要孙进程还活着，'close' 就永远不会触发（之前任务卡死就是这个原因）。
    child.on("exit", (code, signal) => {
      exited = true;
      exitCode = code === null || code === undefined ? (signal ? 1 : 0) : code;
      drainTimer = setTimeout(() => finish(timedOut ? 124 : exitCode), 250);
    });

    child.on("close", (code) => {
      if (settled || !exited) return;
      finish(timedOut ? 124 : (code ?? exitCode));
    });
  });
}

/* ---------------- 产物巡检与「提前结束回合」续跑 ---------------- */

/**
 * 巡检「本次运行窗口内」写出的 skill 输出目录，返回每个目录的完成情况。
 *
 * 用途有两个：
 *   1. 产物校验的兜底：4 个文件齐全的目录才算真正的产出（见 verifySkillUpload）；
 *   2. 回合提前结束时，把「已经写到哪、还差哪个文件」告诉续跑的 agent。
 *
 * @param {object} options
 * @param {string} options.root        输出根目录（如 ~/Documents/tmpaiskill）
 * @param {number} options.startedAtMs 本次 skill 运行的开始时间（毫秒）
 * @param {string[]} options.files     判定完成所需的文件名
 * @param {number} [options.toleranceMs] 允许的时钟/写入回退余量，默认 90 秒
 * @returns {Array<{docId:string,dir:string,present:Array<{name:string,size:number}>,missing:string[],complete:boolean,mtimeMs:number}>}
 */
export function inspectSkillOutputs({ root, startedAtMs, files = [], toleranceMs = 90_000 } = {}) {
  let entries;
  try {
    entries = readdirSync(root, { withFileTypes: true });
  } catch {
    return []; // 根目录不存在 = 还没有任何产物
  }

  const outputs = [];
  for (const entry of entries) {
    if (!entry.isDirectory() || !/^\d{10,}$/.test(entry.name)) continue;
    const dir = join(root, entry.name);
    let mtimeMs;
    try {
      mtimeMs = statSync(dir).mtimeMs;
    } catch {
      continue; // 目录刚被删掉：忽略
    }
    if (mtimeMs < startedAtMs - toleranceMs) continue; // 不是本次运行写出来的

    const present = [];
    const missing = [];
    for (const name of files) {
      try {
        const size = statSync(join(dir, name)).size;
        if (size > 0) present.push({ name, size });
        else missing.push(name);
      } catch {
        missing.push(name);
      }
    }
    outputs.push({ docId: entry.name, dir, present, missing, complete: missing.length === 0, mtimeMs });
  }

  // 最近写出的排最前；DocID 是 Unix 毫秒，时间相同再按 ID 从大到小。
  return outputs.sort((a, b) => b.mtimeMs - a.mtimeMs || b.docId.localeCompare(a.docId));
}

/**
 * 生成「续跑」提示词。
 *
 * headless 是单回合语义：agent 一结束回合，`dsh` 进程立刻退出，**同一次调用无法再续**。
 * 所以唯一的续跑办法是**再起一个回合**（再调用一次 aiskillsrunner）。
 * 这里把上一轮的实际落盘情况写清楚，让它直接补做缺失步骤，
 * 而不是从头重读素材、重写已完成的文件（那正是把输出预算烧在 reasoning 里的元凶）。
 */
export function buildSkillResumePrompt({
  skillName,
  skillDir,
  outputs = [],
  sttPath,
  tmpRoot
} = {}) {
  const lines = [
    "上一次执行在完成前就结束了回合（headless 单回合语义，进程已退出），任务并未完成。",
    "请**接着做完剩下的步骤**：不要重新审阅素材，也不要重写已经写好的文件。"
  ];

  if (skillName) {
    lines.push(
      `继续执行 skill「${skillName}」${skillDir ? `（说明文件：${skillDir}/SKILL.md）` : ""}，` +
        "严格按其中的 Step 顺序推进。"
    );
  }

  const latest = outputs.find((output) => !output.complete) || outputs[0];
  if (latest) {
    lines.push(`本次输出目录：${latest.dir}`);
    if (latest.present.length) {
      lines.push(`已存在且非空：${latest.present.map((file) => `${file.name}（${file.size} 字节）`).join("、")}`);
    }
    if (latest.missing.length) lines.push(`仍需补齐：${latest.missing.join("、")}`);
    if (latest.complete) {
      lines.push(
        "4 个文件已经齐全，只差上传：请按 skill 的上传步骤调用 vibecodingjapan-upblog" +
          `（--docid ${latest.docId}，密钥取环境变量 AISKILLS_FIREBASE_ACCOUNT），成功后输出最终报告。`
      );
    } else {
      lines.push(
        "请以该目录下**同一份 中文.md 定稿**为依据补齐缺失文件（英文.md 直接从中文定稿翻译，" +
          "不要从日文转译），随后按 skill 的上传步骤用 " +
          `vibecodingjapan-upblog --docid ${latest.docId} 上传一次，最后输出最终报告。`
      );
    }
  } else {
    lines.push(
      `本次运行没有在 ${tmpRoot} 下留下任何输出目录，请从 Step1 重新开始` +
        `${sttPath ? `（STT 正文在 ${sttPath}）` : ""}。`
    );
  }

  if (sttPath && latest) {
    lines.push(`STT 正文保存在本机：${sttPath}（需要时用 read 工具读原文，不要凭记忆重写）。`);
  }

  lines.push(
    "⚠️ 必须用工具（write / bash）把内容真正写入磁盘：**禁止把整篇文章只写在 reasoning 里**。" +
      "每写完一个文件就用 bash 确认它存在且非空；4 个文件与上传报告全部完成之前，不要结束回合。"
  );
  return lines.join("\n");
}

/**
 * 「执行 skill → 校验产物 → 不完整就续跑」的循环。
 *
 * headless（`dsh --profile headless`）是单回合语义：agent 一结束回合，进程立刻退出，
 * 同一次调用没有「继续」的机会。所以产物不完整时唯一的补救办法是**再起一个回合**，
 * 并把上一轮的实际落盘情况告诉它（而不是从头重来）。
 *
 * 回调约定：
 *   - run({ attempt, isResume, attachRef, resumePrompt, outputs })
 *       → { ok: true, runResult, output } | { ok: false, error }
 *   - verify({ attempt, isResume, output, runResult, outputs })
 *       → { verified: true, docId } | { skipped: true, reason } | { error: "..." }
 *     返回对象里没有 error 即视为「产物已就绪」，循环结束。
 *   - inspect({ attempt }) → outputs（见 inspectSkillOutputs）
 *   - buildResumePrompt({ attempt, outputs }) → string
 *   - onEvent(event) → 可选，用于打日志（event.type: attempt | retry）
 *
 * @returns {Promise<{status:"verified"|"skipped", check:object, attempts:number, output:string}
 *                  | {status:"failed", error:string, attempts:number, output:string}>}
 */
export async function runSkillWithResume({
  maxAttempts = 1,
  run,
  verify,
  inspect,
  buildResumePrompt,
  onEvent = () => {}
} = {}) {
  const total = Math.max(1, Math.trunc(Number(maxAttempts) || 1));
  let output = "";
  let check = null;
  let lastError = "skill 执行结束，但没有产出可校验的产物。";

  for (let attempt = 1; attempt <= total; attempt += 1) {
    const isResume = attempt > 1;
    const outputs = isResume && typeof inspect === "function" ? inspect({ attempt }) || [] : [];
    const resumePrompt =
      isResume && typeof buildResumePrompt === "function" ? buildResumePrompt({ attempt, outputs }) : undefined;
    // 续跑时若已有半成品目录，就不再重发整份 STT：省输出预算，也避免它又从头写一遍。
    const attachRef = !isResume || outputs.length === 0;

    onEvent({ type: "attempt", attempt, total, isResume, outputs });
    const outcome = await run({ attempt, isResume, attachRef, resumePrompt, outputs });
    if (!outcome || outcome.ok !== true) {
      return {
        status: "failed",
        error: (outcome && outcome.error) || "skill 执行失败。",
        attempts: attempt,
        output
      };
    }
    output = String(outcome.output || "");

    check = await verify({ attempt, isResume, output, runResult: outcome.runResult, outputs });
    if (!check || !check.error) {
      return {
        status: check && check.verified ? "verified" : "skipped",
        check: check || { skipped: true },
        attempts: attempt,
        output
      };
    }

    lastError = check.error;
    if (attempt >= total) break;
    onEvent({ type: "retry", attempt, total, error: lastError, outputs });
  }

  return { status: "failed", error: lastError, attempts: total, output };
}
