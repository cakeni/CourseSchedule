import { Course, ScheduleState, Semester, ScheduleSettings, coursesOverlap, validCourse, nextCourseId, currentSemesterId, importJson } from './ScheduleCore';
import { parseMarkup, nodes, nodeText, MarkupNode } from './Markup';
import { academicHtml } from './AcademicParser';

export interface ParsedImport { courses: Course[]; semester?: Semester; settings?: ScheduleSettings; sourceLabel: string }
export interface ImportAnalysis { accepted: Course[]; conflicts: Course[]; duplicates: Course[]; invalid: Course[] }
export interface ImportOptions { includeConflicts: boolean; restoreMetadata: boolean; replaceExisting: boolean }

export function emptyImport(): ParsedImport { return { courses: [], sourceLabel: '' }; }
export function makeCourse(name: string, day: number, sections: number[], weeks: number[], type: number = 0): Course {
  return { id: 0, courseName: name.trim(), teacher: '', classroom: '', dayOfWeek: day,
    startSection: sections[0], endSection: sections[1], startWeek: weeks[0], endWeek: weeks[1], weekType: type,
    semesterId: 1, colorIndex: 0, note: '', reminderMinutes: -1, createTime: Date.now() };
}
export function parseDay(text: string): number | null {
  const value = text.trim().toLowerCase().replace(/^(周|星期|礼拜)/, '');
  const english = ['monday', 'tuesday', 'wednesday', 'thursday', 'friday', 'saturday', 'sunday'];
  const chinese = '一二三四五六日';
  if (/^[1-7]$/.test(value)) return Number(value);
  if (value === '天') return 7;
  const index = chinese.indexOf(value);
  if (value.length === 1 && index >= 0) return index + 1;
  const en = english.findIndex(day => value === day || value === day.slice(0, 3));
  return en >= 0 ? en + 1 : null;
}
export function parseRange(text: string): number[] | null {
  const values = text.match(/\d+/g);
  return values ? [Number(values[0]), Number(values[1] ?? values[0])] : null;
}
export function weekType(text: string): number { return /单|odd/i.test(text) ? 1 : /双|even/i.test(text) ? 2 : 0; }
const aliases: Record<string, string[]> = {
  name: ['课程', '课程名', '课程名称', 'course', 'name'], teacher: ['教师', '老师', '任课教师', 'teacher'],
  room: ['教室', '地点', '上课地点', 'classroom', 'room', 'location'], day: ['星期', '周几', '上课日', 'day', 'weekday'],
  section: ['节次', '上课节次', 'section', 'sections'], startSection: ['开始节次', '起始节次', 'startsection'],
  endSection: ['结束节次', 'endsection'], weeks: ['周次', '上课周', 'weeks', 'week'],
  startWeek: ['开始周', '起始周', 'startweek'], endWeek: ['结束周', 'endweek'], type: ['单双周', '周类型', 'weektype']
};
const normalize = (text: string): string => text.trim().toLowerCase().replace(/[\s_-]/g, '');
const field = (text: string): string => Object.keys(aliases).find(key => aliases[key].includes(normalize(text))) ?? '';

export function csvRow(line: string): string[] {
  const values: string[] = []; let current = ''; let quoted = false;
  for (let i = 0; i < line.length; i++) {
    const c = line[i];
    if (c === '"') { if (quoted && line[i + 1] === '"') { current += '"'; i++; } else quoted = !quoted; }
    else if (c === ',' && !quoted) { values.push(current.trim()); current = ''; } else current += c;
  }
  values.push(current.trim()); return values;
}

