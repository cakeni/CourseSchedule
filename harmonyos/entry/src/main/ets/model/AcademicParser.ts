import { Course } from './ScheduleCore';
import { ParsedImport, makeCourse, parseDay } from './ImportCore';
import { MarkupNode, parseMarkup, nodes, nodeText } from './Markup';
import { AcademicSchool, allowsTimetable } from './AcademicSchools';

type JsonObject = Record<string, unknown>;
const normalize = (s: string): string => s.replace(/[（]/g, '(').replace(/[）]/g, ')').replace(/[【]/g, '[').replace(/[】]/g, ']')
  .replace(/[，、]/g, ',').replace(/；/g, ';').replace(/：/g, ':').replace(/[~～—–－至]/g, '-');
const text = (row: JsonObject, ...keys: string[]): string => {
  for (const key of keys) { const value = row[key]; if (typeof value === 'string' || typeof value === 'number') { const s = String(value).trim(); if (s) return s; } }
  return '';
};
const integer = (row: JsonObject, ...keys: string[]): number | null => { const s = text(row, ...keys); return /^\d+$/.test(s) ? Number(s) : null; };
const object = (value: unknown): JsonObject => value && typeof value === 'object' && !Array.isArray(value) ? value as JsonObject : {};
const list = (value: unknown): unknown[] => Array.isArray(value) ? value : [];
export function collectObjects(root: unknown): JsonObject[] {
  const result: JsonObject[] = []; let count = 0;
  const visit = (value: unknown, depth: number) => {
    if (depth > 12 || ++count > 12000 || !value || typeof value !== 'object') return;
    if (Array.isArray(value)) value.forEach(child => visit(child, depth + 1));
    else { result.push(value as JsonObject); Object.values(value).forEach(child => visit(child, depth + 1)); }
  }; visit(root, 0); return result;
}

export function numberSet(value: string, maximum: number = 30): number[] {
  const result = new Set<number>();
  for (const part of normalize(value).replace(/\s+/g, '').split(',')) {
    if (!part) continue;
    const match = /^(\d{1,2})(?:-(\d{1,2}))?$/.exec(part); if (!match) throw new Error('无法识别节次');
    const start = Number(match[1]); const end = Number(match[2] ?? match[1]);
    if (start < 1 || end < start || end > maximum) throw new Error('节次超出范围');
    for (let i = start; i <= end; i++) result.add(i);
  } return [...result].sort((a, b) => a - b);
}
export function bitmapWeeks(value: string, sentinel: boolean = false): number[] {
  let bits = value.trim(); if (!/^[01]{1,53}$/.test(bits)) throw new Error('课程周次位图无效');
  if (sentinel && bits.startsWith('0')) bits = bits.slice(1);
  if (bits.length > 52) throw new Error('课程周次超出范围');
  const weeks = Array.from(bits, (bit, i) => bit === '1' ? i + 1 : 0).filter(Boolean);
  if (!weeks.length) throw new Error('课程周次为空'); return weeks;
}
export function explicitWeeks(value: string, totalWeeks: number, bitmap: boolean = false): number[] {
  if (bitmap && /^[01]{1,53}$/.test(value.trim().replace(/[\[\]{}]/g, ''))) return bitmapWeeks(value.replace(/[\[\]{}]/g, ''));
  const clean = normalize(value).replace('周次:', '').replace('(周)', '').replace(/[周第\s\[\]{}:]/g, '');
  if (['单', '(单)', '双', '(双)', '全', '每'].includes(clean)) return Array.from({ length: totalWeeks }, (_, i) => i + 1)
    .filter(w => !clean.includes('单') && !clean.includes('双') || (w % 2 === 1) === clean.includes('单'));
  if (!clean) throw new Error('课程周次缺失');
  const result = new Set<number>();
  for (const part of clean.split(/[,;]/)) {
    const match = /^(\d{1,2})(?:-(\d{1,2}))?(?:\(([单双])\)|([单双]))?$/.exec(part);
    if (!match) throw new Error('无法识别课程周次');
    const start = Number(match[1]); const end = Number(match[2] ?? match[1]); const marker = match[3] ?? match[4] ?? '';
    if (start < 1 || end < start || end > 52) throw new Error('课程周次超出范围');
    for (let w = start; w <= end; w++) if (!marker || marker === '单' && w % 2 === 1 || marker === '双' && w % 2 === 0) result.add(w);
  }
  if (!result.size) throw new Error('课程周次为空'); return [...result].sort((a, b) => a - b);
}
export function runs(values: number[]): number[][] {
  const result: number[][] = []; for (const value of [...new Set(values)].sort((a, b) => a - b)) {
    const last = result[result.length - 1]; if (last && last[1] + 1 === value) last[1] = value; else result.push([value, value]);
  } return result;
}
export function weekRanges(values: number[]): number[][] {
  const sorted = [...new Set(values)].sort((a, b) => a - b); const result: number[][] = [];
  for (let i = 0; i < sorted.length;) {
    const start = sorted[i]; const step = sorted[i + 1] - start === 2 ? 2 : 1; let end = i;
    while (end + 1 < sorted.length && sorted[end + 1] - sorted[end] === step) end++;
    result.push([start, sorted[end], step === 2 ? start % 2 ? 1 : 2 : 0]); i = end + 1;
  } return result;
}
function hashCode(name: string): number { let hash = 0; for (const c of name) hash = Math.imul(hash, 31) + c.charCodeAt(0) | 0; return (hash % 16 + 16) % 16; }
function build(name: string, teacher: string, room: string, day: number | null, sections: number[][], weeks: number[]): Course[] {
  if (!name.trim()) throw new Error('课程名称缺失');
  if (!day || day < 1 || day > 7) throw new Error(name + ' 的星期缺失或超出范围');
  if (!weeks.length) throw new Error(name + ' 的周次为空');
  if (!sections.length || sections.some(s => !s[0] || s[0] < 1 || s[1] < s[0] || s[1] > 30)) throw new Error(name + ' 的节次缺失或超出范围');
  return sections.flatMap(s => weekRanges(weeks).map(w => ({ ...makeCourse(name, day, s, w, w[2]), teacher: teacher.trim(), classroom: room.trim(), colorIndex: hashCode(name) })));
}
const sectionExpression = (value: string): RegExpExecArray | null => /(?:\[|第)?\s*(\d{1,2}(?:\s*[-,]\s*\d{1,2})*)\s*节\s*\]?/.exec(normalize(value));
function sections(value: string): number[][] {
  const clean = normalize(value).trim(); const match = sectionExpression(clean);
  const digits = match?.[1] ?? clean.replace(/^[\[第]|[\]节]$/g, '');
  return /^\d{1,2}(?:\s*[-,]\s*\d{1,2})*$/.test(digits) ? runs(numberSet(digits)) : [];
}
function fromTime(name: string, teacher: string, room: string, day: number, value: string, fallback: number[][], total: number): Course[] {
  const normalized = normalize(value); const section = sectionExpression(normalized);
  const weeks = explicitWeeks(section ? normalized.slice(0, section.index) + normalized.slice(section.index + section[0].length) : normalized, total);
  return build(name, teacher, room, day, section ? runs(numberSet(section[1])) : fallback, weeks);
}

