import assert from 'node:assert/strict';
import test from 'node:test';
import {
  courseInWeek, coursesOverlap, dayCells, defaultState, exportJson, importJson, semesterWeek, upsertCourse,
  type Course
} from '../entry/src/main/ets/model/ScheduleCore.ts';

const base = defaultState(new Date(2026, 8, 28).getTime());
const course: Course = {
  id: 0, courseName: '高等数学', teacher: '李老师', classroom: 'A101', dayOfWeek: 1,
  startSection: 1, endSection: 2, startWeek: 1, endWeek: 20, weekType: 1,
  semesterId: 1, colorIndex: 0, note: '', reminderMinutes: -1, createTime: 0
};

test('周次计算按自然日处理，单双周筛选正确', () => {
  assert.equal(semesterWeek(base.semester, new Date(2026, 9, 5).getTime()), 2);
  assert.equal(courseInWeek(course, 1), true);
  assert.equal(courseInWeek(course, 2), false);
});

test('同节次的单双周课程不冲突，重叠周次会拒绝', () => {
  const even: Course = { ...course, weekType: 2, courseName: '英语' };
  assert.equal(coursesOverlap(course, even), false);
  assert.equal(coursesOverlap(course, { ...even, weekType: 0 }), true);
  const saved = upsertCourse(base, course);
  assert.equal(saved.courses[0].id, 1);
  assert.throws(() => upsertCourse(saved, { ...course, courseName: '物理' }), /冲突/);
});

test('未开课的课程不会遮住当周有效课程', () => {
  const inactive: Course = { ...course, id: 1, startSection: 1, endSection: 2, startWeek: 3, weekType: 0 };
  const active: Course = { ...course, id: 2, startSection: 2, endSection: 3, startWeek: 1, weekType: 0 };
  const cells = dayCells({ ...base, courses: [inactive, active] }, 1, 1);
  assert.equal(cells.find(cell => cell.section === 2)?.course?.id, 2);
});

test('Android v2 JSON 备份可导入并往返，损坏课程不会覆盖数据', () => {
  const saved = upsertCourse(base, course);
  const restored = importJson(exportJson(saved), base);
  assert.equal(restored.courses[0].courseName, '高等数学');
  assert.equal(restored.semester.startDate, saved.semester.startDate);
  assert.throws(() => importJson('[{"courseName":"坏数据"}]', saved), /无效/);
  assert.throws(() => importJson('{"schemaVersion":3,"courses":[]}', saved), /更高版本/);
});
