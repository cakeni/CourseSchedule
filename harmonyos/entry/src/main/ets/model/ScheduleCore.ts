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
}

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
    semester: { name: '当前学期', startDate: mondayOf(new Date(now)).getTime(), totalWeeks: 20 },
    settings: {
      showWeekend: true,
      showTime: true,
      showInactiveCourses: true,
      sectionHeightDp: 72,
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
  if (first.dayOfWeek !== second.dayOfWeek || first.endSection < second.startSection ||
    second.endSection < first.startSection) return false;
  const end = Math.min(first.endWeek, second.endWeek);
  for (let week = Math.max(first.startWeek, second.startWeek); week <= end; week++) {
    if (courseInWeek(first, week) && courseInWeek(second, week)) return true;
  }
  return false;
}

export function validCourse(course: Course, totalWeeks: number): boolean {
  return course.courseName.trim().length > 0 && course.dayOfWeek >= 1 && course.dayOfWeek <= 7 &&
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
  const id = course.id > 0 ? course.id : Math.max(0, ...state.courses.map(item => item.id)) + 1;
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
        sectionHeightDp: typeof raw.sectionHeightDp === 'number' ? raw.sectionHeightDp : 72,
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