function jsonCourses(system: string, root: unknown, total: number, strict: boolean): Course[] {
  const all = collectObjects(root);
  if (system === 'WISEDU') {
    const rows = all.filter(r => ('KCM' in r || 'KCMC' in r) && ('SKXQ' in r || 'PKSJDD' in r) || 'kcmc' in r && 'qmz' in r);
    return rows.flatMap(r => {
      const name = text(r, 'KCM', 'KCMC', 'kcmc');
      if ('PKSJDD' in r) return text(r, 'PKSJDD').split(/[;；\n]+/).filter(Boolean).flatMap(meeting => {
        const day = /(?:星期|周)([一二三四五六日天七])/.exec(meeting)?.[1];
        const sec = sectionExpression(meeting); const week = /(?:\d{1,2}\s*(?:[-~～—–－至]\s*\d{1,2})?\s*[,，、]?\s*)+[单双]?\s*周(?:\s*[（(][单双][）)])?/.exec(meeting)?.[0];
        if (!day || !sec || !week) throw new Error('金智课程上课安排字段不完整');
        return build(name, text(r, 'RKJS', 'SKJS'), meeting.slice(sec.index + sec[0].length).replace(/^[\s,，]+/, '') || text(r, 'JASMC', 'SKDD'), parseDay(day === '七' ? '日' : day),
          runs(numberSet(sec[1])), explicitWeeks(week, total));
      });
      if ('kcmc' in r) {
        const marker = integer(r, 'dsz'); if (marker === null || marker < 0 || marker > 2) throw new Error('金智移动课表单双周字段不完整');
        const sec = integer(r, 'djj'); return build(name, text(r, 'jsxm'), text(r, 'skdd'), integer(r, 'xqj'), [[sec!, sec!]],
          explicitWeeks(text(r, 'qmz'), total, true).filter(w => marker === 2 || w % 2 === marker));
      }
      const pattern = text(r, 'SKZC'); if (strict && !pattern) throw new Error('课程周次缺失');
      const start = integer(r, 'KSJC'); const end = integer(r, 'JSJC') ?? (!strict ? start : null);
      return build(name, text(r, 'SKJS'), text(r, 'JASMC', 'SKDD'), integer(r, 'SKXQ'), [[start!, end!]],
        pattern ? explicitWeeks(pattern, total, true) : Array.from({ length: total }, (_, i) => i + 1));
    });
  }
  if (system === 'URP_NEW') {
    const parents = all.filter(r => 'courseName' in r && 'timeAndPlaceList' in r);
    if (parents.length) return parents.flatMap(r => list(r.timeAndPlaceList).flatMap(value => {
      const t = object(value); const start = integer(t, 'classSessions'); const count = integer(t, 'continuingSession');
      return build(text(r, 'courseName'), text(r, 'attendClassTeacher'), text(t, 'campusName') + text(t, 'teachingBuildingName') + text(t, 'classroomName'),
        integer(t, 'classDay'), [[start!, start! + count! - 1]], bitmapWeeks(text(t, 'classWeek')));
    }));
    return all.filter(r => 'kcm' in r && 'id' in r).flatMap(r => {
      const id = object(r.id); const start = integer(id, 'skjc'); const count = integer(r, 'cxjc');
      return build(text(r, 'kcm'), text(r, 'jsm'), text(r, 'jxlm') + text(r, 'jasm'), integer(id, 'skxq'), [[start!, start! + count! - 1]], bitmapWeeks(text(id, 'skzc')));
    });
  }
  if (system === 'SHUWEI' || system === 'EAMS') {
    const container = all.find(r => Array.isArray(r.activities)); if (!container) return [];
    const activities = list(container.activities); const current = activities.some(a => 'weekIndexes' in object(a));
    if (current) return activities.flatMap(a => {
      const r = object(a); return build(text(r, 'courseName'), text(r, 'teachers'), text(r, 'room'), integer(r, 'weekday'),
        [[integer(r, 'startUnit')!, integer(r, 'endUnit')!]], list(r.weekIndexes).map(Number));
    });
    const unitCount = integer(container, 'unitCount') ?? list(container.courseUnits).length;
    if (unitCount < 1 || unitCount > 30 || activities.length > unitCount * 7) throw new Error('课表节次网格范围异常');
    const meetings = new Map<string, { name: string; teacher: string; room: string; day: number; sections: number[]; weeks: number[] }>();
    activities.forEach((cell, index) => {
      if (!Array.isArray(cell)) throw new Error('课表单元格结构不完整');
      cell.forEach(item => {
        const r = object(item); const name = text(r, 'courseName'); const teacher = text(r, 'teacherName'); const room = text(r, 'roomName');
        const bitmap = text(r, 'vaildWeeks');
        if (system === 'EAMS' && !/^[01]{2,53}$/.test(bitmap)) throw new Error('EAMS 周次位图无效');
        const weeks = bitmapWeeks(system === 'EAMS' ? bitmap.slice(1) : bitmap, system !== 'EAMS');
        const day = Math.floor(index / unitCount) + 1; const key = JSON.stringify([name, teacher, room, day, weeks]);
        if (!meetings.has(key)) meetings.set(key, { name, teacher, room, day, sections: [], weeks });
        meetings.get(key)!.sections.push(index % unitCount + 1);
      });
    });
    return [...meetings.values()].flatMap(m => build(m.name, m.teacher, m.room, m.day, runs(m.sections), m.weeks));
  }
  if (system === 'CHENGFANG') return all.filter(r => 'kcmc' in r && 'jcdm2' in r).flatMap(r =>
    build(text(r, 'kcmc'), text(r, 'teaxms'), text(r, 'jxcdmcs'), integer(r, 'xq'), runs(numberSet(text(r, 'jcdm2'))), explicitWeeks(text(r, 'zcs'), total, true)));
  if (system === 'XBELL' || system === 'CHAOXING') return all.filter(r => 'kcmc' in r && (system === 'XBELL' ? 'qmz' in r : 'zc' in r)).flatMap(r => {
    const marker = integer(r, system === 'XBELL' ? 'dsz' : 'zctype'); const type = system === 'XBELL' ? marker === 0 ? 2 : marker === 1 ? 1 : marker === 2 ? 0 : -1 : marker ?? 0;
    if (type < 0) throw new Error('课程单双周类型缺失');
    const sec = integer(r, system === 'XBELL' ? 'djj' : 'djc');
    return build(text(r, 'kcmc'), text(r, 'jsxm', 'tmc'), text(r, 'skdd', 'croommc'), integer(r, 'xqj', 'xq'), [[sec!, sec!]],
      explicitWeeks(text(r, 'qmz', 'zc'), total, true).filter(w => type === 0 || type === 1 && w % 2 === 1 || type === 2 && w % 2 === 0));
  });
  if (system === 'CHAOXING_SHARE') return all.filter(r => 'name' in r && 'beginNumber' in r).flatMap(r => {
    const start = integer(r, 'beginNumber'); const length = integer(r, 'length');
    const weeks = Array.isArray(r.weeks) ? list(r.weeks).map(Number) : explicitWeeks(text(r, 'weeks'), total, true);
    return build(text(r, 'name'), text(r, 'teacherName'), text(r, 'location') + text(r, 'onlineLocation'), integer(r, 'dayOfWeek'), [[start!, start! + length! - 1]], weeks);
  });
  if (system === 'ZHENGFANG_HTML') return all.filter(r => {
    const fields = [['xqj', 'xq', 'skxq', 'day', 'weekday'], ['zcd', 'zc', 'skzc', 'weeks'], ['jcs', 'jc', 'sksj', 'ksjc', 'skjc', 'startSection']];
    const count = fields.filter(keys => keys.some(k => k in r)).length;
    return ['kcmc', 'kcm', 'courseName'].some(k => k in r) && count > 0 || count >= 2;
  }).flatMap(r => {
    const range = text(r, 'jcs', 'jc', 'sksj'); const start = integer(r, 'ksjc', 'skjc', 'startSection');
    const end = integer(r, 'jsjc', 'endSection') ?? (integer(r, 'cxjc', 'sectionCount') !== null ? start! + integer(r, 'cxjc', 'sectionCount')! - 1 : null);
    return build(text(r, 'kcmc', 'kcm', 'courseName'), text(r, 'xm', 'jsxm', 'jsmc', 'teacher'), text(r, 'cdmc', 'jxcdmc', 'jasmc', 'classroom', 'room'),
      parseDay(text(r, 'xqj', 'xq', 'skxq', 'day', 'weekday')), range ? sections(range) : [[start!, end!]], explicitWeeks(text(r, 'zcd', 'zc', 'skzc', 'weeks'), total, true));
  });
  return [];
}