export function parseRows(input: string[][], totalWeeks: number, sourceLabel: string): ParsedImport {
  const rows = input.filter(row => row.some(cell => cell.trim()));
  const headerIndex = rows.findIndex(row => {
    const keys = row.map(field); return keys.includes('name') && keys.includes('day') && (keys.includes('section') || keys.includes('startSection'));
  });
  const courses: Course[] = [];
  if (headerIndex >= 0) {
    const keys = rows[headerIndex].map(field);
    for (const row of rows.slice(headerIndex + 1)) {
      const get = (key: string): string => row[keys.indexOf(key)] ?? '';
      const name = get('name'); const day = parseDay(get('day'));
      const sections = keys.includes('section') ? parseRange(get('section')) : parseRange(`${get('startSection')}-${get('endSection') || get('startSection')}`);
      const weeks = keys.includes('weeks') ? parseRange(get('weeks')) : keys.includes('startWeek') ? parseRange(`${get('startWeek')}-${get('endWeek') || get('startWeek')}`) : [1, totalWeeks];
      if (!name.trim() || day === null || !sections || !weeks) continue;
      const course = makeCourse(name, day, sections, weeks, weekType(get('type') || get('weeks')));
      course.teacher = get('teacher').trim(); course.classroom = get('room').trim(); courses.push(course);
    }
  } else {
    const gridHeader = rows.findIndex(row => row.filter(cell => parseDay(cell) !== null).length >= 2);
    if (gridHeader >= 0) {
      const days = rows[gridHeader].map(parseDay); const first = days.findIndex(day => day !== null);
      for (const row of rows.slice(gridHeader + 1)) {
        const sections = parseRange(row.slice(0, first).join(' '));
        days.forEach((day, column) => { if (day !== null) courses.push(...parseGridCell(row[column] ?? '', day, sections, totalWeeks)); });
      }
    }
    if (!courses.length) for (const row of rows) {
      if (row.length < 5) continue;
      const day = parseDay(row[3]); if (day === null) continue;
      const course = makeCourse(row[0], day, [Number(row[4]), Number(row[5] || row[4])],
        [Number(row[6] || 1), Number(row[7] || totalWeeks)], Number(row[8] || 0));
      course.teacher = row[1]; course.classroom = row[2]; courses.push(course);
    }
  }
  return { courses, sourceLabel };
}

export function parseGridCell(text: string, day: number, fallback: number[] | null, totalWeeks: number): Course[] {
  if (!text.trim() || ['-', '--', '无', '1'].includes(text.trim())) return [];
  const courses: Course[] = [];
  const records = text.replace(/\r\n/g, '\n').split(/\n(?=\s*\d{6,}\s*-)/);
  for (const record of records) {
    const lines = record.split('\n').map(s => s.trim()).filter(Boolean);
    const name = (lines[0] ?? '').replace(/^\s*\d{6,}\s*-\s*/, '').replace(/\s*\[[^\]]*\]\s*$/, '').trim();
    const sectionRoom = /第\s*(\d+)\s*节\s*[-~至]\s*第?\s*(\d+)\s*节\s*([^,，\n]*)/.exec(record);
    const sections = sectionRoom ? [Number(sectionRoom[1]), Number(sectionRoom[2])] : fallback;
    if (!name || !sections) continue;
    const beforeDay = record.split(/(?:星期|周)\s*[一二三四五六日天1-7]/)[0];
    const ranges = Array.from(beforeDay.matchAll(/(\d+)\s*(?:[-~至]\s*(\d+))?\s*周/g), m => [Number(m[1]), Number(m[2] ?? m[1])]);
    if (!ranges.length) ranges.push([1, totalWeeks]);
    for (const weeks of ranges) {
      const course = makeCourse(name, day, sections, weeks, weekType(record));
      course.teacher = /(?:教师|老师)\s*[:：]?\s*([^\s,，;；]+)/.exec(record)?.[1] ?? '';
      course.classroom = sectionRoom?.[3]?.trim().replace(/[,，]+$/, '') ?? /(?:教室|地点|校区)\s*[:：]?\s*([^\s,，;；]+)/.exec(record)?.[1] ?? '';
      courses.push(course);
    }
  }
  return courses;
}

export function parseJsonImport(text: string, state: ScheduleState): ParsedImport {
  let raw: unknown; try { raw = JSON.parse(text); } catch (_) { throw new Error('JSON 文件格式不正确'); }
  const root = raw as { courses?: Partial<Course>[]; schemaVersion?: number; semester?: Semester; settings?: ScheduleSettings };
  if (!Array.isArray(raw) && (!root || !Array.isArray(root.courses))) throw new Error('JSON 中没有课程列表');
  if ((root.schemaVersion ?? 2) > 2) throw new Error('该备份来自更高版本的应用');
  const list: Partial<Course>[] = Array.isArray(raw) ? raw : root.courses!;
  if (list.length > 10000) throw new Error('课程数量过多');
  const courses = list.map(item => ({ ...makeCourse('', 0, [0, 0], [0, 0]), ...item,
    reminderMinutes: item.reminderMinutes ?? state.settings.defaultReminderMinutes }));
  if (Array.isArray(raw)) return { courses, sourceLabel: 'JSON' };
  // Reuse backup validation for metadata without rejecting invalid course rows: the preview reports them.
  const checked = root.semester ? importJson(JSON.stringify({ ...root, courses: [] }), state) : state;
  return { courses, semester: root.semester ? checked.semester : undefined, settings: root.settings ? checked.settings : undefined, sourceLabel: '完整备份' };
}

