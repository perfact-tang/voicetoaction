import { existsSync, rmSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

/**
 * 给后端准备 Python 环境（默认是 faster-whisper 的 STT）。
 *
 * 两处加固，都是踩过坑的：
 *
 * 1. **优先用 uv 管理的 Python，而不是系统 python3。**
 *    Ubuntu 26.04 升级时把 python3.12 从系统里删掉、/usr/bin/python3 变成 3.14，
 *    `.venv`（包装在 lib/python3.12/ 下）当场失效，整个 STT 全断。uv 装的 Python
 *    在 ~/.local/share/uv/python/ 下，跟系统升级解耦。
 *    没有 uv 时才回退「系统 python3 -m venv」。
 *
 * 2. **解释器版本不对就重建 venv。**
 *    venv 的 bin/python3 通常是指向系统解释器的符号链接，系统 python 一升级链接就指到
 *    新版本，而依赖还在旧版本的目录里。检测到版本不一致直接删掉重建。
 *
 * 另外：如果存在同名 `.lock.txt`（例如 requirements-faster-whisper.lock.txt），
 * 就按锁定的版本安装。不锁的话会装上最新的传递依赖 —— huggingface_hub 从 1.29 跳到
 * 1.33 就曾让 faster-whisper 直接报 `open() got an unexpected keyword argument
 * 'metadata_errors'`。
 */
const projectRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const requirementsName = process.argv[2];

if (!requirementsName) {
  console.error("Usage: node scripts/setup-python-backend.js <requirements-file>");
  process.exit(2);
}

const requirementsPath = resolve(projectRoot, requirementsName);
if (!existsSync(requirementsPath)) {
  console.error(`Requirements file not found: ${requirementsPath}`);
  process.exit(2);
}

/** 期望的 Python 版本（major.minor）。 */
const PY_VERSION = process.env.MEDIA_SPLITTER_PY_VERSION || "3.12";

const lockPath = requirementsPath.replace(/\.txt$/i, ".lock.txt");
const installPath = existsSync(lockPath) ? lockPath : requirementsPath;
if (installPath === lockPath) {
  console.log(`Using locked versions: ${lockPath}`);
}

const windows = process.platform === "win32";
const venvDir = join(projectRoot, ".venv");
const venvPython = join(venvDir, windows ? "Scripts/python.exe" : "bin/python");

function run(command, args, options = {}) {
  return spawnSync(command, args, {
    cwd: projectRoot,
    encoding: "utf8",
    stdio: options.capture ? "pipe" : "inherit",
  });
}

function candidate(command, args = []) {
  return { command, args };
}

function findUv() {
  if (process.env.UV && existsSync(process.env.UV)) return process.env.UV;
  const result = run("uv", ["--version"], { capture: true });
  return result.status === 0 ? "uv" : null;
}

/** 现有 venv 的解释器版本（major.minor），取不到返回 null。 */
function venvPythonVersion() {
  if (!existsSync(venvPython)) return null;
  const result = run(venvPython, ["-c", "import sys;print('%d.%d' % sys.version_info[:2])"], {
    capture: true,
  });
  return result.status === 0 ? result.stdout.trim() : null;
}

const uv = findUv();
const current = venvPythonVersion();

if (current && current !== PY_VERSION) {
  console.log(
    [
      `Existing .venv uses Python ${current}, but ${PY_VERSION} is required.`,
      "This usually means the system Python was upgraded (e.g. Ubuntu 24.04 -> 26.04",
      "removed python3.12); the packages are still under lib/python3.12/, so nothing",
      "imports any more. Removing and recreating it.",
    ].join("\n"),
  );
  rmSync(venvDir, { recursive: true, force: true });
}

if (!existsSync(venvPython)) {
  if (uv) {
    console.log(`Creating .venv with uv-managed Python ${PY_VERSION} (decoupled from the OS)`);
    const installed = run(uv, ["python", "install", PY_VERSION]);
    if (installed.status !== 0) {
      console.error(`uv python install ${PY_VERSION} failed`);
      process.exit(installed.status ?? 1);
    }
    const created = run(uv, ["venv", "--python", PY_VERSION, venvDir]);
    if (created.status !== 0) {
      process.exit(created.status ?? 1);
    }
  } else {
    const candidates = [
      ...(process.env.MEDIA_SPLITTER_PYTHON ? [candidate(process.env.MEDIA_SPLITTER_PYTHON)] : []),
      ...(windows ? [candidate("py", ["-3"]), candidate("python")] : [candidate("python3"), candidate("python")]),
    ];
    const usable = candidates.find((entry) => {
      if ((entry.command.includes("/") || entry.command.includes("\\")) && !existsSync(entry.command)) {
        return false;
      }
      return (
        run(entry.command, [...entry.args, "-c", "import encodings, venv, sys"], { capture: true })
          .status === 0
      );
    });
    if (!usable) {
      console.error(
        [
          "No working Python 3 installation was found, and uv is not available.",
          `Install uv (https://docs.astral.sh/uv/) or Python ${PY_VERSION}, then run this again.`,
          "You can also set MEDIA_SPLITTER_PYTHON to the full path of the interpreter.",
        ].join("\n"),
      );
      process.exit(1);
    }
    console.log(`Using Python: ${usable.command} ${usable.args.join(" ")}`.trim());
    const created = run(usable.command, [...usable.args, "-m", "venv", venvDir]);
    if (created.status !== 0) {
      process.exit(created.status ?? 1);
    }
  }
}

console.log(`Installing ${installPath === lockPath ? lockPath : requirementsName} into ${venvPython}`);
const installResult = uv
  ? run(uv, ["pip", "install", "--python", venvPython, "-r", installPath])
  : run(venvPython, ["-m", "pip", "install", "-r", installPath]);
if (installResult.status !== 0) {
  process.exit(installResult.status ?? 1);
}

const version = venvPythonVersion();
console.log(`Installed successfully into ${venvPython} (Python ${version})`);
