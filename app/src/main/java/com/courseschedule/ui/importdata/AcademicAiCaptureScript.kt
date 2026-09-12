package com.courseschedule.ui.importdata

/**
 * Extracts only schedule-shaped, visible table/grid text from same-origin documents.
 * It intentionally never reads cookies, form values, URLs, scripts, or raw HTML.
 */
internal object AcademicAiCaptureScript {
    internal val SCRIPT = """
        (function () {
          'use strict';
          const TEXT_BUDGET = 90000;
          const MAX_TABLES = 12;
          const MAX_GRIDS = 8;
          let used = 0;

          const dayPattern = /(星期[一二三四五六日天1-7]|周[一二三四五六日天1-7]|monday|tuesday|wednesday|thursday|friday|saturday|sunday)/i;
          const schedulePattern = /(节次|第\s*\d+\s*节|课程|课表|上课时间|上课地点|教室|教师|周次|course|timetable|classroom|teacher|period)/i;

          function clean(value, limit) {
            return String(value || '')
              .replace(/[\u0000-\u001f\u007f]+/g, ' ')
              .replace(/\s+/g, ' ')
              .trim()
              .slice(0, limit);
          }

          function cleanBlock(value, limit) {
            return String(value || '')
              .split(/\r?\n/)
              .map(function (line) { return clean(line, 500); })
              .filter(Boolean)
              .slice(0, 160)
              .join('\n')
              .slice(0, limit);
          }

          function visible(element) {
            if (!element || !element.ownerDocument || !element.getClientRects().length) return false;
            const style = element.ownerDocument.defaultView.getComputedStyle(element);
            return style.display !== 'none' && style.visibility !== 'hidden' && style.opacity !== '0';
          }

          function scheduleShaped(text) {
            return dayPattern.test(text) || (schedulePattern.test(text) && /\d/.test(text));
          }

          function collectDocuments(root, output, depth) {
            if (!root || depth > 4 || output.indexOf(root) >= 0) return;
            output.push(root);
            const frames = root.querySelectorAll('iframe,frame');
            for (let i = 0; i < frames.length; i += 1) {
              try {
                collectDocuments(frames[i].contentDocument, output, depth + 1);
              } catch (_) {
                // Cross-origin frames are deliberately ignored.
              }
            }
          }

          function readTable(table) {
            if (!visible(table) || table.querySelector('table')) return null;
            const rows = [];
            let localCost = 0;
            const rowNodes = table.querySelectorAll('tr');
            for (let r = 0; r < rowNodes.length && r < 120 && used + localCost < TEXT_BUDGET; r += 1) {
              const cells = [];
              const cellNodes = Array.prototype.filter.call(rowNodes[r].children, function (child) {
                return child.tagName === 'TH' || child.tagName === 'TD';
              });
              for (let c = 0; c < cellNodes.length && c < 24 && used + localCost < TEXT_BUDGET; c += 1) {
                const cell = cellNodes[c];
                const text = clean(cell.innerText, 240);
                localCost += text.length + 36;
                cells.push({
                  text: text,
                  rowSpan: Math.max(1, Math.min(52, Number(cell.rowSpan) || 1)),
                  colSpan: Math.max(1, Math.min(24, Number(cell.colSpan) || 1))
                });
              }
              if (cells.length) rows.push(cells);
            }
            if (rows.length < 2) return null;
            const evidence = rows.map(function (row) {
              return row.map(function (cell) { return cell.text; }).join(' ');
            }).join(' ');
            if (evidence.length < 20 || !scheduleShaped(evidence)) return null;
            const captionNode = Array.prototype.find.call(table.children, function (child) {
              return child.tagName === 'CAPTION';
            });
            used += localCost;
            return {
              caption: clean(captionNode && captionNode.innerText, 160),
              rows: rows
            };
          }

          function readGrid(element) {
            if (!visible(element) || element.querySelector('table')) return null;
            const marker = clean((element.id || '') + ' ' + (element.className || ''), 300).toLowerCase();
            const namedLikeSchedule = /(schedule|timetable|calendar|course|lesson|kbtable|kbcontent)/.test(marker);
            const roleLikeGrid = element.getAttribute('role') === 'grid' || element.getAttribute('role') === 'table';
            if (!namedLikeSchedule && !roleLikeGrid) return null;
            const text = cleanBlock(element.innerText, 12000);
            if (text.length < 30 || !scheduleShaped(text)) return null;
            used += text.length + 24;
            return { text: text };
          }

          const documents = [];
          collectDocuments(document, documents, 0);
          const tables = [];
          const grids = [];
          for (let d = 0; d < documents.length && used < TEXT_BUDGET; d += 1) {
            const doc = documents[d];
            const tableNodes = doc.querySelectorAll('table');
            for (let t = 0; t < tableNodes.length && tables.length < MAX_TABLES && used < TEXT_BUDGET; t += 1) {
              const table = readTable(tableNodes[t]);
              if (table) tables.push(table);
            }
            const candidates = doc.querySelectorAll('[role="grid"],[role="table"],div,section');
            for (let g = 0; g < candidates.length && g < 2500 && grids.length < MAX_GRIDS && used < TEXT_BUDGET; g += 1) {
              const grid = readGrid(candidates[g]);
              if (grid && !grids.some(function (known) { return known.text === grid.text; })) grids.push(grid);
            }
          }
          if (!tables.length && !grids.length) return JSON.stringify({ error: 'missing' });
          return JSON.stringify({ version: 1, tables: tables, grids: grids });
        })();
    """.trimIndent()
}
