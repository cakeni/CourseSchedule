const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const ts = require(process.env.HARMONY_TYPESCRIPT || 'D:/devco/DevEco Studio/tools/hvigor/hvigor/node_modules/typescript/lib/typescript.js');
require.extensions['.ts'] = (module, filename) => module._compile(ts.transpileModule(fs.readFileSync(filename, 'utf8'), {
  compilerOptions: { target: ts.ScriptTarget.ES2020, module: ts.ModuleKind.CommonJS }
}).outputText, filename);
const model = path.resolve(__dirname, '../entry/src/main/ets/model');
const core = require(path.join(model, 'ScheduleCore.ts'));
const calendar = require(path.join(model, 'CalendarDates.ts'));
for (const [year, month, count, offset] of [[2026, 8, 30, 1], [2026, 1, 28, 6], [2028, 1, 29, 1], [2026, 11, 31, 1], [2027, 0, 31, 4]]) {
  const days = calendar.calendarMonthDays(year, month);
  assert.equal(days.length, 42);
  assert.equal(days.indexOf(1), offset);
  assert.deepEqual(days.filter(day => day > 0), Array.from({ length: count }, (_, index) => index + 1));
}
const originalTZ = process.env.TZ;
try {
  for (const zone of ['Asia/Hong_Kong', 'America/Los_Angeles', 'Pacific/Kiritimati']) {
    process.env.TZ = zone;
    const chosen = new Date('2026-08-31T00:00:00');
    assert.equal(calendar.calendarDateText(chosen), '2026-08-31');
    assert.equal(calendar.calendarDateText(core.mondayOf(new Date('2026-09-06T00:00:00'))), '2026-08-31');
  }
} finally {
  if (originalTZ === undefined) delete process.env.TZ;
  else process.env.TZ = originalTZ;
}
const imports = require(path.join(model, 'ImportCore.ts'));
const schools = require(path.join(model, 'AcademicSchools.ts'));
const academic = require(path.join(model, 'AcademicParser.ts'));
const ai = require(path.join(model, 'AiRecognition.ts'));
const digits = require(path.join(model, 'DateDigitMotion.ts'));
const dateBefore = digits.dateGlyphLayout('9/30', [10, 6, 10, 10]);
const dateAfter = digits.dateGlyphLayout('10/1', [10, 10, 6, 10]);
const rolling = digits.retargetDateGlyphs(dateBefore, dateAfter, 1, 12);
assert.ok(rolling.filter(g => !g.digit).every(g => g.fromY === 0 && g.toY === 0 && g.fromAlpha === 1 && g.toAlpha === 1));
assert.ok(rolling.some(g => g.digit && g.fromY === -12 && g.fromAlpha === 0));
assert.ok(rolling.some(g => g.digit && g.toY === 12 && g.toAlpha === 0));
const reverse = digits.retargetDateGlyphs(rolling, dateBefore, 0.5, -12);
assert.ok(reverse.some(g => g.fromAlpha > 0 && g.fromAlpha < 1));
assert.equal(schools.decodeWebResult(JSON.stringify('{"sourceUrl":"https://jw.example.edu.cn"}')), '{"sourceUrl":"https://jw.example.edu.cn"}');
assert.equal(schools.decodeWebResult('{"error":"table"}'), '{"error":"table"}');
const request = JSON.parse(ai.aiRequest('{"tables":["课程证据"]}', 20, ai.AI_PROVIDERS[1]));
assert.equal(request.store, false); assert.equal(request.text.format.strict, true);
assert.equal(request.text.format.schema.properties.courses.items.properties.weeks.items.maximum, 20);
const aiResult = ai.aiResponse(JSON.stringify({ output: [{ content: [{ type: 'output_text', text: JSON.stringify({ courses: [
  { courseName: '英语', teacher: '教师甲', classroom: 'A101', dayOfWeek: 2, startSection: 3, endSection: 4, weeks: [1, 3, 5, 21] },
  { courseName: '无效', dayOfWeek: 9, startSection: 1, endSection: 2, weeks: [1] }
] }) }] }] }), 20, ai.AI_PROVIDERS[0]);
assert.equal(aiResult.courses.length, 1); assert.equal(aiResult.courses[0].weekType, 1); assert.equal(aiResult.courses[0].endWeek, 5);
assert.throws(() => ai.aiResponse('{"output":[]}', 20, ai.AI_PROVIDERS[0]));
const now = new Date(2026, 8, 28).getTime();
const base = core.defaultState(now);
const math = imports.makeCourse('高等数学', 1, [1, 2], [1, 16], 1);
const saved = core.upsertCourse(base, math);
const spring = core.saveSemester(saved, { name: '春季学期', startDate: new Date(2027, 2, 3).getTime(), totalWeeks: 18 });
assert.equal(spring.courses.length, 0);
assert.equal(spring.archives[0].courses[0].courseName, '高等数学');
const back = core.switchSemester(core.restoreState(core.serializeState(spring)), 1);
assert.equal(back.courses[0].id, saved.courses[0].id);
assert.equal(back.archives[0].semester.name, '春季学期');
assert.throws(() => core.deleteSemester(back, 1), /不能删除/);
assert.equal(core.deleteSemester(back, 2).archives.length, 0);

