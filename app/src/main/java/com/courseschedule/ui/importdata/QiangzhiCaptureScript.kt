package com.courseschedule.ui.importdata

import com.google.gson.Gson

/** One local snapshot via evaluateJavascript. No JavascriptInterface is exposed on Qiangzhi pages. */
internal object QiangzhiCaptureScript {
    fun create(school: AcademicSchool): String = SCRIPT
        .replace("__ROUTES__", Gson().toJson(school.timetablePrefixes))
        .replace("__SCOPED__", school.isGeneric.toString())
        .replace("__LIMIT__", AcademicSchools.MAX_PAYLOAD_CHARS.toString())

    private val SCRIPT = """
        (function () {
          'use strict';
          const routes = __ROUTES__;
          const limit = __LIMIT__;
          const visited = new Set();
          let blockedFrame = false;
          function scope(url) {
            const proxy = url.pathname.match(/^\/(https?(?:-[0-9]+)?)\/([^/]+)(?:\/|$)/i);
            if (proxy) return url.origin + '/' + proxy[1] + '/' + proxy[2] + '/';
            if (/^\/(?:https?|wss?|tcp|udp|ftp)(?:[-/]|$)/i.test(url.pathname)) return null;
            return url.origin + '/';
          }
          function readable(doc) {
            try {
              const url = new URL(doc.defaultView.location.href);
              const path = url.origin + url.pathname;
              if (__SCOPED__) return routes.some(function (route) { return scope(url) === route; });
              return routes.some(function (route) { return path.startsWith(route); });
            } catch (_) { return false; }
          }
          function visible(element) {
            if (!element || element.hidden || element.getAttribute('aria-hidden') === 'true') return false;
            const style = element.ownerDocument.defaultView.getComputedStyle(element);
            return style.display !== 'none' && style.visibility !== 'hidden';
          }
          function find(doc, depth) {
            if (!doc || depth > 5 || visited.has(doc) || visited.size >= 24) return null;
            visited.add(doc);
            const preferred = ['#kbtable','#kbtable1','#kbTable','table.kbtable','table[class*="kbtable" i]'];
            const tables = [];
            preferred.forEach(function (selector) {
              try { doc.querySelectorAll(selector).forEach(function (table) {
                if (!tables.includes(table)) tables.push(table);
              }); } catch (_) {}
            });
            doc.querySelectorAll('table').forEach(function (table) {
              const value = (table.innerText || table.textContent || '').slice(0, 12000);
              const days = new Set(value.match(/(?:星期|周|礼拜)?[一二三四五六日天]/g) || []);
              if (days.size >= 2 && /周次|\d+\s*(?:[-~至]\s*\d+)?\s*周/.test(value) &&
                  /节次|(?:第|\[)?\s*\d+\s*(?:[-~至]\s*\d+)?\s*节/.test(value) && !tables.includes(table)) tables.push(table);
            });
            const table = tables.find(function (candidate) {
              return candidate.tagName === 'TABLE' && visible(candidate) && candidate.getClientRects().length;
            });
            if (readable(doc) && table && table.tagName === 'TABLE' && visible(table) && table.getClientRects().length) {
              return {doc: doc, table: table};
            }
            const frames = doc.querySelectorAll('iframe, frame');
            for (const frame of frames) {
              if (!visible(frame)) continue;
              try {
                const child = frame.contentDocument;
                if (!child) { blockedFrame = true; continue; }
                const found = find(child, depth + 1);
                if (found) return found;
              } catch (_) { blockedFrame = true; }
            }
            return null;
          }
          try {
            const found = find(document, 0);
            if (!found) return JSON.stringify({error: blockedFrame ? 'frame' : 'table'});
            // Copy only table text/structure. Inputs (including passwords), script, href/src,
            // event handlers, page body, Cookies and storage are never included in the result.
            const tags = new Set(['TABLE','THEAD','TBODY','TFOOT','TR','TD','TH','DIV','SPAN','FONT','BR','HR','P','B','STRONG','I','EM','SMALL','A']);
            const attributes = ['id', 'class', 'title', 'rowspan', 'colspan'];
            let copied = 0;
            function copy(node, depth) {
              if (depth > 32 || ++copied > 15000) throw new Error('limit');
              if (node.nodeType === 3) return document.createTextNode(node.nodeValue || '');
              if (node.nodeType !== 1 || !tags.has(node.tagName) || !visible(node)) return null;
              const clean = document.createElement(node.tagName === 'A' ? 'span' : node.tagName.toLowerCase());
              for (const name of attributes) {
                if (node.hasAttribute(name)) clean.setAttribute(name, node.getAttribute(name).slice(0, 256));
              }
              for (const child of node.childNodes) {
                const safe = copy(child, depth + 1);
                if (safe) clean.appendChild(safe);
              }
              return clean;
            }
            const table = copy(found.table, 0);
            const selector = found.doc.querySelector('select#xnxq01id, select#xnxq, select#XNXQDM, select[name="xnxq01id"]');
            let term = '';
            if (selector) {
              if (selector.tagName === 'SELECT' && selector.selectedIndex >= 0) {
                term = selector.options[selector.selectedIndex].textContent || '';
              }
              const value = String(selector.value || '');
              if (/^20[0-9]{2}[-_]20[0-9]{2}[-_][1-3]$/.test(value)) term = value;
            }
            const source = new URL(found.doc.defaultView.location.href);
            const result = JSON.stringify({html: table.outerHTML, term: term.slice(0, 80), sourceUrl: source.origin + source.pathname});
            return result.length > limit ? JSON.stringify({error: 'large'}) : result;
          } catch (_) { return JSON.stringify({error: 'capture'}); }
        })();
    """.trimIndent()
}
