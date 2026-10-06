#!/usr/bin/env node
/**
 * Upload one rendered explainer movie and publish it as a note.
 *
 *   1. upload the file to Firebase Storage           -> movie/<noteid>.mp4
 *   2. read back a tokenised public URL
 *   3. write /users/<userid>/notes/<noteid>          (Firestore)
 *   4. optionally send an FCM notification
 *
 * Usage:
 *   node upload-movie.js --file <path> [options]
 *
 * Options:
 *   --file <path>        rendered movie (required)
 *   --note <path>        note JSON: { title, body, color, hidden, tags }
 *   --noteid <id>        note document id        (default: DocID or timestamp)
 *   --docid <id>         build id; used as the default note id and file name
 *   --userid <uid>       Firebase uid            (default: env / built-in)
 *   --storagepath <p>    Storage path            (default: movie/<noteid>.mp4)
 *   --title <text>       override the note title
 *   --body <text>        override the note body
 *   --tags <a,b>         override the note tags  (default: movie)
 *   --public             also write a download-token URL *and* make the object
 *                        publicly readable (uniform bucket access permitting)
 *   --dry-run            validate and print everything, write nothing
 *   --json               print a single machine-readable JSON result
 *   --no-notify          skip the FCM notification
 *   --notify-topic <t>   FCM topic  (default: env AISKILLS_FCM_TOPIC)
 *   --notify-token <t>   FCM device token (may be repeated)
 *
 * Credentials (highest priority first), mirroring upload-blog.sh:
 *   AISKILLS_FIREBASE_ACCOUNT  service account JSON path (set by aiskillsrunner)
 *   AISKILLS_MOVIE_KEY         same, for direct runs
 *   ~/Downloads/vibecodingjapan-firebase-adminsdk-fbsvc-f376a3b494.json
 *
 * Exit codes: 0 ok, 1 bad input, 2 upload/write failure.
 */

"use strict";

const fs = require("node:fs");
const path = require("node:path");
const os = require("node:os");

const DEFAULT_KEY = path.join(
  os.homedir(),
  "Downloads",
  "vibecodingjapan-firebase-adminsdk-fbsvc-f376a3b494.json",
);
const DEFAULT_UID = "KYTF9y43qgc39vKn0sI3qWpsjRE2";
const DEFAULT_TAGS = ["movie"];
const NOTE_COLOR = "paper";

// ---------------------------------------------------------------- args

function parseArgs(argv) {
  const out = { notifyTokens: [] };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    const next = () => {
      const v = argv[++i];
      if (v === undefined) {
        fail(`missing value for ${a}`);
      }
      return v;
    };
    switch (a) {
      case "--file": out.file = next(); break;
      case "--note": out.note = next(); break;
      case "--noteid": out.noteId = next(); break;
      case "--docid": out.docId = next(); break;
      case "--userid": out.userId = next(); break;
      case "--storagepath": out.storagePath = next(); break;
      case "--title": out.title = next(); break;
      case "--body": out.body = next(); break;
      case "--tags": out.tags = next().split(",").map((s) => s.trim()).filter(Boolean); break;
      case "--key": out.key = next(); break;
      case "--notify-topic": out.notifyTopic = next(); break;
      case "--notify-token": out.notifyTokens.push(next()); break;
      case "--public": out.public = true; break;
      case "--dry-run": out.dryRun = true; break;
      case "--json": out.json = true; break;
      case "--no-notify": out.noNotify = true; break;
      case "-h":
      case "--help": out.help = true; break;
      default:
        fail(`unknown option: ${a}`);
    }
  }
  return out;
}

function fail(msg, code = 1) {
  console.error(`✖ ${msg}`);
  process.exit(code);
}

const USAGE = `用法: node upload-movie.js --file <movie.mp4> [--note note.json] [选项]

  --file <path>        渲染好的视频（必填）
  --note <path>        笔记 JSON: { title, body, color, hidden, tags }
  --noteid <id>        笔记文档 id（默认 DocID 或当前毫秒时间戳）
  --docid <id>         构建 id；同时作为默认 note id 与文件名
  --userid <uid>       Firebase uid（默认取环境变量或内置值）
  --storagepath <p>    Storage 路径（默认 movie/<noteid>.mp4）
  --title/--body/--tags  覆盖笔记字段（tags 默认 movie）
  --public             额外把对象设为公开可读
  --dry-run            只校验并打印，不写任何数据
  --json               输出单行 JSON 结果
  --no-notify          不发送 FCM
  --notify-topic <t>   FCM topic
  --notify-token <t>   FCM device token（可重复）`;