const csv = imports.parseTextImport('课程名称,教师,教室,星期,节次,周次\n"大学英语,视听说",张老师,A101,周二,3-4,1-16双周', base, 'csv');
assert.equal(csv.courses[0].courseName, '大学英语,视听说');
assert.equal(csv.courses[0].dayOfWeek, 2);
assert.equal(csv.courses[0].weekType, 2);
const html = '<table><tr><th>课程名称</th><th>星期</th><th>节次</th><th>周次</th></tr><tr><td>数据结构</td><td>周三</td><td>5-6</td><td>2-18单周</td></tr></table>';
assert.equal(imports.parseHtmlImport(html, 20).courses[0].startSection, 5);
assert.throws(() => imports.parseHtmlImport('<table><tr><td>课程说明</td></tr></table>', 20), /未找到/);
const grid = imports.parseRows([
  ['节次/星期', '', '星期一', '星期二'],
  ['第1节-第2节', '', '5621003035-操作系统[2001]\n1周,3-5周,7-8周,星期1,第1节-第2节博学楼A508,\n教师：张老师\n\n2515670030-概率统计(Ⅰ)[2004]\n10-17周,星期1,第1节-第2节思学楼A413', '1']
], 20, 'Excel');
assert.deepEqual(grid.courses.map(c => [c.startWeek, c.endWeek]), [[1, 1], [3, 5], [7, 8], [10, 17]]);
assert.equal(grid.courses[0].teacher, '张老师');
assert.equal(grid.courses[0].classroom, '博学楼A508');
const analysis = imports.analyzeImport([math, { ...math, courseName: '冲突课程' }, { ...math, dayOfWeek: 8 }], saved.courses, 1, 20);
assert.equal(analysis.duplicates.length, 1);
assert.equal(analysis.conflicts.length, 1);
assert.equal(analysis.invalid.length, 1);
const backup = imports.parseJsonImport(core.exportJson(saved), spring);
const appended = imports.commitImport(spring, backup, { restoreMetadata: false, replaceExisting: false, includeConflicts: false });
assert.equal(appended.semester.name, '春季学期');
assert.equal(appended.courses[0].semesterId, 2);
assert.equal(appended.archives[0].courses.length, 1);
assert.throws(() => imports.commitImport(saved, backup, { restoreMetadata: false, replaceExisting: false, includeConflicts: false }), /没有选择/);
assert.equal(imports.commitImport(saved, backup, { restoreMetadata: true, replaceExisting: true, includeConflicts: false }).courses.length, 1);
assert.equal(imports.analyzeImport(imports.parseJsonImport('[{"courseName":null}]', base).courses, [], 1, 20).invalid.length, 1);

const shared = imports.xlsxSharedStrings('<sst><si><t>课程名称</t></si><si><r><t>高等</t></r><r><t>数学</t></r></si></sst>');
assert.deepEqual(shared, ['课程名称', '高等数学']);
assert.deepEqual(imports.xlsxRows('<worksheet><sheetData><row><c r="A1" t="s"><v>1</v></c><c r="C1" t="inlineStr"><is><t>A&amp;B</t></is></c><c r="D1"><v>3.0</v></c></row></sheetData></worksheet>', shared), [['高等数学', '', 'A&B', '3']]);
assert.throws(() => imports.validateXlsxZip(new ArrayBuffer(25)), /有效/);

