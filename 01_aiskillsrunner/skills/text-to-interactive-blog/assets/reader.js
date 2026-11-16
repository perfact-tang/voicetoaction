(() => {
  const model = JSON.parse(document.querySelector('#article-data').textContent);
  const reader = document.querySelector('#reader');
  const initial = reader.firstElementChild;
  const editions = new Map([[initial.dataset.locale, initial]]);
  const templates = new Map([...document.querySelectorAll('[data-edition]')]
    .map((template) => [template.dataset.edition, template]));
  const languagePicker = document.querySelector('#languages');
  const languageButtons = [...languagePicker.querySelectorAll('button')];
  const graphLink = document.querySelector('#graph-link');
  const articleLink = document.querySelector('#article-link');
  const reduceMotion = matchMedia('(prefers-reduced-motion: reduce)');
  const coarsePointer = matchMedia('(pointer: coarse)');
  const expanded = new Set();
  const offsets = new Map();
  let disconnectGraph;
  let diagramCount = 0;
  let diagramQueue = Promise.resolve();

  const children = new Map();
  const roots = [];
  const hierarchy = [];
  for (const node of model.nodes) {
    if (node.parent === undefined) {
      roots.push(node);
    } else {
      if (!children.has(node.parent)) children.set(node.parent, []);
      children.get(node.parent).push(node);
      hierarchy.push({ source: node.id, target: node.parent, relation: node.relation });
    }
  }
  const edges = [...hierarchy, ...(model.edges ?? []).map((edge) => ({ ...edge, crossLink: true }))];
  const nodesById = new Map(model.nodes.map((node) => [node.id, node]));

  function scrollToAnchor(id, { focus = false, smooth = false } = {}) {
    const target = document.getElementById(id);
    if (!target || !reader.contains(target)) return;
    if (target.matches('details')) target.open = true;
    if (focus) target.focus({ preventScroll: true });
    target.scrollIntoView({ block: 'start', behavior: smooth && !reduceMotion.matches ? 'smooth' : 'auto' });
  }

  function jumpToContent(anchor) {
    const id = `content-${anchor}`;
    if (location.hash !== `#${id}`) history.pushState(null, '', `#${id}`);
    scrollToAnchor(id, { focus: true, smooth: true });
  }

  function visibleLevels() {
    const result = [];
    for (let level = roots; level.length; level = level.flatMap(
      (node) => expanded.has(node.id) ? children.get(node.id) : []
    )) {
      result.push(level);
    }
    return result;
  }

  function setupGraph(view, locale) {
    const ui = model.ui[locale];
    const panel = view.querySelector('.graph');
    const viewport = panel.querySelector('.graph-viewport');
    const stage = panel.querySelector('.graph-stage');
    const container = panel.querySelector('.graph-nodes');
    const canvas = panel.querySelector('canvas');
    const context = canvas.getContext('2d');
    const legend = panel.querySelector('.graph-legend');
    const relationshipList = panel.querySelector('.graph-relations');
    const collapse = panel.querySelector('[data-collapse]');
    const palette = getComputedStyle(panel);
    const colors = new Map(model.relations.map((relation) => [
      relation, palette.getPropertyValue(`--${relation}-ink`).trim(),
    ]));
    const points = new Map();
    const buttons = new Map();
    let visibleEdges = [];
    let frame = 0;
    let width = 0;
    let height = 0;

    function draw() {
      if (!context) return;
      const scale = Math.min(devicePixelRatio || 1, 2);
      const pixelWidth = Math.round(width * scale);
      const pixelHeight = Math.round(height * scale);
      if (canvas.width !== pixelWidth) canvas.width = pixelWidth;
      if (canvas.height !== pixelHeight) canvas.height = pixelHeight;
      context.setTransform(scale, 0, 0, scale, 0, 0);
      context.clearRect(0, 0, width, height);
      for (const edge of visibleEdges) {
        const from = points.get(edge.source);
        const to = points.get(edge.target);
        context.beginPath();
        context.setLineDash(edge.crossLink ? [4, 5] : []);
        context.strokeStyle = colors.get(edge.relation);
        context.globalAlpha = edge.crossLink ? 0.58 : 0.3;
        context.lineWidth = edge.crossLink ? 1.5 : 1;
        context.moveTo(from.x, from.y);
        context.lineTo(to.x, to.y);
        context.stroke();
      }
    }

    function place(point, button) {
      const offset = offsets.get(button.dataset.node);
      const limit = (value, min, max) => Math.max(min, Math.min(max, value));
      const margin = point.diameter / 2 + 3;
      point.x = limit(point.baseX + (offset?.x ?? 0), margin, width - margin);
      point.y = limit(point.baseY + (offset?.y ?? 0), margin, height - margin);
      button.style.left = `${point.x}px`;
      button.style.top = `${point.y}px`;
    }

    function updateRelationships() {
      visibleEdges = edges.filter((edge) => points.has(edge.source) && points.has(edge.target));
      const kinds = new Set([...points.keys()].map((id) => nodesById.get(id).relation));
      for (const edge of visibleEdges) kinds.add(edge.relation);
      legend.replaceChildren(...model.relations.filter((relation) => kinds.has(relation)).map((relation) => {
        const item = document.createElement('li');
        item.className = `relation-${relation}`;
        item.textContent = ui[relation];
        return item;
      }));
      relationshipList.hidden = !visibleEdges.length;
      relationshipList.querySelector('ul').replaceChildren(...visibleEdges.map((edge) => {
        const item = document.createElement('li');
        item.textContent = `${nodesById.get(edge.source).labels[locale]} → ${nodesById.get(edge.target).labels[locale]} · ${ui[edge.relation]}`;
        return item;
      }));
    }

    function showChildren(node) {
      const point = points.get(children.get(node.id)[0].id);
      if (point.y + point.diameter / 2 > viewport.scrollTop + viewport.clientHeight) {
        viewport.scrollTop = point.y + point.diameter / 2 - viewport.clientHeight + 14;
      }
    }

    function makeButton(node) {
      const branch = children.has(node.id);
      const button = document.createElement('button');
      const label = document.createElement('span');
      const name = node.labels[locale];
      button.type = 'button';
      button.dataset.node = node.id;
      button.className = `graph-node relation-${node.relation}`;
      button.title = `${name} · ${ui[node.relation]}`;
      if (!branch) button.setAttribute('aria-controls', `content-${node.anchor}`);
      label.className = 'node-label';
      label.textContent = name;
      button.append(label);
      if (branch) {
        const indicator = document.createElement('span');
        indicator.className = 'branch-indicator';
        indicator.setAttribute('aria-hidden', 'true');
        button.append(indicator);
      }
      let drag;
      let ignoreUntil = 0;
      let touch = false;
      function activate() {
        if (performance.now() < ignoreUntil) return;
        if (!branch) {
          jumpToContent(node.anchor);
          return;
        }
        const opening = !expanded.has(node.id);
        if (opening) expanded.add(node.id);
        else expanded.delete(node.id);
        layout();
        if (opening) showChildren(node);
      }
      button.addEventListener('dblclick', () => {
        if (!touch) activate();
      });
      button.addEventListener('click', (event) => {
        if (event.detail === 0 || (touch && event.detail === 1)) {
          activate();
        }
      });
      button.addEventListener('pointerdown', (event) => {
        if (event.button !== 0 || !event.isPrimary) return;
        touch = event.pointerType === 'touch' || coarsePointer.matches;
        const point = points.get(node.id);
        drag = { id: event.pointerId, x: event.clientX, y: event.clientY, offsetX: point.x - point.baseX, offsetY: point.y - point.baseY };
      });
      button.addEventListener('pointermove', (event) => {
        if (drag?.id !== event.pointerId) return;
        const dx = event.clientX - drag.x;
        const dy = event.clientY - drag.y;
        if (!drag.active && Math.hypot(dx, dy) < 5) return;
        if (!drag.active) {
          drag.active = true;
          button.setPointerCapture(event.pointerId);
          button.classList.add('dragging');
        }
        offsets.set(node.id, { x: drag.offsetX + dx, y: drag.offsetY + dy });
        place(points.get(node.id), button);
        draw();
      });
      function endDrag(event) {
        if (drag?.id !== event.pointerId) return;
        if (drag.active) ignoreUntil = performance.now() + 350;
        drag = undefined;
        button.classList.remove('dragging');
      }
      button.addEventListener('pointerup', endDrag);
      button.addEventListener('pointercancel', endDrag);
      button.addEventListener('lostpointercapture', endDrag);
      button.addEventListener('pointerleave', (event) => {
        if (!drag?.active) endDrag(event);
      });
      return button;
    }

    function layout() {
      width = viewport.clientWidth;
      if (!width) return;
      const maxDiameter = width < 520 ? 102 : 126;
      const minDiameter = 58;
      const rowHeight = maxDiameter + 34;
      const columns = Math.max(1, Math.floor((width - 16) / (maxDiameter + 12)));
      const levels = visibleLevels();
      const shown = new Set(levels.flat().map((node) => node.id));
      const focused = document.activeElement?.dataset.node;
      let row = 0;
      points.clear();
      for (const level of levels) {
        for (let start = 0; start < level.length; start += columns, row += 1) {
          const cells = level.slice(start, start + columns);
          const gap = Math.min(width / cells.length, maxDiameter + 94);
          cells.forEach((node, index) => {
            const diameter = Math.sqrt(minDiameter ** 2 + (maxDiameter ** 2 - minDiameter ** 2) * (node.weight - 1) / 4);
            const baseX = width / 2 + (index - (cells.length - 1) / 2) * gap;
            const baseY = 15 + rowHeight * (row + 0.5);
            points.set(node.id, { baseX, baseY, diameter });
          });
        }
      }
      height = 30 + row * rowHeight;
      stage.style.height = `${height}px`;
      for (const [id, button] of buttons) {
        if (!shown.has(id)) {
          button.remove();
          buttons.delete(id);
        }
      }
      // Match keyboard traversal to the current visual rows.
      const ordered = [];
      for (const [id, point] of points) {
        const button = buttons.get(id) ?? makeButton(nodesById.get(id));
        if (children.has(id)) button.setAttribute('aria-expanded', String(expanded.has(id)));
        button.style.setProperty('--diameter', `${point.diameter}px`);
        place(point, button);
        buttons.set(id, button);
        ordered.push(button);
      }
      if (ordered.some((button, index) => container.children[index] !== button)) {
        container.replaceChildren(...ordered);
        if (focused) buttons.get(focused)?.focus({ preventScroll: true });
      }
      collapse.hidden = ![...expanded].some((id) => shown.has(id));
      updateRelationships();
      draw();
    }

    collapse.onclick = () => {
      expanded.clear();
      layout();
      viewport.scrollTop = 0;
      buttons.get(roots[0].id)?.focus({ preventScroll: true });
    };
    panel.hidden = false;
    layout();
    const resize = new ResizeObserver(() => {
      cancelAnimationFrame(frame);
      frame = requestAnimationFrame(layout);
    });
    resize.observe(viewport);
    return () => {
      resize.disconnect();
      cancelAnimationFrame(frame);
      container.replaceChildren();
    };
  }

  const mermaid = globalThis.mermaid;
  mermaid?.initialize({
    startOnLoad: false, securityLevel: 'strict', suppressErrorRendering: true, htmlLabels: false,
    theme: 'neutral',
  });

  function renderDiagrams(view) {
    if (!mermaid) return;
    diagramQueue = diagramQueue.then(async () => {
      for (const figure of view.querySelectorAll('[data-mermaid]')) {
        if (!view.isConnected) break;
        const diagram = figure.querySelector('.diagram');
        if (!diagram.hidden) continue;
        try {
          const code = figure.querySelector('code').textContent;
          const { svg } = await mermaid.render(`mermaid-diagram-${++diagramCount}`, code);
          diagram.innerHTML = svg;
          diagram.hidden = false;
        } catch (error) {
          figure.querySelector('details').open = true;
          console.warn('Mermaid diagram could not be rendered', error);
        }
      }
    });
  }

  function selectLocale(locale) {
    if (!Object.hasOwn(model.ui, locale)) locale = initial.dataset.locale;
    if (disconnectGraph && document.documentElement.lang === locale) {
      scrollToAnchor(location.hash.slice(1));
      return;
    }
    if (!editions.has(locale)) {
      editions.set(locale, templates.get(locale).content.firstElementChild.cloneNode(true));
    }
    const view = editions.get(locale);
    disconnectGraph?.();
    if (reader.firstElementChild !== view) reader.replaceChildren(view);
    document.documentElement.lang = locale;
    document.title = view.querySelector('h1').textContent;
    languagePicker.setAttribute('aria-label', model.ui[locale].language);
    graphLink.textContent = model.ui[locale].graph;
    articleLink.textContent = model.ui[locale].article;
    for (const button of languageButtons) button.setAttribute('aria-pressed', String(button.dataset.localeButton === locale));
    disconnectGraph = setupGraph(view, locale);
    renderDiagrams(view);
    scrollToAnchor(location.hash.slice(1));
  }

  for (const button of languageButtons) {
    button.addEventListener('click', () => {
      const locale = button.dataset.localeButton;
      if (locale === document.documentElement.lang) return;
      const url = new URL(location.href);
      url.searchParams.set('lang', locale);
      history.replaceState(null, '', url);
      selectLocale(locale);
    });
  }
  languagePicker.hidden = languageButtons.length < 2;
  graphLink.hidden = false;
  addEventListener('popstate', () => selectLocale(new URL(location.href).searchParams.get('lang')));
  addEventListener('hashchange', () => scrollToAnchor(location.hash.slice(1)));
  selectLocale(new URL(location.href).searchParams.get('lang'));
})();
