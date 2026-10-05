#!/usr/bin/env node
/**
 * 只读检查脚本：列出 /users/{uid}/aicalling 与 /allalservice 的现有数据。
 * 不写入任何数据，用于确认 aicalling 的字段结构与数量，决定如何让 CMS 展示。
 *
 * 用法:
 *   node 02_appcms/scripts/inspect-aicalling.mjs            # 全部用户
 *   node 02_appcms/scripts/inspect-aicalling.mjs <uid>      # 只看某个用户
 */
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import admin from 'firebase-admin';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(HERE, '..', '..');

function adminKeyPath() {
  if (process.env.FIREBASE_ADMIN_KEY_PATH) return process.env.FIREBASE_ADMIN_KEY_PATH;
  try {
    const env = readFileSync(path.join(ROOT, '.env'), 'utf8');
    const match = env.match(/^FIREBASE_ADMIN_KEY_PATH=(.+)$/m);
    if (match) return match[1].trim();
  } catch { /* fall through */ }
  return path.join(ROOT, 'keys', 'vibecodingjapan-firebase-adminsdk-fbsvc-f376a3b494.json');
}

const serviceAccount = JSON.parse(readFileSync(adminKeyPath(), 'utf8'));
admin.initializeApp({ credential: admin.credential.cert(serviceAccount), projectId: serviceAccount.project_id });
const db = admin.firestore();

const targetUid = process.argv[2] || null;
const preview = (value) => {
  const text = typeof value === 'string' ? value : JSON.stringify(value);
  return text.length > 160 ? text.slice(0, 160) + '…' : text;
};

console.log(`项目: ${serviceAccount.project_id}`);
console.log(`过滤用户: ${targetUid || '(全部)'}\n`);

// ---- /users/{uid}/aicalling ----
const aicallingSnap = await db.collectionGroup('aicalling').get();
const byUser = new Map();
let aicallingTotal = 0;
aicallingSnap.forEach((doc) => {
  const uid = doc.ref.parent.parent ? doc.ref.parent.parent.id : '(unknown)';
  if (targetUid && uid !== targetUid) return;
  aicallingTotal += 1;
  if (!byUser.has(uid)) byUser.set(uid, []);
  byUser.get(uid).push({ id: doc.id, data: doc.data() });
});

console.log(`===== /users/{uid}/aicalling : ${aicallingTotal} 件 =====`);
const fieldNames = new Set();
for (const [uid, items] of byUser) {
  console.log(`\n-- ${uid} (${items.length} 件)`);
  for (const item of items.sort((a, b) => (a.data.sort ?? 0) - (b.data.sort ?? 0))) {
    Object.keys(item.data).forEach((key) => fieldNames.add(key));
    console.log(`   [sort=${item.data.sort ?? '-'}] docId=${item.id}`);
    console.log(`      ${preview(item.data)}`);
  }
}
console.log(`\naicalling 字段集合: ${[...fieldNames].sort().join(', ') || '(无)'}`);

// ---- /allalservice ----
const serviceSnap = await db.collection('allalservice').get();
const services = [];
serviceSnap.forEach((doc) => {
  const data = doc.data();
  if (targetUid && data.userUid !== targetUid) return;
  services.push({ id: doc.id, data });
});

console.log(`\n===== /allalservice : ${services.length} 件 =====`);
const counts = services.reduce((all, item) => {
  const type = item.data.type || '(no type)';
  all[type] = (all[type] || 0) + 1;
  return all;
}, {});
console.log('按 type 统计:', counts);
for (const item of services) {
  const d = item.data;
  console.log(`   docId=${item.id} type=${d.type} nameZh=${preview(d.nameZh)} drive=${preview(d.googleDriveUrl)} skillZipUrl=${preview(d.skillZipUrl)} deleted=${d.isDeleted}`);
}

process.exit(0);