export function parseTextImport(text: string, state: ScheduleState, extension: string = 'txt'): ParsedImport {
  if (text.length > 8000000) throw new Error('文件内容超过 8 MB');
  if (/^[\s\uFEFF]*[\[{]/.test(text)) return parseJsonImport(text.replace(/^\uFEFF/, ''), state);
  if (extension === 'html' || extension === 'htm' || /^\s*<(?:!doctype|html|table)/i.test(text)) return parseHtmlImport(text, state.semester.totalWeeks);
  const lines = text.replace(/^\uFEFF/, '').split(/\r?\n/).filter(line => line.trim());
  if (extension === 'csv') return parseRows(lines.map(csvRow), state.semester.totalWeeks, 'CSV');
  const courses: Course[] = [];
  for (const line of lines) {
    const parts = (line.includes('|') ? line.split('|') : line.includes('\t') ? line.split('\t') : line.includes(',') ? csvRow(line) : line.trim().split(/\s+/)).map(s => s.trim());
    if (parts.some(p => field(p) === 'name') || parts.length < 3) continue;
    const full = parts.length >= 5; const day = parseDay(parts[full ? 3 : 1]);
    const sections = parseRange(parts[full ? 4 : 2]); const weeksText = parts[full ? 5 : 3] ?? '';
    const weeks = weeksText ? parseRange(weeksText) : [1, state.semester.totalWeeks];
    if (day === null || !sections || !weeks) continue;
    const course = makeCourse(parts[0], day, sections, weeks, weekType(weeksText));
    if (full) { course.teacher = parts[1]; course.classroom = parts[2]; }
    courses.push(course);
  }
  return { courses, sourceLabel: '文本' };
}

export function parseHtmlImport(text: string, totalWeeks: number): ParsedImport {
  const root = parseMarkup(text);
  if (nodes(root, 'div').some(n => (n.attributes.class || '').split(/\s+/).some(c => c === 'kbcontent' || c === 'kbcontent1' || c === 'qz-toolitiplists'))) {
    return { courses: academicHtml(text, totalWeeks, 'QIANGZHI_HTML'), sourceLabel: 'HTML' };
  }
  const courses: Course[] = [];
  for (const table of nodes(root, 'table')) {
    const rows = nodes(table, 'tr').map(tr => tr.children.filter(c => c.tag === 'th' || c.tag === 'td').map(c => nodeText(c).trim()));
    courses.push(...parseRows(rows, totalWeeks, 'HTML').courses);
  }
  if (!courses.length) throw new Error('未找到带星期和节次信息的课程表');
  return { courses, sourceLabel: 'HTML' };
}

export function duplicateCourse(a: Course, b: Course): boolean {
  return ['courseName', 'teacher', 'classroom'].every(key => String(a[key as keyof Course]).trim().toLowerCase() === String(b[key as keyof Course]).trim().toLowerCase()) &&
    ['dayOfWeek', 'startSection', 'endSection', 'startWeek', 'endWeek', 'weekType'].every(key => a[key as keyof Course] === b[key as keyof Course]);
}
export function analyzeImport(courses: Course[], existing: Course[], semesterId: number, totalWeeks: number): ImportAnalysis {
  const analysis: ImportAnalysis = { accepted: [], conflicts: [], duplicates: [], invalid: [] }; const seen: Course[] = [];
  courses.forEach((original, index) => {
    const course = { ...original, id: 0, semesterId, colorIndex: (((original.colorIndex || index) % 16) + 16) % 16, createTime: Date.now() };
    if (!validCourse(course, totalWeeks)) analysis.invalid.push(course);
    else if ([...existing, ...seen].some(c => duplicateCourse(c, course))) analysis.duplicates.push(course);
    else {
      if ([...existing, ...analysis.accepted, ...analysis.conflicts].some(c => coursesOverlap(c, course))) analysis.conflicts.push(course);
      else analysis.accepted.push(course);
      seen.push(course);
    }
  }); return analysis;
}

export function commitImport(state: ScheduleState, parsed: ParsedImport, options: ImportOptions): ScheduleState {
  const semester = options.restoreMetadata && parsed.semester ? { ...parsed.semester, id: currentSemesterId(state) } : state.semester;
  const analysis = analyzeImport(parsed.courses, options.replaceExisting ? [] : state.courses, currentSemesterId(state), semester.totalWeeks);
  const selected = [...analysis.accepted, ...(options.includeConflicts ? analysis.conflicts : [])];
  if (!selected.length) throw new Error('没有选择可导入的课程');
  let id = nextCourseId(state);
  return { ...state, semester, settings: options.restoreMetadata && parsed.settings ? parsed.settings : state.settings,
    courses: [...(options.replaceExisting ? [] : state.courses), ...selected.map(course => ({ ...course, id: id++ }))] };
}

export function xlsxRows(xml: string, sharedStrings: string[]): string[][] {
  const root = parseMarkup(xml); const result: string[][] = [];
  for (const row of nodes(root, 'row').slice(0, 10000)) {
    const cells: string[] = []; let next = 0;
    for (const cell of row.children.filter(c => c.tag === 'c')) {
      const letters = /^[a-z]+/i.exec(cell.attributes.r ?? '')?.[0].toUpperCase();
      let column = letters ? Array.from(letters).reduce((n, c) => n * 26 + c.charCodeAt(0) - 64, 0) - 1 : next;
      if (column < 0 || column >= 256) continue;
      const raw = nodes(cell, cell.attributes.t === 'inlineStr' ? 't' : 'v').map(nodeText).join('').trim();
      const value = cell.attributes.t === 's' ? sharedStrings[Number(raw)] ?? '' : cell.attributes.t === 'b' ? raw === '1' ? 'TRUE' : 'FALSE' : raw.replace(/^(-?\d+)\.0+$/, '$1');
      if (value) { while (cells.length <= column) cells.push(''); cells[column] = value; }
      next = column + 1;
    }
    if (cells.length) result.push(cells);
  } return result;
}
export function xlsxSharedStrings(xml: string): string[] { return nodes(parseMarkup(xml), 'si').map(si => nodes(si, 't').map(nodeText).join('')); }

export interface ZipEntry { name: string; size: number; method: number; offset: number; compressedSize: number; crc: number }

const CRC_TABLE = Array.from({ length: 256 }, (_, i) => {
  let value = i; for (let bit = 0; bit < 8; bit++) value = value & 1 ? 0xedb88320 ^ value >>> 1 : value >>> 1; return value >>> 0;
});
export function crc32(bytes: ArrayBuffer): number {
  let crc = 0xffffffff; for (const byte of new Uint8Array(bytes)) crc = CRC_TABLE[(crc ^ byte) & 255] ^ crc >>> 8;
  return (crc ^ 0xffffffff) >>> 0;
}
export function validateXlsxZip(buffer: ArrayBuffer): ZipEntry[] {
  const bytes = new Uint8Array(buffer); const view = new DataView(buffer); let end = -1;
  for (let i = bytes.length - 22; i >= Math.max(0, bytes.length - 65557); i--) if (view.getUint32(i, true) === 0x06054b50) { end = i; break; }
  if (end < 0 || view.getUint16(end + 4, true) || view.getUint16(end + 6, true)) throw new Error('所选文件不是有效的 .xlsx 文件');
  let offset = view.getUint32(end + 16, true); const count = view.getUint16(end + 10, true); let total = 0;
  if (count > 10000) throw new Error('Excel 文件项目过多');
  const entries: ZipEntry[] = [];
  for (let i = 0; i < count; i++) {
    if (offset + 46 > end || view.getUint32(offset, true) !== 0x02014b50) throw new Error('Excel 文件目录损坏');
    const size = view.getUint32(offset + 24, true); const length = view.getUint16(offset + 28, true);
    const nameBytes = bytes.slice(offset + 46, offset + 46 + length);
    const name = Array.from(nameBytes, n => String.fromCharCode(n)).join('').replace(/\\/g, '/');
    if (name.startsWith('/') || name.includes(':') || name.split('/').includes('..') || name.includes('\0') || (view.getUint16(offset + 8, true) & 1)) throw new Error('Excel 文件包含不安全的路径或加密内容');
    total += size; if (size > 16 * 1024 * 1024 || total > 48 * 1024 * 1024) throw new Error('Excel 文件内容过大');
    const local = view.getUint32(offset + 42, true);
    if (local + 30 > buffer.byteLength || view.getUint32(local, true) !== 0x04034b50) throw new Error('Excel 工作表位置损坏');
    const localLength = view.getUint16(local + 26, true);
    const localName = Array.from(bytes.slice(local + 30, local + 30 + localLength), n => String.fromCharCode(n)).join('').replace(/\\/g, '/');
    const method = view.getUint16(offset + 10, true);
    const dataOffset = local + 30 + localLength + view.getUint16(local + 28, true);
    const compressedSize = view.getUint32(offset + 20, true);
    if (localName !== name || ![0, 8].includes(method) || dataOffset + compressedSize > offset) throw new Error('Excel 文件项目损坏');
    entries.push({ name, size, method, offset: dataOffset, compressedSize, crc: view.getUint32(offset + 16, true) });
    offset += 46 + length + view.getUint16(offset + 30, true) + view.getUint16(offset + 32, true);
  }
  if (!entries.some(e => /^xl\/worksheets\/[^/]+\.xml$/.test(e.name))) throw new Error('Excel 文件中没有工作表');
  return entries;
}