const definitions = JSON.parse(fs.readFileSync(path.resolve(model, '../../resources/rawfile/academic_profiles.json'), 'utf8'));
const catalog = JSON.parse(fs.readFileSync(path.resolve(model, '../../resources/rawfile/academic_school_directory.json'), 'utf8')).entries;
for (const entry of catalog) schools.schoolMatches(entry, 'nanjing');
assert.equal(schools.createSchool({ id:'test-default', name:'测试', profile:'wisedu', url:'https://jw.test.edu.cn', category:'undergraduate', support:'compatible' }, definitions).adapterId, 'wisedu_auto');
const school = schools.createSchool({ id: 'test', name: '测试大学', profile: 'zhengfang', url: 'https://jw.test.edu.cn/', category: 'undergraduate', support: 'compatible', adapterId: 'zhengfang_auto' }, definitions);
assert.equal(schools.allowsTimetable(school, 'https://jw.test.edu.cn/kbcx/table'), true);
assert.equal(schools.allowsTimetable(school, 'https://auth.test.edu.cn/login'), false);
assert.equal(schools.allowsNavigation(school, 'https://auth.test.edu.cn/login'), true);
assert.equal(schools.webAddress('https://jw.test.edu.cn/%2e%2e/private'), null);
assert.equal(schools.webAddress('https://user@jw.test.edu.cn/'), null);
const authSchool = schools.authorizeAuthentication(school, 'https://login.example.com/');
assert.equal(schools.allowsNavigation(authSchool, 'https://login.example.com/sso'), true);
assert.equal(schools.allowsTimetable(authSchool, 'https://login.example.com/sso'), false);
const zf = academic.parseAcademic(school, JSON.stringify({ sourceUrl: school.loginUrl, data: { kbList: [{ kcmc: '高等数学', xqj: '2', jcs: '3-4', zcd: '1-3周,7-9周(单)', xm: '王老师', cdmc: 'A101' }] } }), 20);
assert.deepEqual(zf.courses.map(c => [c.startWeek, c.endWeek, c.weekType]), [[1, 3, 0], [7, 9, 1]]);
assert.throws(() => academic.parseAcademic(school, JSON.stringify({ sourceUrl: 'https://evil.example.com/', html }), 20), /确认/);
assert.throws(() => academic.parseAcademic(school, JSON.stringify({ sourceUrl: school.loginUrl, data: [{ kcmc: '缺少周次', xqj: '1', jcs: '1-2' }] }), 20), /周次/);
assert.throws(() => academic.parseAcademic(school, JSON.stringify({ sourceUrl: school.loginUrl, data: [
  { kcmc: '有效课程', xqj: '1', jcs: '1-2', zcd: '1-8周' }, { xqj: '2', jcs: '3-4', zcd: '1-8周' }
] }), 20), /名称/);
assert.equal(schools.createSchool({ id:'spa', name:'测试', profile:'zhengfang', url:'https://jw.test.edu.cn/?token=discard#/schedule', category:'undergraduate', support:'compatible' }, definitions).loginUrl, 'https://jw.test.edu.cn/#/schedule');
assert.equal(schools.builtinSchools()[0].loginUrl, 'https://deanservices.swpu.edu.cn/jwapp/sys/jwauthapp/login/index.html');
assert.equal(catalog.find(e => e.name === '西南石油大学').url, 'https://deanservices.swpu.edu.cn/');
const overridden = schools.createSchool({ id:'override', name:'测试', profile:'zhengfang', adapterId:'zhengfang_auto', url:'https://old.test.edu.cn/', category:'undergraduate', support:'compatible',
  timetableUrls:['https://old.test.edu.cn/kbcx/'], authenticationUrls:['https://login.example.com/sso'] }, definitions, '  new.test.edu.cn/?token=discard#/schedule  ');
