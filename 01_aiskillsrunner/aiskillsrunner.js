#!/usr/bin/env node
/**
 * aiskillsrunner — 调用本机 DeepSeek Harness (dsh) 执行指定 skill 的 CLI 工具。
 *
 * 用法：
 *   aiskillsrunner --skill <名称> [--prompt <提示词>] [--reffilepath <路径>]
 *                 [--installurl <zip地址>] [--userid <用户ID>]
 *
 * 说明：
 *   --skill       已安装 skill 的名称（必填）
 *   --prompt      传给 skill 的提示词（可选）
 *   --reffilepath  接在提示词之后的参考文件的本地地址（可选）
 *   --installurl  当没有 skills 时，用于安装 skills 的 zip 文件 URL（可选）
 *   --userid      用户 ID，现阶段不处理（可选）
 *
 * 环境变量：
 *   AISKILLSRUNNER_DSH         指定 dsh 可执行文件路径（默认从 PATH 查找）
 *   AISKILLSRUNNER_SKILLS_DIR  指定 skills 安装目录（默认 ~/.agents/skills）
 */

import { spawn, spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { Readable } from 'node:stream';
import { pipeline } from 'node:stream/promises';

const VERSION = '1.0.0';

const USAGE = `用法: aiskillsrunner --skill <名称> [选项]

选项:
  --skill <名称>         已安装 skill 的名称（必填）
  --prompt <提示词>      传给 skill 的提示词
  --reffilepath <路径>   接在提示词之后的参考文件的本地地址
  --installurl <URL>     当没有 skills 时，用于安装 skills 的 zip 文件 URL
  --force-install        配合 --installurl：无论本地是否已有同名 skill 都重新安装
                         （会先删除安装目录里的旧文件，用于版本升级）
  --firebasekey <路径>   Firebase 服务账号密钥 JSON 的本地路径（别名 --key）；
                         校验通过后以环境变量 AISKILLS_FIREBASE_ACCOUNT 传给 skill
  --userid <ID>          用户 ID；以环境变量 AISKILLS_USERID 传给 skill
  --env <KEY=VALUE>      额外传给 skill 的环境变量（可重复）
  -h, --help             显示帮助
  -V, --version          显示版本

环境变量:
  AISKILLSRUNNER_DSH         指定 dsh 可执行文件路径（默认从 PATH 查找）
  AISKILLSRUNNER_SKILLS_DIR  指定 skills 安装目录（默认 ~/.agents/skills）

示例:
  aiskillsrunner --skill firebase-basics --prompt "初始化一个 Firebase 项目"
  aiskillsrunner --skill my-skill --prompt "分析该文件" --reffilepath ./report.txt
  aiskillsrunner --skill my-skill --installurl https://example.com/skills.zip
  aiskillsrunner --skill audio-to-multilingual-blog --reffilepath ./stt.txt \\
    --firebasekey ~/Downloads/vibecodingjapan-firebase-adminsdk-xxxx.json \\
    --userid KYTF9y43qgc39vKn0sI3qWpsjRE2
`;

/** 解析命令行参数，返回 { skill, prompt, reffilepath, installurl, userid, firebasekey, env }。 */
function parseArgs(argv) {
  const opts = {
    skill: undefined,
    prompt: undefined,
    reffilepath: undefined,
    installurl: undefined,
    forceInstall: false,
    userid: undefined,
    firebasekey: undefined,
    env: [],
  };

  const takeValue = (i, flag) => {
    if (i + 1 >= argv.length) {
      throw new Error(`选项 ${flag} 缺少参数值`);
    }
    return { value: argv[i + 1], next: i + 1 };
  };

  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];

    // --flag=value 形式
    const eq = arg.startsWith('--') ? arg.indexOf('=') : -1;
    if (eq !== -1) {
      const flag = arg.slice(0, eq);
      const value = arg.slice(eq + 1);
      switch (flag) {
        case '--skill': opts.skill = value; break;
        case '--prompt': opts.prompt = value; break;
        case '--reffilepath': opts.reffilepath = value; break;
        case '--installurl': opts.installurl = value; break;
        case '--force-install': opts.forceInstall = value !== 'false'; break;
        case '--userid': opts.userid = value; break;
        case '--firebasekey':
        case '--key': opts.firebasekey = value; break;
        case '--env': opts.env.push(value); break;
        default: throw new Error(`未知选项: ${flag}`);
      }
      continue;
    }

    switch (arg) {
      case '--skill': {
        const r = takeValue(i, '--skill');
        opts.skill = r.value;
        i = r.next;
        break;
      }
      case '--prompt': {
        const r = takeValue(i, '--prompt');
        opts.prompt = r.value;
        i = r.next;
        break;
      }
      case '--reffilepath': {
        const r = takeValue(i, '--reffilepath');
        opts.reffilepath = r.value;
        i = r.next;
        break;
      }
      case '--installurl': {
        const r = takeValue(i, '--installurl');
        opts.installurl = r.value;
        i = r.next;
        break;
      }
      case '--force-install': {
        opts.forceInstall = true;
        break;
      }
      case '--userid': {
        const r = takeValue(i, '--userid');
        opts.userid = r.value;
        i = r.next;
        break;
      }
      case '--firebasekey':
      case '--key': {
        const r = takeValue(i, arg);
        opts.firebasekey = r.value;
        i = r.next;
        break;
      }
      case '--env': {
        const r = takeValue(i, '--env');
        opts.env.push(r.value);
        i = r.next;
        break;
      }
      case '-h':
      case '--help':
        opts.help = true;
        break;
      case '-V':
      case '--version':
        opts.version = true;
        break;
      default:
        throw new Error(`未知参数: ${arg}`);
    }
  }

  return opts;
}

