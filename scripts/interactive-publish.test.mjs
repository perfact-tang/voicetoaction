import assert from 'node:assert/strict';
import { chmod, mkdtemp, mkdir, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import {
  INTERACTIVE_PUBLIC_SITE_SHOW,
  INTERACTIVE_REQUIRED_FILES,
  findInteractiveOutputs,
  pickInteractiveOutput,
  publishInteractiveArticle
} from '../monitor-interactive-blog.js';

async function makeOutput(root, docId, { skip = [] } = {}) {
  const dir = join(root, docId);
  await mkdir(dir, { recursive: true });
  for (const name of INTERACTIVE_REQUIRED_FILES) {
    if (skip.includes(name)) continue;
    await writeFile(join(dir, name), name.endsWith('.json') ? '{}' : `# ${docId}\n`);
  }
  return dir;
}

const info = (docId) => ({
  docid: docId,
  ispublic: true,
  ispublicsiteshow: false,
  createdat: Number(docId),
  translations: {
    ja: { blogtitle: 'ja', blogseo: 'a,b', blogsummary: 's' },
    zh: { blogtitle: 'zh', blogseo: 'a,b', blogsummary: 's' },
    en: { blogtitle: 'en', blogseo: 'a,b', blogsummary: 's' }
  }
});

const graph = {
  version: 1,
  defaultLocale: 'zh',
  nodes: [
    { id: 'a', labels: { zh: '甲', ja: '甲', en: 'A' }, weight: 5, relation: 'theme' },
    { id: 'b', labels: { zh: '乙', ja: '乙', en: 'B' }, weight: 3, relation: 'supports', parent: 'a', anchor: 'x' }
  ],
  edges: []
};

test('findInteractiveOutputs only accepts complete numeric directories inside the run window', async (t) => {
  const root = await mkdtemp(join(tmpdir(), 'interactive-publish-'));
  t.after(() => rm(root, { recursive: true, force: true }));

  const startedAtMs = Date.now();
  const good = await makeOutput(root, '1794823698683');
  await makeOutput(root, '1794823698684', { skip: ['graph.json'] });
  await mkdir(join(root, 'notes'), { recursive: true }); // 非数字目录：忽略

  const outputs = await findInteractiveOutputs(root, startedAtMs);
  assert.equal(outputs.length, 2);
  const complete = outputs.find((output) => output.docId === '1794823698683');
  assert.equal(complete.dir, good);
  assert.equal(complete.complete, true);
  assert.deepEqual(complete.missing, []);

  const incomplete = outputs.find((output) => output.docId === '1794823698684');
  assert.equal(incomplete.complete, false);
  assert.deepEqual(incomplete.missing, ['graph.json']);

  const picked = await pickInteractiveOutput(root, startedAtMs);
  assert.equal(picked.docId, '1794823698683');
  assert.deepEqual(await findInteractiveOutputs(root, Date.now() + 10 * 60_000), []);
});

test('publish refuses mismatched docid / missing metadata before touching Firebase', async (t) => {
  const root = await mkdtemp(join(tmpdir(), 'interactive-publish-'));
  t.after(() => rm(root, { recursive: true, force: true }));
  const docId = '1794823698683';
  const dir = await makeOutput(root, docId);
  const db = { collection: () => { throw new Error('should not reach Firestore'); } };

  await writeFile(join(dir, '文章信息.json'), JSON.stringify(info('1794823999999')));
  await assert.rejects(
    publishInteractiveArticle({ dir, docId, userId: 'uid', db }),
    /docid=1794823999999 与目录名 1794823698683 不一致/
  );

  const bad = info(docId);
  delete bad.translations.ja.blogseo;
  await writeFile(join(dir, '文章信息.json'), JSON.stringify(bad));
  await assert.rejects(
    publishInteractiveArticle({ dir, docId, userId: 'uid', db }),
    /ja\.blogseo 为空/
  );

  await writeFile(join(dir, '文章信息.json'), JSON.stringify(info(docId)));
  await writeFile(join(dir, 'graph.json'), JSON.stringify({ version: 1, nodes: [] }));
  await assert.rejects(
    publishInteractiveArticle({ dir, docId, userId: 'uid', db }),
    /graph\.json 没有 nodes/
  );

  await writeFile(join(dir, 'graph.json'), JSON.stringify(graph));
  await assert.rejects(
    publishInteractiveArticle({ dir, docId, userId: '', db }),
    /缺少作者 uid/
  );
});

/** 假的 vibecodingjapan-upblog：记录收到的 文章信息.json 与参数，不做任何网络写入。 */
const FAKE_UPBLOG = `#!/usr/bin/env node
import { readFileSync, writeFileSync } from 'node:fs';
const argv = process.argv.slice(2);
const flag = (name) => { const index = argv.indexOf(name); return index === -1 ? '' : argv[index + 1]; };
const infoPath = flag('--info');
const info = JSON.parse(readFileSync(infoPath, 'utf8'));
writeFileSync(process.env.UPBLOG_CAPTURE, JSON.stringify({ info, infoPath, args: argv }));
console.log('✔ mkfile/' + flag('--docid') + '-ja-test.md をアップロードしました');
`;


test('publishing forces ispublicsiteshow=false even when 文章信息.json asks for true', async (t) => {
  assert.equal(INTERACTIVE_PUBLIC_SITE_SHOW, false);

  const root = await mkdtemp(join(tmpdir(), 'interactive-publish-'));
  t.after(() => rm(root, { recursive: true, force: true }));
  const docId = '1794823698683';
  const dir = await makeOutput(root, docId);
  // skill 误把公开站展示打开的情况
  await writeFile(join(dir, '文章信息.json'), JSON.stringify({ ...info(docId), ispublicsiteshow: true }));
  await writeFile(join(dir, 'graph.json'), JSON.stringify(graph));

  const bin = join(root, 'fake-upblog.mjs');
  await writeFile(bin, FAKE_UPBLOG);
  await chmod(bin, 0o755);
  const capture = join(root, 'capture.json');
  const previousCapture = process.env.UPBLOG_CAPTURE;
  process.env.UPBLOG_CAPTURE = capture;
  t.after(() => {
    if (previousCapture === undefined) delete process.env.UPBLOG_CAPTURE;
    else process.env.UPBLOG_CAPTURE = previousCapture;
  });

  const writes = [];
  const stored = {};
  const db = {
    collection: () => ({
      doc: () => ({
        set: async (payload, options) => {
          writes.push({ payload, options });
          Object.assign(stored, payload);
        },
        get: async () => ({ exists: true, data: () => stored, get: (key) => stored[key] })
      })
    })
  };

  const published = await publishInteractiveArticle({ dir, docId, userId: 'uid', db, upblogBin: bin });
  assert.equal(published.docId, docId);
  assert.equal(published.blogPath, `/blog/${docId}`);
  assert.equal(published.graphNodes, 2);
  assert.equal(published.graphEdges, 0);

  const captured = JSON.parse(await readFile(capture, 'utf8'));
  assert.equal(captured.info.ispublicsiteshow, false, '上传 CLI 收到的必须是 false');
  assert.equal(captured.info.ispublic, true, 'ispublic 不受影响');
  assert.equal(captured.info.translations.ja.blogtitle, 'ja');
  assert.notEqual(captured.infoPath, join(dir, '文章信息.json'), '不能直接把 skill 的元信息文件交给 CLI');
  await assert.rejects(readFile(captured.infoPath, 'utf8'), /ENOENT/, '临时元信息文件用完要删掉');

  const visibilityWrite = writes.find((write) => write.payload.ispublicsiteshow === false);
  assert.ok(visibilityWrite, '发布后应再把 mkblog 文档的 ispublicsiteshow 写成 false');
  assert.equal(visibilityWrite.options.merge, true);
  assert.ok(visibilityWrite.payload.graph, '同一次写入应带上知识图谱');
  assert.equal(stored.ispublicsiteshow, false);

  // 本地产物保持原样，只有上传用的临时副本被改。
  const localInfo = JSON.parse(await readFile(join(dir, '文章信息.json'), 'utf8'));
  assert.equal(localInfo.ispublicsiteshow, true);
});