const classHas = (node: MarkupNode, name: string): boolean => (node.attributes.class ?? '').split(/\s+/).includes(name);
const descendant = (node: MarkupNode): MarkupNode[] => node.children.flatMap(c => [c, ...descendant(c)]);
function tableRows(table: MarkupNode): MarkupNode[] {
  return table.children.flatMap(c => c.tag === 'tr' ? [c] : c.tag === 'table' ? [] : tableRows(c));
}
function cleanTree(node: MarkupNode): void {
  if (node.tag === '#text') node.text = node.text.replace(/\s+/g, ' ');
  node.children = node.children.filter(c => !['script', 'style', 'input', 'select', 'textarea', 'button', 'iframe', 'frame', 'object', 'embed'].includes(c.tag) &&
    !/display\s*:\s*none|visibility\s*:\s*hidden/i.test(c.attributes.style ?? '') && c.attributes['aria-hidden'] !== 'true' && !('hidden' in c.attributes));
  node.children.forEach(cleanTree);
}
function labeled(node: MarkupNode, titles: string[]): string {
  return nodeText(descendant(node).find(n => titles.includes(n.attributes.title ?? n.attributes['data-original-title'] ?? (n.attributes['aria-describedby'] ?? '').split('_').pop()!)) ??
    { tag: '', attributes: {}, children: [], text: '' }).trim();
}
function parseBlock(block: MarkupNode, day: number, fallback: number[][], total: number, qiangzhi: boolean): Course[] {
  if (qiangzhi) return qiangzhiBlock(block, day, fallback, total);
  const full = nodeText(block); const lines = full.split('\n').map(s => s.trim()).filter(Boolean);
  const titleName = labeled(block, ['课程名称', '课程名', 'kcmc']);
  const className = descendant(block).find(n => classHas(n, 'title') || classHas(n, 'qz-tooltipContent-title'));
  const name = titleName || (className ? nodeText(className).trim() : '') || /课程(?:名称|名)?\s*[:：]\s*([^\n]+)/.exec(full)?.[1]?.trim() || lines.find(line => !line.includes('周') && !sectionExpression(line) &&
    !/^(?=.*\d)[A-Za-z0-9_.-]{6,}$/.test(line) && !/^(教师|老师|教室|地点)/.test(line)) || '';
  const timeFields = descendant(block).filter(n => (n.attributes.title ?? '').includes('周次') || classHas(n, 'qz-tooltipContent-detailitem') && nodeText(n).includes('周'));
  const timeLines = timeFields.length ? timeFields.map(nodeText) : lines.filter(line => line.includes('周'));
  if (!timeLines.length) throw new Error('课程周次缺失');
  return timeLines.flatMap(time => {
    const index = lines.indexOf(time.trim());
    const teacher = labeled(block, ['教师', '老师', '任课教师', '授课教师']) || /(?:教师|老师)\s*[:：]?\s*([^\s,，;；]+)/.exec(full)?.[1] || (index > 1 ? lines[index - 1] : '');
    const room = labeled(block, ['教室', '地点', '上课地点']) || /(?:教室|地点)\s*[:：]?\s*([^\n,，;；]+)/.exec(full)?.[1] || lines[index + 1] || '';
    const clean = normalize(time).replace(/^时间:/, '').trim();
    const section = sectionExpression(clean); const before = section ? clean.slice(0, section.index) : clean;
    const week = /(?:\d{1,2}(?:\s*[-,;]\s*\d{1,2})*\s*(?:周)?\s*(?:\([单双]\)|[单双])?\s*周?|[单双全]周)/.exec(before)?.[0];
    if (!week) throw new Error('课程周次缺失');
    return build(name, teacher, room, day, section ? runs(numberSet(section[1])) : fallback, explicitWeeks(week, total));
  });
}