/** skills 的安装目录（可被 AISKILLSRUNNER_SKILLS_DIR 覆盖）。 */
function skillsInstallDir() {
  return process.env.AISKILLSRUNNER_SKILLS_DIR || path.join(os.homedir(), '.agents', 'skills');
}

/**
 * dsh 会扫描的 skill 根目录（用于判断某个 skill 是否已安装）。
 * 顺序与 dsh-skill-filesystem 的默认发现顺序一致。
 */
function skillRoots() {
  const cwd = process.cwd();
  const home = os.homedir();
  return [
    path.join(cwd, '.dsh', 'skills'),
    path.join(cwd, '.agents', 'skills'),
    skillsInstallDir(),
    path.join(home, '.dsh', 'skills'),
    path.join(home, '.agents', 'skills'),
  ];
}

/** 判断某个名称的 skill 是否已安装。 */
function skillExists(name) {
  const candidates = [];
  for (const root of skillRoots()) {
    candidates.push(path.join(root, name, 'SKILL.md'));
    candidates.push(path.join(root, `${name}.md`));
  }
  return candidates.some((p) => {
    try {
      return fs.statSync(p).isFile();
    } catch {
      return false;
    }
  });
}

/**
 * 删除安装目录里已安装的 skill（目录型 + 扁平型），用于 --force-install 覆盖升级。
 * 只操作 skillsInstallDir()，不会动全局 ~/.agents/skills。
 */
function removeInstalledSkill(name) {
  const target = skillsInstallDir();
  const removed = [];
  const dir = path.join(target, name);
  if (fs.existsSync(dir)) {
    fs.rmSync(dir, { recursive: true, force: true });
    removed.push(dir);
  }
  const flat = path.join(target, `${name}.md`);
  if (fs.existsSync(flat)) {
    fs.rmSync(flat, { force: true });
    removed.push(flat);
  }
  return removed;
}

/** 读取参考文件，返回 { absPath, content }。 */
function readReferenceFile(p) {
  const abs = path.resolve(p);
  let stat;
  try {
    stat = fs.statSync(abs);
  } catch {
    throw new Error(`参考文件不存在: ${abs}`);
  }
  if (!stat.isFile()) {
    throw new Error(`参考文件不是普通文件: ${abs}`);
  }
  return { absPath: abs, content: fs.readFileSync(abs, 'utf8') };
}

