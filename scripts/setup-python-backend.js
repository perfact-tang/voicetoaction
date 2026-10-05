import { existsSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

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

const windows = process.platform === "win32";
const venvPython = join(
  projectRoot,
  ".venv",
  windows ? "Scripts/python.exe" : "bin/python",
);

function candidate(command, args = []) {
  return { command, args };
}

const candidates = [];
if (process.env.MEDIA_SPLITTER_PYTHON) {
  candidates.push(candidate(process.env.MEDIA_SPLITTER_PYTHON));
}

if (windows) {
  const profile = process.env.USERPROFILE;
  if (profile) {
    candidates.push(
      candidate(
        join(
          profile,
          ".cache",
          "codex-runtimes",
          "codex-primary-runtime",
          "dependencies",
          "python",
          "python.exe",
        ),
      ),
    );
  }
  candidates.push(candidate("py", ["-3"]), candidate("python"));
} else {
  candidates.push(candidate("python3"), candidate("python"));
}

function run(command, args, options = {}) {
  return spawnSync(command, args, {
    cwd: projectRoot,
    encoding: "utf8",
    stdio: options.capture ? "pipe" : "inherit",
  });
}

function isUsablePython(entry) {
  if (
    (entry.command.includes("/") || entry.command.includes("\\")) &&
    !existsSync(entry.command)
  ) {
    return false;
  }
  const result = run(
    entry.command,
    [...entry.args, "-c", "import encodings, venv, sys; print(sys.executable)"],
    { capture: true },
  );
  return result.status === 0;
}

let python = candidates.find(isUsablePython);
if (!python) {
  console.error(
    [
      "No working Python 3 installation was found.",
      "Install Python 3.10-3.12, reopen cmd, then run this command again.",
      "You can also set MEDIA_SPLITTER_PYTHON to the full path of python.exe.",
    ].join("\n"),
  );
  process.exit(1);
}

console.log(`Using Python: ${python.command} ${python.args.join(" ")}`.trim());

if (!existsSync(venvPython)) {
  console.log(`Creating project environment: ${join(projectRoot, ".venv")}`);
  const createResult = run(python.command, [
    ...python.args,
    "-m",
    "venv",
    join(projectRoot, ".venv"),
  ]);
  if (createResult.status !== 0) {
    process.exit(createResult.status ?? 1);
  }
}

console.log(`Installing ${requirementsName}...`);
const installResult = run(venvPython, [
  "-m",
  "pip",
  "install",
  "-r",
  requirementsPath,
]);
if (installResult.status !== 0) {
  process.exit(installResult.status ?? 1);
}

console.log(`Installed successfully into ${venvPython}`);
