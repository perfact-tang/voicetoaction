const labels = {
  zh: {
    language: '语言', article: '正文', contents: '文章目录', graph: '知识图谱',
    pointerHint: '拖动节点调整位置。双击展开分支或跳到对应正文。',
    touchHint: '拖动节点调整位置。轻点展开分支或跳到对应正文。',
    collapse: '收起分支', relations: '查看可见关系', sources: '来源',
    theme: '主题', supports: '论据', explains: '解释', illustrates: '案例',
    qualifies: '限制', contrasts: '对照',
  },
  ja: {
    language: '言語', article: '本文', contents: '目次', graph: '知識グラフ',
    pointerHint: 'ノードはドラッグで移動できます。ダブルクリックで展開するか、対応する本文へ移動します。',
    touchHint: 'ノードはドラッグで移動できます。タップで展開するか、対応する本文へ移動します。',
    collapse: 'すべて折りたたむ', relations: '表示中の関係を見る', sources: '出典',
    theme: 'テーマ', supports: '根拠', explains: '解説', illustrates: '具体例',
    qualifies: '条件・限界', contrasts: '対比',
  },
  en: {
    language: 'Language', article: 'Article', contents: 'On this page', graph: 'Knowledge graph',
    pointerHint: 'Drag nodes to move them. Double-click to expand a branch or jump to the article.',
    touchHint: 'Drag nodes to move them. Tap to expand a branch or jump to the article.',
    collapse: 'Collapse branches', relations: 'View visible relationships', sources: 'Sources',
    theme: 'Theme', supports: 'Evidence', explains: 'Explanation', illustrates: 'Example',
    qualifies: 'Limitation', contrasts: 'Contrast',
  },
};

export const relations = ['theme', 'supports', 'explains', 'illustrates', 'qualifies', 'contrasts'];
const slug = /^[a-z][a-z0-9]*(?:-[a-z0-9]+)*$/;
const object = (value) => value !== null && typeof value === 'object' && !Array.isArray(value);
const string = (value) => typeof value === 'string' && value.trim().length > 0;
const strings = (value) => Array.isArray(value) && value.length > 0 && value.every(string);

function requireValue(condition, location, description) {
  if (!condition) throw new Error(`${location} ${description}`);
}

export function uiFor(locale, edition) {
  const base = labels[locale.split('-')[0]] ?? {};
  return { ...base, ...edition.ui };
}

