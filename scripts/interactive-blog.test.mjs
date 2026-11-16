import assert from 'node:assert/strict';
import { execFile } from 'node:child_process';
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';
import { promisify } from 'node:util';
import { validateArticle } from '../01_aiskillsrunner/skills/text-to-interactive-blog/scripts/article-format.mjs';

const root = new URL('../01_aiskillsrunner/skills/text-to-interactive-blog/', import.meta.url);
const sample = JSON.parse(await readFile(new URL('references/article.example.json', root), 'utf8'));
const render = fileURLToPath(new URL('scripts/render-article.mjs', root));
const run = promisify(execFile);

test('translated editions must keep the same article anchors', () => {
  validateArticle(sample);
  const article = structuredClone(sample);
  article.locales.ja.sections[0].blocks[1].id = 'translated-id';
  assert.throws(() => validateArticle(article), /ja must preserve section and block order, IDs/);
});

test('graph validation rejects missing destinations and cycles in expansion', () => {
  const missingAnchor = structuredClone(sample);
  missingAnchor.graph.nodes.find((node) => node.id === 'causality').anchor = 'missing-section';
  assert.throws(() => validateArticle(missingAnchor), /graph\.nodes\.causality needs a valid article anchor/);

  const cycle = structuredClone(sample);
  cycle.graph.nodes.find((node) => node.id === 'evidence').parent = 'causality';
  assert.throws(() => validateArticle(cycle), /has a parent cycle/);
});

test('the skill exports readable editions and preserves Mermaid source when the bundle is unavailable', async (t) => {
  const scratch = await mkdtemp(join(tmpdir(), 'interactive-blog-test-'));
  t.after(() => rm(scratch, { recursive: true, force: true }));
  const article = structuredClone(sample);
  article.locales.zh.title += ' <em>测试</em>';
  const input = join(scratch, 'input.json');
  const output = join(scratch, 'article');
  await writeFile(input, JSON.stringify(article));
  const { stderr } = await run(process.execPath, [render, input, output], {
    env: { ...process.env, INTERACTIVE_BLOG_MERMAID_BUNDLE: join(scratch, 'unavailable.js') },
  });
  assert.match(stderr, /MERMAID_SOURCE_ONLY/);
  const html = await readFile(join(output, 'index.html'), 'utf8');
  assert.match(html, /&lt;em&gt;测试&lt;\/em&gt;/);
  assert.match(html, /id="content-causal-gap"/);
  assert.match(html, /data-edition="ja"/);
  assert.deepEqual(JSON.parse(await readFile(join(output, 'article.json'), 'utf8')), article);
  for (const locale of ['zh', 'ja', 'en']) {
    const markdown = await readFile(join(output, `${locale}.md`), 'utf8');
    assert.match(markdown, /id="content-causal-gap"/);
    assert.match(markdown, /```mermaid\nflowchart LR/);
    assert.match(markdown, /https:\/\/openai\.com\/index\/gpt-6-for-everyone\//);
  }
});
