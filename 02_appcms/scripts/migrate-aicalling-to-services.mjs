#!/usr/bin/env node
/**
 * 迁移脚本：/users/{uid}/aicalling  ->  /allalservice
 *
 * 背景
 *   CMS 只读取 /allalservice，而现有数据在 /users/{uid}/aicalling，字段为
 *   { aicallingid, title, info, sort }，因此 CMS 一览里看不到。
 *   aicallingid 是 Google Drive 的文件夹 ID（monitor.js 用它作为生成 Google Doc
 *   的 folderId），所以可映射为 googleDriveUrl:
 *       https://drive.google.com/drive/folders/{aicallingid}
 *
 * 行为
 *   - 默认 dry-run：只打印将要写入的内容，不修改任何数据。
 *   - --apply 才真正写入。
 *   - allalservice 文档 ID 直接使用 aicalling 的文档 ID，可重复执行（幂等）。
 *   - 已存在的同名文档默认跳过，加 --overwrite 才覆盖。
 *
 * 用法
 *   node 02_appcms/scripts/migrate-aicalling-to-services.mjs                       # dry-run（全部用户）
 *   node 02_appcms/scripts/migrate-aicalling-to-services.mjs --uid <UID>
 *   node 02_appcms/scripts/migrate-aicalling-to-services.mjs --lang ja             # 标题/说明写入哪个语言（默认 ja）
 *   node 02_appcms/scripts/migrate-aicalling-to-services.mjs --uid <UID> --apply
 */
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import admin from 'firebase-admin';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(HERE, '..', '..');
const LANGUAGES = ['zh', 'ja', 'en', 'ko'];
const SERVICE_FIELDS = [
  'userUid',
  'nameZh', 'nameJa', 'nameEn', 'nameKo',
  'descriptionZh', 'descriptionJa', 'descriptionEn', 'descriptionKo',
  'type', 'googleDriveUrl', 'applicationName',
  'info', 'sort',
  'skillZipUrl', 'skillStoragePath', 'skillFileName', 'skillFileSize',
  'status', 'isDeleted',
  'createdAt', 'updatedAt', 'deletedAt'
];

function parseArgs(argv) {
  const opts = { uid: null, lang: 'ja', apply: false, overwrite: false, help: false };
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === '--apply') opts.apply = true;
    else if (arg === '--overwrite') opts.overwrite = true;
    else if (arg === '--help' || arg === '-h') opts.help = true;
    else if (arg === '--uid') opts.uid = argv[++i];
    else if (arg.startsWith('--uid=')) opts.uid = arg.slice(6);
    else if (arg === '--lang') opts.lang = argv[++i];
    else if (arg.startsWith('--lang=')) opts.lang = arg.slice(7);
    else throw new Error(`未知参数: ${arg}`);
  }
  return opts;
}

function adminKeyPath() {
  if (process.env.FIREBASE_ADMIN_KEY_PATH) return process.env.FIREBASE_ADMIN_KEY_PATH;
  try {
    const env = readFileSync(path.join(ROOT, '.env'), 'utf8');
    const match = env.match(/^FIREBASE_ADMIN_KEY_PATH=(.+)$/m);
    if (match) return match[1].trim();
  } catch { /* fall through */ }
  return path.join(ROOT, 'keys', 'vibecodingjapan-firebase-adminsdk-fbsvc-f376a3b494.json');
}

/** 与 firestore.rules 的 validServiceData 保持一致，避免写入 CMS 之后无法编辑的文档。 */
function validatePayload(payload) {
  const problems = [];
  const keys = Object.keys(payload);
  for (const key of SERVICE_FIELDS) if (!keys.includes(key)) problems.push(`缺少字段 ${key}`);
  for (const key of keys) if (!SERVICE_FIELDS.includes(key)) problems.push(`多余字段 ${key}`);
  if (!LANGUAGES.some((lang) => {
    const suffix = lang.charAt(0).toUpperCase() + lang.slice(1);
    return payload['name' + suffix].length > 0 && payload['description' + suffix].length > 0;
  })) problems.push('至少需要一种完整的语言（名称+说明）');
  for (const lang of LANGUAGES) {
    const suffix = lang.charAt(0).toUpperCase() + lang.slice(1);
    const name = payload['name' + suffix];
    const description = payload['description' + suffix];
    if (name.length > 120) problems.push(`name${suffix} 超过 120 字符`);
    if (description.length > 2000) problems.push(`description${suffix} 超过 2000 字符`);
    if ((name.length === 0) !== (description.length === 0)) problems.push(`${lang} 名称与说明必须成对`);
  }
  if (payload.type !== 'google_workspace_studio' && payload.type !== 'deepseek_harness') problems.push('type 非法');
  if (!/^https:\/\/drive\.google\.com\/.+/.test(payload.googleDriveUrl)) problems.push('googleDriveUrl 必须是 drive.google.com 的 HTTPS 地址');
  if (payload.googleDriveUrl.length > 2048) problems.push('googleDriveUrl 过长');
  if (payload.applicationName !== '') problems.push('google_workspace_studio 的 applicationName 必须为空');
  if (payload.info.length > 2000) problems.push('info 超过 2000 字符');
  if (!Number.isInteger(payload.sort) || payload.sort < 0 || payload.sort > 1000000) problems.push('sort 必须是 0..1000000 的整数');
  if (payload.skillZipUrl || payload.skillStoragePath || payload.skillFileName || payload.skillFileSize) problems.push('google 类型不应带 skill 字段');
  if (payload.status !== 'active' && payload.status !== 'inactive') problems.push('status 非法');
  if (payload.isDeleted !== false || payload.deletedAt !== null) problems.push('新建文档必须 isDeleted=false / deletedAt=null');
  return problems;
}

