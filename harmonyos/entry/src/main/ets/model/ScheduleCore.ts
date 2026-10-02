export interface Course {
  id: number;
  courseName: string;
  teacher: string;
  classroom: string;
  dayOfWeek: number;
  startSection: number;
  endSection: number;
  startWeek: number;
  endWeek: number;
  weekType: number;
  semesterId: number;
  colorIndex: number;
  note: string;
  reminderMinutes: number;
  createTime: number;
}

export interface Semester {
  id?: number;
  createTime?: number;
  name: string;
  startDate: number;
  totalWeeks: number;
}

export interface ScheduleSettings {
  showWeekend: boolean;
  showTime: boolean;
  showInactiveCourses: boolean;
  sectionHeightDp: number;
  reminderEnabled: boolean;
  defaultReminderMinutes: number;
  sectionTimes: string[];
  sectionEndTimes: string[];
}

export interface ScheduleState {
  semester: Semester;
  settings: ScheduleSettings;
  courses: Course[];
  archives?: SemesterArchive[];
  // Conversation state is saved with courses, but excluded from exported timetable backups.
  assistantChats?: string;
}

export interface SemesterArchive { semester: Semester; courses: Course[] }

export interface ScheduleCell {
  section: number;
  span: number;
  course: Course | null;
  active: boolean;
}

const DAY_MS = 24 * 60 * 60 * 1000;

function calendarDay(timestamp: number): number {
  const date = new Date(timestamp);
  return Math.floor(Date.UTC(date.getFullYear(), date.getMonth(), date.getDate()) / DAY_MS);
}

export function mondayOf(date: Date): Date {
  const monday = new Date(date.getFullYear(), date.getMonth(), date.getDate());
  monday.setDate(monday.getDate() - ((monday.getDay() + 6) % 7));
  return monday;
}

export function defaultState(now: number = Date.now()): ScheduleState {
  return {
    semester: { id: 1, createTime: now, name: '当前学期', startDate: mondayOf(new Date(now)).getTime(), totalWeeks: 20 },
    settings: {
      showWeekend: true,
      showTime: true,
      showInactiveCourses: true,
      sectionHeightDp: 64,
      reminderEnabled: true,
      defaultReminderMinutes: 15,
      sectionTimes: [],
      sectionEndTimes: []
    },
    courses: []
  };
}

export function semesterWeek(semester: Semester, now: number = Date.now()): number {
  const rawWeek = Math.floor((calendarDay(now) - calendarDay(mondayOf(new Date(semester.startDate)).getTime())) / 7) + 1;
  return Math.max(1, Math.min(semester.totalWeeks, rawWeek));
}

export function weekStart(semester: Semester, week: number): Date {
  const date = mondayOf(new Date(semester.startDate));
  date.setDate(date.getDate() + (week - 1) * 7);
  return date;
}

export function courseInWeek(course: Course, week: number): boolean {
  if (week < course.startWeek || week > course.endWeek) return false;
  return course.weekType === 0 || (course.weekType === 1 && week % 2 === 1) ||
    (course.weekType === 2 && week % 2 === 0);
}

export function coursesOverlap(first: Course, second: Course): boolean {
  if (first.semesterId !== second.semesterId) return false;
  if (first.dayOfWeek !== second.dayOfWeek || first.endSection < second.startSection ||
    second.endSection < first.startSection) return false;
  const end = Math.min(first.endWeek, second.endWeek);
  for (let week = Math.max(first.startWeek, second.startWeek); week <= end; week++) {
    if (courseInWeek(first, week) && courseInWeek(second, week)) return true;
  }
  return false;
}

export function validCourse(course: Course, totalWeeks: number): boolean {
  return typeof course.courseName === 'string' && course.courseName.trim().length > 0 &&
    typeof course.teacher === 'string' && typeof course.classroom === 'string' && typeof course.note === 'string' &&
    course.dayOfWeek >= 1 && course.dayOfWeek <= 7 &&
    Number.isInteger(course.dayOfWeek) && Number.isInteger(course.startSection) &&
    Number.isInteger(course.endSection) && course.startSection >= 1 && course.endSection <= 12 &&
    course.startSection <= course.endSection && Number.isInteger(course.startWeek) &&
    Number.isInteger(course.endWeek) && course.startWeek >= 1 && course.endWeek <= totalWeeks &&
    course.startWeek <= course.endWeek && Number.isInteger(course.weekType) &&
    course.weekType >= 0 && course.weekType <= 2;
}

