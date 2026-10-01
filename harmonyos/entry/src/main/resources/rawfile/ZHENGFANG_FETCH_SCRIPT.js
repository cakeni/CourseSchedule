(function () {
  'use strict';
  const requestToken = __REQUEST_TOKEN__;
  const resultKey = '__courseSchedule_' + requestToken;
  const bridge = __BRIDGE__;
  const limit = __LIMIT__;
  const fields = [
    'kcmc', 'kcm', 'courseName', 'xqj', 'xq', 'skxq', 'day', 'weekday',
    'jcs', 'jc', 'sksj', 'ksjc', 'skjc', 'startSection', 'jsjc',
    'endSection', 'cxjc', 'sectionCount', 'zcd', 'zc', 'skzc', 'weeks',
    'xm', 'jsxm', 'jsmc', 'teacher', 'cdmc', 'jxcdmc', 'jasmc',
    'classroom', 'room'
  ];

  function object(value) {
    return value && typeof value === 'object' && !Array.isArray(value) ? value : null;
  }
  function hasAny(value, names) {
    return names.some(function (name) { return name in value; });
  }
  function courseRow(value) {
    return object(value) &&
      hasAny(value, ['kcmc', 'kcm', 'courseName']) &&
      hasAny(value, ['xqj', 'xq', 'skxq', 'day', 'weekday']) &&
      hasAny(value, ['jcs', 'jc', 'sksj', 'ksjc', 'skjc', 'startSection']) &&
      hasAny(value, ['zcd', 'zc', 'skzc', 'weeks']);
  }
  function findRows(root, depth, seen) {
    if (root == null || depth > 10) return null;
    if (typeof root === 'object') {
      if (seen.has(root) || seen.size > 3000) return null;
      seen.add(root);
    }
    if (Array.isArray(root)) {
      if (root.some(courseRow)) return root.filter(courseRow).slice(0, 1500);
      for (let index = 0; index < Math.min(root.length, 600); index++) {
        const rows = findRows(root[index], depth + 1, seen);
        if (rows) return rows;
      }
    } else if (object(root)) {
      const keys = Object.keys(root).slice(0, 160);
      for (const key of keys) {
        const rows = findRows(root[key], depth + 1, seen);
        if (rows) return rows;
      }
    }
    return null;
  }
  function cleanRows(root) {
    const rows = findRows(root, 0, new Set());
    if (!rows) return null;
    return rows.map(function (row) {
      const clean = {};
      fields.forEach(function (field) {
        const value = row[field];
        if (typeof value === 'string' || typeof value === 'number') {
          clean[field] = String(value).slice(0,
            /^(?:zcd|zc|skzc|weeks)$/.test(field) ? 512 : 256);
        }
      });
      return clean;
    });
  }
  function decoded(value) {
    if (typeof value !== 'string') return value;
    const source = value.trim();
    if (!/^[\[{]/.test(source) || source.length > limit / 2) return null;
    try { return JSON.parse(source); } catch (_) { return null; }
  }
  function controlValue(doc, name) {
    const controls = doc.querySelectorAll('input,select');
    for (const control of controls) {
      const id = String(control.id || '').toLowerCase();
      const field = String(control.name || '').toLowerCase();
      if (id === name || field === name) {
        const value = String(control.value || '').trim();
        if (value) return value;
      }
    }
    return '';
  }
  function endpointFor(doc) {
    const location = doc.defaultView.location;
    const explicit = doc.querySelector(
      '[action*="xskbcx_cxXskbcxIndex"],[href*="xskbcx_cxXskbcxIndex"]'
    );
    if (explicit) {
      const value = explicit.getAttribute('action') || explicit.getAttribute('href');
      try {
        const url = new URL(value, location.href);
        if (url.origin === location.origin) return url.origin + url.pathname;
      } catch (_) {}
    }
    const match = location.pathname.match(/^(.*?)(?:\/(?:kbcx|xtgl|xsxxxggl|xkgl)\/)/i);
    if (!match) return '';
    return location.origin + match[1] + '/kbcx/xskbcx_cxXskbcxIndex.html';
  }
  function page(doc, depth) {
    if (!doc || depth > 4) return null;
    const win = doc.defaultView;
    const candidates = [];
    try { candidates.push(win.veInitDefaultJson); } catch (_) {}
    try { candidates.push(win.__INITIAL_STATE__); } catch (_) {}
    try {
      const body = doc.body;
      const raw = body && body.children.length <= 1 ? (body.textContent || '').trim() : '';
      if (raw.length >= 2 && raw.length <= limit / 2 && /^[\[{]/.test(raw)) {
        candidates.push(raw);
      }
    } catch (_) {}
    for (const candidate of candidates) {
      const rows = cleanRows(decoded(candidate));
      if (rows) return {doc: doc, rows: rows};
    }
    const year = controlValue(doc, 'xnm');
    const term = controlValue(doc, 'xqm');
    const endpoint = endpointFor(doc);
    if (/^\d{4}$/.test(year) && /^\d{1,2}$/.test(term) && endpoint) {
      return {doc: doc, year: year, term: term, endpoint: endpoint};
    }
    for (const frame of doc.querySelectorAll('iframe,frame')) {
      try {
        const found = page(frame.contentDocument, depth + 1);
        if (found) return found;
      } catch (_) {}
    }
    return null;
  }
  function finish(found, rows) {
    const location = found.doc.defaultView.location;
    const payload = JSON.stringify({
      html: '', data: rows, term: found.year && found.term ? found.year + '-' + found.term : '',
      sourceUrl: location.origin + location.pathname, profile: 'zhengfang'
    });
    if (payload.length > limit) bridge.onStage(requestToken, 'fallback');
    else bridge.onScheduleJson(requestToken, payload);
  }
  async function run() {
    const found = page(document, 0);
    if (!found) {
      bridge.onStage(requestToken, 'fallback');
      return;
    }
    if (found.rows) {
      finish(found, found.rows);
      return;
    }
    const controller = typeof AbortController === 'function' ? new AbortController() : null;
    const timer = setTimeout(function () { if (controller) controller.abort(); }, 7000);
    try {
      const body = new URLSearchParams({
        xnm: found.year, xqm: found.term, kzlx: 'ck',
        'queryModel.showCount': '2000', 'queryModel.currentPage': '1',
        'queryModel.sortName': '', 'queryModel.sortOrder': 'asc'
      }).toString();
      const response = await fetch(
        found.endpoint + '?doType=query&gnmkdm=N2151',
        {
          method: 'POST', credentials: 'include',
          headers: {
            'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8',
            'X-Requested-With': 'XMLHttpRequest'
          },
          body: body,
          signal: controller ? controller.signal : undefined
        }
      );
      const text = await response.text();
      const rows = response.ok && text.length <= limit / 2
        ? cleanRows(decoded(text)) : null;
      if (rows) finish(found, rows);
      else bridge.onStage(requestToken, 'fallback');
    } catch (_) {
      bridge.onStage(requestToken, 'fallback');
    } finally {
      clearTimeout(timer);
    }
  }
  run();
})();