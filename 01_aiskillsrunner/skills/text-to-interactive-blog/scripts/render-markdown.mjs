import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { relations, uiFor, validateArticle } from './article-format.mjs';

/**
 * 把 article.json 渲染成「博客系统可直接发布」的三语 Markdown 与知识图谱数据。
 *
 * 输出（不带任何 HTML / JS 资源）：
 *   <output>/zh.md ja.md en.md   博客正文，只使用 MarkdownView 支持的语法
 *   <output>/graph.json          知识图谱（节点/边/语言标签），由后端写入 Firestore
 *   <output>/article.json        结构化源（便于续跑与人工核对）
 *
 * 正文里的 `<a id="content-xxx"></a>` 是图谱末级节点的跳转锚点；两个博客系统的
 * MarkdownView 会把它渲染成不可见的 span。
 */

const anchor = (id) => `content-${id}`;

/** 博客的 MarkdownView 不支持 HTML 转义实体，所以这里只转义 Markdown 元字符。 */
const md = (value) =>
  String(value)
    .replace(/[\\`*_[\]#|~]/g, '\\$&')
    .replace(/^(\s*(?:[-+]|\d+\.)\s)/gm, '\\$1')
    .replace(/^(\s*)>/gm, '$1\\>');

const cell = (value) => md(value).replace(/\s*\n\s*/g, ' ');

function sourceIndex(article) {
  return new Map(
    (article.sources ?? []).map((source, index) => [
      source.id,
      { ...source, number: index + 1, url: new URL(source.url).href }
    ])
  );
}

function citations(block, sources) {
  return (block.sources ?? [])
    .map((id) => {
      const source = sources.get(id);
      return ` [${source.number}](${source.url})`;
    })
    .join('');
}

function blockMarkdown(block, sources) {
  const refs = citations(block, sources);
  const lines = [`<a id="${anchor(block.id)}"></a>`];

  switch (block.type) {
    case 'paragraph':
      lines.push(md(block.text) + refs);
      break;
    case 'list':
      lines.push(
        block.items
          .map((item, index) => `${block.ordered ? `${index + 1}.` : '-'} ${md(item)}`)
          .join('\n') + refs
      );
      break;
    case 'comparison': {
      const row = (cells) => `| ${cells.map(cell).join(' | ')} |`;
      lines.push(
        `**${md(block.title)}**${refs}`,
        [
          row(block.columns),
          row(block.columns.map(() => '---')),
          ...block.rows.map(row)
        ].join('\n')
      );
      break;
    }
    case 'details':
      // 博客的 MarkdownView 没有 <details>，用引用块表达「旁支问答/案例」。
      lines.push(
        [`**${md(block.label)}**${refs}`, ...md(block.text).split('\n')]
          .map((line) => `> ${line}`)
          .join('\n')
      );
      break;
    case 'mermaid': {
      const ticks = block.code.match(/`+/g) ?? [];
      const fence = '`'.repeat(Math.max(3, ...ticks.map((run) => run.length + 1)));
      lines.push(
        `**${md(block.caption)}**${refs}`,
        md(block.description),
        `${fence}mermaid\n${block.code.trim()}\n${fence}`
      );
      break;
    }
  }

  return lines.join('\n\n');
}

function editionMarkdown(locale, edition, sources) {
  const ui = uiFor(locale, edition);
  // 标题与摘要在博客 hero / 列表里已经展示，正文里不再重复。
  const lines = [`# ${md(edition.title)}`];

  for (const section of edition.sections) {
    lines.push(`<a id="${anchor(section.id)}"></a>\n\n## ${md(section.title)}`);
    for (const block of section.blocks) {
      lines.push(blockMarkdown(block, sources));
    }
  }

  if (sources.size) {
    lines.push(
      `## ${md(ui.sources)}`,
      [...sources.values()]
        .map((source) => `${source.number}. [${md(source.title)}](${source.url})`)
        .join('\n')
    );
  }

  return `${lines.join('\n\n')}\n`;
}

/**
 * 图谱交给博客渲染时需要的最小数据：去掉构建期的 ui/relations 文案，
 * 只保留节点、边与三语标签。字段与 article-format.md 的约定一致。
 */
function graphPayload(article) {
  const nodes = article.graph.nodes.map((node) => ({
    id: node.id,
    labels: node.labels,
    weight: node.weight,
    relation: node.relation,
    ...(node.parent === undefined ? {} : { parent: node.parent }),
    ...(node.anchor === undefined ? {} : { anchor: node.anchor })
  }));
  const edges = (article.graph.edges ?? []).map((edge) => ({
    source: edge.source,
    target: edge.target,
    relation: edge.relation
  }));
  return { version: 1, defaultLocale: article.defaultLocale, relations, nodes, edges };
}

/**
 * 校验并渲染。返回写出的文件列表，方便 CLI / 测试断言。
 */
export async function renderArticle(input, directory) {
  const article = JSON.parse(await readFile(input, 'utf8'));
  validateArticle(article);

  const sources = sourceIndex(article);
  const editions = Object.entries(article.locales);
  const graph = graphPayload(article);

  await mkdir(directory, { recursive: true });

  const written = [];
  for (const [locale, edition] of editions) {
    const file = join(directory, `${locale}.md`);
    await writeFile(file, editionMarkdown(locale, edition, sources));
    written.push(file);
  }

  const graphFile = join(directory, 'graph.json');
  await writeFile(graphFile, `${JSON.stringify(graph, null, 2)}\n`);
  written.push(graphFile);

  const articleFile = join(directory, 'article.json');
  if (resolve(input) !== resolve(articleFile)) {
    await writeFile(articleFile, `${JSON.stringify(article, null, 2)}\n`);
    written.push(articleFile);
  }

  return { graph, written, locales: editions.map(([locale]) => locale) };
}

const [input, output] = process.argv.slice(2);
const invokedDirectly = process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url);
if (!invokedDirectly) {
  // 被测试或其它模块 import 时不执行 CLI。
} else if (!input || !output) {
  console.error('Usage: node render-markdown.mjs <article.json> <output-directory>');
  process.exitCode = 1;
} else {
  try {
    const result = await renderArticle(input, resolve(output));
    console.log(
      `Rendered ${result.locales.join(', ')} → ${resolve(output)}` +
        `（${result.graph.nodes.length} 个图谱节点，${result.graph.edges.length} 条边）`
    );
  } catch (error) {
    console.error(error.message);
    process.exitCode = 1;
  }
}