export function upsertCourse(state: ScheduleState, input: Course): ScheduleState {
  const course: Course = {
    ...input,
    courseName: input.courseName.trim(),
    teacher: input.teacher.trim(),
    classroom: input.classroom.trim(),
    note: input.note.trim()
  };
  if (!validCourse(course, state.semester.totalWeeks)) throw new Error('课程名称、星期、节次或周次无效');
  if (state.courses.some(existing => existing.id !== course.id && coursesOverlap(existing, course))) {
    throw new Error('课程时间与已有课程冲突');
  }
  const id = course.id > 0 ? course.id : nextCourseId(state);
  const saved: Course = { ...course, id };
  return { ...state, courses: [...state.courses.filter(item => item.id !== id), saved] };
}

export function removeCourse(state: ScheduleState, id: number): ScheduleState {
  return { ...state, courses: state.courses.filter(item => item.id !== id) };
}

export function dayCells(state: ScheduleState, week: number, day: number): ScheduleCell[] {
  const candidates = state.courses.filter(course => course.dayOfWeek === day);
  const activeCourses = candidates.filter(course => courseInWeek(course, week));
  const cells: ScheduleCell[] = [];
  let section = 1;
  while (section <= 12) {
    const starts = candidates.filter(course => course.startSection === section);
    const active = starts.find(course => courseInWeek(course, week));
    const upcoming = starts.find(course => {
      if (activeCourses.some(activeCourse => activeCourse.startSection <= course.endSection &&
        course.startSection <= activeCourse.endSection)) return false;
      for (let future = Math.max(week, course.startWeek); future <= course.endWeek; future++) {
        if (courseInWeek(course, future)) return true;
      }
      return false;
    });
    const selected = active ?? (state.settings.showInactiveCourses ? upcoming : undefined);
    if (selected) {
      const span = selected.endSection - selected.startSection + 1;
      cells.push({ section, span, course: selected, active: courseInWeek(selected, week) });
      section += span;
    } else {
      cells.push({ section, span: 1, course: null, active: false });
      section++;
    }
  }
  return cells;
}

export function selectCoursesForWeek(courses: Course[], week: number, showInactive: boolean): Course[] {
  const groups = new Map<string, Course[]>();
  for (const course of courses) {
    const key = `${course.dayOfWeek}:${course.startSection}:${course.endSection}`;
    if (!groups.has(key)) groups.set(key, []); groups.get(key)!.push(course);
  }
  const result: Course[] = [];
  for (const sameSlot of groups.values()) {
    let chosen: Course | undefined; let closest = Infinity;
    for (const course of sameSlot) {
      for (let next = Math.max(week, course.startWeek); next <= course.endWeek; next++) {
        if (courseInWeek(course, next)) {
          if ((showInactive || next === week) && next < closest) { chosen = course; closest = next; } break;
        }
      }
    }
    if (chosen) result.push(chosen);
  }
  return result;
}

function object(value: unknown): Record<string, unknown> {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) {
    throw new Error('备份文件结构无效');
  }
  return value as Record<string, unknown>;
}

function number(value: unknown, name: string): number {
  if (typeof value !== 'number' || !Number.isFinite(value)) throw new Error(`${name} 无效`);
  return value;
}

function string(value: unknown, fallback: string = ''): string {
  return typeof value === 'string' ? value : fallback;
}

function importedCourse(value: unknown, index: number, totalWeeks: number): Course {
  const raw = object(value);
  const course: Course = {
    id: index + 1,
    courseName: string(raw.courseName),
    teacher: string(raw.teacher),
    classroom: string(raw.classroom),
    dayOfWeek: number(raw.dayOfWeek, '星期'),
    startSection: number(raw.startSection, '开始节次'),
    endSection: number(raw.endSection, '结束节次'),
    startWeek: number(raw.startWeek, '开始周'),
    endWeek: number(raw.endWeek, '结束周'),
    weekType: raw.weekType === undefined ? 0 : number(raw.weekType, '单双周'),
    semesterId: 1,
    colorIndex: typeof raw.colorIndex === 'number' ? raw.colorIndex : 0,
    note: string(raw.note),
    reminderMinutes: typeof raw.reminderMinutes === 'number' ? raw.reminderMinutes : -1,
    createTime: typeof raw.createTime === 'number' ? raw.createTime : Date.now()
  };
  if (!validCourse(course, totalWeeks)) throw new Error(`第 ${index + 1} 门课程内容无效`);
  return course;
}