// ---------------------------------------------------------------- firebase

function resolveKeyPath(explicit) {
  return (
    explicit ||
    process.env.AISKILLS_FIREBASE_ACCOUNT ||
    process.env.AISKILLS_MOVIE_KEY ||
    DEFAULT_KEY
  );
}

function initAdmin(keyPath) {
  let admin;
  try {
    admin = require("firebase-admin");
  } catch {
    fail(
      "找不到 firebase-admin。请先在 skill 目录安装依赖：\n" +
        "    cd <skill>/scripts && npm install",
    );
  }
  if (!fs.existsSync(keyPath)) {
    fail(
      `找不到 Firebase 服务账号密钥: ${keyPath}\n` +
        "  可用 AISKILLS_MOVIE_KEY 指定其它路径。",
    );
  }
  let credential;
  try {
    credential = admin.credential.cert(JSON.parse(fs.readFileSync(keyPath, "utf8")));
  } catch (err) {
    fail(`密钥文件无法解析: ${keyPath}\n  ${err.message}`);
  }
  const projectId = JSON.parse(fs.readFileSync(keyPath, "utf8")).project_id;
  if (admin.apps.length === 0) {
    admin.initializeApp({ credential, projectId, storageBucket: `${projectId}.firebasestorage.app` });
  }
  return admin;
}

/** The bucket name has changed spelling over time; try both. */
async function resolveBucket(admin, projectId) {
  const candidates = [
    `${projectId}.firebasestorage.app`,
    `${projectId}.appspot.com`,
  ];
  for (const name of candidates) {
    try {
      const bucket = admin.storage().bucket(name);
      const [exists] = await bucket.exists();
      if (exists) return { bucket, name };
    } catch {
      /* try the next spelling */
    }
  }
  // fall back to the modern name and let the upload surface the real error
  return { bucket: admin.storage().bucket(candidates[0]), name: candidates[0] };
}

// ---------------------------------------------------------------- note

function loadNote(args) {
  let note = {};
  if (args.note) {
    if (!fs.existsSync(args.note)) fail(`找不到笔记 JSON: ${args.note}`);
    try {
      note = JSON.parse(fs.readFileSync(args.note, "utf8"));
    } catch (err) {
      fail(`笔记 JSON 无法解析: ${args.note}\n  ${err.message}`);
    }
  }
  const title = args.title ?? note.title;
  const body = args.body ?? note.body;
  if (!title || String(title).trim() === "") {
    fail("笔记缺少 title（用 --title 或 note.json 的 title 提供）");
  }
  if (!body || String(body).trim() === "") {
    fail("笔记缺少 body（用 --body 或 note.json 的 body 提供）");
  }
  if (body.length > 1000) {
    console.error(`⚠ body 超过 1000 字（${body.length}），仍会写入，请确认是否符合要求。`);
  }
  return {
    title: String(title),
    body: String(body),
    color: note.color ?? NOTE_COLOR,
    hidden: note.hidden ?? false,
    tags: args.tags ?? note.tags ?? DEFAULT_TAGS,
  };
}

// ---------------------------------------------------------------- main