function qiangzhiBlock(block: MarkupNode, day: number, fallback: number[][], total: number): Course[] {
  const all = descendant(block); const full = normalize(nodeText(block));
  const name2024 = all.find(n => classHas(n, 'qz-tooltipContent-title'));
  if (name2024) {
    const name = nodeText(name2024).trim();
    const teacher = /(?:老师|教师)\s*:?\s*([^\s,;]+)/.exec(full)?.[1] || '';
    const room = /(?:地点|教室)\s*:?\s*([^\n,;]+)/.exec(full)?.[1]?.trim() || '';
    const fields = all.filter(n => classHas(n, 'qz-tooltipContent-detailitem')).map(nodeText).filter(s => s.includes('周') && sectionExpression(s));
    const times = fields.length ? fields : [...full.matchAll(/\d{1,2}(?:\s*[-,]\s*\d{1,2})*\s*周.{0,48}?(?:第|\[)?\s*\d{1,2}(?:\s*[-,]\s*\d{1,2})*\s*节\s*\]?/gs)].map(m => m[0]);
    if (!times.length) throw new Error('课程周次或节次缺失');
    return times.flatMap(t => fromTime(name, teacher, room, day, normalize(t).replace(/^时间:/, '').trim(), fallback, total));
  }
  const titled = all.filter(n => !!n.attributes.title);
  const timeFields = titled.filter(n => n.attributes.title.includes('周次'));
  const teacherField = (n: MarkupNode) => /教师|老师/.test(n.attributes.title);
  const roomField = (n: MarkupNode) => /教室|地点/.test(n.attributes.title);
  if (timeFields.length) {
    const plain = (n: MarkupNode): string => n.attributes.title ? '' : n.tag === 'br' ? '\n' : n.text + n.children.map(plain).join('');
    const leading = plain(block).split('\n').map(s => s.trim()).filter(Boolean);
    const name = labeled(block, ['课程名称', '课程名']) || leading.find(s => !/^(?=.*\d)[A-Za-z0-9_.-]{6,}$/.test(s)) || '';
    return timeFields.flatMap(t => {
      const index = titled.indexOf(t);
      const teacher = titled.slice(0, index).filter(teacherField).slice(-1)[0];
      const after = titled.slice(index + 1); const next = after.findIndex(n => n.attributes.title.includes('周次'));
      const room = (next < 0 ? after : after.slice(0, next)).find(roomField) || titled.find(roomField);
      return fromTime(name, teacher ? nodeText(teacher) : '', room ? nodeText(room) : '', day, nodeText(t), fallback, total);
    });
  }
  const lines = full.split('\n').map(s => s.trim()).filter(Boolean);
  const indexes = lines.map((s, i) => s.includes('周') && sectionExpression(s) ? i : -1).filter(i => i >= 0);
  const name = lines.slice(0, indexes[0] || 0).find(s => !/^(?=.*\d)[A-Za-z0-9_.-]{6,}$/.test(s)) || '';
  if (!indexes.length) throw new Error('课程周次或节次缺失');
  return indexes.flatMap(i => {
    if (!name || i < 2 || i + 1 >= lines.length) throw new Error('课程字段不完整');
    return fromTime(name, lines[i - 1], lines[i + 1], day, lines[i], fallback, total);
  });
}