/**
 * 解析并校验 --firebasekey 指向的服务账号密钥文件。
 * 支持写法里的 ~（部分 shell 在加引号时不会展开），返回绝对路径。
 */
function resolveFirebaseKey(p) {
  const expanded = p.replace(/^~(?=$|\/)/, os.homedir());
  const abs = path.resolve(expanded);
  let stat;
  try {
    stat = fs.statSync(abs);
  } catch {
    throw new Error(`Firebase 密钥文件不存在: ${abs}`);
  }
  if (!stat.isFile()) {
    throw new Error(`Firebase 密钥路径不是普通文件: ${abs}`);
  }
  return abs;
}

/**
 * 解析 --env KEY=VALUE，返回 [{ key, value }]。
 * KEY 必须是合法的环境变量名，且不能为空。
 */
function parseEnvPairs(rawPairs) {
  const pairs = [];
  for (const raw of rawPairs) {
    const eq = raw.indexOf('=');
    if (eq <= 0) {
      throw new Error(`--env 需要 KEY=VALUE 形式，收到: ${raw}`);
    }
    const key = raw.slice(0, eq).trim();
    if (!/^[A-Za-z_][A-Za-z0-9_]*$/.test(key)) {
      throw new Error(`--env 的变量名不合法: ${key}`);
    }
    pairs.push({ key, value: raw.slice(eq + 1) });
  }
  return pairs;
}

/** 从 URL 下载文件到本地路径。 */
async function downloadFile(url, destPath) {
  let res;
  try {
    res = await fetch(url, { redirect: 'follow' });
  } catch (err) {
    throw new Error(`下载失败（无法连接）: ${err.message}`);
  }
  if (!res.ok || !res.body) {
    throw new Error(`下载失败: HTTP ${res.status} ${res.statusText}`);
  }
  await pipeline(Readable.fromWeb(res.body), fs.createWriteStream(destPath));
}

/** 解压 zip 到目标目录（优先 unzip，回退 python3）。 */
function extractZip(zipPath, destDir) {
  fs.mkdirSync(destDir, { recursive: true });

  let r = spawnSync('unzip', ['-o', '-q', zipPath, '-d', destDir], { stdio: 'ignore' });
  if (!r.error && r.status === 0) return;

  r = spawnSync('python3', ['-m', 'zipfile', '-e', zipPath, destDir], { stdio: 'ignore' });
  if (!r.error && r.status === 0) return;

  throw new Error('无法解压 zip 文件（需要系统安装 unzip 或 python3）');
}

/**
 * 在解压后的目录树中查找 skill bundle。
 * 支持两种 DSH 格式：目录型 (<name>/SKILL.md) 与扁平型 (<name>.md)。
 * 返回 [{ type: 'dir'|'file', src, name }]。
 */
function findSkillBundles(root) {
  const bundles = [];
  const skillDirs = new Set();

  const list = (dir) => {
    let entries;
    try {
      entries = fs.readdirSync(dir, { withFileTypes: true });
    } catch {
      return;
    }
    for (const e of entries) {
      const full = path.join(dir, e.name);
      if (e.isDirectory()) {
        const md = path.join(full, 'SKILL.md');
        if (fs.existsSync(md) && fs.statSync(md).isFile()) {
          skillDirs.add(full);
          bundles.push({ type: 'dir', src: full, name: e.name });
        }
        list(full);
      }
    }
  };
  list(root);

  const isUnderSkillDir = (p) => {
    let d = path.dirname(p);
    while (true) {
      if (skillDirs.has(d)) return true;
      if (d === root) return false;
      const up = path.dirname(d);
      if (up === d) return false;
      d = up;
    }
  };

  const collectFlat = (dir) => {
    let entries;
    try {
      entries = fs.readdirSync(dir, { withFileTypes: true });
    } catch {
      return;
    }
    for (const e of entries) {
      const full = path.join(dir, e.name);
      if (e.isDirectory()) {
        collectFlat(full);
      } else if (e.name.endsWith('.md') && e.name !== 'SKILL.md' && !isUnderSkillDir(full)) {
        bundles.push({ type: 'file', src: full, name: e.name.replace(/\.md$/, '') });
      }
    }
  };
  collectFlat(root);

  // 去重：同名时目录型优先
  const seen = new Map();
  for (const b of bundles) {
    if (!seen.has(b.name) || b.type === 'dir') seen.set(b.name, b);
  }
  return [...seen.values()];
}

