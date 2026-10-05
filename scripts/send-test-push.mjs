#!/usr/bin/env node
/**
 * 手动发一条测试推送 —— 排查「后台显示推送成功，但手机没反应」。
 *
 * 用法：
 *   node scripts/send-test-push.mjs
 *   node scripts/send-test-push.mjs <uid> "标题" "正文"
 *
 * 结果怎么读：
 *   - 「推送已受理」只代表 FCM 收下了（令牌有效），**不代表手机弹了通知**；
 *   - 脚本会对比 `users/{uid}/fcmTokens/{token}.lastPushAt`（App 收到后回执的字段）：
 *       有变化 → 消息确实到达了手机 → 手机没弹就是**通知权限/渠道**问题
 *                （Android 13+ 的 POST_NOTIFICATIONS，或用户关掉了通知）；
 *       一直没变 → 消息没到手机 → App 被「强行停止」、手机离线、或令牌已失效。
 */
import admin from "firebase-admin";
import { readFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
try {
  process.loadEnvFile(join(root, ".env"));
} catch {
  // .env 不存在时下面的报错会说明
}

const uid = (process.argv[2] || String(process.env.MONITOR_USER_IDS || "").split(",")[0] || "").trim();
const title = process.argv[3] || "测试推送";
const body = process.argv[4] || `VoiceToAction AI 测试通知（${new Date().toLocaleTimeString()}）`;

if (!uid) {
  console.error("用法: node scripts/send-test-push.mjs <uid> [标题] [正文]");
  process.exit(2);
}

const keyPath = process.env.FIREBASE_ADMIN_KEY_PATH;
if (!keyPath) {
  console.error("错误：.env 里没有 FIREBASE_ADMIN_KEY_PATH");
  process.exit(2);
}

const serviceAccount = JSON.parse(readFileSync(keyPath, "utf8"));
admin.initializeApp({ credential: admin.credential.cert(serviceAccount), projectId: serviceAccount.project_id });
const db = admin.firestore();
const messaging = admin.messaging();

function isoOrNull(value) {
  if (!value) return null;
  const date = typeof value.toDate === "function" ? value.toDate() : new Date(value);
  return Number.isNaN(date.getTime()) ? null : date.toISOString();
}

async function readTokensWithReceipts() {
  const snap = await db.collection("users").doc(uid).collection("fcmTokens").get();
  return snap.docs.map((doc) => ({
    id: doc.id,
    token: String(doc.get("token") || doc.id),
    platform: String(doc.get("platform") || ""),
    lastPushAt: isoOrNull(doc.get("lastPushAt")),
    lastPushTitle: doc.get("lastPushTitle") || null
  }));
}

const before = await readTokensWithReceipts();
console.log(`项目   : ${serviceAccount.project_id}`);
console.log(`用户   : ${uid}`);
console.log(`设备令牌: ${before.length} 个`);
for (const entry of before) {
  console.log(`  - ${entry.id.slice(0, 16)}… (${entry.platform || "?"}) 上次收到推送: ${entry.lastPushAt || "从未"}`);
}
if (!before.length) {
  console.log("\n没有登记任何设备令牌：请先在 App 里登录（App 会用 users/{uid}/fcmTokens 上报令牌）。");
  process.exit(0);
}

console.log(`\n发送: 「${title}」/「${body}」`);
const summary = { sent: 0, failed: 0 };
for (const entry of before) {
  try {
    const messageId = await messaging.send({
      token: entry.token,
      notification: { title, body },
      data: { type: "test_push", documentId: "test", sentAt: String(Date.now()) },
      android: { priority: "high", notification: { channelId: "luyin_job_result", sound: "default" } }
    });
    summary.sent += 1;
    console.log(`  已受理 ${entry.id.slice(0, 16)}… messageId=${messageId}`);
  } catch (error) {
    summary.failed += 1;
    console.log(`  失败   ${entry.id.slice(0, 16)}… ${error?.code || ""} ${error?.message || error}`);
  }
}

console.log(`\n等待 App 回执（最多 20 秒）…`);
let received = false;
for (let i = 0; i < 7 && !received; i += 1) {
  await new Promise((resolvePromise) => setTimeout(resolvePromise, 3000));
  const after = await readTokensWithReceipts();
  for (const entry of after) {
    const previous = before.find((item) => item.id === entry.id);
    if (entry.lastPushAt && entry.lastPushAt !== previous?.lastPushAt) {
      console.log(`  ✅ 手机已收到：${entry.id.slice(0, 16)}… lastPushAt=${entry.lastPushAt}「${entry.lastPushTitle || ""}」`);
      received = true;
    }
  }
}

console.log(`\n结论：受理 ${summary.sent}，失败 ${summary.failed}`);
if (received) {
  console.log("→ 消息已到达手机。如果没看到通知，是**通知权限/渠道**被关掉了：");
  console.log("   手机「设置 → 应用 → ideavox → 通知」打开总开关，并确认「处理结果」渠道未被关闭。");
} else {
  console.log("→ 手机没有回执，说明消息没到达 App：");
  console.log("   1) 手机是否离线？2) App 是否被「强行停止」（设置里强制停止后 FCM 不再接收）？");
  console.log("   3) App 是否已安装含 FCM 的新版本并登录过？");
}
process.exit(0);