(async () => {
  const args = parseArgs(process.argv.slice(2));
  if (args.help) {
    console.log(USAGE);
    process.exit(0);
  }
  if (!args.file) {
    console.error(USAGE);
    fail("--file 是必填项");
  }
  const file = path.resolve(args.file);
  if (!fs.existsSync(file)) fail(`找不到视频文件: ${file}`);
  const sizeMB = (fs.statSync(file).size / 1048576).toFixed(2);

  const keyPath = resolveKeyPath(args.key);
  const userId =
    args.userId ||
    process.env.AISKILLS_USERID ||
    process.env.AISKILLS_MOVIE_UID ||
    DEFAULT_UID;
  const noteId = String(args.noteId || args.docId || Date.now());
  const note = loadNote(args);
  const storagePath =
    args.storagePath || `movie/${noteId}${path.extname(file) || ".mp4"}`;

  const report = {
    ok: false,
    dryRun: Boolean(args.dryRun),
    file,
    sizeMB,
    storagePath,
    userId,
    noteId,
    notePath: `users/${userId}/notes/${noteId}`,
    publicUrl: null,
    notified: false,
  };

  if (args.dryRun) {
    const result = { ...report, ok: true, credentials: keyPath };
    if (args.json) console.log(JSON.stringify(result));
    else {
      console.log("── dry run，不写任何数据 ──");
      console.log(`  视频      : ${file} (${sizeMB} MB)`);
      console.log(`  密钥      : ${keyPath}`);
      console.log(`  Storage   : ${storagePath}`);
      console.log(`  笔记路径  : ${result.notePath}`);
      console.log(`  title     : ${note.title}`);
      console.log(`  body      : ${note.body.slice(0, 120)}${note.body.length > 120 ? "…" : ""}`);
      console.log(`  tags      : ${note.tags.join(", ")}   color: ${note.color}   hidden: ${note.hidden}`);
    }
    process.exit(0);
  }

  const admin = initAdmin(keyPath);
  const projectId = JSON.parse(fs.readFileSync(keyPath, "utf8")).project_id;
  const { bucket, name: bucketName } = await resolveBucket(admin, projectId);
  console.log(`→ Firebase  : ${projectId}`);
  console.log(`→ Bucket    : ${bucketName}`);
  console.log(`→ 上传      : ${file} (${sizeMB} MB) -> ${storagePath}`);

  // ---- 1/2. upload + public url -------------------------------------
  const downloadToken = require("node:crypto").randomUUID();
  try {
    await bucket.upload(file, {
      destination: storagePath,
      resumable: fs.statSync(file).size > 5 * 1024 * 1024,
      metadata: {
        contentType: "video/mp4",
        cacheControl: "public, max-age=31536000",
        metadata: { firebaseStorageDownloadTokens: downloadToken },
      },
    });
  } catch (err) {
    fail(`上传失败: ${err.message}`, 2);
  }

  if (args.public) {
    try {
      await bucket.file(storagePath).makePublic();
    } catch (err) {
      console.error(
        `⚠ 无法设为公开可读（桶可能启用了 uniform bucket-level access）: ${err.message}`,
      );
    }
  }

  const encoded = encodeURIComponent(storagePath);
  const publicUrl = `https://firebasestorage.googleapis.com/v0/b/${bucketName}/o/${encoded}?alt=media&token=${downloadToken}`;
  report.publicUrl = publicUrl;
  console.log(`→ public URL: ${publicUrl}`);

  // ---- 3. firestore note --------------------------------------------
  const db = admin.firestore();
  const bodyWithUrl = `${note.body}\n\n${publicUrl}`;
  const doc = {
    body: bodyWithUrl,
    color: note.color,
    hidden: note.hidden,
    id: noteId,
    tags: note.tags,
    title: note.title,
    updatedAt: admin.firestore.FieldValue.serverTimestamp(),
  };
  try {
    await db.collection("users").doc(userId).collection("notes").doc(noteId).set(doc);
  } catch (err) {
    fail(`Firestore 写入失败: ${err.message}`, 2);
  }
  console.log(`→ note      : ${report.notePath}`);

  // ---- 4. FCM --------------------------------------------------------
  if (!args.noNotify) {
    const topic = args.notifyTopic || process.env.AISKILLS_FCM_TOPIC || "";
    const tokens = args.notifyTokens;
    const message = {
      notification: { title: note.title, body: "新的动画已上传" },
      data: { noteId, userId, url: publicUrl, type: "movie" },
    };
    try {
      if (tokens.length > 0) {
        const res = await admin.messaging().sendEachForMulticast({ ...message, tokens });
        console.log(`→ FCM       : ${res.successCount}/${tokens.length} 成功`);
        report.notified = res.successCount > 0;
      } else if (topic) {
        await admin.messaging().send({ ...message, topic });
        console.log(`→ FCM       : topic "${topic}" 已发送`);
        report.notified = true;
      } else {
        console.log("→ FCM       : 未指定 topic / token，跳过（用 --notify-topic 或 AISKILLS_FCM_TOPIC）");
      }
    } catch (err) {
      // A notification failure must not lose the upload result.
      console.error(`⚠ FCM 发送失败（上传已完成）: ${err.message}`);
    }
  }

  report.ok = true;
  if (args.json) {
    console.log(JSON.stringify(report));
  } else {
    console.log("");
    console.log("✔ 完成");
    console.log(`  noteId     : ${noteId}`);
    console.log(`  note path  : ${report.notePath}`);
    console.log(`  public url : ${publicUrl}`);
  }
  process.exit(0);
})().catch((err) => {
  fail(`未预期的错误: ${err && err.stack ? err.stack : err}`, 2);
});