assert.equal(overridden.loginUrl, 'https://new.test.edu.cn/#/schedule');
assert.equal(overridden.adapterId, 'zhengfang_auto');
assert.equal(schools.allowsTimetable(overridden, 'https://new.test.edu.cn/kbcx/'), true);
assert.equal(schools.allowsTimetable(overridden, 'https://old.test.edu.cn/kbcx/'), false);
assert.equal(schools.allowsNavigation(overridden, 'https://login.example.com/sso'), false);
assert.throws(() => schools.createSchool(catalog.find(e => e.name === '西南石油大学'), definitions, 'file:///table.html'), /有效网址/);
const qiangzhi = { ...school, system: 'QIANGZHI_HTML', adapterId: 'qiangzhi_standard' };
const qz = academic.parseAcademic(qiangzhi, JSON.stringify({ sourceUrl: school.loginUrl, html: '<table id="kbtable"><tr><td>节次</td><td>星期一</td><td>星期二</td></tr><tr><td>第1-2节</td><td><div class="kbcontent">操作系统<br><font title="教师">张老师</font><br><font title="周次">1-8周[1-2节]</font><br><font title="教室">A101</font></div></td><td></td></tr></table>' }), 20);
assert.equal(qz.courses[0].courseName, '操作系统');
assert.equal(qz.courses[0].teacher, '张老师');
assert.equal(qz.courses[0].classroom, 'A101');
const qzTable = content => '<table id="kbtable"><tr><th>节次</th><td>星期一</td><td>星期二</td></tr><tr><td>第1-2节</td><td>' + content + '</td><td></td></tr></table>';
const qzTitled = time => '<div class="kbcontent">实验课<br><font title="教师">教师甲</font><br><font title="周次">' + time + '</font><br><font title="教室">A101</font></div>';
assert.throws(() => academic.parseAcademic(school, JSON.stringify({ sourceUrl: school.loginUrl, data: { kbList: [{ kcmc:'新版课程', xqj:'1', jcs:'1-2', zcd:'1-8周' }] }, html:qzTable(qzTitled('1-8周[1-2节]')).replace('kbtable','Table1') }), 20), /不同结果/);
const mixed = academic.academicHtml(qzTable(qzTitled('1-8(单),10-16(双)(周)[01-02节]')), 20, 'QIANGZHI_HTML', 'qiangzhi_auto');
assert.deepEqual(Array.from({length:20}, (_,i)=>i+1).filter(w=>mixed.some(c=>core.courseInWeek(c,w))), [1,3,5,7,10,12,14,16]);
const oldQz = academic.academicHtml(qzTable('<div>高等数学<br>教师甲<br>1-4,7-8周[1-2节]<br>A101<br>教师乙<br>9-12周[5-6节]<br>B202</div>'), 20, 'QIANGZHI_HTML');
assert.equal(oldQz.slice(-1)[0].teacher, '教师乙'); assert.equal(oldQz.slice(-1)[0].classroom, 'B202');
assert.throws(()=>academic.academicHtml(qzTable(qzTitled('1-8周[1-2节]')+qzTitled('[1-2节]')), 20, 'QIANGZHI_HTML'));
const qz2024 = '<table><tr><th>节次</th><td>星期一</td><td>星期二</td></tr><tr><td>第1-2节</td><td name="kbDataTd"><div class="qz-toolitiplists"><div class="qz-tooltipContent-title">新版课程</div><div>老师：教师甲</div><div>教室：A101</div><div class="qz-tooltipContent-detailitem">时间：1-8周[1-2节]</div></div></td><td></td></tr></table>';
assert.equal(academic.academicHtml(qz2024,20,'QIANGZHI_HTML','qiangzhi_auto')[0].courseName,'新版课程');
for (let bitmap=1; bitmap<1024; bitmap++) {
  const weeks = Array.from({length:10},(_,i)=>i+1).filter(w=>bitmap&(1<<(w-1)));
  const restored = academic.weekRanges(weeks).flatMap(([start,end,type])=>Array.from({length:end-start+1},(_,i)=>i+start).filter(w=>type===0||w%2===(type===1?1:0)));
  assert.deepEqual(restored,weeks);
}
const active = {...math,startWeek:3,endWeek:3,weekType:0};
const sooner = {...active,courseName:'后续课程',startWeek:5,endWeek:8};
assert.equal(core.selectCoursesForWeek([sooner,active],2,true)[0].courseName,math.courseName);
assert.equal(core.selectCoursesForWeek([sooner,active],2,false).length,0);
const eams = { ...school, system: 'EAMS', adapterId: 'eams_table0' };
const cells = Array.from({ length: 14 }, () => []);
cells[0] = [{ courseName: '程序设计', teacherName: '李老师', roomName: '机房', vaildWeeks: '01010' }];
cells[1] = cells[0];
const ea = academic.parseAcademic(eams, JSON.stringify({ sourceUrl: school.loginUrl, data: { unitCount: 2, activities: cells } }), 20);
assert.equal(ea.courses[0].startWeek, 1); assert.equal(ea.courses[0].endWeek, 3); assert.equal(ea.courses[0].weekType, 1);
assert.equal(ea.courses[0].endSection, 2);
const reminders = { ...saved, courses: [{ ...saved.courses[0], reminderMinutes: 15 }] };
const times = ['08:00', '08:50', '09:50', '10:40', '11:30', '14:30', '15:20', '16:20', '17:10', '19:00', '19:50', '20:40'];
assert.equal(core.reminderOccurrences(reminders, times, now)[0].reminderTime, new Date(2026, 8, 28, 7, 45).getTime());
assert.equal(core.reminderOccurrences(reminders, times, new Date(2026, 8, 28, 7, 55).getTime())[0].reminderTime, new Date(2026, 8, 28, 7, 55, 1).getTime());
assert.equal(core.reminderOccurrences(reminders, times, new Date(2026, 8, 28, 8, 0).getTime())[0].classStart, new Date(2026, 9, 12, 8, 0).getTime());
const fixtures = require('./ReportFixtures.json');
const reports = [
  ['gdeiNestedGridReadsCourseBlockFields', 'gdei_nested_grid', '离散数学', 1, 3, 4, '赵老师', 'A201'],
  ['xhtdBlocksUseDivCoordinatesAndFiveLineRecords', 'xhtd_block_grid', '编译原理', 3, 3, 4, '陈老师', 'A301'],
  ['uestcPostGridReadsSlashDelimitedFields', 'uestc_post_grid', '高等代数', 1, 3, 4, '张老师', 'A101'],
  ['hitPrintGridUsesRowSectionsAndWeekMarkers', 'hit_print_grid', '高等数学', 1, 1, 2, '张老师', 'A101'],
  ['hitszCardGridReadsBracketMetadataByDayColumn', 'hitsz_card_grid', '数据结构', 1, 1, 2, '王老师', 'A101'],
  ['scauPrintGridUsesColumnDaysAndTwoSectionsPerRow', 'scau_print_grid', '大学英语', 2, 1, 2, '李老师', 'B202'],
  ['xjuPostGridSupportsBraceBlocksAndLegacyParenthesisBlocks', 'xju_post_grid', '大学英语', 1, 1, 2, '李老师', 'B202'],
  ['cuplPostGridSplitsBoldCourseBlocks', 'cupl_post_grid', '刑法学', 1, 1, 2, '李老师', '端升102'],
  ['sudaPostGridUsesKnownRowAndRowspanSectionSemantics', 'suda_post_grid', '编译原理', 1, 1, 2],
  ['zjuPostGridUsesLeafCourseBlocksAndRowspanSections', 'zju_post_grid', '数据结构', 1, 1, 2],
  ['southSoftGridRespectsRowspanAndRequiresExplicitWeeks', 'south_soft', '操作系统', 1, 1, 4, '教师乙', 'C303'],
  ['kingosoftReportHandlesMergedFieldsAndMultipleTimeRows', 'kingosoft_new', '大学物理', 3, 5, 6, '教师甲', 'B202']
];
for (const [fixture, adapter, name, day, start, end, teacher, room] of reports) {
  const courses = academic.academicHtml(fixtures[fixture], 20, 'REPORT_HTML', adapter);
  const c = courses.find(c => c.courseName === name && c.dayOfWeek === day);
  assert.ok(c, fixture + ' 课程缺失');
  assert.deepEqual([c.startSection, c.endSection], [start, end], fixture);
  if (teacher) assert.equal(c.teacher, teacher, fixture);
  if (room) assert.equal(c.classroom, room, fixture);
}
assert.throws(() => academic.academicHtml(fixtures.southSoftGridRespectsRowspanAndRequiresExplicitWeeks.replace('1-8周', '时间待定'), 20, 'REPORT_HTML', 'south_soft'));
// Separate parses stamp their courses at different times; compare the course content.
const courseContent = courses => courses.map(({ createTime, ...course }) => course);
assert.deepEqual(courseContent(academic.academicHtml(fixtures.southSoftGridRespectsRowspanAndRequiresExplicitWeeks + qzTable(qzTitled('1-8周[1-2节]')).replace('kbtable','unrelated'), 20, 'REPORT_HTML', 'south_soft')),
  courseContent(academic.academicHtml(fixtures.southSoftGridRespectsRowspanAndRequiresExplicitWeeks, 20, 'REPORT_HTML', 'south_soft')));
console.log('完整移植核心校验通过：多学期、追加/替换、冲突检查、文件导入、教务域名、正方/强智/EAMS、12种原版报表样例、单双周提醒');
