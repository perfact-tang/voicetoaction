#!/usr/bin/env node
/**
 * 手动发布一篇 text-to-interactive-blog 产物（补发/重发用）。
 *
 * 与 monitor.js 走完全相同的路径：vibecodingjapan-upblog 上传三语正文，
 * 再把 graph.json 写进 mkblog/<docid>.graph。
 *
 * 用法：
 *   node scripts/publish-interactive-article.mjs \
 *     --dir ~/Documents/interactive-articles/1794823698683 \
 *     --uid KYTF9y43qgc39vKn0sI3qWpsjRE2 \
 *     --key ~/Downloads/vibecodingjapan-firebase-adminsdk-xxxx.json
 *
 * 省略 --dir 时取 ~/Documents/interactive-articles 下最近一个完整目录。
 */
import { readFileSync } from "node:fs";
import { basename, resolve } from "node:path";
import { cert, initializeApp } from "firebase-admin/app";
import { getFirestore } from "firebase-admin/firestore";
import { homedir } from "node:os";
import { join } from "node:path";
import {
  INTERACTIVE_REQUIRED_FILES,
  INTERACTIVE_ROOT,
  pickInteractiveOutput,
  publishInteractiveArticle
} from "../monitor-interactive-blog.js";

function parseArgs(argv) {
  const values = {};
  for (let index = 0; index < argv.length; index += 1) {
    const token = argv[index];
    if (!token.startsWith("--")) continue;
    const key = token.slice(2);
    const next = argv[index + 1];
    if (next && !next.startsWith("--")) {
      values[key] = next;
      index += 1;
    } else {
      values[key] = true;
    }
  }
  return values;
}

const args = parseArgs(process.argv.slice(2));
const keyPath = String(args.key || process.env.AISKILLS_FIREBASE_ACCOUNT || "").replace(/^~(?=\/)/, homedir());
const uid = String(args.uid || process.env.AISKILLS_USERID || "").trim();

if (!keyPath || !uid) {
  console.error(
    "用法：node scripts/publish-interactive-article.mjs --dir <产物目录> --uid <作者 uid> --key <服务账号密钥.json>"
  );
  process.exit(2);
}

let dir = args.dir ? resolve(String(args.dir).replace(/^~(?=\/)/, homedir())) : "";
if (!dir) {
  const latest = await pickInteractiveOutput(INTERACTIVE_ROOT, 0);
  if (!latest) {
    console.error(`${INTERACTIVE_ROOT} 下没有可发布的目录（需要 ${INTERACTIVE_REQUIRED_FILES.join(" / ")}）。`);
    process.exit(2);
  }
  dir = latest.dir;
}

const docId = String(args.docid || basename(dir));
const serviceAccount = JSON.parse(readFileSync(keyPath, "utf8"));
initializeApp({ credential: cert(serviceAccount) });

try {
  const published = await publishInteractiveArticle({
    dir,
    docId,
    userId: uid,
    db: getFirestore(),
    firebaseKeyPath: keyPath,
    log: (line) => console.log(`[publish] ${line}`)
  });
  console.log(
    `✔ 已发布 ${published.blogPath}（docid=${published.docId}，` +
      `${published.graphNodes} 个图谱节点 / ${published.graphEdges} 条边）`
  );
  if (published.uploads) console.log(`  ${published.uploads}`);
  process.exit(0);
} catch (error) {
  console.error(`✖ 发布失败：${error.message}`);
  process.exit(1);
}