export function importJson(text: string, current: ScheduleState): ScheduleState {
  let parsed: unknown;
  try { parsed = JSON.parse(text); } catch (_) { throw new Error('JSON 文件格式不正确'); }
  let semester = current.semester;
  let settings = current.settings;
  let rawCourses: unknown;
  if (Array.isArray(parsed)) {
    rawCourses = parsed;
  } else {
    const root = object(parsed);
    if (root.schemaVersion !== undefined && number(root.schemaVersion, '备份版本') > 2) {
      throw new Error('该备份来自更高版本的应用');
    }
    const rawSemester = object(root.semester);
    semester = {
      name: string(rawSemester.name),
      startDate: number(rawSemester.startDate, '开学日期'),
      totalWeeks: number(rawSemester.totalWeeks, '总周数')
    };
    if (!semester.name || !Number.isInteger(semester.totalWeeks) || semester.totalWeeks < 1 ||
      semester.totalWeeks > 52) throw new Error('学期设置无效');
    if (root.settings !== undefined) {
      const raw = object(root.settings);
      settings = {
        showWeekend: typeof raw.showWeekend === 'boolean' ? raw.showWeekend : true,
        showTime: typeof raw.showTime === 'boolean' ? raw.showTime : true,
        showInactiveCourses: typeof raw.showInactiveCourses === 'boolean' ? raw.showInactiveCourses : true,
        sectionHeightDp: typeof raw.sectionHeightDp === 'number' ? raw.sectionHeightDp : 64,
        reminderEnabled: typeof raw.reminderEnabled === 'boolean' ? raw.reminderEnabled : true,
        defaultReminderMinutes: typeof raw.defaultReminderMinutes === 'number' ? raw.defaultReminderMinutes : 15,
        sectionTimes: Array.isArray(raw.sectionTimes) && raw.sectionTimes.every(item => typeof item === 'string') ? raw.sectionTimes as string[] : [],
        sectionEndTimes: Array.isArray(raw.sectionEndTimes) && raw.sectionEndTimes.every(item => typeof item === 'string') ? raw.sectionEndTimes as string[] : []
      };
    }
    rawCourses = root.courses;
  }
  if (!Array.isArray(rawCourses)) throw new Error('JSON 中没有课程列表');
  if (rawCourses.length > 10000) throw new Error('课程数量过多');
  const courses = rawCourses.map((item, index) => importedCourse(item, index, semester.totalWeeks));
  return { semester, settings, courses };
}

export function exportJson(state: ScheduleState): string {
  return JSON.stringify({
    schemaVersion: 2,
    exportedAt: Date.now(),
    semester: state.semester,
    settings: state.settings,
    courses: state.courses
  }, null, 2);
}

export function currentSemesterId(state: ScheduleState): number { return state.semester.id ?? 1; }

export function nextCourseId(state: ScheduleState): number {
  return Math.max(0, ...state.courses.map(c => c.id),
    ...(state.archives ?? []).flatMap(a => a.courses.map(c => c.id))) + 1;
}

// Local storage retains all semesters and stable IDs; Android v2 export contains the current semester.
export function serializeState(state: ScheduleState): string {
  return JSON.stringify({ localVersion: 1, state });
}

export function restoreState(text: string): ScheduleState {
  const raw = JSON.parse(text);
  if (raw.localVersion !== 1) return importJson(text, defaultState());
  const state = raw.state as ScheduleState;
  const validate = (semester: Semester, courses: Course[]) => {
    if (!semester || !Number.isFinite(semester.startDate) || !Number.isInteger(semester.totalWeeks) ||
      semester.totalWeeks < 1 || semester.totalWeeks > 52 || !Array.isArray(courses)) throw new Error('本地学期数据无效');
    // Reducing a semester length in Android preserves courses beyond its last week.
    if (courses.some(c => !validCourse(c, 52) || !Number.isInteger(c.id) || c.id <= 0)) throw new Error('本地课程数据无效');
  };
  validate(state.semester, state.courses);
  for (const archive of state.archives ?? []) validate(archive.semester, archive.courses);
  if (!state.settings) throw new Error('本地显示设置无效');
  return state;
}

