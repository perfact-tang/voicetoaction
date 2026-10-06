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
import { existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
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
 * 两个环境细节（后端通常以 root 运行，PATH 里没有 nvm 的 bin 目录）：
 *   - runnerBin 指向 .js 文件时用 `process.execPath` 执行，不依赖 shebang/PATH；
 *   - 子进程 PATH 前置当前 node 所在目录，让 aiskillsrunner 能解析到 `dsh`。
 */
export function runAiskillsrunner({
  runnerBin = "aiskillsrunner",
  skillsDir,
  skillName,
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
