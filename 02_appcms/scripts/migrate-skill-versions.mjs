#!/usr/bin/env node
/**
 * 迁移脚本：把「没有版本记录」的既有 Skill 包补记为 V1。
 *
 * 背景
 *   Skill 版本管控上线后，每个 zip 都会在
 *     /allalservice/{serviceId}/skillVersions/{版本号}
 *   下生成一份不可变的版本文档，父文档用 skillActiveVersion 指向当前生效版本。
 *   在此之前上传的 Skill 包只有父文档上的
 *     skillZipUrl / skillStoragePath / skillFileName / skillFileSize
 *   四个镜像字段，没有版本文档。
 *
 * 行为
 *   - 默认 dry-run：只打印将要写入的内容，不修改任何数据。
 *   - --apply 才真正写入。
 *   - 只处理 type=deepseek_harness 且 skillZipUrl 非空的文档。
 *   - 已有版本文档的文档默认跳过（--overwrite 才补写 V1，不会删除已有版本）。
 *   - 版本号沿用父文档的 skillActiveVersion（缺失或非法时为 1），保持镜像字段不变。
 *   - 幂等：可重复执行。
 *
 * 用法
 *   node 02_appcms/scripts/migrate-skill-versions.mjs                 # dry-run
 *   node 02_appcms/scripts/migrate-skill-versions.mjs --uid <UID>
 *   node 02_appcms/scripts/migrate-skill-versions.mjs --apply
 */
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import admin from 'firebase-admin';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(HERE, '..', '..');
const VERSION_LIMIT = 999999;

function parseArgs(argv) {
  const opts = { uid: null, apply: false, overwrite: false, help: false };
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === '--apply') opts.apply = true;
    else if (arg === '--overwrite') opts.overwrite = true;
    else if (arg === '--help' || arg === '-h') opts.help = true;
    else if (arg === '--uid') opts.uid = argv[++i];
    else if (arg.startsWith('--uid=')) opts.uid = arg.slice(6);
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

/** 与 firestore.rules 的 validSkillVersionData 保持一致。 */
function validateVersion(payload) {
  const problems = [];
  if (!Number.isInteger(payload.version) || payload.version < 1 || payload.version > VERSION_LIMIT) problems.push('version 非法');
  if (typeof payload.zipUrl !== 'string' || !payload.zipUrl.startsWith('https://firebasestorage.googleapis.com/')) problems.push('zipUrl 必须是 Firebase Storage 下载地址');
  if (typeof payload.storagePath !== 'string' || !payload.storagePath.startsWith(`skills/${payload.userUid}/`)) problems.push('storagePath 必须位于本人 skills/ 目录下');
  if (typeof payload.storagePath === 'string' && !payload.storagePath.endsWith('.zip')) problems.push('storagePath 必须以 .zip 结尾');
  if (typeof payload.fileName !== 'string' || !payload.fileName.endsWith('.zip')) problems.push('fileName 必须以 .zip 结尾');
  if (!Number.isInteger(payload.fileSize) || payload.fileSize <= 0 || payload.fileSize > 26214400) problems.push('fileSize 必须是 1..25MiB 的整数');
  if (typeof payload.userUid !== 'string' || payload.userUid.length === 0) problems.push('userUid 非法');
  return problems;
}

function seedVersionNumber(data) {
  const raw = Number(data.skillActiveVersion);
  if (Number.isInteger(raw) && raw >= 1 && raw <= VERSION_LIMIT) return raw;
  return 1;
}

async function main() {
  const opts = parseArgs(process.argv.slice(2));
  if (opts.help) {
    console.log('用法: node migrate-skill-versions.mjs [--uid <UID>] [--apply] [--overwrite]');
    return;
  }

  const serviceAccount = JSON.parse(readFileSync(adminKeyPath(), 'utf8'));
  admin.initializeApp({ credential: admin.credential.cert(serviceAccount), projectId: serviceAccount.project_id });
  const db = admin.firestore();

  const snap = await db.collection('allalservice').get();
  const items = snap.docs.filter((doc) => {
    const data = doc.data();
    if (opts.uid && data.userUid !== opts.uid) return false;
    return data.type === 'deepseek_harness' && typeof data.skillZipUrl === 'string' && data.skillZipUrl.length > 0;
  });

  console.log(`项目       : ${serviceAccount.project_id}`);
  console.log(`模式       : ${opts.apply ? 'APPLY（会写入）' : 'DRY-RUN（不写入）'}`);
  console.log(`用户       : ${opts.uid || '(全部)'}`);
  console.log(`待处理 Skill 包: ${items.length} 件\n`);

  let created = 0;
  let skipped = 0;
  let invalid = 0;
  let orphaned = 0;

  for (const doc of items) {
    const data = doc.data();
    const label = `${data.applicationName || '(无名称)'} @ allalservice/${doc.id}`;
    const versions = await doc.ref.collection('skillVersions').get();

    if (!versions.empty && !opts.overwrite) {
      const hasPointer = Number.isInteger(data.skillActiveVersion) && data.skillActiveVersion > 0;
      if (!hasPointer) {
        orphaned += 1;
        console.log(`! 已有 ${versions.size} 个版本但缺少 skillActiveVersion，请在编辑页重新设定：${label}`);
      } else {
        skipped += 1;
      }
      continue;
    }

    const version = seedVersionNumber(data);
    const payload = {
      version,
      zipUrl: data.skillZipUrl,
      storagePath: data.skillStoragePath || '',
      fileName: data.skillFileName || '',
      fileSize: Number(data.skillFileSize) || 0,
      // 规则对客户端写入要求 createdAt == request.time；admin SDK 绕过规则，
      // 这里优先保留原始的更新时间，让版本列表显示真实的上传时间。
      createdAt: data.updatedAt instanceof admin.firestore.Timestamp
        ? data.updatedAt
        : admin.firestore.FieldValue.serverTimestamp(),
      userUid: data.userUid
    };
    const problems = validateVersion(payload);
    if (problems.length) {
      invalid += 1;
      console.log(`✗ 跳过 ${label}\n    校验失败: ${problems.join('; ')}`);
      continue;
    }

    if (!opts.apply) {
      console.log(`${versions.empty ? '＋ 将补记' : '↻ 将覆盖'} V${version} ${label}`);
      console.log(`    ${payload.storagePath} · ${payload.fileName} · ${payload.fileSize} bytes`);
      continue;
    }

    const batch = db.batch();
    batch.set(doc.ref.collection('skillVersions').doc(String(version)), payload, { merge: false });
    batch.update(doc.ref, { skillActiveVersion: version, updatedAt: admin.firestore.FieldValue.serverTimestamp() });
    await batch.commit();
    created += 1;
    console.log(`${versions.empty ? '＋ 已补记' : '↻ 已覆盖'} V${version} ${label}`);
  }

  if (!opts.apply) {
    console.log('\n以上为 DRY-RUN；加 --apply 才会真正写入。');
  } else {
    console.log(`\n完成：写入 ${created} 件，跳过 ${skipped} 件，校验失败 ${invalid} 件，需人工确认 ${orphaned} 件。`);
  }
  process.exit(0);
}

main().catch((error) => {
  console.error(`错误: ${error.message}`);
  process.exit(1);
});