function specializedHtml(root: MarkupNode, total: number, adapter: string, system: string): Course[] | null {
  const all = descendant(root);
  const cells = (r: MarkupNode) => r.children.filter(c => c.tag === 'td' || c.tag === 'th');
  const lines = (n: MarkupNode) => nodeText(n).split('\n').map(s => s.trim());
  const matchRoot = (id: string, cls: string = '') => all.find(n => n.attributes.id === id || cls && classHas(n, cls));
  const result: Course[] = [];
  const weekToken = /\d{1,2}(?:\s*[-,]\s*\d{1,2})*\s*周(?:\s*\(?[单双]\)?)?/g;
  const label = (s: string, kind: string) => new RegExp(`(?:${kind})\\s*[:：]\\s*(.+?)(?=\\s*(?:教师|老师|地点|教室)\\s*[:：]|[,，;；\\[\\]}｝]|$)`).exec(s)?.[1]?.trim() || '';
  if (adapter === 'scau_print_grid') {
    const table = all.find(n => n.tag === 'table' && n.attributes.border === '1' && n.attributes.bordercolor?.toLowerCase() === '#000000');
    if (!table) throw new Error('未找到华农打印课表');
    const rows = tableRows(table).slice(2); if (rows.length > 30) throw new Error('华农课表行数异常');
    rows.forEach((row, i) => cells(row).filter(c => c.attributes.valign?.toLowerCase() === 'top').slice(0, 7).forEach((c, d) => {
      const f = lines(c).filter(Boolean); if (f.length < 3) return;
      const parts = f[0].split(/[:：]/); const names = parts[0].trim().split(/\s+/); const embedded = /^[\d,，\-~～—–－至]+$/.test(names.slice(-1)[0] || '') ? names.pop() : '';
      result.push(...build(embedded ? names.join('') : parts[0], parts[1] || '', f[1], d + 1, [[i * 2 + 1, i * 2 + 2]], explicitWeeks(embedded || f[2], total)));
    }));
  } else if (adapter === 'xhtd_block_grid') {
    const area = matchRoot('kbtable'); if (!area) throw new Error('未找到协和天地课表');
    for (const n of nodes(area, 'div').slice(0, 240)) {
      const m = /^(\d{1,2})-([1-7])$/.exec(n.attributes.id || ''); if (!m || !nodes(n, 'nobr').length || Number(m[1]) > 15) continue;
      const f = lines(n); while (!f.slice(-1)[0]) f.pop();
      for (let i = 0; i < f.length; i += 5) {
        if (f.slice(i, i + 5).every(s => !s)) continue;
        if (i + 4 >= f.length || !f[i] || !f[i + 3]) throw new Error('协和天地课程块字段不完整，本次未导入');
        result.push(...build(f[i], f[i + 2], f[i + 4], Number(m[2]), [[Number(m[1]) * 2 - 1, Number(m[1]) * 2]], explicitWeeks(f[i + 3], total)));
      }
    }
  } else if (adapter === 'uestc_post_grid') {
    const area = matchRoot('tbl'); if (!area) throw new Error('未找到电子科大研究生课表');
    tableRows(area).slice(1, 31).forEach(r => cells(r).slice(1, 8).forEach((c, d) => {
      nodeText(c).trim().split(/，\s+/).filter(Boolean).forEach(s => {
        const f = s.split('/').map(s => s.trim()); if (f.length < 8 || !f[1]) throw new Error('电子科大课程块字段不完整，本次未导入');
        const sec = /(\d{1,2})\s*[-~]\s*(\d{1,2})/.exec(f[6]); if (!sec) throw new Error('电子科大课程节次缺失');
        result.push(...build(f[1], f[4], f[7], d + 1, [[Number(sec[1]), Number(sec[2])]], explicitWeeks(f[5], total)));
      });
    }));
  } else if (adapter === 'gdei_nested_grid') {
    const area = all.find(n => n.tag === 'tbody' && nodes(n, 'tr').some(r => sectionExpression(nodeText(cells(r)[0] || root))));
    if (!area) throw new Error('未找到广二师课表');
    nodes(area, 'tr').slice(0, 30).forEach(r => {
      const cs = cells(r); const fallback = sections(nodeText(cs[0] || root)); if (!fallback.length) return;
      cs.slice(1, 8).forEach((c, d) => c.children.filter(n => n.tag !== '#text').forEach(wrapper => {
        const f = wrapper.children.filter(n => n.tag !== '#text'); if (!f.length || f.every(n => !nodeText(n).trim())) return;
        if (f.length < 5) throw new Error('广二师课程块字段不完整，本次未导入');
        const spans = nodes(f[1], 'span');
        result.push(...build(nodeText(f[0]).trim(), nodeText(f[4]), nodeText(f[3]).split('(')[0], d + 1,
          spans[1] ? sections(nodeText(spans[1])) : fallback, explicitWeeks(nodeText(spans[0] || root).replace(/;/g, ','), total)));
      }));
    });
  } else if (adapter === 'hitsz_card_grid') {
    const area = matchRoot('', 'ivu-table-tbody'); if (!area) throw new Error('未找到哈工深课表');
    const rows = nodes(area, 'tr'); if (rows.length > 30) throw new Error('哈工深课表行数异常');
    rows.forEach(r => cells(r).slice(1, 8).forEach((c, d) => descendant(c).filter(n => classHas(n, 'ivu-card-body') && !descendant(n).some(c => classHas(c, 'ivu-card-body'))).forEach(n => {
      const s = normalize(nodeText(n)).replace(/\s+/g, ' ').trim(); const name = s.split(' ')[0];
      const sec = sectionExpression(s); if (!sec) throw new Error('哈工深课程节次缺失');
      const weeks = [...s.matchAll(weekToken)].map(m => m[0]).join(',');
      const details = [...s.matchAll(/\[([^\]]+)\]/g)].map(m => m[1]).filter(s => s !== '实验' && !/周/.test(s) && !sectionExpression(s));
      result.push(...build(name, name.startsWith('[实验]') ? '' : details[0] || '', name.startsWith('[实验]') ? details.slice(-1)[0] || '' : details.slice(1).slice(-1)[0] || '', d + 1, runs(numberSet(sec[1])), explicitWeeks(weeks, total)));
    })));
  } else if (adapter === 'hit_print_grid') {
    const areas = all.filter(n => n.attributes.id === 'xszp' || classHas(n, 'xfyq_con')); if (!areas.length) throw new Error('未找到哈工大课表');
    areas.flatMap(n => nodes(n, 'tr')).slice(0, 240).forEach(r => {
      const cs = cells(r); if (cs.length < 8 || cs.length > 9) return;
      const sectionColumn = cs.length === 8 ? 0 : 1; const digits = nodeText(cs[sectionColumn]).match(/\d{1,2}/g); if (!digits?.length) return;
      const fallback = [[Number(digits[0]), Number(digits.slice(-1)[0])]];
      cs.slice(sectionColumn + 1).forEach((c, d) => {
        const f = lines(c).flatMap(s => s.split('◇')).map(normalize).filter(Boolean); const markers = f.map((s, i) => s.includes('周') ? i : -1).filter(i => i >= 0); let lastName = '';
        markers.forEach((i, j) => {
          const name = f[i - 1]?.includes('周') ? lastName : f[i - 1] || ''; const end = markers[j + 1] === undefined ? f.length : Math.max(i + 1, markers[j + 1] - 1);
          const s = f.slice(i, end).join(' '); const sec = sectionExpression(s);
          result.push(...build(name, label(s, '(?:任课|授课)?(?:教师|老师)'), label(s, '(?:上课)?(?:地点|教室)'), d + 1, sec ? runs(numberSet(sec[1])) : fallback, explicitWeeks([...s.matchAll(weekToken)].map(m => m[0]).join(','), total))); lastName = name;
        });
      });
    });
  } else if (system === 'AIC_HTML') {
    const table = matchRoot('table'); if (!table || table.tag !== 'table') throw new Error('未找到 AIC 学期课表');
    tableRows(table).slice(1).forEach((r, i) => cells(r).slice(1).forEach((c, d) => descendant(c).filter(n => classHas(n, 'courseInfo')).forEach(n => {
      const name = nodeText(n.children.find(c => c.tag !== '#text') || root).trim();
      const field = (cls: string) => descendant(n).filter(c => classHas(c, cls)).map(nodeText).join(' ');
      result.push(...build(name, field('teacher'), field('place'), d + 1, [[i + 1, i + 1]], explicitWeeks(field('weekDetail'), total)));
    })));
  } else return null;
  if (!result.length) throw new Error('课表页面没有课程'); return result;
}