export function semesterList(state: ScheduleState): Semester[] {
  return [state.semester, ...(state.archives ?? []).map(a => a.semester)]
    .sort((a, b) => (b.createTime ?? 0) - (a.createTime ?? 0));
}

export function switchSemester(state: ScheduleState, id: number): ScheduleState {
  if (id === currentSemesterId(state)) return state;
  const selected = (state.archives ?? []).find(a => a.semester.id === id);
  if (!selected) throw new Error('未找到该学期');
  return { ...state, semester: selected.semester, courses: selected.courses,
    archives: [...(state.archives ?? []).filter(a => a.semester.id !== id),
      { semester: { ...state.semester, id: currentSemesterId(state) }, courses: state.courses }] };
}

export function saveSemester(state: ScheduleState, semester: Semester, id: number = 0): ScheduleState {
  if (!semester.name.trim() || semester.totalWeeks < 1 || semester.totalWeeks > 52) throw new Error('学期设置无效');
  const saved = { ...semester, name: semester.name.trim(), startDate: mondayOf(new Date(semester.startDate)).getTime() };
  if (!id) {
    saved.id = Math.max(currentSemesterId(state), ...(state.archives ?? []).map(a => a.semester.id ?? 0)) + 1;
    saved.createTime = Date.now();
    return { ...state, semester: saved, courses: [], archives: [...(state.archives ?? []),
      { semester: { ...state.semester, id: currentSemesterId(state) }, courses: state.courses }] };
  }
  saved.id = id;
  saved.createTime = semesterList(state).find(s => s.id === id)?.createTime ?? Date.now();
  if (id === currentSemesterId(state)) return { ...state, semester: saved };
  return { ...state, archives: (state.archives ?? []).map(a => a.semester.id === id ? { ...a, semester: saved } : a) };
}

export function deleteSemester(state: ScheduleState, id: number): ScheduleState {
  if (id === currentSemesterId(state)) throw new Error('当前学期不能删除');
  return { ...state, archives: (state.archives ?? []).filter(a => a.semester.id !== id) };
}

export function validSectionTimes(starts: string[], ends: string[]): boolean {
  const minutes = (text: string): number => {
    if (!/^([01]\d|2[0-3]):[0-5]\d$/.test(text)) return -1;
    return Number(text.slice(0, 2)) * 60 + Number(text.slice(3));
  };
  return starts.length === 12 && ends.length === 12 && starts.every((start, i) =>
    minutes(start) >= 0 && minutes(ends[i]) > minutes(start) && (i === 0 || minutes(start) >= minutes(ends[i - 1])));
}

export interface CourseOccurrence { course: Course; classStart: number; reminderTime: number }

export function reminderOccurrences(state: ScheduleState, starts: string[], now: number = Date.now()): CourseOccurrence[] {
  if (!state.settings.reminderEnabled) return [];
  const result: CourseOccurrence[] = [];
  for (const course of state.courses) {
    if (course.reminderMinutes <= 0) continue;
    const time = starts[course.startSection - 1];
    if (!time || !/^([01]\d|2[0-3]):[0-5]\d$/.test(time)) continue;
    for (let week = course.startWeek; week <= Math.min(course.endWeek, state.semester.totalWeeks); week++) {
      if (!courseInWeek(course, week)) continue;
      const date = weekStart(state.semester, week);
      date.setDate(date.getDate() + course.dayOfWeek - 1);
      date.setHours(Number(time.slice(0, 2)), Number(time.slice(3)), 0, 0);
      if (date.getTime() > now) result.push({ course, classStart: date.getTime(),
        reminderTime: Math.max(now + 1000, date.getTime() - course.reminderMinutes * 60000) });
    }
  }
  return result.sort((a, b) => a.reminderTime - b.reminderTime);
}
