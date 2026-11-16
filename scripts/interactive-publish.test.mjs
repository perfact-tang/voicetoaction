import assert from 'node:assert/strict';
import { mkdtemp, mkdir, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import {
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
  ispublicsiteshow: true,
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