/** 从 URL 安装 skills：下载 zip -> 解压 -> 复制 skill bundle 到安装目录。 */
async function installSkills(url) {
  const target = skillsInstallDir();
  const tmpRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'aiskillsrunner-'));
  const zipPath = path.join(tmpRoot, 'skills.zip');
  const extractDir = path.join(tmpRoot, 'extracted');

  try {
    process.stderr.write(`正在下载 skills: ${url}\n`);
    await downloadFile(url, zipPath);

    process.stderr.write('正在解压...\n');
    extractZip(zipPath, extractDir);

    const bundles = findSkillBundles(extractDir);
    if (bundles.length === 0) {
      throw new Error('zip 中没有找到任何 skill（需包含 <名称>/SKILL.md 或 <名称>.md）');
    }

    fs.mkdirSync(target, { recursive: true });
    for (const b of bundles) {
      if (b.type === 'dir') {
        fs.cpSync(b.src, path.join(target, b.name), { recursive: true, force: true });
      } else {
        fs.copyFileSync(b.src, path.join(target, `${b.name}.md`));
      }
      process.stderr.write(`  已安装 skill: ${b.name}\n`);
    }
    process.stderr.write(`skills 安装完成，共 ${bundles.length} 个（目录: ${target}）\n`);
  } finally {
    fs.rmSync(tmpRoot, { recursive: true, force: true });
  }
}

/** 组合最终交给 dsh 的任务文本。extras 为已解析的运行参数说明行。 */
function composeTask(skill, prompt, ref, extras = []) {
  const parts = [];
  // "/名称" 令牌：确定性加载并注入该 skill 的指令内容。
  parts.push(`/${skill}`);
  parts.push(`请使用已安装的 skill "${skill}" 完成以下任务，并在完成后输出处理结果报告。`);

  if (prompt && prompt.trim()) {
    parts.push(`任务提示词：\n${prompt.trim()}`);
  }
  if (ref && ref.content.trim()) {
    parts.push(`参考文件（${ref.absPath}）内容：\n${ref.content}`);
  }
  if (!(prompt && prompt.trim()) && !(ref && ref.content.trim())) {
    parts.push('请执行该 skill 并输出处理结果报告。');
  }
  if (extras.length) {
    parts.push(
      '运行参数（由 aiskillsrunner 以环境变量传入，skill 请直接读取环境变量，' +
        '不要在命令里写死这些值）：\n' +
        extras.join('\n')
    );
  }
  return parts.join('\n\n');
}

/** 调用 dsh headless 模式执行任务，返回 { code, stdout, error }。 */
function runDsh(task, childEnv = process.env) {
  return new Promise((resolve) => {
    const bin = process.env.AISKILLSRUNNER_DSH || 'dsh';
    const child = spawn(bin, ['--profile', 'headless', task], {
      stdio: ['inherit', 'pipe', 'inherit'],
      env: childEnv,
    });

    let stdout = '';
    child.stdout.setEncoding('utf8');
    child.stdout.on('data', (d) => { stdout += d; });

    child.on('error', (err) => {
      resolve({ code: 127, stdout, error: err });
    });
    child.on('close', (code) => {
      resolve({ code: code ?? 1, stdout, error: null });
    });
  });
}

