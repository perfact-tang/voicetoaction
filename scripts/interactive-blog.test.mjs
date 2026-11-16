import assert from 'node:assert/strict';
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';
import { renderArticle } from '../01_aiskillsrunner/skills/text-to-interactive-blog/scripts/render-markdown.mjs';
import { validateArticle } from '../01_aiskillsrunner/skills/text-to-interactive-blog/scripts/article-format.mjs';

const skillRoot = new URL('../01_aiskillsrunner/skills/text-to-interactive-blog/', import.meta.url);
const sample = JSON.parse(await readFile(new URL('references/article.example.json', skillRoot), 'utf8'));
const samplePath = fileURLToPath(new URL('references/article.example.json', skillRoot));

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

test('the skill renders blog markdown and graph data without any HTML runtime', async (t) => {
  const scratch = await mkdtemp(join(tmpdir(), 'interactive-blog-test-'));
  t.after(() => rm(scratch, { recursive: true, force: true }));

  const article = structuredClone(sample);
  article.locales.zh.title += ' <em>测试</em>';
  const input = join(scratch, 'input.json');
  const output = join(scratch, 'article');
  await writeFile(input, JSON.stringify(article));

  const result = await renderArticle(input, output);
  assert.deepEqual(result.locales, ['zh', 'ja', 'en']);
  assert.equal(result.graph.nodes.length, sample.graph.nodes.length);

  for (const locale of ['zh', 'ja', 'en']) {
    const markdown = await readFile(join(output, `${locale}.md`), 'utf8');
    assert.match(markdown, /^# /m);
    assert.match(markdown, /<a id="content-causal-gap"><\/a>/);
    assert.match(markdown, /```mermaid\nflowchart LR/);
    assert.match(markdown, /\[3\]\(https:\/\/openai\.com\/index\/gpt-6-for-everyone\/\)/);
    assert.doesNotMatch(markdown, /<script|<style|<iframe/i);
    assert.doesNotMatch(markdown, /&lt;|&gt;|&amp;/);
  }

  const html = await readFile(join(output, 'zh.md'), 'utf8');
  assert.match(html, /# 把跳跃的原稿写成可探索的文章 <em>测试<\/em>/);

  const graph = JSON.parse(await readFile(join(output, 'graph.json'), 'utf8'));
  assert.equal(graph.version, 1);
  assert.equal(graph.defaultLocale, 'zh');
  assert.ok(graph.nodes.every((node) => node.labels.zh && node.labels.ja && node.labels.en));
  assert.ok(graph.edges.length > 0);
  assert.ok(graph.nodes.some((node) => node.anchor));

  assert.deepEqual(JSON.parse(await readFile(join(output, 'article.json'), 'utf8')), article);
  assert.deepEqual(JSON.parse(await readFile(samplePath, 'utf8')), sample);
});