function buildPayload(uid, aicalling, lang) {
  const suffix = lang.charAt(0).toUpperCase() + lang.slice(1);
  const name = String(aicalling.title || '').trim();
  const info = String(aicalling.info || '').trim();
  const folderId = String(aicalling.aicallingid || '').trim();
  const rawSort = Number(aicalling.sort);
  const payload = {
    userUid: uid,
    nameZh: '', nameJa: '', nameEn: '', nameKo: '',
    descriptionZh: '', descriptionJa: '', descriptionEn: '', descriptionKo: '',
    type: 'google_workspace_studio',
    googleDriveUrl: `https://drive.google.com/drive/folders/${folderId}`,
    applicationName: '',
    info,
    sort: Number.isFinite(rawSort) ? Math.trunc(rawSort) : 0,
    skillZipUrl: '', skillStoragePath: '', skillFileName: '', skillFileSize: 0,
    status: 'active',
    isDeleted: false,
    createdAt: admin.firestore.FieldValue.serverTimestamp(),
    updatedAt: admin.firestore.FieldValue.serverTimestamp(),
    deletedAt: null
  };
  payload['name' + suffix] = name;
  // 规则要求「名称+说明」成对且至少一种语言完整；info 为空时用 title 兜底说明。
  payload['description' + suffix] = info || name;
  return payload;
}

async function main() {
  const opts = parseArgs(process.argv.slice(2));
  if (opts.help) {
    console.log('用法: node migrate-aicalling-to-services.mjs [--uid <UID>] [--lang ja|zh|en|ko] [--apply] [--overwrite]');
    return;
  }
  if (!LANGUAGES.includes(opts.lang)) throw new Error(`--lang 必须是 ${LANGUAGES.join('/')}`);

  const serviceAccount = JSON.parse(readFileSync(adminKeyPath(), 'utf8'));
  admin.initializeApp({ credential: admin.credential.cert(serviceAccount), projectId: serviceAccount.project_id });
  const db = admin.firestore();

  const snap = await db.collectionGroup('aicalling').get();
  const items = [];
  snap.forEach((doc) => {
    const uid = doc.ref.parent.parent ? doc.ref.parent.parent.id : null;
    if (!uid) return;
    if (opts.uid && uid !== opts.uid) return;
    items.push({ uid, docId: doc.id, data: doc.data() });
  });

  console.log(`项目       : ${serviceAccount.project_id}`);
  console.log(`模式       : ${opts.apply ? 'APPLY（会写入）' : 'DRY-RUN（不写入）'}`);
  console.log(`语言       : ${opts.lang}`);
  console.log(`用户       : ${opts.uid || '(全部)'}`);
  console.log(`aicalling  : ${items.length} 件\n`);

  let created = 0;
  let skipped = 0;
  let invalid = 0;

  for (const item of items.sort((a, b) => (a.data.sort ?? 0) - (b.data.sort ?? 0))) {
    const targetId = item.docId;
    const payload = buildPayload(item.uid, item.data, opts.lang);
    const problems = validatePayload(payload);
    const label = `[sort=${payload.sort}] ${payload['name' + opts.lang.charAt(0).toUpperCase() + opts.lang.slice(1)] || '(无标题)'}`;

    if (problems.length) {
      invalid += 1;
      console.log(`✗ 跳过 ${label}\n    docId=${targetId}\n    校验失败: ${problems.join('; ')}`);
      continue;
    }

    const ref = db.collection('allalservice').doc(targetId);
    const existing = await ref.get();

    if (existing.exists && !opts.overwrite) {
      skipped += 1;
      console.log(`- 已存在，跳过 ${label} (allalservice/${targetId})`);
      continue;
    }

    if (!opts.apply) {
      console.log(`${existing.exists ? '↻ 将覆盖' : '＋ 将创建'} ${label}`);
      console.log(`    allalservice/${targetId}`);
      console.log(`    googleDriveUrl: ${payload.googleDriveUrl}`);
      console.log(`    info: ${payload.info.slice(0, 80)}`);
      continue;
    }

    await ref.set(payload, { merge: false });
    created += 1;
    console.log(`${existing.exists ? '↻ 已覆盖' : '＋ 已创建'} ${label} (allalservice/${targetId})`);
  }

  if (!opts.apply) {
    console.log('\n以上为 DRY-RUN；加 --apply 才会真正写入。');
  } else {
    console.log(`\n完成：创建/覆盖 ${created} 件，跳过 ${skipped} 件，校验失败 ${invalid} 件。`);
  }
  process.exit(0);
}

main().catch((error) => {
  console.error(`错误: ${error.message}`);
  process.exit(1);
});