async function main() {
  let opts;
  try {
    opts = parseArgs(process.argv.slice(2));
  } catch (err) {
    process.stderr.write(`错误: ${err.message}\n\n${USAGE}`);
    process.exit(2);
  }

  if (opts.help) {
    process.stdout.write(USAGE);
    return;
  }
  if (opts.version) {
    process.stdout.write(`aiskillsrunner ${VERSION}\n`);
    return;
  }

  if (!opts.skill || !opts.skill.trim()) {
    process.stderr.write(`错误: 缺少必填参数 --skill\n\n${USAGE}`);
    process.exit(2);
  }
  const skill = opts.skill.trim();

  // 解析要透传给 skill 的运行参数（通过子进程环境变量传递）。
  let firebaseKeyPath = null;
  let envPairs = [];
  try {
    if (opts.firebasekey) firebaseKeyPath = resolveFirebaseKey(opts.firebasekey);
    envPairs = parseEnvPairs(opts.env);
  } catch (err) {
    process.stderr.write(`错误: ${err.message}\n`);
    process.exit(2);
  }

  const userId = opts.userid && opts.userid.trim() ? opts.userid.trim() : null;

  const childEnv = { ...process.env };
  const extras = [];
  if (firebaseKeyPath) {
    // 注意：不能用 *_KEY 之类的名字。dsh 的 subprocess 层会把名字匹配
    // /KEY|PASSWORD|SECRET|TOKEN/i 的环境变量从子进程环境里剔除，
    // 所以这里用 AISKILLS_FIREBASE_ACCOUNT。
    childEnv.AISKILLS_FIREBASE_ACCOUNT = firebaseKeyPath;
    extras.push(`- Firebase 服务账号密钥：${firebaseKeyPath}（环境变量 AISKILLS_FIREBASE_ACCOUNT）`);
  }
  if (userId) {
    childEnv.AISKILLS_USERID = userId;
    extras.push(`- 用户 ID：${userId}（环境变量 AISKILLS_USERID）`);
  }
  for (const { key, value } of envPairs) {
    childEnv[key] = value;
    extras.push(`- ${key}：${value}（环境变量 ${key}）`);
  }

  // 安装 skills：仅在提供了 --installurl 时触发。
  if (opts.forceInstall && !opts.installurl) {
    process.stderr.write('错误: --force-install 需要配合 --installurl 使用\n');
    process.exit(2);
  }
  if (opts.installurl) {
    if (opts.forceInstall) {
      // 覆盖升级：先删掉安装目录里的旧版本，避免 cpSync 保留已删除的旧文件
      const removed = removeInstalledSkill(skill);
      if (removed.length) {
        process.stderr.write(`--force-install：已删除本地旧版本 ${removed.join(', ')}\n`);
      }
      await installSkills(opts.installurl);
    } else if (!skillExists(skill)) {
      process.stderr.write(`未找到 skill "${skill}"，从 --installurl 安装...\n`);
      await installSkills(opts.installurl);
    } else {
      process.stderr.write(`skill "${skill}" 已存在，跳过安装。\n`);
    }
  }

  // 读取参考文件。
  let ref = null;
  if (opts.reffilepath) {
    ref = readReferenceFile(opts.reffilepath);
  }

  const task = composeTask(skill, opts.prompt, ref, extras);

  if (extras.length) {
    process.stderr.write(`已透传运行参数：${extras.map((line) => line.split('（')[0].replace(/^- /, '')).join('; ')}\n`);
  }
  process.stderr.write(`正在使用 skill "${skill}" 执行任务（dsh --profile headless）...\n`);
  const result = await runDsh(task, childEnv);

  if (result.error) {
    process.stderr.write(`错误: 无法启动 dsh：${result.error.message}\n`);
    process.stderr.write('请确认已安装 DeepSeek Harness，且 dsh 在 PATH 中，或用 AISKILLSRUNNER_DSH 指定路径。\n');
    process.exit(127);
  }

  // 打印报告结果（dsh 处理后的最终结果，位于 stdout）。
  if (result.stdout.trim()) {
    process.stdout.write(result.stdout);
    if (!result.stdout.endsWith('\n')) process.stdout.write('\n');
  }

  process.exit(result.code);
}

main().catch((err) => {
  process.stderr.write(`错误: ${err.message}\n`);
  process.exit(1);
});
