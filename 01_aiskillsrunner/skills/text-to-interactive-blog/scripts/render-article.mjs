import { createHash } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { relations, uiFor, validateArticle } from './article-format.mjs';

const asset = (name) => readFile(new URL(`../assets/${name}`, import.meta.url), 'utf8');
const html = (value) => value.replace(/[&<>"']/g, (character) => ({
  '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
})[character]);
const anchor = (id) => `content-${id}`;
const scriptText = (code) => code.replace(/<\/script/gi, '<\\/script');
const md = (value) => value.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
  .replace(/[\\*_`\[\]#|]/g, '\\$&').replace(/^(\s*(?:[-+]|\d+\.)\s)/gm, '\\$1');

const mermaidVersion = '12.3.0';
const mermaidSha256 = '336df803d87a38b70a01e736e666c8f7601d2ce42f5e47c8fafe5d721074b9a3';

async function mermaidScript() {
  const local = process.env.INTERACTIVE_BLOG_MERMAID_BUNDLE;
  const url = `https://cdn.jsdelivr.net/npm/mermaid@${mermaidVersion}/dist/mermaid.min.js`;
  try {
    let bundle;
    if (local) {
      bundle = await readFile(local);
    } else {
      const response = await fetch(url, { signal: AbortSignal.timeout(15000) });
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      bundle = Buffer.from(await response.arrayBuffer());
    }
    if (createHash('sha256').update(bundle).digest('hex') !== mermaidSha256) {
      throw new Error(`SHA-256 mismatch for Mermaid ${mermaidVersion}`);
    }
    const license = await asset('mermaid-license.txt');
    return `<script>/*!\n${license}\n*/\n${scriptText(bundle.toString('utf8'))}</script>`;
  } catch (error) {
    console.warn(`MERMAID_SOURCE_ONLY: ${error.message}`);
    return '';
  }
}

function sourceIndex(article) {
  return new Map((article.sources ?? []).map((source, index) => [source.id, {
    ...source, number: index + 1, url: new URL(source.url).href,
  }]));
}

function blockHtml(block, sources) {
  const citations = (block.sources ?? []).map((id) => {
    const source = sources.get(id);
    return ` <sup><a href="#source-${id}" aria-label="${html(source.title)}">[${source.number}]</a></sup>`;
  }).join('');
  const attrs = `id="${anchor(block.id)}" tabindex="-1" class="content-block"`;

  switch (block.type) {
    case 'paragraph':
      return `<p ${attrs}>${html(block.text)}${citations}</p>`;
    case 'list': {
      const tag = block.ordered ? 'ol' : 'ul';
      const entries = block.items.map((item) => `<li>${html(item)}</li>`).join('');
      return `<div ${attrs}><${tag}>${entries}</${tag}>${citations}</div>`;
    }
    case 'comparison': {
      const headings = block.columns.map((column) => `<th scope="col">${html(column)}</th>`).join('');
      const rows = block.rows.map(([label, ...cells]) => `<tr><th scope="row">${html(label)}</th>${cells.map((cell) => `<td>${html(cell)}</td>`).join('')}</tr>`).join('');
      return `<div ${attrs}><div class="table-scroll"><table><caption>${html(block.title)}${citations}</caption><thead><tr>${headings}</tr></thead><tbody>${rows}</tbody></table></div></div>`;
    }
    case 'details':
      return `<details ${attrs}><summary>${html(block.label)}</summary><p>${html(block.text)}${citations}</p></details>`;
    case 'mermaid':
      return `<figure ${attrs} data-mermaid>
        <figcaption>${html(block.caption)}${citations}</figcaption>
        <div class="diagram" aria-hidden="true" hidden></div>
        <p>${html(block.description)}</p>
        <details class="diagram-source"><summary>Mermaid</summary><pre><code>${html(block.code)}</code></pre></details>
      </figure>`;
  }
}

function editionHtml(locale, edition, sources) {
  const ui = uiFor(locale, edition);
  const toc = edition.sections.map((section) => `<li><a href="#${anchor(section.id)}">${html(section.title)}</a></li>`).join('');
  const sections = edition.sections.map((section) => `<section class="chapter" id="${anchor(section.id)}" tabindex="-1">
    <h2>${html(section.title)}</h2>
    ${section.blocks.map((block) => blockHtml(block, sources)).join('\n')}
  </section>`).join('\n');
  const citations = [...sources.entries()].map(([id, source]) => `<li id="source-${id}"><a href="${html(source.url)}" target="_blank" rel="noopener noreferrer">${html(source.title)}</a></li>`).join('');

  return `<div class="edition" data-locale="${locale}" lang="${locale}" dir="auto">
    <header class="hero shell">
      <h1>${html(edition.title)}</h1>
      <p>${html(edition.summary)}</p>
    </header>
    <section class="graph shell" id="knowledge-graph" tabindex="-1" aria-labelledby="graph-heading" hidden>
      <div class="graph-heading">
        <h2 id="graph-heading">${html(ui.graph)}</h2>
        <button class="collapse" type="button" data-collapse hidden>${html(ui.collapse)}</button>
      </div>
      <p class="graph-hint"><span class="pointer-hint">${html(ui.pointerHint)}</span><span class="touch-hint">${html(ui.touchHint)}</span></p>
      <div class="graph-viewport" role="group" aria-label="${html(ui.graph)}">
        <div class="graph-stage"><canvas aria-hidden="true"></canvas><div class="graph-nodes"></div></div>
      </div>
      <div class="graph-footer">
        <ul class="graph-legend"></ul>
        <details class="graph-relations" hidden><summary>${html(ui.relations)}</summary><ul></ul></details>
      </div>
    </section>
    <div class="reading shell" id="article-text" tabindex="-1">
      <nav class="toc" aria-label="${html(ui.contents)}"><p>${html(ui.contents)}</p><ol>${toc}</ol></nav>
      <article>
        ${sections}
        ${citations ? `<section class="references"><h2>${html(ui.sources)}</h2><ol>${citations}</ol></section>` : ''}
      </article>
    </div>
  </div>`;
}

function editionMarkdown(locale, edition, sources) {
  const lines = [`# ${md(edition.title)}`, md(edition.summary)];
  for (const section of edition.sections) {
    lines.push(`<a id="${anchor(section.id)}"></a>\n\n## ${md(section.title)}`);
    for (const block of section.blocks) {
      const refs = (block.sources ?? []).map((id) => {
        const source = sources.get(id);
        return ` [\`[${source.number}]\`](<${source.url}>)`;
      }).join('');
      lines.push(`<a id="${anchor(block.id)}"></a>`);
      switch (block.type) {
        case 'paragraph':
          lines.push(md(block.text) + refs);
          break;
        case 'list':
          lines.push(block.items.map((item, index) => `${block.ordered ? `${index + 1}.` : '-'} ${md(item)}`).join('\n') + refs);
          break;
        case 'comparison': {
          const row = (cells) => `| ${cells.map((cell) => md(cell).replace(/\s*\n\s*/g, ' ')).join(' | ')} |`;
          lines.push(`**${md(block.title)}**${refs}`, [
            row(block.columns), row(block.columns.map(() => '---')), ...block.rows.map(row),
          ].join('\n'));
          break;
        }
        case 'details':
          lines.push(`**${md(block.label)}**`, md(block.text) + refs);
          break;
        case 'mermaid': {
          const ticks = block.code.match(/`+/g) ?? [];
          const fence = '`'.repeat(Math.max(3, ...ticks.map((run) => run.length + 1)));
          lines.push(`**${md(block.caption)}**${refs}`, md(block.description), `${fence}mermaid\n${block.code.trim()}\n${fence}`);
          break;
        }
      }
    }
  }
  if (sources.size) {
    lines.push(`## ${md(uiFor(locale, edition).sources)}`, [...sources.values()]
      .map((source) => `${source.number}. [${md(source.title)}](<${source.url}>)`).join('\n'));
  }
  return `${lines.join('\n\n')}\n`;
}

async function build(input, directory) {
  const article = JSON.parse(await readFile(input, 'utf8'));
  validateArticle(article);
  const sources = sourceIndex(article);
  const editions = Object.entries(article.locales);
  const { defaultLocale } = article;
  const defaultEdition = article.locales[defaultLocale];
  const ui = uiFor(defaultLocale, defaultEdition);
  const hasDiagrams = defaultEdition.sections.some((section) => section.blocks.some((block) => block.type === 'mermaid'));
  const [css, viewer, mermaid] = await Promise.all([
    asset('reader.css'), asset('reader.js'), hasDiagrams ? mermaidScript() : '',
  ]);
  const buttons = editions.map(([locale]) => {
    const name = new Intl.DisplayNames([locale], { type: 'language' }).of(locale);
    return `<button type="button" data-locale-button="${locale}" lang="${locale}" aria-pressed="${locale === defaultLocale}">${html(name)}</button>`;
  }).join('');
  const templates = editions.filter(([locale]) => locale !== defaultLocale).map(([locale, edition]) =>
    `<template data-edition="${locale}">${editionHtml(locale, edition, sources)}</template>`).join('\n');
  const runtime = JSON.stringify({
    ...article.graph,
    relations,
    ui: Object.fromEntries(editions.map(([locale, edition]) => [locale, uiFor(locale, edition)])),
  }).replace(/</g, '\\u003c');
  const output = `<!doctype html>
<html lang="${defaultLocale}">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src data:; font-src data:; base-uri 'none'; form-action 'none'">
<title>${html(defaultEdition.title)}</title>
<style>${css}</style>
</head>
<body>
<header class="topbar"><div class="shell topbar-inner">
  <nav><a id="graph-link" href="#knowledge-graph" hidden>${html(ui.graph)}</a><a id="article-link" href="#article-text">${html(ui.article)}</a></nav>
  <div id="languages" role="group" aria-label="${html(ui.language)}" hidden>${buttons}</div>
</div></header>
<main id="reader">${editionHtml(defaultLocale, defaultEdition, sources)}</main>
${templates}
<script id="article-data" type="application/json">${runtime}</script>
${mermaid}
<script>${scriptText(viewer)}</script>
</body>
</html>\n`;

  await mkdir(directory, { recursive: true });
  const jsonPath = join(directory, 'article.json');
  if (resolve(input) !== resolve(jsonPath)) {
    await writeFile(jsonPath, `${JSON.stringify(article, null, 2)}\n`);
  }
  for (const [locale, edition] of editions) {
    await writeFile(join(directory, `${locale}.md`), editionMarkdown(locale, edition, sources));
  }
  const preview = join(directory, 'index.html');
  await writeFile(preview, output);
  console.log(`Built ${preview} (${editions.map(([locale]) => locale).join(', ')})`);
}

const [input, output] = process.argv.slice(2);
if (!input || !output) {
  console.error('Usage: node render-article.mjs <article.json> <output-directory>');
  process.exitCode = 1;
} else {
  try {
    await build(input, resolve(output));
  } catch (error) {
    console.error(error.message);
    process.exitCode = 1;
  }
}
