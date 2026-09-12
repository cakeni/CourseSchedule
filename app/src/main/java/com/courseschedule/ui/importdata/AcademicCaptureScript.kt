package com.courseschedule.ui.importdata

import com.google.gson.Gson

/** Captures only schedule-shaped fields and visible timetable markup from the trusted page. */
internal object AcademicCaptureScript {
    private val gson = Gson()

    fun create(school: AcademicSchool): String = SCRIPT
        .replace("__ROUTES__", gson.toJson(school.timetablePrefixes))
        .replace("__SCOPED__", school.isGeneric.toString())
        .replace("__PROFILE__", gson.toJson(school.genericProfileId.orEmpty()))
        .replace("__SYSTEM__", gson.toJson(school.system.name))
        .replace("__ADAPTER__", gson.toJson(school.adapterId))
        .replace("__CAPTURE_MODE__", gson.toJson(AcademicAdapterRegistry.captureModeFor(school).name))
        .replace("__GLOBALS__", gson.toJson(AcademicAdapterRegistry.globalsFor(school)))
        .replace("__SELECTORS__", gson.toJson(
            AcademicAdapterRegistry.selectorsFor(school).ifEmpty { selectors(school.system) }
        ))
        .replace("__LIMIT__", AcademicSchools.MAX_PAYLOAD_CHARS.toString())

    private fun selectors(system: AcademicSystem): List<String> = when (system) {
        AcademicSystem.AIC_HTML -> listOf("table#table")
        AcademicSystem.ZHENGFANG_HTML -> listOf("#Table1", "#kbgrid_table", "#table1", "#sycjlrtabGrid")
        AcademicSystem.URP_HTML -> listOf(".displayTag", "table.table-striped.table-bordered", "#tb")
        AcademicSystem.VATUU_HTML -> listOf("#table_border", ".table_border")
        AcademicSystem.UMOOC_HTML -> listOf("#timetable", ".timetable")
        AcademicSystem.SOUTH_SOFT_HTML -> listOf("#kb", ".kb")
        AcademicSystem.YILIAN_HTML -> listOf("[class*='CourseTimeTable'][class*='timeTable']")
        AcademicSystem.KINGOSOFT_HTML -> listOf(".pageRpt", "#reportArea", "#mytable", "#kbDiv", "table")
        AcademicSystem.STRUCTURED_HTML -> listOf("table")
        else -> listOf("#kbDiv", "table")
    }