function checkBlock(block, location, sources) {
  switch (block.type) {
    case 'paragraph':
      requireValue(string(block.text), location, 'needs text');
      break;
    case 'list':
      requireValue(strings(block.items), location, 'needs items');
      requireValue(block.ordered === undefined || typeof block.ordered === 'boolean', location, 'has an invalid ordered value');
      break;
    case 'comparison':
      requireValue(string(block.title) && strings(block.columns) && block.columns.length > 1, location, 'needs a title and two or more columns');
      requireValue(Array.isArray(block.rows) && block.rows.length > 0 && block.rows.every(
        (row) => strings(row) && row.length === block.columns.length
      ), location, 'has rows that do not match its columns');
      break;
    case 'details':
      requireValue(string(block.label) && string(block.text), location, 'needs a label and text');
      break;
    case 'mermaid':
      requireValue(string(block.caption) && string(block.description) && string(block.code), location, 'needs a caption, text description and Mermaid code');
      requireValue(!/^\s*---|%%\s*\{|^\s*click\s/im.test(block.code), location, 'must use Mermaid without front matter, configuration directives or click handlers');
      break;
    default:
      throw new Error(`${location} has an unknown block type`);
  }
  if (block.sources !== undefined) {
    requireValue(Array.isArray(block.sources) && block.sources.every((id) => sources.has(id)), location, 'contains an unknown source ID');
  }
}

function indexById(values, location, optional = false) {
  const index = new Map();
  if (optional && values === undefined) return index;
  requireValue(Array.isArray(values) && (optional || values.length > 0), location, 'must be an array of identified items');
  for (const item of values) {
    requireValue(object(item) && typeof item.id === 'string' && slug.test(item.id), location, 'contains an invalid ID');
    requireValue(!index.has(item.id), `${location}.${item.id}`, 'is duplicated');
    index.set(item.id, item);
  }
  return index;
}

export function validateArticle(article) {
  requireValue(object(article) && article.version === 1, 'Article', 'must have version 1');
  requireValue(object(article.locales) && string(article.defaultLocale) && Object.hasOwn(article.locales, article.defaultLocale), 'defaultLocale', 'must name an article language');
  const editions = Object.entries(article.locales);
  const sources = indexById(article.sources, 'sources', true);
  for (const [id, source] of sources) {
    let url;
    try { url = new URL(source.url); } catch { /* Report the source below. */ }
    requireValue(string(source.title) && url?.protocol === 'https:' && !url.username && !url.password, `sources.${id}`, 'needs a title and an HTTPS URL');
  }

  let structure;
  let anchors;
  for (const [locale, edition] of editions) {
    let canonical;
    try { [canonical] = Intl.getCanonicalLocales(locale); } catch { /* Report the locale below. */ }
    requireValue(locale === canonical, locale, 'is not a canonical BCP 47 language tag');
    requireValue(object(edition) && string(edition.title) && string(edition.summary), locale, 'needs a title and summary');
    requireValue(Object.keys(labels.en).every((key) => string(uiFor(locale, edition)[key])), locale, 'needs localized UI labels');
    const ids = new Set();
    const order = [];
    for (const [id, section] of indexById(edition.sections, locale)) {
      requireValue(!ids.has(id) && string(section.title), `${locale}.${id}`, 'needs a unique section ID and title');
      ids.add(id);
      order.push(`section:${id}`);
      for (const [blockId, block] of indexById(section.blocks, `${locale}.${id}`)) {
        requireValue(!ids.has(blockId), `${locale}.${blockId}`, 'reuses an article anchor');
        ids.add(blockId);
        checkBlock(block, `${locale}.${blockId}`, sources);
        const dimensions = block.type === 'comparison' ? [block.columns.length, block.rows.length]
          : block.type === 'list' ? [Boolean(block.ordered), block.items.length] : [];
        order.push([block.type, blockId, block.sources ?? [], dimensions]);
      }
    }
    const signature = JSON.stringify(order);
    requireValue(!structure || signature === structure, locale, 'must preserve section and block order, IDs, citations, types and dimensions');
    structure = signature;
    anchors = ids;
  }

  requireValue(object(article.graph), 'graph', 'is required');
  const nodes = indexById(article.graph.nodes, 'graph.nodes');
  const parents = new Set();
  for (const [id, node] of nodes) {
    const location = `graph.nodes.${id}`;
    const root = node.parent === undefined;
    requireValue(!root || node.relation === 'theme', location, 'needs a theme relation at the first level');
    requireValue(root || (nodes.has(node.parent) && relations.slice(1).includes(node.relation)), location, 'needs an existing parent and a valid relationship');
    requireValue(Number.isInteger(node.weight) && node.weight >= 1 && node.weight <= 5, location, 'needs an integer weight from 1 to 5');
    requireValue(object(node.labels) && editions.every(([locale]) => string(node.labels[locale])), location, 'needs a label in each language');
    if (!root) parents.add(node.parent);
  }
  for (const [id, node] of nodes) {
    const seen = new Set([id]);
    for (let parent = node.parent; parent !== undefined; parent = nodes.get(parent).parent) {
      requireValue(!seen.has(parent), `graph.nodes.${id}`, 'has a parent cycle');
      seen.add(parent);
    }
    if (!parents.has(id) || node.anchor !== undefined) {
      requireValue(anchors.has(node.anchor), `graph.nodes.${id}`, 'needs a valid article anchor');
    }
  }

  const edges = article.graph.edges ?? [];
  requireValue(Array.isArray(edges), 'graph.edges', 'must be an array');
  const edgeKeys = new Set();
  for (const edge of edges) {
    requireValue(object(edge) && nodes.has(edge.source) && nodes.has(edge.target) && edge.source !== edge.target &&
      relations.slice(1).includes(edge.relation), 'graph.edges', 'contains invalid endpoints or an unknown relationship');
    const key = `${edge.source}:${edge.target}:${edge.relation}`;
    requireValue(!edgeKeys.has(key), 'graph.edges', 'contains a duplicate relationship');
    edgeKeys.add(key);
  }
}