export function academicHtml(html: string, total: number, system: string, adapterId: string = ''): Course[] {
  if (!html.trim()) throw new Error('未读取到课表页面内容');
  const root = parseMarkup(html); cleanTree(root); const result: Course[] = [];
  const specialized = specializedHtml(root, total, adapterId, system); if (specialized) return specialized;
  const all = descendant(root);
  if (system === 'QIANGZHI_HTML' && adapterId === 'qiangzhi_auto') {
    if (all.some(n => n.attributes.name === 'kbDataTd') && all.some(n => classHas(n, 'qz-toolitiplists') || classHas(n, 'qz-tooltipContent-title'))) adapterId = 'qiangzhi_2024';
    else if (all.some(n => classHas(n, 'el-table__header')) && all.some(n => classHas(n, 'el-table__body'))) adapterId = 'qiangzhi_2017';
    else if (all.some(n => classHas(n, 'kbcontent1'))) adapterId = 'qiangzhi_crazy';
    else if (!all.some(n => n.attributes.id === 'kbtable' && descendant(n).some(c => classHas(c, 'kbcontent')))) throw new Error('当前页面不属于已登记的本地解析变体');
  }
  let tables = nodes(root, 'table');
  const scopeIds: Record<string, string[]> = { south_soft: ['kb'], kingosoft_new: ['mytable', 'reportArea'], kingosoft_selected: ['mytable', 'reportArea', 'kbDiv'],
    suda_post_grid: ['DataGrid1', 'MainWork_DataGrid1'], zju_post_grid: ['kcbForm'], xju_post_grid: ['ctl00_contentParent_dgData', 'contentParent_dgData'], cupl_post_grid: ['tabCT'] };
  const ids = scopeIds[adapterId];
  if (ids) {
    const areas = all.filter(n => ids.includes(n.attributes.id) || adapterId === 'south_soft' && classHas(n, 'kb') ||
      adapterId.startsWith('kingosoft_') && classHas(n, 'pageRpt'));
    if (!areas.length) throw new Error('当前页面不属于已登记的本地解析变体');
    tables = Array.from(new Set(areas.flatMap(n => n.tag === 'table' ? [n] : nodes(n, 'table'))));
  }
  const selectedTable = tables.find(t => system === 'QIANGZHI_HTML' ? ['kbtable', 'kbtable1', 'kbTable'].includes(t.attributes.id) :
    adapterId === 'zhengfang_legacy' ? ['Table1', 'table1'].includes(t.attributes.id) :
    adapterId === 'zhengfang_jwglxt' ? ['kbgrid_table', 'sycjlrtabGrid'].includes(t.attributes.id) : false);
  if (selectedTable) tables = [selectedTable];
  else if (adapterId === 'zhengfang_legacy' || adapterId === 'zhengfang_jwglxt') throw new Error('当前页面不属于已登记的本地解析变体');
  if (adapterId === 'qiangzhi_2017') {
    const header = tables.find(t => classHas(t, 'el-table__header')); const body = tables.find(t => classHas(t, 'el-table__body'));
    if (header && body) { body.children.unshift(...tableRows(header).slice(-1)); tables.splice(0, tables.length, body); }
  }
  for (const table of tables) {
    const rows = tableRows(table); if (rows.length > 240) throw new Error('课表行数异常');
    const expanded: { cell: MarkupNode; origin: boolean; row: number; span: number; colSpan: number }[][] = [];
    rows.forEach((r, i) => {
      const slots = expanded[i] ?? (expanded[i] = []); let col = 0;
      for (const cell of r.children.filter(c => c.tag === 'td' || c.tag === 'th')) {
        while (slots[col]) col++;
        const span = Math.min(240, Math.max(1, Number(cell.attributes.rowspan) || 1));
        const colSpan = Math.min(32, Math.max(1, Number(cell.attributes.colspan) || 1));
        if (col + colSpan > 32) throw new Error('课表列数异常');
        for (let y = i; y < Math.min(rows.length, i + span); y++) for (let x = col; x < col + colSpan; x++) {
          const target = expanded[y] ?? (expanded[y] = []);
          target[x] = { cell, origin: y === i && x === col, row: i, span, colSpan };
        }
        col += colSpan;
      }
    });
    const blank: MarkupNode = { tag: '', attributes: {}, children: [], text: '' };
    const cellRows = expanded.map(slots => Array.from({ length: slots.length }, (_, i) => slots[i]?.cell || blank));
    const keys: Record<string, string[]> = { name: ['课程', '课程名', '课程名称', 'kcmc', 'kcm', 'course', 'coursename'],
      teacher: ['教师', '老师', '任课教师', '授课教师', '代课教师', 'teacher', 'jsxm'], room: ['教室', '地点', '上课地点', '教学楼', 'classroom', 'room', 'location', 'sksjdd'],
      day: ['星期', '周几', '上课日', 'day', 'weekday', 'skxq'], weeks: ['周次', '上课周', 'weeks', 'week', 'skzc', 'zc'],
      section: ['节次', '上课节次', 'section', 'sections', 'jcdm2', 'sksj'], start: ['开始节次', '起始节次', 'startsection', 'skjc'],
      end: ['结束节次', 'endsection', 'jsjc'], count: ['节数', '持续节次', 'sectioncount', 'cxjc'] };
    const header = cellRows.findIndex(cells => {
      const labels = cells.map(c => nodeText(c).trim().toLowerCase().replace(/[\s_\-/：:]/g, ''));
      return ['name', 'day', 'weeks'].every(k => labels.some(s => keys[k].includes(s))) && labels.some(s => [...keys.section, ...keys.start].includes(s));
    });
    if (header >= 0) {
      const labels = cellRows[header].map(c => nodeText(c).trim().toLowerCase().replace(/[\s_\-/：:]/g, ''));
      for (const cells of cellRows.slice(header + 1)) {
        const get = (key: string) => nodeText(cells[labels.findIndex(s => keys[key].includes(s))] ?? { tag: '', attributes: {}, children: [], text: '' }).trim();
        const name = get('name'); if (!name) continue;
        const start = Number(get('start')); const end = Number(get('end')) || (Number(get('count')) ? start + Number(get('count')) - 1 : 0);
        result.push(...build(name, get('teacher'), get('room'), parseDay(get('day')), get('section') ? sections(get('section')) : [[start, end]], explicitWeeks(get('weeks'), total, true)));
      }
      continue;
    }
    const dayColumns = new Map<number, number>(); let dayHeader = -1;
    cellRows.forEach((cells, rowIndex) => {
      const positioned = cells.map((cell, column) => ({ column, cell })).filter(p => expanded[rowIndex][p.column]?.origin);
      const headings = positioned.filter(p => parseDay(nodeText(p.cell).trim()) !== null);
      if (headings.length >= 2) { dayHeader = rowIndex; dayColumns.clear(); headings.forEach(p => dayColumns.set(p.column, parseDay(nodeText(p.cell).trim())!)); return; }
      if (!dayColumns.size) return;
      for (const p of positioned) {
        const day = dayColumns.get(p.column); if (!day) continue;
        const cellText = nodeText(p.cell).trim(); if (!cellText || ['-', '--', '无', '暂无', '1'].includes(cellText)) continue;
        const slot = expanded[rowIndex][p.column];
        if (Array.from({ length: slot.colSpan }, (_, i) => dayColumns.get(p.column + i)).filter(Boolean).length > 1) throw new Error('课程跨星期单元格无法确认');
        const ordered = ['suda_post_grid', 'zju_post_grid', 'xju_post_grid', 'cupl_post_grid'].includes(adapterId);
        const fallbackNumbers = cellRows.slice(rowIndex, rowIndex + slot.span).flatMap(cs => cs.flatMap((c, i) => !dayColumns.has(i) ? sections(nodeText(c)).flatMap(s => numberSet(s[0] + '-' + s[1])) : []));
        const fallback = fallbackNumbers.length ? runs(fallbackNumbers) : ordered ? [[rowIndex - dayHeader, rowIndex - dayHeader + slot.span - 1]] : [];
        const nameClass = adapterId === 'qiangzhi_crazy' ? 'kbcontent1' : adapterId === 'qiangzhi_2024' ? 'qz-toolitiplists' : 'kbcontent';
        const contents = descendant(p.cell).filter(n => classHas(n, nameClass));
        const leaves = nodes(p.cell, 'div').filter(n => nodeText(n).includes('周') && !nodes(n, 'div').some(c => nodeText(c).includes('周')));
        let blocks = contents.length ? contents : leaves.length && adapterId !== 'cupl_post_grid' ? leaves : [p.cell];
        if (['suda_post_grid', 'cupl_post_grid', 'xju_post_grid'].includes(adapterId)) {
          const groups: MarkupNode[][] = [[]]; let previousBreak = false;
          for (const child of p.cell.children) {
            const separator = adapterId === 'suda_post_grid' && child.tag === 'br' && previousBreak || adapterId === 'cupl_post_grid' && child.tag === 'b';
            if (separator && groups.slice(-1)[0]!.some(n => nodeText(n).trim())) groups.push([]);
            if (adapterId === 'xju_post_grid' && child.tag === '#text') {
              const segments = child.text.split(/[;；]/); segments.forEach((s, i) => { if (i) groups.push([]); groups.slice(-1)[0]!.push({ ...child, text: s }); });
            } else groups.slice(-1)[0]!.push(child);
            if (child.tag !== '#text' || child.text.trim()) previousBreak = child.tag === 'br';
          }
          blocks = groups.filter(g => g.some(n => nodeText(n).trim())).map(children => ({ ...p.cell, children }));
        }
        for (const block of blocks) {
          if (!nodeText(block).trim()) continue;
          if (adapterId === 'xju_post_grid') {
            const full = normalize(nodeText(block)).trim(); const name = full.split(/[｛{(]/)[0].trim(); const detail = /\(([^)]+)\)/.exec(full)?.[1]?.trim().split(/\s+/);
            const week = /\d{1,2}(?:\s*[-,]\s*\d{1,2})*\s*周(?:\s*\(?[单双]\)?)?/.exec(full)?.[0];
            const value = (kind: string) => new RegExp(`${kind}:([^,\\]}｝]+)`).exec(full)?.[1]?.trim();
            result.push(...build(name, value('教师') || detail?.[0] || '', value('地点') || detail?.slice(-1)[0] || '', day, fallback, week ? explicitWeeks(week, total) : Array.from({ length: total }, (_, i) => i + 1))); continue;
          }
          if (!nodeText(block).includes('周')) throw new Error('有课程块的周次无法确认，本次未导入');
          // Each hr separates a complete meeting record in older Qiangzhi pages.
          const groups: MarkupNode[][] = [[]];
          for (const child of block.children) { if (child.tag === 'hr') groups.push([]); else groups[groups.length - 1].push(child); }
          for (const children of groups) result.push(...parseBlock({ ...block, children }, day, fallback, total, system === 'QIANGZHI_HTML'));
        }
      }
    });
  }
  if (!result.length) throw new Error('当前页面没有完整的课程、星期、节次和周次，请打开学期课表');
  return result;
}

export function parseAcademic(school: AcademicSchool, payload: string, totalWeeks: number): ParsedImport {
  if (payload.length > 400000) throw new Error('教务数据过大，请只查询一个学期');
  let root: JsonObject; try { root = object(JSON.parse(payload)); } catch (_) { throw new Error('课表页面数据格式不正确'); }
  const error = text(root, 'error'); if (error) throw new Error(error === 'frame' ? '课表位于不可读取的跨域页面，请直接打开课表网址' : error === 'table' ? '未找到学期课表，请先打开完整课表页面' : error);
  if (!allowsTimetable(school, text(root, 'sourceUrl'))) throw new Error('只能读取本次确认的教务域名和路径');
  const data = root.payload ?? root.data; const html = text(root, 'html');
  let courses: Course[];
  if (school.system === 'ZHENGFANG_HTML' && school.adapterId === 'zhengfang_auto') {
    const tableIds = nodes(parseMarkup(html), 'table').map(n => n.attributes.id);
    const variants: Course[][] = [];
    if (data !== undefined && data !== null || tableIds.some(id => ['kbgrid_table', 'sycjlrtabGrid'].includes(id))) {
      const current = jsonCourses(school.system, data, totalWeeks, school.scoped);
      variants.push(current.length ? current : academicHtml(html, totalWeeks, school.system, 'zhengfang_jwglxt'));
    }
    if (tableIds.some(id => ['Table1', 'table1'].includes(id))) variants.push(academicHtml(html, totalWeeks, school.system, 'zhengfang_legacy'));
    if (!variants.length) throw new Error('当前页面不属于已登记的本地解析变体');
    const canonical = (values: Course[]) => JSON.stringify(values.map(c => JSON.stringify([c.courseName, c.teacher, c.classroom, c.dayOfWeek, c.startSection, c.endSection, c.startWeek, c.endWeek, c.weekType])).sort());
    if (variants.some(values => canonical(values) !== canonical(variants[0]))) throw new Error('多个教务解析变体得到不同结果，请导出脱敏诊断后反馈');
    courses = variants[0];
  } else {
    courses = school.adapterId === 'zhengfang_legacy' ? [] : jsonCourses(school.system, data ?? root, totalWeeks, school.scoped);
    if (!courses.length) courses = academicHtml(html, totalWeeks, school.system, school.adapterId);
  }
  const unique = new Map<string, Course>();
  courses.forEach(c => unique.set(JSON.stringify([c.courseName, c.teacher, c.classroom, c.dayOfWeek, c.startSection, c.endSection, c.startWeek, c.endWeek, c.weekType]), c));
  if (unique.size > 1500) throw new Error('课表课程数量异常');
  return { courses: [...unique.values()], sourceLabel: school.name + ' · ' + (text(root, 'term') || '页面所选学期') };
}