    private val SCRIPT = """
        (function () {
          'use strict';
          const routes = __ROUTES__;
          const selectors = __SELECTORS__;
          const profile = __PROFILE__;
          const system = __SYSTEM__;
          const adapter = __ADAPTER__;
          const captureMode = __CAPTURE_MODE__;
          const registeredGlobals = __GLOBALS__;
          const limit = __LIMIT__;
          const visitedDocs = new Set();
          let blockedFrame = false;
          const selectorHits = new Set();

          function scope(url) {
            const proxy = url.pathname.match(/^\/(https?(?:-[0-9]+)?)\/([^/]+)(?:\/|${'$'})/i);
            if (proxy) return url.origin + '/' + proxy[1] + '/' + proxy[2] + '/';
            if (/^\/(?:https?|wss?|tcp|udp|ftp)(?:[-/]|${'$'})/i.test(url.pathname)) return null;
            return url.origin + '/';
          }
          function readable(doc) {
            try {
              const url = new URL(doc.defaultView.location.href);
              const page = url.origin + url.pathname;
              if (__SCOPED__) return routes.some(function (route) { return scope(url) === route; });
              return routes.some(function (route) { return page.startsWith(route); });
            } catch (_) { return false; }
          }
          function visible(element) {
            if (!element || element.hidden || element.getAttribute('aria-hidden') === 'true') return false;
            const style = element.ownerDocument.defaultView.getComputedStyle(element);
            return style.display !== 'none' && style.visibility !== 'hidden';
          }
          function text(value, max) {
            if (value === null || value === undefined) return '';
            if (typeof value !== 'string' && typeof value !== 'number') return '';
            return String(value).slice(0, max || 256);
          }
          function integer(value) {
            const number = Number(value);
            return Number.isInteger(number) ? number : null;
          }
          function array(value) { return Array.isArray(value) ? value : []; }
          function object(value) {
            return value && typeof value === 'object' && !Array.isArray(value) ? value : null;
          }
          function findArray(root, predicate, depth, seen) {
            if (!root || depth > 10) return null;
            if (typeof root === 'object') {
              if (seen.has(root) || seen.size > 3000) return null;
              seen.add(root);
            }
            if (Array.isArray(root)) {
              if (root.some(function (item) { return object(item) && predicate(item); })) return root;
              for (let i = 0; i < Math.min(root.length, 600); i++) {
                const found = findArray(root[i], predicate, depth + 1, seen);
                if (found) return found;
              }
            } else if (object(root)) {
              const keys = Object.keys(root).slice(0, 160);
              for (const key of keys) {
                const found = findArray(root[key], predicate, depth + 1, seen);
                if (found) return found;
              }
            }
            return null;
          }
          function findObject(root, predicate, depth, seen) {
            if (!root || depth > 10) return null;
            if (typeof root === 'object') {
              if (seen.has(root) || seen.size > 3000) return null;
              seen.add(root);
            }
            if (object(root) && predicate(root)) return root;
            const values = Array.isArray(root) ? root.slice(0, 600) : object(root) ?
              Object.keys(root).slice(0, 160).map(function (key) { return root[key]; }) : [];
            for (const value of values) {
              const found = findObject(value, predicate, depth + 1, seen);
              if (found) return found;
            }
            return null;
          }
          function projectUrp(root) {
            const holder = findObject(root, function (value) { return Array.isArray(value.dateList); }, 0, new Set());
            if (holder) {
              return {dateList: holder.dateList.slice(0, 80).map(function (date) {
                return {selectCourseList: array(date && date.selectCourseList).slice(0, 500).map(function (course) {
                  return {
                    courseName: text(course && course.courseName),
                    attendClassTeacher: text(course && course.attendClassTeacher),
                    timeAndPlaceList: array(course && course.timeAndPlaceList).slice(0, 100).map(function (time) {
                      return {campusName: text(time && time.campusName), classDay: integer(time && time.classDay),
                        classSessions: integer(time && time.classSessions), classWeek: text(time && time.classWeek, 64),
                        classroomName: text(time && time.classroomName), continuingSession: integer(time && time.continuingSession),
                        teachingBuildingName: text(time && time.teachingBuildingName)};
                    })
                  };
                })};
              })};
            }
            const rows = findArray(root, function (item) { return 'kcm' in item && object(item.id) && 'skzc' in item.id; }, 0, new Set());
            if (!rows) return null;
            return rows.slice(0, 1000).map(function (item) {
              return {kcm: text(item.kcm), jsm: text(item.jsm), jxlm: text(item.jxlm), jasm: text(item.jasm),
                cxjc: integer(item.cxjc), id: {skxq: integer(item.id.skxq), skzc: text(item.id.skzc, 64),
                  skjc: integer(item.id.skjc)}};
            });
          }
          function projectRows(root, required, fields) {
            const rows = findArray(root, function (item) {
              return required.every(function (key) { return key in item; });
            }, 0, new Set());
            if (!rows) return null;
            return rows.slice(0, 1500).map(function (item) {
              const source = object(item) || {};
              const clean = {};
              fields.forEach(function (key) { clean[key] = text(source[key], key === 'weeks' || key === 'qmz' || key === 'zc' ? 512 : 256); });
              return clean;
            });
          }
          function projectShuwei(root) {
            const holder = findObject(root, function (value) { return Array.isArray(value.activities); }, 0, new Set());
            if (!holder) return null;
            const hasCurrent = holder.activities.some(function (item) {
              return object(item) && 'courseName' in item && 'weekday' in item &&
                'startUnit' in item && 'endUnit' in item && Array.isArray(item.weekIndexes);
            });
            if (hasCurrent) {
              return {activities: holder.activities.slice(0, 1500).map(function (item) {
                const source = object(item) || {};
                const teachers = array(source.teachers).length ? array(source.teachers).slice(0, 20)
                  .map(function (teacher) { return text(teacher); }).join(',') : text(source.teachers);
                return {courseName: text(source.courseName), room: text(source.room), teachers: teachers,
                  weekday: integer(source.weekday), startUnit: integer(source.startUnit), endUnit: integer(source.endUnit),
                  weekIndexes: array(source.weekIndexes).slice(0, 52).map(integer).filter(function (week) { return week !== null; })};
              })};
            }
            const cells = array(holder.activities);
            const totalCells = integer(holder.unitCounts) || cells.length;
            const inferredUnits = totalCells > 0 && totalCells % 7 === 0 ? totalCells / 7 : 0;
            const units = integer(holder.unitCount) || inferredUnits || array(holder.courseUnits).length;
            return {unitCount: units, activities: holder.activities.slice(0, 240).map(function (cell) {
              return array(cell).slice(0, 30).map(function (item) {
                return {courseName: text(item && item.courseName), teacherName: text(item && item.teacherName),
                  roomName: text(item && item.roomName), vaildWeeks: text(item && item.vaildWeeks, 64)};
              });
            })};
          }
          function projectZhengfang(root) {
            const names = ['kcmc','kcm','courseName'];
            const days = ['xqj','xq','skxq','day','weekday'];
            const sections = ['jcs','jc','sksj','ksjc','skjc','startSection'];
            const weeks = ['zcd','zc','skzc','weeks'];
            function hasAny(item, fields) {
              return fields.some(function (key) { return key in item; });
            }
            const rows = findArray(root, function (item) {
              return hasAny(item, names) && hasAny(item, days) &&
                hasAny(item, sections) && hasAny(item, weeks);
            }, 0, new Set());
            if (!rows) return null;
            const fields = names.concat(days, sections, ['jsjc','endSection','cxjc','sectionCount'], weeks,
              ['xm','jsxm','jsmc','teacher','cdmc','jxcdmc','jasmc','classroom','room']);
            return rows.slice(0, 1500).map(function (item) {
              const source = object(item) || {};
              const clean = {};
              fields.forEach(function (key) {
                if (key in source) clean[key] = text(source[key], key === 'zcd' || key === 'zc' || key === 'skzc' || key === 'weeks' ? 512 : 256);
              });
              return clean;
            });
          }
          function project(root) {
            if (!root) return null;
            if (system === 'URP_NEW') return projectUrp(root);
            if (system === 'CHENGFANG') return projectRows(root, ['kcmc','xq','jcdm2','zcs'],
              ['kcmc','xq','jcdm2','zcs','jxcdmcs','teaxms']);
            if (system === 'EAMS' || system === 'SHUWEI') return projectShuwei(root);
            if (system === 'ZHENGFANG_HTML') return projectZhengfang(root);
            if (system === 'XBELL') return projectRows(root, ['kcmc','xqj','djj','qmz'],
              ['kcmc','xqj','djj','qmz','dsz','jsxm','skdd']);
            if (system === 'CHAOXING') return projectRows(root, ['kcmc','xq','djc','zc'],
              ['kcmc','xq','djc','zc','zctype','tmc','croommc']);
            if (system === 'CHAOXING_SHARE') return projectRows(root, ['name','dayOfWeek','beginNumber','length','weeks'],
              ['name','dayOfWeek','beginNumber','length','weeks','teacherName','location','onlineLocation']);
            return null;
          }
          function rawJson(doc) {
            try {
              const body = doc.body;
              if (!body || body.children.length > 1) return null;
              const only = body.firstElementChild;
              if (only && only.tagName !== 'PRE') return null;
              const raw = (only ? only.textContent : body.textContent || '').trim();
              if (raw.length < 2 || raw.length > limit / 2 || !/^[\[{]/.test(raw)) return null;
              return JSON.parse(raw);
            } catch (_) { return null; }
          }
          function projectedData(doc) {
            const win = doc.defaultView;
            const candidates = [rawJson(doc)];
            registeredGlobals.forEach(function (name) {
              try { candidates.push(win[name]); } catch (_) {}
            });
            try { candidates.push(win.kbxx); } catch (_) {}
            try { candidates.push({dateList: win.dateList}); } catch (_) {}
            try { candidates.push({activities: win.activities, unitCount: win.unitCount, courseUnits: win.courseUnits}); } catch (_) {}
            try {
              if (win.table0 && typeof win.table0 === 'object') {
                const table = Array.isArray(win.table0)
                  ? {activities: win.table0.slice(0, 3000), unitCount: integer(win.unitCount)}
                  : Object.assign({}, win.table0);
                if (!Array.isArray(win.table0) && Number.isInteger(Number(win.unitCount))) {
                  table.unitCount = Number(win.unitCount);
                }
                candidates.push(table);
              }
            } catch (_) {}
            try { candidates.push(win.veInitDefaultJson); } catch (_) {}
            try { candidates.push({kckbData: win.kckbData}); } catch (_) {}
            try { candidates.push({lessonArray: win.lessonArray}); } catch (_) {}
            try { candidates.push(win.__INITIAL_STATE__); } catch (_) {}
            try { candidates.push(win.__NEXT_DATA__); } catch (_) {}
            for (const candidate of candidates) {
              try {
                let source = candidate;
                if (typeof source === 'string' && /^[\[{]/.test(source.trim()) && source.length <= limit / 2) {
                  source = JSON.parse(source);
                }
                const clean = project(source);
                if (clean && JSON.stringify(clean).length > 4) return clean;
              } catch (_) {}
            }
            return null;
          }
          function scheduleScore(node) {
            const value = (node.innerText || node.textContent || '').slice(0, 6000);
            const days = (value.match(/(?:星期|周)[一二三四五六日天]/g) || []).length;
            let score = Math.min(days, 7) * 2;
            if (/周次|\d+\s*(?:[-~至]\s*\d+)?\s*周/.test(value)) score += 4;
            if (/节次|(?:第|\[)?\s*\d+\s*(?:[-~至]\s*\d+)?\s*节/.test(value)) score += 3;
            if (/课程|教师|教室|上课地点/.test(value)) score += 2;
            if (node.querySelector && node.querySelector('.courseInfo,.weekDetail,[title*=周次],[aria-describedby$=_kcmc]')) score += 6;
            return score;
          }
          function candidateNodes(doc) {
            const nodes = [];
            selectors.forEach(function (selector) {
              try { doc.querySelectorAll(selector).forEach(function (node) {
                if (visible(node) && scheduleScore(node) >= 7 && !nodes.includes(node)) {
                  nodes.push(node);
                  selectorHits.add(selector);
                }
              }); } catch (_) {}
            });
            return nodes.slice(0, 8);
          }
          const tags = new Set(['TABLE','THEAD','TBODY','TFOOT','TR','TD','TH','DIV','SPAN','FONT','BR','HR','P','B','STRONG','I','EM','SMALL']);
          const attributes = ['id','class','title','rowspan','colspan','aria-describedby','data-original-title'];
          let copied = 0;
          function copy(node, depth, outputDoc) {
            if (depth > 36 || ++copied > 18000) throw new Error('limit');
            if (node.nodeType === 3) return outputDoc.createTextNode((node.nodeValue || '').slice(0, 4096));
            if (node.nodeType !== 1 || !tags.has(node.tagName) || !visible(node)) return null;
            const clean = outputDoc.createElement(node.tagName.toLowerCase());
            attributes.forEach(function (name) {
              if (node.hasAttribute(name)) clean.setAttribute(name, node.getAttribute(name).slice(0, 256));
            });
            node.childNodes.forEach(function (child) {
              const safe = copy(child, depth + 1, outputDoc);
              if (safe) clean.appendChild(safe);
            });
            return clean;
          }
          function selectedTerm(doc) {
            const candidates = doc.querySelectorAll('select[id*=xnxq i],select[name*=xnxq i],select[id*=term i],select[name*=semester i]');
            for (const selector of candidates) {
              if (selector.selectedIndex >= 0) {
                const value = text(selector.value, 80);
                const label = text(selector.options[selector.selectedIndex].textContent, 80);
                if (/20\d{2}/.test(value + label)) return value || label;
              }
            }
            return '';
          }
          function captureDiagnostics() {
            return {adapterId: adapter, captureMode: captureMode,
              selectorHits: Array.from(selectorHits).slice(0, 16),
              iframeState: blockedFrame ? 'blocked' : (visitedDocs.size > 1 ? 'same_origin' : 'none'),
              documentsVisited: visitedDocs.size};
          }
          function find(doc, depth) {
            if (!doc || depth > 5 || visitedDocs.has(doc) || visitedDocs.size >= 24) return null;
            visitedDocs.add(doc);
            if (readable(doc)) {
              const data = projectedData(doc);
              const nodes = candidateNodes(doc);
              if (data || nodes.length) return {doc: doc, data: data, nodes: nodes};
            }
            for (const frame of doc.querySelectorAll('iframe,frame')) {
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
            if (!found) return JSON.stringify({error: blockedFrame ? 'frame' : 'table',
              diagnostics: captureDiagnostics()});
            const wrapper = document.implementation.createHTMLDocument('schedule').createElement('div');
            found.nodes.forEach(function (node) {
              const safe = copy(node, 0, wrapper.ownerDocument);
              if (safe) wrapper.appendChild(safe);
            });
            const source = new URL(found.doc.defaultView.location.href);
            const result = JSON.stringify({html: wrapper.innerHTML, data: found.data,
              term: selectedTerm(found.doc), sourceUrl: source.origin + source.pathname, profile: profile,
              diagnostics: captureDiagnostics()});
            return result.length > limit ? JSON.stringify({error: 'large',
              diagnostics: captureDiagnostics()}) : result;
          } catch (_) { return JSON.stringify({error: 'capture', diagnostics: captureDiagnostics()}); }
        })();
    """.trimIndent()
}
