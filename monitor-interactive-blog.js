import { spawn } from "node:child_process";
import { mkdtemp, readdir, readFile, rm, stat, writeFile } from "node:fs/promises";
import { homedir, tmpdir } from "node:os";
import { join } from "node:path";

/**
 * text-to-interactive-blog 的产物与发布。
 *
 * 这个 skill 只写本地文件（不调用上传工具），所以发布是后端的事：
 *   1. 在 `~/Documents/interactive-articles/<Unix 毫秒>/` 找到本次写出的完整产物；
 *   2. 调用一次 `vibecodingjapan-upblog` 写 Cloud Storage + Firestore(mkblog)；
 *   3. 把 `graph.json` 写进 `mkblog/<docid>.graph`，供两个博客系统渲染知识图谱；
 *   4. 公开站展示始终关闭（`ispublicsiteshow` 强制 false），需要公开时在编辑器里手动开启。
 *
 * 目录名就是文章 docid，因此 `文章信息.json` 的 docid 必须与目录名一致。
 */

export const INTERACTIVE_OUTPUT_FILES = ["zh.md", "ja.md", "en.md", "graph.json", "文章信息.json"];
export const INTERACTIVE_ROOT = process.env.INTERACTIVE_BLOG_TMPDIR || join(homedir(), "Documents", "interactive-articles");
export const INTERACTIVE_BLOG_SKILL = "text-to-interactive-blog";
/**
 * 公开站展示开关，固定 false。
 *
 * 公开站（02_vibecodingjapanpublic）只渲染 `ispublicsiteshow === true` 的文章，
 * 所以这里不接受 skill 生成的 `文章信息.json` 里的值：无论它写 `true` 还是漏写，
 * 发布时都强制写成 `false`，文章只留在后台编辑器里。
 * 需要公开时由作者打开 `/editblog/<docid>` 手动开启展示。
 */
export const INTERACTIVE_PUBLIC_SITE_SHOW = false;
/** 判定完成所需的文件（article.json 是可选的调试产物，不参与校验）。 */
export const INTERACTIVE_REQUIRED_FILES = INTERACTIVE_OUTPUT_FILES;

/** 本次运行窗口内写出的输出目录，最近的排最前。 */
export async function findInteractiveOutputs(root, startedAtMs, { toleranceMs = 90_000 } = {}) {
  let entries;
  try {
    entries = await readdir(root, { withFileTypes: true });
  } catch {
    return [];
  }

  const outputs = [];
  for (const entry of entries) {
    if (!entry.isDirectory() || !/^\d{10,}$/.test(entry.name)) continue;
    const dir = join(root, entry.name);
    let mtimeMs;
    try {
      mtimeMs = (await stat(dir)).mtimeMs;
    } catch {
      continue;
    }
    if (startedAtMs && mtimeMs < startedAtMs - toleranceMs) continue;

    const present = [];
    const missing = [];
    for (const name of INTERACTIVE_REQUIRED_FILES) {
      try {
        const size = (await stat(join(dir, name))).size;
        if (size > 0) present.push({ name, size });
        else missing.push(name);
      } catch {
        missing.push(name);
      }
    }
    outputs.push({
      docId: entry.name,
      dir,
      present,
      missing,
      complete: missing.length === 0,
      mtimeMs
    });
  }

  return outputs.sort((a, b) => b.mtimeMs - a.mtimeMs || b.docId.localeCompare(a.docId));
}

/** 找到本次要发布的那一篇；完整产物优先，其次最近写出的一份。 */
export async function pickInteractiveOutput(root, startedAtMs) {
  const outputs = await findInteractiveOutputs(root, startedAtMs);
  return outputs.find((output) => output.complete) || outputs[0] || null;
}

async function readJson(path, label) {
  let text;
  try {
    text = await readFile(path, "utf8");
  } catch (error) {
    throw new Error(`${label} 读取失败（${path}）：${error.message}`);
  }
  try {
    return JSON.parse(text);
  } catch (error) {
    throw new Error(`${label} 不是合法 JSON（${path}）：${error.message}`);
  }
}

/** 放宽校验：docid/createdat 必须指向同一个目录，三语标题必须存在。 */
function validateArticleInfo(info, docId) {
  const infoDocId = String(info?.docid ?? "").trim();
  if (infoDocId && infoDocId !== docId) {
    throw new Error(`文章信息.json 的 docid=${infoDocId} 与目录名 ${docId} 不一致`);
  }
  const translations = info?.translations;
  if (!translations || typeof translations !== "object") {
    throw new Error("文章信息.json 缺少 translations");
  }
  for (const locale of ["ja", "zh", "en"]) {
    const entry = translations[locale];
    if (!entry || typeof entry !== "object") throw new Error(`文章信息.json 缺少 ${locale}`);
    if (!String(entry.blogtitle ?? "").trim()) throw new Error(`文章信息.json 的 ${locale}.blogtitle 为空`);
    if (!String(entry.blogseo ?? "").trim()) throw new Error(`文章信息.json 的 ${locale}.blogseo 为空`);
  }
  return translations;
}

function validateGraph(graph) {
  const nodes = graph?.nodes;
  if (!Array.isArray(nodes) || nodes.length === 0) throw new Error("graph.json 没有 nodes");
  const ids = new Set();
  for (const node of nodes) {
    if (!node || typeof node.id !== "string") throw new Error("graph.json 的节点缺少 id");
    if (ids.has(node.id)) throw new Error(`graph.json 的节点 id 重复：${node.id}`);
    ids.add(node.id);
    const labels = node.labels || {};
    for (const locale of ["zh", "ja", "en"]) {
      if (!String(labels[locale] ?? "").trim()) throw new Error(`graph.json 的节点 ${node.id} 缺少 ${locale} 标签`);
    }
  }
  return graph;
}

function runCommand(command, args, { timeoutMs = 120_000, cwd } = {}) {
  return new Promise((resolve) => {
    let child;
    try {
      child = spawn(command, args, { cwd, env: process.env, stdio: ["ignore", "pipe", "pipe"] });
    } catch (error) {
      resolve({ code: -1, stdout: "", stderr: error.message, timedOut: false });
      return;
    }

    let stdout = "";
    let stderr = "";
    let settled = false;
    const finish = (result) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      resolve(result);
    };
    const timer = setTimeout(() => {
      try {
        child.kill("SIGKILL");
      } catch {
        /* 进程已经退出 */
      }
      finish({ code: -1, stdout, stderr: `${stderr}\n命令超时（${timeoutMs} ms）`, timedOut: true });
    }, timeoutMs);

    child.stdout.on("data", (chunk) => {
      stdout += chunk.toString();
    });
    child.stderr.on("data", (chunk) => {
      stderr += chunk.toString();
    });
    child.on("error", (error) => finish({ code: -1, stdout, stderr: error.message, timedOut: false }));
    child.on("close", (code) => finish({ code: code ?? -1, stdout, stderr, timedOut: false }));
  });
}

/**
 * 发布一篇 interactive 文章。
 *
 * @param {object} options
 * @param {string} options.dir             产物目录
 * @param {string} options.docId           文章 docid（= 目录名）
 * @param {string} options.userId          作者 uid
 * @param {object} options.db              Firestore 实例（firebase-admin）
 * @param {string} [options.upblogBin]     上传 CLI，默认 vibecodingjapan-upblog
 * @param {string} [options.firebaseKeyPath] 服务账号密钥路径
 * @param {(line:string)=>void} [options.log]
 * @returns {Promise<{docId:string, blogPath:string, uploads:string, graphNodes:number, graphEdges:number}>}
 */
export async function publishInteractiveArticle({
  dir,
  docId,
  userId,
  db,
  upblogBin = "vibecodingjapan-upblog",
  firebaseKeyPath,
  collection = "mkblog",
  log = () => {}
}) {
  if (!docId || !/^\d{10,}$/.test(docId)) throw new Error(`无效的 docid：${docId}`);
  if (!userId) throw new Error("缺少作者 uid，无法发布文章");

  const infoPath = join(dir, "文章信息.json");
  const info = await readJson(infoPath, "文章信息.json");
  validateArticleInfo(info, docId);

  const graph = validateGraph(await readJson(join(dir, "graph.json"), "graph.json"));

  // 公开站展示必须关闭：不信任 skill 写出的 文章信息.json，上传前生成一份强制
  // ispublicsiteshow=false 的临时副本交给 CLI，原始产物文件保持不动。
  const uploadInfoDir = await mkdtemp(join(tmpdir(), "interactive-info-"));
  const uploadInfoPath = join(uploadInfoDir, "文章信息.json");
  await writeFile(
    uploadInfoPath,
    JSON.stringify({ ...info, ispublicsiteshow: INTERACTIVE_PUBLIC_SITE_SHOW }, null, 2)
  );

  const args = [
    "--ja", join(dir, "ja.md"),
    "--zh", join(dir, "zh.md"),
    "--en", join(dir, "en.md"),
    "--info", uploadInfoPath,
    "--docid", docId,
    "--uid", userId
  ];
  if (firebaseKeyPath) args.push("--key", firebaseKeyPath);

  log(`上传文章 ${docId}（${upblogBin}，ispublicsiteshow=false 已强制）...`);
  let result;
  try {
    result = await runCommand(upblogBin, args, { timeoutMs: 180_000 });
  } finally {
    await rm(uploadInfoDir, { recursive: true, force: true });
  }
  if (result.code !== 0) {
    const reason = `${result.stderr || result.stdout || ""}`.trim().split("\n").slice(-8).join(" ").slice(0, 600);
    throw new Error(`vibecodingjapan-upblog 失败（exit ${result.code}）：${reason || "无输出"}`);
  }

  // 图谱不属于 upblog 的字段集合，单独写回同一条 mkblog 文档。
  // 同一次 merge 再写一遍 ispublicsiteshow=false：即使 CLI 或别处刚写过 true，
  // 也保证文章不会出现在公开站。
  await db.collection(collection).doc(docId).set(
    {
      graph,
      graphVersion: Number(graph.version) || 1,
      graphUpdatedAt: Date.now(),
      ispublicsiteshow: INTERACTIVE_PUBLIC_SITE_SHOW
    },
    { merge: true }
  );

  const snapshot = await db.collection(collection).doc(docId).get();
  if (!snapshot.exists) throw new Error(`发布后仍找不到 ${collection}/${docId}`);

  const uploadLines = (result.stdout || "")
    .split("\n")
    .map((line) => line.trim())
    .filter((line) => /mkfile\/|Firestore|完了|完成|✔|✓/i.test(line))
    .slice(0, 8)
    .join(" | ");

  return {
    docId,
    blogPath: `/blog/${docId}`,
    uploads: uploadLines,
    graphNodes: graph.nodes.length,
    graphEdges: Array.isArray(graph.edges) ? graph.edges.length : 0
  };
}
