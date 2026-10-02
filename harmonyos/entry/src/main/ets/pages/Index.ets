import { common, ConfigurationConstant } from '@kit.AbilityKit';
import { window, curves } from '@kit.ArkUI';
import { pasteboard } from '@kit.BasicServicesKit';
import { util } from '@kit.ArkTS';
import { i18n } from '@kit.LocalizationKit';
import { AcademicImport } from './AcademicImport';
import { CourseAssistant } from './CourseAssistant';
import { DUCK_FRAMES } from './DuckFrames';
import { DateHeader } from './DateHeader';
import { NavigationItem } from './NavigationItem';
import { ProjectNotice } from './ProjectNotice';
import { SemesterCalendar } from './SemesterCalendar';
import { calendarDateText } from '../model/CalendarDates';
import { SchoolEntry, AcademicSchool, AcademicDefinitions, builtinSchools, createSchool, schoolMatches, webAddress } from '../model/AcademicSchools';
import { syncReminders, authorizeNotifications, testReminder, reminderError } from '../data/CourseReminders';
import { BusinessError } from '@kit.BasicServicesKit';
import { pickImport, saveBackup } from '../data/BackupFiles';
import { ParsedImport, ImportAnalysis, emptyImport, parseTextImport, analyzeImport, commitImport } from '../model/ImportCore';
import { loadState, saveState, loadDarkMode, saveDarkMode } from '../data/ScheduleStore';
import { LIGHT, DARK, SchedulePalette, DAY_NAMES, SECTIONS, COLOR_INDEXES, START_TIMES, END_TIMES } from './ScheduleTheme';
import {
  Course, Semester, ScheduleSettings, ScheduleCell, ScheduleState, dayCells, defaultState, exportJson, importJson,
  mondayOf, removeCourse, semesterWeek, upsertCourse, weekStart, courseInWeek,
  currentSemesterId, semesterList, switchSemester, saveSemester, deleteSemester, validSectionTimes, selectCoursesForWeek
} from '../model/ScheduleCore';

const DAYS: string[] = ['周一', '周二', '周三', '周四', '周五', '周六', '周日'];
const REMINDER_VALUES: number[] = [-1, 5, 10, 15, 30, 60];
const REMINDER_LABELS: string[] = ['不提醒', '5分钟前', '10分钟前', '15分钟前', '30分钟前', '1小时前'];
function overshoot(tension: number): curves.ICurve {
  return curves.customCurve((fraction: number) => { const t = fraction - 1; return t * t * ((tension + 1) * t + tension) + 1; });
}
interface SchoolDirectoryFile { entries: SchoolEntry[] }

@Entry
@Component
struct Index {
  @State private state: ScheduleState = defaultState();
  @State private week: number = 1;
  @State private screen: string = 'schedule';
  @State private message: string = '';
  @State private ready: boolean = false;
  @State private dark: boolean = false;
  @State private weekPicker: boolean = false;
  @State private semesterPicker: boolean = false;
  @State private semesterDatePicker: boolean = false;
  @State private quickDay: number = 0;
  @State private quickStart: number = 1;
  @State private quickEnd: number = 1;
  @State private quickDragging: boolean = false;
  private quickAnchor: number = 1;
  @State private pressedCourseId: number = 0;
  @State private duckPressed: boolean = false;
  @State private pressedAction: string = '';
  @State private entrance: number = 1;
  @State private pageEntrance: number = 1;
  @State private entranceAnimating: boolean = false;
  @State private emptyEntrance: number = 1;
  @State private dateForward: boolean = true;
  private entranceGeneration: number = 0;
  private readonly pressCurve = curves.cubicBezierCurve(0.22, 1, 0.36, 1);
  private readonly importCurve = curves.cubicBezierCurve(0.2, 0.85, 0.25, 1);
  private readonly headerCurve = curves.cubicBezierCurve(0.2, 0.8, 0.2, 1);
  @State private weekdayOffset: number = 0;
  private weekScrolls: Map<number, number> = new Map();
  private schoolSearch: Map<string, string> = new Map();
  private gridScroller: Scroller = new Scroller();
  private progressWidth: number = 1;
  private returnScreen: string = 'schedule';
  private importOrigin: string = 'backup';
  @State private pendingImport: ParsedImport = emptyImport();
  @State private importAnalysis: ImportAnalysis = { accepted: [], conflicts: [], duplicates: [], invalid: [] };
  @State private includeConflicts: boolean = false;
  @State private restoreMetadata: boolean = false;
  @State private replaceExisting: boolean = false;
  @State private importText: string = '';
  @State private undoState: ScheduleState | null = null;
  @State private schoolEntries: SchoolEntry[] = [];
  @State private schoolDefinitions: AcademicDefinitions = { profiles: [], specs: [] };
  @State private schoolQuery: string = '';
  @State private schoolCategory: string = '';
  @State private schoolVisibleCount: number = 80;
  @State private configuredEntry: SchoolEntry | null = null;
  @State private configuredAddress: string = '';
  @State private selectedSchool: AcademicSchool = builtinSchools()[0];
  @State private detailCourse: Course | null = null;
  @State private formReminder: number = 15;
  @State private reminderStatus: string = '';
  @State private timeStarts: string[] = START_TIMES.slice();
  @State private timeEnds: string[] = END_TIMES.slice();
  private semesterEditingId: number = 0;
  private academicBack: () => boolean = () => false;
  private assistantBack: () => boolean = () => false;
  private assistantReturn: string = 'schedule';

  private openAssistant(origin: string): void {
    this.assistantReturn = origin; this.message = ''; this.navigate('assistant');
  }
  private async closeAssistant(target: string): Promise<void> {
    await this.load(); this.navigate(target);
  }

  @State private editingId: number = 0;
  @State private formName: string = '';
  @State private formTeacher: string = '';
  @State private formRoom: string = '';
  @State private formDay: number = 1;
  @State private formStartSection: string = '1';
  @State private formEndSection: string = '2';
  @State private formStartWeek: string = '1';
  @State private formEndWeek: string = '20';
  @State private formWeekType: number = 0;
  @State private formNote: string = '';
  @State private formColor: number = 0;
  @State private focusedField: string = '';

  @State private semesterName: string = '';
  @State private semesterDate: string = '';
  @State private semesterWeeks: string = '20';

  aboutToAppear(): void {
    this.load();
  }

  private abilityContext(): common.UIAbilityContext {
    return this.getUIContext().getHostContext() as common.UIAbilityContext;
  }

  private navigate(target: string): void {
    if (this.screen === target) return;
    const entering = !this.modal() && ['backup', 'settings', 'schedule'].includes(target);
    const generation = ++this.entranceGeneration;
    if (entering) { this.entranceAnimating = true; this.pageEntrance = 0; this.entrance = 0; this.emptyEntrance = 0; }
    if (entering) this.screen = target;
    else this.getUIContext().animateTo({ duration: 300, curve: this.pressCurve, expectedFrameRateRange: { min: 30, max: 120, expected: 60 } }, () => this.screen = target);
    if (entering) setTimeout(() => {
      if (generation !== this.entranceGeneration) return;
      this.pageEntrance = 1; this.emptyEntrance = 1;
      this.getUIContext().animateTo({ duration: 420, curve: this.pressCurve }, () => this.entrance = 1);
      setTimeout(() => { if (generation === this.entranceGeneration) this.entranceAnimating = false; }, 1100);
    }, 32);
  }

  private closeDetails(): void {
    this.getUIContext().animateTo({ duration: 220, curve: this.headerCurve }, () => this.detailCourse = null);
  }

  private setWeekPicker(value: boolean): void {
    this.getUIContext().animateTo({ duration: 260, curve: this.pressCurve }, () => this.weekPicker = value);
  }

  private setSemesterPicker(value: boolean): void {
    this.getUIContext().animateTo({ duration: 260, curve: this.pressCurve }, () => this.semesterPicker = value);
  }

  private entryAnimation(index: number, settings: boolean = false): AnimateParam {
    return { duration: settings ? 620 : 540, delay: index * (settings ? 56 : 50), curve: settings ? this.pressCurve : this.importCurve,
      expectedFrameRateRange: { min: 30, max: 120, expected: 60 } };
  }


  private courseDisplayName(name: string): string {
    return name.replace(/^\s*\d{6,}\s*-\s*/, '').replace(/\s*\[[^\]]+\]\s*$/, '').trim() || name;
  }

  private async load(): Promise<void> {
    try {
      const loaded = await loadState(this.abilityContext());
      this.state = loaded;
      this.week = semesterWeek(loaded.semester);
      this.dark = await loadDarkMode(this.abilityContext());
      this.updateSystemBars();
      this.ready = true;
      this.reminderStatus = await syncReminders(this.abilityContext(), loaded);
    } catch (error) {
      this.message = '本地课表读取失败；可从备份恢复：' + String(error);
      this.ready = false;
    }
  }

  private async persist(next: ScheduleState): Promise<boolean> {
    try {
      if (next.archives === undefined) next.archives = this.state.archives;
      if (next.semester.id === undefined) next.semester.id = currentSemesterId(this.state);
      await saveState(this.abilityContext(), next);
      this.state = next;
      this.ready = true;
      syncReminders(this.abilityContext(), next).then((status: string) => this.reminderStatus = status);
      return true;
    } catch (error) {
      this.message = '保存失败：' + String(error);
      return false;
    }
  }

  private toast(message: string): void {
    this.message = '';
    this.getUIContext().getPromptAction().showToast({ message });
  }

  private days(): number[] {
    return this.state.settings.showWeekend ? [1, 2, 3, 4, 5, 6, 7] : [1, 2, 3, 4, 5];
  }

  private openCourse(course: Course | null, day: number = 0, section: number = 1, endSection: number = 0): void {
    if (!this.ready) return;
    this.quickDay = 0;
    this.editingId = course ? course.id : 0;
    this.formName = course ? course.courseName : '';
    this.formTeacher = course ? course.teacher : '';
    this.formRoom = course ? course.classroom : '';
    this.formDay = course ? course.dayOfWeek : day || (new Date().getDay() + 6) % 7 + 1;
    this.formStartSection = String(course ? course.startSection : section);
    this.formEndSection = String(course ? course.endSection : endSection || Math.min(12, section + 1));
    this.formStartWeek = String(course ? course.startWeek : day ? this.week : 1);
    this.formEndWeek = String(course ? course.endWeek : day ? this.week : Math.min(16, this.state.semester.totalWeeks));
    this.formWeekType = course ? course.weekType : 0;
    this.formNote = course ? course.note : '';
    this.formColor = course ? course.colorIndex : 0;
    this.formReminder = course ? course.reminderMinutes : this.state.settings.defaultReminderMinutes;
    this.detailCourse = null;
    this.message = '';
    this.navigate('course');
  }

  private async saveCourseForm(): Promise<void> {
    const original = this.state.courses.find(course => course.id === this.editingId);
    const course: Course = {
      id: this.editingId,
      courseName: this.formName,
      teacher: this.formTeacher,
      classroom: this.formRoom,
      dayOfWeek: this.formDay,
      startSection: Number(this.formStartSection),
      endSection: Number(this.formEndSection),
      startWeek: Number(this.formStartWeek),
      endWeek: Number(this.formEndWeek),
      weekType: this.formWeekType,
      semesterId: currentSemesterId(this.state),
      colorIndex: this.formColor,
      note: this.formNote,
      reminderMinutes: this.formReminder,
      createTime: original ? original.createTime : Date.now()
    };
    try {
      const next = upsertCourse(this.state, course);
      if (await this.persist(next)) {
        this.toast('课程已保存');
        this.navigate('schedule');
      }
    } catch (error) {
      this.message = String(error);
    }
  }

  private async deleteCourse(): Promise<void> {
    if (await this.persist(removeCourse(this.state, this.editingId))) {
      this.toast('课程已删除');
      this.navigate('schedule');
    }
  }

  private openSemester(existing: Semester | null = this.state.semester): void {
    this.semesterEditingId = existing ? existing.id || currentSemesterId(this.state) : 0;
    const date = new Date(existing ? existing.startDate : mondayOf(new Date()).getTime());
    this.semesterName = existing ? existing.name : '';
    this.semesterDate = calendarDateText(date);
    this.semesterWeeks = String(existing ? existing.totalWeeks : 20);
    this.message = '';
    this.returnScreen = this.screen;
    this.navigate('semester');
  }

  private async saveSemesterForm(): Promise<void> {
    const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(this.semesterDate);
    const weeks = Number(this.semesterWeeks);
    if (!match || !Number.isInteger(weeks) || weeks < 1 || weeks > 52 || !this.semesterName.trim()) {
      this.message = '请填写学期名称、YYYY-MM-DD 日期和 1–52 周';
      return;
    }
    const date = new Date(Number(match[1]), Number(match[2]) - 1, Number(match[3]));
    if (date.getFullYear() !== Number(match[1]) || date.getMonth() !== Number(match[2]) - 1 ||
      date.getDate() !== Number(match[3])) {
      this.message = '开学日期无效';
      return;
    }
    const semester: Semester = { name: this.semesterName.trim(), startDate: mondayOf(date).getTime(), totalWeeks: weeks };
    const next = saveSemester(this.state, semester, this.semesterEditingId);
    if (await this.persist(next)) {
      this.week = semesterWeek(next.semester);
      this.toast('学期已保存');
      this.navigate(this.returnScreen);
    }
  }

  private showSemesters(): void {
    this.setSemesterPicker(true);
  }

  private semesterActions(semester: Semester): void {
    const current = semester.id === currentSemesterId(this.state);
    this.getUIContext().getPromptAction().showActionMenu({ title: semester.name, buttons: current ?
      [{ text: '编辑学期', color: this.palette().text }] : [
        { text: '设为当前学期', color: this.palette().text },
        { text: '编辑学期', color: this.palette().text }, { text: '删除学期', color: '#BA5252' }
      ] }).then((result) => {
      if (current || result.index === 1) this.openSemester(semester);
      else if (result.index === 0) this.activateSemester(semester.id || 1);
      else this.getUIContext().showAlertDialog({ title: '删除学期', message: '该学期的全部课程也将删除，确定继续吗？',
        primaryButton: { value: '取消', action: () => {} }, secondaryButton: { value: '删除', action: () => {
          this.persist(deleteSemester(this.state, semester.id || 0));
        } } });
    }).catch(() => {});
  }

  private async activateSemester(id: number): Promise<void> {
    const next = switchSemester(this.state, id);
    if (await this.persist(next)) { this.week = semesterWeek(next.semester); this.toast('已切换学期'); }
  }

  private async saveSettings(settings: ScheduleSettings): Promise<void> {
    const next: ScheduleState = { semester: this.state.semester, settings, courses: this.state.courses };
    await this.persist(next);
  }

  private async setReminder(enabled: boolean, minutes: number = this.state.settings.defaultReminderMinutes): Promise<void> {
    const settings: ScheduleSettings = JSON.parse(JSON.stringify(this.state.settings));
    settings.reminderEnabled = enabled;
    settings.defaultReminderMinutes = minutes;
    await this.saveSettings(settings);
  }

  private openTimes(): void {
    this.timeStarts = this.state.settings.sectionTimes.length === 12 ? this.state.settings.sectionTimes.slice() : START_TIMES.slice();
    this.timeEnds = this.state.settings.sectionEndTimes.length === 12 ? this.state.settings.sectionEndTimes.slice() : END_TIMES.slice();
    this.navigate('times');
  }

  private async saveTimes(): Promise<void> {
    if (!validSectionTimes(this.timeStarts, this.timeEnds)) { this.message = '请输入 HH:mm 时间，结束时间须晚于开始时间，节次之间不能重叠'; return; }
    const settings: ScheduleSettings = JSON.parse(JSON.stringify(this.state.settings));
    settings.sectionTimes = this.timeStarts.slice();
    settings.sectionEndTimes = this.timeEnds.slice();
    await this.saveSettings(settings);
    this.navigate('settings'); this.toast('节次时间已保存');
  }

  private applyReminders(): void {
    const minutes = this.state.settings.defaultReminderMinutes;
    if (minutes <= 0) { this.toast('请先选择一个提前提醒时间'); return; }
    this.getUIContext().showAlertDialog({ title: '将默认提醒应用到本学期课程',
      message: `将本学期全部课程改为提前 ${minutes} 分钟提醒，包括当前设为“不提醒”的课程。是否应用？`,
      primaryButton: { value: '取消', action: () => {} }, secondaryButton: { value: '确定', action: () => {
        const courses: Course[] = this.state.courses.map((course: Course) => {
          const copy: Course = JSON.parse(JSON.stringify(course)); copy.reminderMinutes = minutes; return copy;
        });
        this.persist({ semester: this.state.semester, settings: this.state.settings, courses });
      } } });
  }

  private async authorizeReminders(test: boolean = false): Promise<void> {
    try {
      if (test) { await testReminder(this.abilityContext()); this.toast('已安排测试提醒（10秒后）'); }
      else { await authorizeNotifications(this.abilityContext()); this.reminderStatus = await syncReminders(this.abilityContext(), this.state); }
    } catch (error) { this.message = '提醒授权或排程失败：' + reminderError(error as BusinessError); }
  }

  private async openRepository(): Promise<void> {
    try { await this.abilityContext().startAbility({ action: 'ohos.want.action.viewData', uri: 'https://github.com/cakeni/CourseSchedule' }); }
    catch (_) { this.copyRepository(); }
  }

  private copyRepository(): void {
    pasteboard.getSystemPasteboard().setDataSync(pasteboard.createData(pasteboard.MIMETYPE_TEXT_PLAIN, 'https://github.com/cakeni/CourseSchedule'));
    this.toast('项目地址已复制');
  }

  private async setDisplaySettings(showWeekend: boolean, showInactiveCourses: boolean,
    showTime: boolean = this.state.settings.showTime,
    sectionHeight: number = this.state.settings.sectionHeightDp): Promise<void> {
    const next: ScheduleState = {
      semester: this.state.semester,
      settings: {
        showWeekend,
        showTime,
        showInactiveCourses,
        sectionHeightDp: sectionHeight,
        reminderEnabled: this.state.settings.reminderEnabled,
        defaultReminderMinutes: this.state.settings.defaultReminderMinutes,
        sectionTimes: this.state.settings.sectionTimes,
        sectionEndTimes: this.state.settings.sectionEndTimes
      },
      courses: this.state.courses
    };
    await this.persist(next);
  }

  private async setWeekend(show: boolean): Promise<void> {
    await this.setDisplaySettings(show, this.state.settings.showInactiveCourses);
  }

  private async setInactive(show: boolean): Promise<void> {
    await this.setDisplaySettings(this.state.settings.showWeekend, show);
  }

  private async selectImport(): Promise<void> {
    try {
      const parsed = await pickImport(this.abilityContext(), this.state);
      if (parsed === null) return;
      this.previewImport(parsed);
    } catch (error) {
      this.message = '导入失败：' + String(error);
    }
  }

  private async confirmImport(): Promise<void> {
    try {
      const next = commitImport(this.state, this.pendingImport, { includeConflicts: this.includeConflicts,
        restoreMetadata: this.restoreMetadata, replaceExisting: this.replaceExisting });
      const old = this.state;
      if (await this.persist(next)) {
        this.undoState = old;
        this.week = semesterWeek(next.semester);
        this.toast('课程导入成功');
        this.navigate('backup');
      }
    } catch (error) { this.message = String(error); }
  }

  private previewImport(parsed: ParsedImport): void {
    this.importOrigin = this.screen === 'academic' ? 'academic' : 'backup';
    if (parsed.sourceLabel !== '完整备份' && parsed.sourceLabel !== 'JSON') {
      parsed.courses = parsed.courses.map((course: Course) => {
        const copy: Course = JSON.parse(JSON.stringify(course));
        copy.reminderMinutes = this.state.settings.defaultReminderMinutes;
        return copy;
      });
    }
    this.pendingImport = parsed;
    this.importAnalysis = analyzeImport(parsed.courses, this.state.courses, currentSemesterId(this.state),
      parsed.semester ? parsed.semester.totalWeeks : this.state.semester.totalWeeks);
    if (!parsed.courses.length) { this.message = '未解析出课程，请检查课程名称、星期、节次和周次'; return; }
    this.includeConflicts = false; this.restoreMetadata = false; this.replaceExisting = false;
    this.message = ''; this.navigate('confirmImport');
  }

  private openTextImport(): void {
    this.importText = '';
    this.navigate('textImport');
  }

  private refreshImportAnalysis(): void {
    this.importAnalysis = analyzeImport(this.pendingImport.courses, this.replaceExisting ? [] : this.state.courses,
      currentSemesterId(this.state), this.restoreMetadata && this.pendingImport.semester ? this.pendingImport.semester.totalWeeks : this.state.semester.totalWeeks);
  }

  private parseImportText(): void {
    try { this.previewImport(parseTextImport(this.importText, this.state)); }
    catch (error) { this.message = '导入失败：' + String(error); }
  }

  private async openSchools(): Promise<void> {
    try {
      if (!this.schoolEntries.length) {
        const manager = this.abilityContext().resourceManager;
        const directory: SchoolDirectoryFile = JSON.parse(util.TextDecoder.create('utf-8').decodeToString(await manager.getRawFileContent('academic_school_directory.json')));
        this.schoolDefinitions = JSON.parse(util.TextDecoder.create('utf-8').decodeToString(await manager.getRawFileContent('academic_profiles.json')));
        const builtins: SchoolEntry[] = builtinSchools().map((school: AcademicSchool): SchoolEntry => ({
          id: school.id, name: school.name, profile: school.profile, url: school.loginUrl, support: school.id === 'swpu' ? 'verified' : 'compatible',
          category: school.id === 'nuaa_graduate' ? 'graduate' : 'undergraduate', adapterId: school.adapterId
        }));
        this.schoolEntries = builtins.concat(directory.entries.filter((entry: SchoolEntry) => !builtins.some((item: SchoolEntry) => item.name === entry.name)));
        try {
          const transliterator = i18n.Transliterator.getInstance('Han-Latin; Latin-ASCII; Lower()');
          for (const entry of this.schoolEntries) this.schoolSearch.set(entry.id, transliterator.transform(entry.name).replace(/\s+/g, ''));
        } catch (_) {}
      }
      this.schoolQuery = ''; this.schoolCategory = ''; this.schoolVisibleCount = 80; this.navigate('schools');
    } catch (error) { this.message = '学校目录读取失败：' + String(error); }
  }

  private filteredSchools(): SchoolEntry[] {
    const wanted = this.schoolQuery.toLowerCase().replace(/\s+/g, '');
    return this.schoolEntries.filter((entry: SchoolEntry) => (!this.schoolCategory || entry.category === this.schoolCategory) &&
      (schoolMatches(entry, this.schoolQuery) || (this.schoolSearch.get(entry.id) || '').includes(wanted)));
  }

  private selectSchool(entry: SchoolEntry): void {
    this.configuredEntry = null;
    if (entry.support === 'adapter_required') {
      const profile = this.schoolDefinitions.profiles.find(p => p.id === entry.profile);
      if (!profile) { this.message = '该条目需要新增学校适配，请通过开源项目反馈'; return; }
      this.configuredEntry = entry; this.configuredAddress = ''; this.navigate('schoolAddress'); return;
    }
    try {
      const school = builtinSchools().find(s => s.id === entry.id) || createSchool(entry, this.schoolDefinitions);
      this.confirmSchool(school);
    } catch (error) { this.message = String(error); }
  }

  private configureSchool(): void {
    if (!this.configuredEntry) return;
    try { this.confirmSchool(createSchool(this.configuredEntry, this.schoolDefinitions, this.configuredAddress)); }
    catch (error) { this.message = String(error); }
  }

  private confirmSchool(school: AcademicSchool): void {
    const enter = (): void => { this.selectedSchool = school; this.message = ''; this.navigate('academic'); };
    if (school.cleartextHosts.length || this.configuredEntry) {
      this.getUIContext().showAlertDialog({ title: school.cleartextHosts.length ? '学校教务使用 HTTP' : '确认教务地址',
        message: school.name + '\n' + school.loginUrl + (school.cleartextHosts.length ? '\n该学校教务使用明文 HTTP，请确认在可信网络中访问。' : '\n请确认这是学校官方教务地址。'),
        primaryButton: { value: '取消', action: () => {} }, secondaryButton: { value: '继续', action: enter } });
    } else enter();
  }

  private async undoImport(): Promise<void> {
    if (this.undoState && await this.persist(this.undoState)) { this.undoState = null; this.toast('已撤销导入'); }
  }

  private async exportBackup(): Promise<void> {
    try {
      if (await saveBackup(this.abilityContext(), exportJson(this.state))) this.toast('备份已保存');
    } catch (error) {
      this.message = '导出失败：' + String(error);
    }
  }

  private palette(): SchedulePalette {
    return this.dark ? DARK : LIGHT;
  }

  private sectionHeight(): number {
    return Math.max(56, Math.min(104, this.state.settings.sectionHeightDp));
  }

  private async updateSystemBars(): Promise<void> {
    try {
      this.abilityContext().getApplicationContext().setColorMode(this.dark ?
        ConfigurationConstant.ColorMode.COLOR_MODE_DARK : ConfigurationConstant.ColorMode.COLOR_MODE_LIGHT);
      const mainWindow = await window.getLastWindow(this.abilityContext());
      await mainWindow.setWindowSystemBarProperties({
        statusBarColor: this.palette().gradientStart,
        statusBarContentColor: this.palette().text,
        navigationBarColor: this.palette().navigation,
        navigationBarContentColor: this.palette().text
      });
    } catch (_) {}
  }

  private async setDark(value: boolean): Promise<void> {
    try {
      await saveDarkMode(this.abilityContext(), value);
      this.dark = value;
      this.updateSystemBars();
    } catch (error) {
      this.message = '外观设置保存失败：' + String(error);
    }
  }

  private selectWeek(value: number): void {
    const next = Math.max(1, Math.min(this.state.semester.totalWeeks, value));
    if (next === this.week) { this.setWeekPicker(false); this.quickDay = 0; return; }
    this.weekScrolls.set(this.week, this.gridScroller.currentOffset().yOffset);
    this.dateForward = next > this.week;
    this.weekdayOffset = this.dateForward ? 12 : -12;
    this.emptyEntrance = 0;
    this.quickDay = 0;
    this.week = next;
    this.setWeekPicker(false);
    setTimeout(() => {
      this.gridScroller.scrollTo({ xOffset: 0, yOffset: this.weekScrolls.get(this.week) || 0, animation: false });
      this.getUIContext().animateTo({ duration: 360, curve: this.pressCurve, expectedFrameRateRange: { min: 30, max: 120, expected: 60 } }, () => this.weekdayOffset = 0);
      this.emptyEntrance = 1;
    }, 16);
  }

  private weeks(): number[] {
    const result: number[] = [];
    for (let week = 1; week <= this.state.semester.totalWeeks; week++) result.push(week);
    return result;
  }

  private displayedDate(): Date {
    const now = new Date();
    const start = weekStart(this.state.semester, this.week);
    const end = new Date(start);
    end.setDate(end.getDate() + 7);
    return now >= start && now < end ? now : start;
  }

  private dateTitle(): string {
    const date = this.displayedDate();
    return `${date.getFullYear()}/${date.getMonth() + 1}/${date.getDate()}`;
  }

  private weekContext(): string {
    const start = weekStart(this.state.semester, this.week);
    const end = new Date(start);
    end.setDate(end.getDate() + 6);
    const format = (date: Date): string => `${String(date.getMonth() + 1).padStart(2, '0')}.${String(date.getDate()).padStart(2, '0')}`;
    const now = new Date();
    const semesterEnd = weekStart(this.state.semester, this.state.semester.totalWeeks + 1);
    const phase = now < new Date(this.state.semester.startDate) ? '未开学' :
      now >= semesterEnd ? '假期' : this.week === semesterWeek(this.state.semester) ? '当前周' : '学期中';
    const count = this.state.courses.filter(course => courseInWeek(course, this.week) &&
      (this.state.settings.showWeekend || course.dayOfWeek <= 5)).length;
    return `${phase} · ${format(start)}-${format(end)} · ${count}门课`;
  }

  private dayDate(day: number): Date {
    const date = weekStart(this.state.semester, this.week);
    date.setDate(date.getDate() + day - 1);
    return date;
  }

  private isToday(day: number): boolean {
    const date = this.dayDate(day);
    return date.toDateString() === new Date().toDateString();
  }

  private visibleCourses(): boolean {
    return selectCoursesForWeek(this.state.courses, this.week, this.state.settings.showInactiveCourses).some(c => this.days().includes(c.dayOfWeek));
  }

  private timeLabel(section: number, end: boolean): string {
    const times = end ? this.state.settings.sectionEndTimes : this.state.settings.sectionTimes;
    return times.length === 12 ? times[section - 1] : (end ? END_TIMES : START_TIMES)[section - 1];
  }

  private color(index: number): string {
    return this.palette().courses[((Math.floor(index) % 16) + 16) % 16];
  }

  private showCourse(course: Course): void {
    if (this.quickDay) { this.quickDay = 0; return; }
    this.getUIContext().animateTo({ duration: 480, curve: this.headerCurve, expectedFrameRateRange: { min: 30, max: 120, expected: 60 } }, () => this.detailCourse = course);
  }

  private deleteWithConfirmation(): void {
    this.getUIContext().showAlertDialog({
      title: '删除课程', message: '确定要删除这门课程吗？',
      primaryButton: { value: '取消', action: () => {} },
      secondaryButton: { value: '删除', action: () => { this.deleteCourse(); } }
    });
  }

  private goBack(): void {
    this.message = '';
    this.navigate(this.screen === 'semester' ? this.returnScreen :
      this.screen === 'schoolAddress' || this.screen === 'academic' ? 'schools' :
      this.screen === 'confirmImport' ? this.importOrigin : this.screen === 'textImport' || this.screen === 'schools' ? 'backup' : this.screen === 'times' ? 'settings' : 'schedule');
  }

  private modal(): boolean { return ['semester', 'times', 'textImport', 'confirmImport', 'schoolAddress'].includes(this.screen); }
  private baseScreen(): string {
    if (this.screen === 'semester') return this.returnScreen;
    if (this.screen === 'times') return 'settings';
    if (this.screen === 'textImport') return 'backup';
    if (this.screen === 'confirmImport') return this.importOrigin;
    if (this.screen === 'schoolAddress') return 'schools';
    return this.screen;
  }

  @Builder
  private modalTitle(title: string) {
    Text(title).fontSize(20).fontWeight(FontWeight.Bold).fontColor(this.palette().text).width('100%').padding({ left: 24, right: 24, top: 20, bottom: 14 });
  }

  onBackPress(): boolean {
    if (this.semesterDatePicker) { this.semesterDatePicker = false; return true; }
    if (this.screen === 'academic' && this.academicBack()) return true;
    if (this.screen === 'assistant' && this.assistantBack()) return true;
    if (this.quickDay) { this.quickDay = 0; return true; }
    if (this.semesterPicker) { this.setSemesterPicker(false); return true; }
    if (this.detailCourse) { this.closeDetails(); return true; }
    if (this.weekPicker) { this.setWeekPicker(false); return true; }
    if (this.screen !== 'schedule') { this.goBack(); return true; }
    return false;
  }

  @Builder
  private semesterPickerDialog() {
    Stack({ alignContent: Alignment.Center }) {
      Column() {
        Text('学期管理').fontSize(20).fontWeight(FontWeight.Bold).fontColor(this.palette().text).padding(20);
        Scroll() {
          Column() {
            Button('创建新学期', { type: ButtonType.Normal }).height(48).width('100%')
              .backgroundColor(Color.Transparent).fontColor(this.palette().primary)
              .onClick(() => { this.setSemesterPicker(false); this.openSemester(null); });
            ForEach(semesterList(this.state), (semester: Semester) => {
              Button((semester.id === currentSemesterId(this.state) ? '当前学期 · ' : '') + semester.name, { type: ButtonType.Normal })
                .height(48).width('100%').backgroundColor(Color.Transparent).fontColor(this.palette().text)
                .onClick(() => { this.setSemesterPicker(false); this.semesterActions(semester); });
            });
          }.width('100%');
        }.constraintSize({ maxHeight: 320 }).width('100%');
        Button('取消', { type: ButtonType.Normal }).height(48).width('100%').backgroundColor(Color.Transparent)
          .fontColor(this.palette().primary).onClick(() => this.setSemesterPicker(false));
      }.width('86%').backgroundColor(this.palette().surface).borderRadius(24)
        .transition(TransitionEffect.OPACITY.combine(TransitionEffect.scale({ x: 0.94, y: 0.94 })));
    }.height('100%').width('100%').backgroundColor('#66000000');
  }

  @Builder
  private header(title: string, back: boolean = true) {
    Row() {
      if (back) {
        Button({ type: ButtonType.Normal }) { Image($rawfile('ic_back.svg')).width(24).height(24).fillColor(this.palette().text).objectFit(ImageFit.Contain); }
          .id('back').width(48).height(48).backgroundColor(Color.Transparent)
          .accessibilityText('返回').onClick(() => this.goBack());
      }
      Text(title).fontSize(20).fontWeight(FontWeight.Medium).fontColor(this.palette().text)
        .margin({ left: back ? 0 : 20 }).layoutWeight(1);
    }.height(56).width('100%');
  }

  @Builder
  private scheduleHeader() {
    Stack({ alignContent: Alignment.TopStart }) {
      Row() {
        DateHeader({ date: this.dateTitle(), summary: `第${this.week}周 · ${DAYS[(this.displayedDate().getDay() + 6) % 7]}`,
          forward: this.dateForward, color: this.palette().text, secondary: this.palette().secondary }).layoutWeight(1);
        Button({ type: ButtonType.Normal }) { Image($rawfile('ic_assistant.svg')).width(23).height(23).fillColor(this.palette().primary); }
          .id('course-assistant-entry').width(42).height(48).backgroundColor(Color.Transparent)
          .accessibilityText('课程助手').enabled(this.ready).onClick(() => this.openAssistant('schedule'));
        Button({ type: ButtonType.Normal }) { Image($rawfile('ic_add.svg')).width(24).height(24).fillColor(this.palette().primary).objectFit(ImageFit.Contain); }
          .id('add-course').width(48).height(48).backgroundColor(Color.Transparent)
          .accessibilityText('添加课程').enabled(this.ready).onClick(() => this.openCourse(null));
        Button({ type: ButtonType.Normal }) {
          ImageAnimator().images(DUCK_FRAMES).state(AnimationStatus.Running).iterations(-1)
            .duration(5000).fixedSize(true).width(46).height(46);
        }.id('current-week').width(48).height(48).backgroundColor(Color.Transparent)
          .scale({ x: this.duckPressed ? 0.97 : 1, y: this.duckPressed ? 0.97 : 1 })
          .animation({ duration: this.duckPressed ? 75 : 150, curve: Curve.EaseOut })
          .onTouch((event: TouchEvent) => this.duckPressed = event.type === TouchType.Down)
          .accessibilityText('回到当前周').onClick(() => this.selectWeek(semesterWeek(this.state.semester)));
      }.padding({ left: 20, right: 2, top: 14 }).width('100%');
      Text('github.com/cakeni/CourseSchedule').fontSize(8).fontColor(this.palette().primary)
        .position({ right: 92, bottom: 15 });
    }.height(92).width('100%');
    Divider().strokeWidth(1).color(this.dark ? '#262626' : '#5CFFFFFF');
    Row() {
      Button({ type: ButtonType.Normal }) { Image($rawfile('ic_chevron_left.svg')).width(24).height(24).fillColor(this.palette().secondary).objectFit(ImageFit.Contain); }
        .id('previous-week').width(40).height(40).backgroundColor(Color.Transparent)
        .opacity(this.week > 1 ? 1 : 0.35).enabled(this.week > 1)
        .accessibilityText('上一周').onClick(() => this.selectWeek(this.week - 1)).scale({ x: this.pressedAction === 'previous-week' ? 0.97 : 1, y: this.pressedAction === 'previous-week' ? 0.97 : 1 })
          .animation({ duration: this.pressedAction === 'previous-week' ? 115 : 245, curve: this.pressCurve })
          .onTouch((event: TouchEvent) => this.pressedAction = event.type === TouchType.Down ? 'previous-week' : '');
      Column({ space: 3 }) {
        Text(`第${this.week}周`).fontSize(15).fontWeight(FontWeight.Bold).fontColor(this.palette().text);
        Text(this.weekContext()).fontSize(10).fontColor(this.palette().secondary).maxLines(1);
      }.layoutWeight(1).onClick(() => this.setWeekPicker(true)).accessibilityText('选择周次').scale({ x: this.pressedAction === 'week-info' ? 0.97 : 1, y: this.pressedAction === 'week-info' ? 0.97 : 1 })
          .animation({ duration: this.pressedAction === 'week-info' ? 115 : 245, curve: this.pressCurve })
          .onTouch((event: TouchEvent) => this.pressedAction = event.type === TouchType.Down ? 'week-info' : '');
      Button({ type: ButtonType.Normal }) { Image($rawfile('ic_chevron_right.svg')).width(24).height(24).fillColor(this.palette().secondary).objectFit(ImageFit.Contain); }
        .id('next-week').width(40).height(40).backgroundColor(Color.Transparent)
        .opacity(this.week < this.state.semester.totalWeeks ? 1 : 0.35)
        .enabled(this.week < this.state.semester.totalWeeks)
        .accessibilityText('下一周').onClick(() => this.selectWeek(this.week + 1)).scale({ x: this.pressedAction === 'next-week' ? 0.97 : 1, y: this.pressedAction === 'next-week' ? 0.97 : 1 })
          .animation({ duration: this.pressedAction === 'next-week' ? 115 : 245, curve: this.pressCurve })
          .onTouch((event: TouchEvent) => this.pressedAction = event.type === TouchType.Down ? 'next-week' : '');
    }.height(48).padding({ left: 8, right: 8 }).width('100%');
    Progress({ value: this.week, total: this.state.semester.totalWeeks, type: ProgressType.Linear })
      .height(2).color(this.palette().primary).backgroundColor(this.dark ? '#333333' : '#42FFFFFF')
      .style({ strokeWidth: 2 }).width('calc(100% - 40vp)').margin({ top: 1 })
      .onAreaChange((_, area: Area) => { this.progressWidth = Number(area.width); })
      .responseRegion({ x: 0, y: -15, width: '100%', height: 32 })
      .gesture(PanGesture({ direction: PanDirection.Horizontal, distance: 1 })
        .onActionUpdate((event: GestureEvent) => {
          if (event.fingerList.length > 0) {
            this.selectWeek(Math.ceil(event.fingerList[0].localX / this.progressWidth * this.state.semester.totalWeeks));
          }
        }));
  }

  @Builder
  private weekdays() {
    Row() {
      Text(`${weekStart(this.state.semester, this.week).getMonth() + 1}\n月`)
        .width(48).fontSize(11).fontWeight(FontWeight.Bold).fontColor(this.palette().secondary)
        .textAlign(TextAlign.Center).lineHeight(17);
      ForEach(this.days(), (day: number) => {
        Column({ space: 3 }) {
          Text(DAY_NAMES[day - 1]).fontSize(12)
            .fontColor(this.isToday(day) ? this.palette().primary : this.palette().secondary)
            .fontWeight(this.isToday(day) ? FontWeight.Bold : FontWeight.Normal);
          Text(String(this.dayDate(day).getDate())).fontSize(12)
            .fontColor(this.isToday(day) ? this.palette().primary : this.palette().secondary)
            .fontWeight(this.isToday(day) ? FontWeight.Bold : FontWeight.Normal);
        }.height(44).width('calc(100% - 4vp)').justifyContent(FlexAlign.Center)
          .backgroundColor(this.isToday(day) ? this.palette().container : Color.Transparent)
          .border({ width: this.isToday(day) ? 1.2 : 0, color: this.palette().primary })
          .borderRadius(8).layoutWeight(1).margin({ left: 1, right: 1 });
      }, (day: number) => String(day));
    }.height(60).width('100%').padding({ bottom: 4 }).translate({ x: this.weekdayOffset })
      .opacity(1 - Math.abs(this.weekdayOffset) / 12 * 0.45);
  }

  @Builder
  private timeColumn() {
    Column() {
      ForEach(SECTIONS, (section: number) => {
        Row() {
          Text(String(section)).fontSize(11).fontWeight(FontWeight.Bold)
            .fontColor(this.palette().secondary).textAlign(TextAlign.Center).width(this.state.settings.showTime ? 18 : 48);
          if (this.state.settings.showTime) {
            Column({ space: 6 }) {
              Text(this.timeLabel(section, false)).fontSize(9).fontColor(this.palette().tertiary);
              Text(this.timeLabel(section, true)).fontSize(9).fontColor(this.palette().tertiary).opacity(0.85);
            }.width(30);
          }
        }.height(this.sectionHeight()).width(48);
      });
    }.width(48).backgroundColor(this.dark ? '#69222222' : '#69F3F6FB');
  }

  @Builder
  private courseCard(course: Course, span: number, active: boolean) {
    Column({ space: 0 }) {
      Text((active ? '' : '[非本周]\n') + this.courseDisplayName(course.courseName))
        .id('course-name-' + course.id).minFontSize(9)
        .maxFontSize(course.courseName.length >= 18 ? 9 : course.courseName.length >= 12 ? 10 : 11.5)
        .fontWeight(FontWeight.Bold).fontColor(Color.White).width('100%').maxLines(span === 1 ? 3 : 7)
        .flexShrink(1).heightAdaptivePolicy(TextHeightAdaptivePolicy.LAYOUT_CONSTRAINT_FIRST)
        .textOverflow({ overflow: TextOverflow.Ellipsis });
      if (span >= 2 && course.classroom) {
        Text('@' + course.classroom).id('course-room-' + course.id).fontSize(9).fontColor('#DCFFFFFF').width('100%')
          .maxLines(2).textOverflow({ overflow: TextOverflow.Ellipsis }).margin({ top: 3 });
      }
      if (span >= 2 && course.teacher) {
        Text(course.teacher).id('course-teacher-' + course.id).fontSize(8.5).fontColor('#CDFFFFFF').width('100%')
          .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
          .margin({ top: course.classroom ? 1 : 3 });
      }
    }.id('course-card-' + course.id).alignItems(HorizontalAlign.Start).justifyContent(FlexAlign.Center)
      .padding({ left: 6, right: 6, top: 5, bottom: 5 })
      .width('calc(100% - 6vp)').height(span * this.sectionHeight() - 6)
      .margin(3).backgroundColor(this.color(course.colorIndex)).borderRadius(10).clip(true)
      .border({ width: this.dark ? 0 : 1.25, color: '#BEFFFFFF' })
      .opacity((active ? 1 : 0.46) * this.entrance).translate({ y: 18 * (1 - this.entrance) })
      .accessibilityText(`${course.courseName}，${DAYS[course.dayOfWeek - 1]}，第${course.startSection}到${course.endSection}节`)
      .scale({ x: this.pressedCourseId === course.id ? 0.975 : 1, y: this.pressedCourseId === course.id ? 0.975 : 1 })
      .animation({ duration: this.pressedCourseId === course.id ? 115 : 245, curve: this.pressCurve, expectedFrameRateRange: { min: 30, max: 120, expected: 60 } })
      .onTouch((event: TouchEvent) => this.pressedCourseId = event.type === TouchType.Down ? course.id : 0)
      .onClick(() => this.showCourse(course));
  }

  @Builder
  private dayColumn(day: number) {
    Stack({ alignContent: Alignment.TopStart }) {
      Column() {
        ForEach(SECTIONS, () => { Column() {}.width('100%').height(this.sectionHeight()); });
      }.width('100%');
      // Retained ForEach nodes keep builder arguments; refresh changed data and week status.
      ForEach(selectCoursesForWeek(this.state.courses, this.week, this.state.settings.showInactiveCourses).filter(c => c.dayOfWeek === day), (course: Course) => {
        Column() { this.courseCard(course, course.endSection - course.startSection + 1, courseInWeek(course, this.week)); }
          .width('100%').position({ x: 0, y: (course.startSection - 1) * this.sectionHeight() });
      }, (course: Course) => `${currentSemesterId(this.state)}:${JSON.stringify(course)}:${courseInWeek(course, this.week)}`);
      if (this.quickDay === day) {
        Stack({ alignContent: Alignment.Center }) {
          Column() {
            Text('•••').fontSize(11).fontColor(this.palette().primary).margin({ top: 2 });
            Blank();
            Text('•••').fontSize(11).fontColor(this.palette().primary).margin({ bottom: 2 });
          }.width('100%').height('100%');
          Button('+', { type: ButtonType.Circle }).width(30).height(30).fontSize(24)
            .backgroundColor(this.palette().primary).fontColor(Color.White).id('quick-add-confirm')
            .accessibilityText(`${DAYS[day - 1]}第${this.quickStart}到${this.quickEnd}节添加课程`)
            .onClick(() => this.openCourse(null, day, this.quickStart, this.quickEnd));
        }.width('calc(100% - 6vp)').height((this.quickEnd - this.quickStart + 1) * this.sectionHeight() - 6)
          .position({ x: 3, y: (this.quickStart - 1) * this.sectionHeight() + 3 })
          .backgroundColor(this.dark ? '#224A82F0' : '#294A82F0')
          .border({ width: 1.5, color: this.palette().primary }).borderRadius(10);
      }
    }.layoutWeight(1).height(12 * this.sectionHeight())
      .onClick(() => { if (this.quickDay) this.quickDay = 0; })
      .gesture(LongPressGesture({ duration: 500 }).onAction((event: GestureEvent) => {
        const section = Math.min(12, Math.max(1, Math.floor((event.fingerList[0]?.localY || 0) / this.sectionHeight()) + 1));
        if (selectCoursesForWeek(this.state.courses, this.week, this.state.settings.showInactiveCourses).some(c => c.dayOfWeek === day && section >= c.startSection && section <= c.endSection)) return;
        this.quickAnchor = section; this.quickStart = section; this.quickEnd = section;
        this.quickDay = day; this.quickDragging = true;
      }))
      .onTouch((event: TouchEvent) => {
        if (this.quickDay !== day) return;
        if (event.type === TouchType.Move && this.quickDragging && event.touches.length) {
          const section = Math.min(12, Math.max(1, Math.floor(event.touches[0].y / this.sectionHeight()) + 1));
          this.quickStart = Math.min(this.quickAnchor, section); this.quickEnd = Math.max(this.quickAnchor, section);
        }
        if (event.type === TouchType.Up || event.type === TouchType.Cancel) this.quickDragging = false;
      });
  }

  @Builder
  private schedulePage() {
    this.scheduleHeader();
    this.weekdays();
    Stack({ alignContent: Alignment.Top }) {
      Scroll(this.gridScroller) {
        Row() {
          this.timeColumn();
          ForEach(this.days(), (day: number) => this.dayColumn(day), (day: number) => String(day));
        }.alignItems(VerticalAlign.Top).width('100%').padding({ bottom: 88 });
      }.scrollable(this.quickDragging ? ScrollDirection.None : ScrollDirection.Vertical).scrollBar(BarState.Off).width('100%').height('100%');
      if (this.ready && !this.visibleCourses() && !this.quickDay) {
        Column() {
          Stack() { Image($rawfile('ic_schedule.svg')).width(34).height(34).fillColor(this.palette().primary).objectFit(ImageFit.Contain); }
            .width(72).height(72).borderRadius(36).backgroundColor(this.palette().container)
            .opacity(this.emptyEntrance).scale({ x: 0.96 + 0.04 * this.emptyEntrance, y: 0.96 + 0.04 * this.emptyEntrance })
            .translate({ y: 4 * (1 - this.emptyEntrance) }).animation({ duration: 620, delay: 45, curve: this.pressCurve });
          Text('这一周还没有课程').fontSize(17).fontWeight(FontWeight.Bold)
            .fontColor(this.palette().text).margin({ top: 12 }).opacity(this.emptyEntrance)
            .translate({ y: 16 * (1 - this.emptyEntrance) }).animation({ duration: 430, delay: 145, curve: this.pressCurve });
          Button('添加课程', { type: ButtonType.Normal }).height(44).width(128).borderRadius(8)
            .backgroundColor(this.palette().primary).fontColor(Color.White).fontSize(14)
            .margin({ top: 16 }).onClick(() => this.openCourse(null)).scale({ x: this.pressedAction === 'empty-add' ? 0.97 : 1, y: this.pressedAction === 'empty-add' ? 0.97 : 1 })
          .animation({ duration: this.pressedAction === 'empty-add' ? 115 : 245, curve: this.pressCurve })
          .onTouch((event: TouchEvent) => this.pressedAction = event.type === TouchType.Down ? 'empty-add' : '').opacity(this.emptyEntrance)
            .translate({ y: 18 * (1 - this.emptyEntrance) }).animation({ duration: 460, delay: 230, curve: this.pressCurve });
        }.margin({ top: 96 }).width(280).opacity(this.emptyEntrance).translate({ y: 10 * (1 - this.emptyEntrance) })
          .animation({ duration: 540, curve: this.pressCurve });
      }
    }.layoutWeight(1).width('100%')
      .parallelGesture(PanGesture({ direction: PanDirection.Horizontal, distance: 35 })
        .onActionEnd((event: GestureEvent) => {
          if (Math.abs(event.offsetX) >= 35 && !this.detailCourse && !this.quickDay) this.selectWeek(this.week + (event.offsetX < 0 ? 1 : -1));
        }));
  }

  @Builder
  private navItem(label: string, target: string, source: Resource) {
    NavigationItem({ label, target, source, selected: this.baseScreen() === target,
      selectedColor: this.palette().primary, inactiveColor: this.palette().tertiary, indicatorColor: this.palette().container,
      onSelected: () => { this.quickDay = 0; this.navigate(target); this.message = ''; } }).layoutWeight(1);
  }

  @Builder
  private bottomNavigation() {
    Row() {
      this.navItem('课表', 'schedule', $rawfile('ic_schedule.svg'));
      this.navItem('导入课程', 'backup', $rawfile('ic_import.svg'));
      this.navItem('设置', 'settings', $rawfile('ic_settings.svg'));
    }.height(72).width('100%').backgroundColor(this.palette().navigation)
      .borderRadius({ topLeft: 18, topRight: 18 })
      .border({ width: 1, color: this.palette().navigationOutline });
  }

  @Builder
  private sectionTitle(title: string) {
    Text(title).fontSize(14).fontWeight(FontWeight.Bold).fontColor(this.palette().primary)
      .width('100%').margin({ top: title === '基本信息' ? 0 : 24, bottom: 10 });
  }

  @Builder
  private fieldLabel(title: string) {
    Text(title).fontSize(13).fontWeight(FontWeight.Bold).fontColor(this.palette().secondary)
      .width('100%').margin({ bottom: 7 });
  }

  @Builder
  private dayChoice(day: number) {
    Button(DAYS[day - 1], { type: ButtonType.Normal }).height(40).width(49).fontSize(12).borderRadius(8)
      .padding({ left: 6, right: 6 })
      .backgroundColor(this.formDay === day ? this.palette().container : Color.Transparent)
      .fontColor(this.formDay === day ? this.palette().primary : this.palette().secondary)
      .border({ width: 1, radius: 8, color: this.formDay === day ? this.palette().primary : this.palette().divider })
      .onClick(() => this.formDay = day);
  }

  @Builder
  private outlinedInput(label: string, value: () => string, fieldId: string, changed: (value: string) => void) {
    Stack({ alignContent: Alignment.TopStart }) {
      TextInput({ text: value(), placeholder: this.focusedField === fieldId ? '' : label }).id(fieldId)
        .height(56).width('100%').fontSize(16).padding({ left: 16, right: 16 })
        .fontColor(this.palette().text).placeholderColor(this.palette().secondary)
        .backgroundColor(this.palette().surface).borderRadius(4)
        .border({ width: this.focusedField === fieldId ? 2 : 1, color: this.focusedField === fieldId ? this.palette().primary : this.palette().divider })
        .onFocus(() => this.focusedField = fieldId).onBlur(() => this.focusedField = '')
        .onChange((text: string) => changed(text));
      if (value() || this.focusedField === fieldId) {
        Text(label).fontSize(12).fontColor(this.focusedField === fieldId ? this.palette().primary : this.palette().secondary)
          .backgroundColor(this.palette().surface).padding({ left: 4, right: 4 }).position({ x: 12, y: -7 });
      }
    }.width('100%').height(56).margin({ top: 6, bottom: 6 });
  }

  @Builder
  private outlinedSelect(label: string, value: () => string, options: SelectOption[], selected: () => number,
    fieldId: string, changed: (index: number) => void) {
    Stack({ alignContent: Alignment.TopStart }) {
      Select(options).selected(selected()).value(value()).id(fieldId).height(56).width('100%')
        .font({ size: 16, weight: FontWeight.Normal }).fontColor(this.palette().text).backgroundColor(this.palette().surface)
        .borderRadius(4).border({ width: 1, color: this.palette().divider }).onSelect((index: number) => changed(index));
      Text(label).fontSize(12).fontColor(this.palette().secondary).backgroundColor(this.palette().surface)
        .padding({ left: 4, right: 4 }).position({ x: 12, y: -7 });
    }.width('100%').height(56).margin({ top: 6, bottom: 6 });
  }

  @Builder
  private coursePage() {
    this.header(this.editingId ? '编辑课程' : '添加课程');
    Divider().color(this.palette().divider).strokeWidth(1);
    Scroll() {
      Column() {
        this.sectionTitle('基本信息');
        this.outlinedInput('课程名称', () => this.formName, 'course-name', (value: string) => this.formName = value);
        this.outlinedInput('教师', () => this.formTeacher, 'course-teacher', (value: string) => this.formTeacher = value);
        this.outlinedInput('教室 / 地点', () => this.formRoom, 'course-room', (value: string) => this.formRoom = value);
        this.sectionTitle('上课时间');
        this.fieldLabel('星期');
        Scroll() {
          Row({ space: 6 }) { ForEach([1, 2, 3, 4, 5, 6, 7], (day: number) => this.dayChoice(day)); }
        }.scrollable(ScrollDirection.Horizontal).scrollBar(BarState.Off).width('100%');
        Row({ space: 12 }) {
          Column() {
            this.outlinedSelect('开始节次', () => `第${this.formStartSection}节`, SECTIONS.map((value: number): SelectOption => ({ value: `第${value}节` })),
              () => Number(this.formStartSection) - 1, 'course-start-section', (index: number) => this.formStartSection = String(index + 1));
          }.layoutWeight(1);
          Column() {
            this.outlinedSelect('结束节次', () => `第${this.formEndSection}节`, SECTIONS.map((value: number): SelectOption => ({ value: `第${value}节` })),
              () => Number(this.formEndSection) - 1, 'course-end-section', (index: number) => this.formEndSection = String(index + 1));
          }.layoutWeight(1);
        }.margin({ top: 8 });
        Row({ space: 12 }) {
          Column() {
            this.outlinedSelect('开始周', () => `第${this.formStartWeek}周`, this.weeks().map((value: number): SelectOption => ({ value: `第${value}周` })),
              () => Number(this.formStartWeek) - 1, 'course-start-week', (index: number) => this.formStartWeek = String(index + 1));
          }.layoutWeight(1);
          Column() {
            this.outlinedSelect('结束周', () => `第${this.formEndWeek}周`, this.weeks().map((value: number): SelectOption => ({ value: `第${value}周` })),
              () => Number(this.formEndWeek) - 1, 'course-end-week', (index: number) => this.formEndWeek = String(index + 1));
          }.layoutWeight(1);
        }
        this.fieldLabel('周类型');
        Row({ space: 8 }) {
          ForEach([0, 1, 2], (type: number) => {
            Button(['每周', '单周', '双周'][type], { type: ButtonType.Normal }).height(40).borderRadius(8)
              .width(66).fontSize(13).fontColor(this.formWeekType === type ? this.palette().primary : this.palette().secondary)
              .backgroundColor(this.formWeekType === type ? this.palette().container : Color.Transparent)
              .border({ width: 1, radius: 8, color: this.formWeekType === type ? this.palette().primary : this.palette().divider })
              .onClick(() => this.formWeekType = type);
          });
        }.width('100%').justifyContent(FlexAlign.Start).margin({ bottom: 16 });
        this.sectionTitle('显示与提醒');
        this.fieldLabel('课程颜色');
        Scroll() {
          Row({ space: 8 }) {
            ForEach(COLOR_INDEXES, (index: number) => {
              Button(this.formColor === index ? '✓' : '', { type: ButtonType.Circle }).width(36).height(36)
                .fontSize(20).fontColor(Color.White).backgroundColor(this.color(index))
                .border({ width: this.formColor === index ? 2 : 0, color: this.palette().primary })
                .accessibilityText('颜色 ' + String(index + 1)).onClick(() => this.formColor = index);
            });
          }.padding(2);
        }.scrollable(ScrollDirection.Horizontal).scrollBar(BarState.Off).width('100%');
        this.outlinedSelect('提醒', () => REMINDER_LABELS[REMINDER_VALUES.indexOf(this.formReminder)], REMINDER_LABELS.map((value: string): SelectOption => ({ value })),
          () => REMINDER_VALUES.indexOf(this.formReminder), 'course-reminder', (index: number) => this.formReminder = REMINDER_VALUES[index]);
        TextArea({ text: this.formNote, placeholder: '备注' }).id('course-note').height(88).fontSize(16)
          .fontColor(this.palette().text).placeholderColor(this.palette().tertiary).backgroundColor(this.palette().surface)
          .borderRadius(4).border({ width: 1, color: this.palette().divider }).margin({ top: 6 }).onChange(value => this.formNote = value);
      }.padding({ left: 16, right: 16, top: 12, bottom: 24 }).width('100%');
    }.layoutWeight(1).scrollBar(BarState.Off).width('100%');
    Row({ space: 12 }) {
      if (this.editingId > 0) {
        Button('删除', { type: ButtonType.Normal }).width(80).height(48).borderRadius(8)
          .fontColor('#BA5252').backgroundColor(this.palette().surface)
          .border({ width: 1, color: '#BA5252' }).onClick(() => this.deleteWithConfirmation());
      }
      Button('保存课程', { type: ButtonType.Normal }).id('save-course').height(48).borderRadius(8).layoutWeight(1)
        .backgroundColor(this.palette().primary).fontColor(Color.White).fontSize(14).onClick(() => this.saveCourseForm());
    }.height(76).padding({ left: 16, right: 16 }).width('100%').backgroundColor(this.palette().surface);
  }

  @Builder
  private semesterPage() {
    this.modalTitle(this.semesterEditingId ? '编辑学期' : '添加学期');
    Column() {
      this.outlinedInput('学期名称', () => this.semesterName, 'semester-name', (value: string) => this.semesterName = value);
      Stack({ alignContent: Alignment.TopStart }) {
      Button(this.semesterDate, { type: ButtonType.Normal }).id('semester-date').height(56).width('100%').borderRadius(4).fontColor(this.palette().text)
        .border({ width: 1, color: this.palette().divider })
        .backgroundColor(this.palette().surface).onClick(() => this.semesterDatePicker = true);
        Text('开学日期').fontSize(12).fontColor(this.palette().secondary).backgroundColor(this.palette().surface)
          .padding({ left: 4, right: 4 }).position({ x: 12, y: -7 });
      }.width('100%').height(56).margin({ top: 6, bottom: 6 });
      this.outlinedSelect('总周数', () => `${this.semesterWeeks}周`, [12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30]
        .map((value: number): SelectOption => ({ value: `${value}周` })), () => Number(this.semesterWeeks) - 12, 'semester-weeks', (index: number) => this.semesterWeeks = String(index + 12));
    }.width('100%').padding({ left: 24, right: 24 });
    Row() {
      Blank(); Button('取消').backgroundColor(Color.Transparent).fontColor(this.palette().primary).onClick(() => this.goBack());
      Button('保存学期').backgroundColor(Color.Transparent).fontColor(this.palette().primary).onClick(() => this.saveSemesterForm());
    }.width('100%').padding({ right: 12, top: 8, bottom: 8 });
  }

  @Builder
  private settingsSection(title: string) {
    Text(title).fontSize(12).fontColor(this.palette().secondary).width('100%')
      .padding({ top: 20, bottom: 7, left: 4 })
      .opacity(0.18 + 0.82 * this.pageEntrance).translate({ x: 32 * (1 - this.pageEntrance) })
      .animation(this.entryAnimation(['外观', '学期管理', '课表显示', '课程提醒', '数据管理', '关于'].indexOf(title) * 2 + 1, true));
  }

  @Builder
  private settingsDivider() {
    Divider().color(this.palette().divider).strokeWidth(1).width('calc(100% - 16vp)').margin({ left: 16 });
  }

  @Builder
  private settingsPage() {
    this.header('设置', false);
    Scroll() {
      Column() {
        ProjectNotice({ palette: this.palette(), onOpen: () => this.openRepository(), onCopy: () => this.copyRepository() })
          .width('100%').margin({ top: 12 })
          .opacity(0.18 + 0.82 * this.pageEntrance).translate({ x: 32 * (1 - this.pageEntrance) }).animation(this.entryAnimation(0, true));
        this.settingsSection('外观');
        Row() {
          Column({ space: 3 }) {
            Text('深色模式').fontSize(15).fontColor(this.palette().text).width('100%');
            Text('在暗光环境下使用低亮度界面').fontSize(12).fontColor(this.palette().secondary).width('100%');
          }.layoutWeight(1);
          Toggle({ type: ToggleType.Switch, isOn: this.dark }).selectedColor(this.palette().primary)
            .id('dark-mode').onChange((value: boolean) => this.setDark(value));
        }.height(72).padding({ left: 16, right: 16 }).width('100%').borderRadius(20).backgroundColor(this.palette().surface)
          .opacity(0.18 + 0.82 * this.pageEntrance).translate({ x: 32 * (1 - this.pageEntrance) }).animation(this.entryAnimation(2, true));
        this.settingsSection('学期管理');
        Row({ space: 14 }) {
          Stack() { Image($rawfile('ic_school.svg')).width(24).height(24).fillColor(this.palette().primary).objectFit(ImageFit.Contain); }
            .width(44).height(44).borderRadius(12).backgroundColor(this.palette().container);
          Column({ space: 4 }) {
            Text(this.state.semester.name).fontSize(16).fontWeight(FontWeight.Bold).fontColor(this.palette().text).width('100%');
            Text(`第${semesterWeek(this.state.semester)}周 · 共${this.state.semester.totalWeeks}周`)
              .fontSize(12).fontColor(this.palette().secondary).width('100%');
          }.layoutWeight(1);
          Image($rawfile('ic_chevron_right.svg')).width(20).height(20).fillColor(this.palette().tertiary).objectFit(ImageFit.Contain);
        }.height(88).padding({ left: 16, right: 16 }).width('100%').borderRadius(20)
          .backgroundColor(this.palette().surface).onClick(() => this.showSemesters()).scale({ x: this.pressedAction === 'semester' ? 0.98 : 1, y: this.pressedAction === 'semester' ? 0.98 : 1 })
          .animation({ duration: this.pressedAction === 'semester' ? 115 : 245, curve: this.pressCurve })
          .onTouch((event: TouchEvent) => this.pressedAction = event.type === TouchType.Down ? 'semester' : '')
          .opacity(0.18 + 0.82 * this.pageEntrance).translate({ x: 32 * (1 - this.pageEntrance) }).animation(this.entryAnimation(4, true));
        this.settingsSection('课表显示');
        Column() {
          Row() {
            Text('显示周末').layoutWeight(1).fontSize(15).fontColor(this.palette().text);
            Toggle({ type: ToggleType.Switch, isOn: this.state.settings.showWeekend }).selectedColor(this.palette().primary)
              .id('show-weekend').onChange((value: boolean) => this.setWeekend(value));
          }.height(62).padding({ left: 16, right: 16 }).width('100%');
          this.settingsDivider();
          Row() {
            Text('显示非本周课程').layoutWeight(1).fontSize(15).fontColor(this.palette().text);
            Toggle({ type: ToggleType.Switch, isOn: this.state.settings.showInactiveCourses }).selectedColor(this.palette().primary)
              .onChange((value: boolean) => this.setInactive(value));
          }.height(62).padding({ left: 16, right: 16 }).width('100%');
          this.settingsDivider();
          Row() {
            Text('显示节次时间').layoutWeight(1).fontSize(15).fontColor(this.palette().text);
            Toggle({ type: ToggleType.Switch, isOn: this.state.settings.showTime }).selectedColor(this.palette().primary)
              .onChange((value: boolean) => this.setDisplaySettings(this.state.settings.showWeekend,
                this.state.settings.showInactiveCourses, value));
          }.height(62).padding({ left: 16, right: 16 }).width('100%');
          this.settingsDivider();
          Row() {
            Text('课程块高度').layoutWeight(1).fontSize(15).fontColor(this.palette().text);
            Select([{ value: '56 dp' }, { value: '64 dp' }, { value: '72 dp' }, { value: '84 dp' }])
              .value(String(this.sectionHeight()) + ' dp').font({ size: 13 }).fontColor(this.palette().primary)
              .backgroundColor(Color.Transparent).onSelect((index: number) => this.setDisplaySettings(
                this.state.settings.showWeekend, this.state.settings.showInactiveCourses,
                this.state.settings.showTime, [56, 64, 72, 84][index]));
          }.height(62).padding({ left: 16, right: 16 }).width('100%');
        }.width('100%').backgroundColor(this.palette().surface).borderRadius(20)
          .opacity(0.18 + 0.82 * this.pageEntrance).translate({ x: 32 * (1 - this.pageEntrance) }).animation(this.entryAnimation(6, true));
        this.settingsSection('课程提醒');
        Column() {
          Row() {
            Text('启用课程提醒').layoutWeight(1).fontSize(15).fontColor(this.palette().text);
            Toggle({ type: ToggleType.Switch, isOn: this.state.settings.reminderEnabled }).selectedColor(this.palette().primary)
              .onChange((value: boolean) => this.setReminder(value));
          }.height(62).padding({ left: 16, right: 16 }).width('100%');
          this.settingsDivider();
          Row() {
            Text('默认提前时间').layoutWeight(1).fontSize(15).fontColor(this.palette().text);
            Select(REMINDER_LABELS.map((value: string): SelectOption => ({ value }))).font({ size: 13 })
              .value(REMINDER_LABELS[REMINDER_VALUES.indexOf(this.state.settings.defaultReminderMinutes)])
              .fontColor(this.palette().primary).backgroundColor(Color.Transparent).enabled(this.state.settings.reminderEnabled)
              .onSelect((index: number) => this.setReminder(this.state.settings.reminderEnabled, REMINDER_VALUES[index]));
          }.height(62).padding({ left: 16, right: 16 }).width('100%');
          this.settingsDivider();
          Column({ space: 4 }) {
            Text('节次时间设置').fontSize(15).fontColor(this.palette().text).width('100%');
            Text(`第1节 ${this.timeLabel(1, false)}-${this.timeLabel(1, true)} · 第12节 ${this.timeLabel(12, false)}-${this.timeLabel(12, true)}`)
              .fontSize(12).fontColor(this.palette().secondary).width('100%');
          }.padding(16).width('100%').onClick(() => this.openTimes()).scale({ x: this.pressedAction === 'times' ? 0.98 : 1, y: this.pressedAction === 'times' ? 0.98 : 1 })
          .animation({ duration: this.pressedAction === 'times' ? 115 : 245, curve: this.pressCurve })
          .onTouch((event: TouchEvent) => this.pressedAction = event.type === TouchType.Down ? 'times' : '');
        }.width('100%').backgroundColor(this.palette().surface).borderRadius(20)
          .opacity(0.18 + 0.82 * this.pageEntrance).translate({ x: 32 * (1 - this.pageEntrance) }).animation(this.entryAnimation(8, true));
        Text(this.reminderStatus).fontSize(12).fontColor(this.palette().secondary).width('100%').padding(12)
          .onClick(() => this.authorizeReminders());
        Button('发送测试提醒（10秒后）', { type: ButtonType.Normal }).height(42).width('100%').borderRadius(8)
          .backgroundColor(this.palette().container).fontColor(this.palette().primary).onClick(() => this.authorizeReminders(true));
        Button('将默认提醒应用到本学期课程', { type: ButtonType.Normal }).height(42).width('100%').borderRadius(8)
          .backgroundColor(this.palette().container).fontColor(this.palette().primary).margin({ top: 8 }).onClick(() => this.applyReminders());
        this.settingsSection('数据管理');
        Column() {
          Row() {
            Text('导出完整备份').layoutWeight(1).fontSize(15).fontColor(this.palette().text);
            Image($rawfile('ic_chevron_right.svg')).width(20).height(20).fillColor(this.palette().tertiary).objectFit(ImageFit.Contain);
          }.height(62).padding({ left: 16, right: 16 }).width('100%').onClick(() => this.exportBackup()).scale({ x: this.pressedAction === 'export' ? 0.98 : 1, y: this.pressedAction === 'export' ? 0.98 : 1 })
          .animation({ duration: this.pressedAction === 'export' ? 115 : 245, curve: this.pressCurve })
          .onTouch((event: TouchEvent) => this.pressedAction = event.type === TouchType.Down ? 'export' : '');
          this.settingsDivider();
          Row() {
            Text('导入 / 恢复课表').layoutWeight(1).fontSize(15).fontColor(this.palette().text);
            Image($rawfile('ic_chevron_right.svg')).width(20).height(20).fillColor(this.palette().tertiary).objectFit(ImageFit.Contain);
          }.height(62).padding({ left: 16, right: 16 }).width('100%').onClick(() => this.navigate('backup')).scale({ x: this.pressedAction === 'restore' ? 0.98 : 1, y: this.pressedAction === 'restore' ? 0.98 : 1 })
          .animation({ duration: this.pressedAction === 'restore' ? 115 : 245, curve: this.pressCurve })
          .onTouch((event: TouchEvent) => this.pressedAction = event.type === TouchType.Down ? 'restore' : '');
        }.width('100%').backgroundColor(this.palette().surface).borderRadius(20)
          .opacity(0.18 + 0.82 * this.pageEntrance).translate({ x: 32 * (1 - this.pageEntrance) }).animation(this.entryAnimation(10, true));
        this.settingsSection('关于');
        Row() {
          Text('晴课表').layoutWeight(1).fontSize(15).fontColor(this.palette().text);
          Text('0.1.0').fontSize(13).fontColor(this.palette().secondary);
        }.height(62).padding({ left: 16, right: 16 }).width('100%').backgroundColor(this.palette().surface).borderRadius(20)
          .onClick(() => this.getUIContext().showAlertDialog({ title: '关于晴课表',
            message: '晴课表 v0.1.0\n\n专注于日常排课与查看，支持周视图、课程提醒、Excel 与文本文件导入，以及 JSON 数据导出。',
            primaryButton: { value: '确定', action: () => {} },
            secondaryButton: { value: '查看开源项目', action: () => this.openRepository() } }));
      }.padding({ left: 16, right: 16, bottom: 32 }).width('100%');
    }.layoutWeight(1).scrollBar(BarState.Off).width('100%');
  }

  @Builder
  private timesPage() {
    this.modalTitle('节次时间设置');
    Scroll() {
      Column({ space: 8 }) {
        ForEach(SECTIONS, (section: number) => {
          Row({ space: 8 }) {
            Column() {
              this.fieldLabel(`第${section}节开始`);
              TextInput({ text: this.timeStarts[section - 1], placeholder: 'HH:mm' }).id('time-start-' + section).height(52)
                .fontColor(this.palette().text).backgroundColor(this.palette().surface).border({ width: 1, radius: 8, color: this.palette().divider })
                .onChange((value: string) => { this.timeStarts[section - 1] = value; });
            }.layoutWeight(1);
            Column() {
              this.fieldLabel(`第${section}节结束`);
              TextInput({ text: this.timeEnds[section - 1], placeholder: 'HH:mm' }).id('time-end-' + section).height(52)
                .fontColor(this.palette().text).backgroundColor(this.palette().surface).border({ width: 1, radius: 8, color: this.palette().divider })
                .onChange((value: string) => { this.timeEnds[section - 1] = value; });
            }.layoutWeight(1);
          }
        });
      }.padding(16).width('100%');
    }.layoutWeight(1).width('100%');
    Row({ space: 12 }) {
      Button('恢复默认', { type: ButtonType.Normal }).height(48).borderRadius(8).layoutWeight(1)
        .backgroundColor(Color.Transparent).fontColor(this.palette().primary)
        .onClick(() => { this.timeStarts = START_TIMES.slice(); this.timeEnds = END_TIMES.slice(); });
      Button('取消').height(48).backgroundColor(Color.Transparent).fontColor(this.palette().primary).onClick(() => this.goBack());
      Button('保存', { type: ButtonType.Normal }).height(48).borderRadius(8)
        .backgroundColor(Color.Transparent).fontColor(this.palette().primary).onClick(() => this.saveTimes());
    }.padding(16).width('100%');
  }

  @Builder
  private detailRow(source: Resource, value: string, color: string, index: number) {
    Row({ space: 22 }) {
      Image(source).width(30).height(30).fillColor(color).objectFit(ImageFit.Contain);
      Text(value).fontSize(16).fontColor(this.palette().text).layoutWeight(1).copyOption(CopyOptions.LocalDevice);
    }.constraintSize({ minHeight: 64 }).width('100%')
      .transition(TransitionEffect.OPACITY.combine(TransitionEffect.translate({ y: 10 + Math.min(index, 3) * 2 }))
        .animation({ duration: 330, delay: 90 + index * 36, curve: this.headerCurve }));
  }

  @Builder
  private courseDetails(course: Course) {
    Stack({ alignContent: Alignment.Bottom }) {
      Column() {}.width('100%').height('100%').backgroundColor('#66000000').onClick(() => this.closeDetails());
      Column() {
        Column() {}.width(38).height(4).borderRadius(2).backgroundColor(this.palette().divider).margin({ top: 10 });
        Row() {
          Text(course.courseName).fontSize(22).fontWeight(FontWeight.Bold).fontColor(this.palette().text).layoutWeight(1).maxLines(2);
          Button({ type: ButtonType.Normal }) { Image($rawfile('ic_edit.svg')).width(26).height(26).fillColor(this.palette().primary); }
            .height(48).width(48).backgroundColor(Color.Transparent).id('edit-course').accessibilityText('编辑课程').onClick(() => this.openCourse(course));
        }.margin({ top: 18, bottom: 14 }).width('100%');
        this.detailRow($rawfile('ic_today.svg'), `第${course.startWeek}-${course.endWeek}周 · ${['每周', '单周', '双周'][course.weekType]}`, '#18A995', 1);
        this.detailRow($rawfile('ic_time.svg'), `${DAYS[course.dayOfWeek - 1]} · 第${course.startSection}-${course.endSection}节`, '#E19A3D', 2);
        this.detailRow($rawfile('ic_person.svg'), course.teacher || '未提供教师', '#4F84C4', 3);
        if (!course.teacher) { Text('导入源未提供教师，可编辑补充').fontSize(11).fontColor(this.palette().tertiary).margin({ left: 52 }); }
        this.detailRow($rawfile('ic_room.svg'), course.classroom || '未设置', '#D65D64', 4);
        if (course.note) { this.detailRow($rawfile('ic_text.svg'), course.note, this.palette().secondary, 5); }
      }.padding({ left: 24, right: 24, bottom: 32 }).width('100%').backgroundColor(this.palette().surface)
        .borderRadius({ topLeft: 24, topRight: 24 }).transition(TransitionEffect.asymmetric(
          TransitionEffect.opacity(0.72).combine(TransitionEffect.scale({ x: 0.9, y: 0.92 })).combine(TransitionEffect.translate({ y: 72 })),
          TransitionEffect.OPACITY.combine(TransitionEffect.move(TransitionEdge.BOTTOM))));
    }.width('100%').height('100%');
  }

  @Builder
  private backupPage() {
    this.header('导入课程', false);
    Scroll() {
      Column({ space: 10 }) {
        Text('选择导入方式').fontSize(24).fontWeight(FontWeight.Bold).fontColor(this.palette().text).width('100%')
          .opacity(0.16 + 0.84 * this.pageEntrance).translate({ y: 30 * (1 - this.pageEntrance) }).animation(this.entryAnimation(0));
        Text('将导入到 ' + this.state.semester.name).fontSize(13).fontColor(this.palette().secondary).width('100%')
          .opacity(0.16 + 0.84 * this.pageEntrance).translate({ y: 30 * (1 - this.pageEntrance) }).animation(this.entryAnimation(1));
        Row({ space: 16 }) {
          Stack() { Image($rawfile('ic_school.svg')).width(30).height(30).fillColor(Color.White); }
            .width(58).height(58).backgroundColor(this.palette().primary).borderRadius(16)
            .scale({ x: 0.2 + 0.8 * this.pageEntrance, y: 0.2 + 0.8 * this.pageEntrance }).rotate({ angle: -24 * (1 - this.pageEntrance) })
            .animation({ duration: 720, delay: 150, curve: overshoot(1.55) });
          Column({ space: 5 }) {
            Text('从教务系统导入').fontSize(17).fontWeight(FontWeight.Bold).fontColor(this.palette().text).width('100%');
            Text('选择学校，登录教务系统后获取课表').fontSize(12).fontColor(this.palette().secondary).width('100%');
          }.layoutWeight(1);
          Image($rawfile('ic_chevron_right.svg')).width(22).height(22).fillColor(this.palette().primary);
        }.height(116).padding({ left: 18, right: 18 }).width('100%').borderRadius(22)
          .backgroundColor(this.palette().container).margin({ top: 10 }).onClick(() => this.openSchools())
          .onTouch((event: TouchEvent) => this.pressedAction = event.type === TouchType.Down ? 'school-import' : '')
          .opacity(0.16 + 0.84 * this.pageEntrance).translate({ y: 30 * (1 - this.pageEntrance) }).scale({ x: (0.9 + 0.1 * this.pageEntrance) * (this.pressedAction === 'school-import' ? 0.98 : 1),
            y: (0.9 + 0.1 * this.pageEntrance) * (this.pressedAction === 'school-import' ? 0.98 : 1) }).animation(this.entranceAnimating ? this.entryAnimation(2) : { duration: this.pressedAction === 'school-import' ? 115 : 245, curve: this.pressCurve });
        Row({ space: 14 }) {
          Stack() { Image($rawfile('ic_assistant.svg')).width(25).height(25).fillColor(this.palette().primary); }
            .width(52).height(52).backgroundColor(this.palette().container).borderRadius(16);
          Column({ space: 5 }) {
            Text('课程助手').fontSize(17).fontWeight(FontWeight.Bold).fontColor(this.palette().text).width('100%');
            Text('对话查课、调整安排与课前提醒').fontSize(12).fontColor(this.palette().secondary).width('100%');
          }.layoutWeight(1);
          Image($rawfile('ic_chevron_right.svg')).width(20).height(20).fillColor(this.palette().tertiary);
        }.id('course-assistant-import-entry').height(96).padding(18).width('100%').borderRadius(22)
          .backgroundColor(this.palette().surface).border({ width: 1, radius: 22, color: this.palette().divider })
          .onClick(() => this.openAssistant('backup'));
        Text('其他导入方式').fontSize(12).fontWeight(FontWeight.Bold).fontColor(this.palette().secondary)
          .width('100%').margin({ top: 14 })
          .opacity(0.16 + 0.84 * this.pageEntrance).translate({ y: 30 * (1 - this.pageEntrance) }).animation(this.entryAnimation(3));
        Row({ space: 14 }) {
          Stack() { Image($rawfile('ic_file_json.svg')).width(28).height(28).fillColor(this.palette().primary).objectFit(ImageFit.Contain); }
            .width(52).height(52).backgroundColor(this.palette().container).borderRadius(14);
          Column({ space: 4 }) {
            Text('从文件导入').fontSize(16).fontWeight(FontWeight.Bold).fontColor(this.palette().text).width('100%');
            Text('Excel / CSV / HTML / JSON').fontSize(12).fontColor(this.palette().secondary).width('100%');
          }.layoutWeight(1);
          Image($rawfile('ic_chevron_right.svg')).width(20).height(20).fillColor(this.palette().tertiary).objectFit(ImageFit.Contain);
        }.height(96).padding({ left: 16, right: 16 }).width('100%').backgroundColor(this.palette().surface)
          .borderRadius(18).border({ width: 1, color: this.palette().divider })
          .onClick(() => this.selectImport()).scale({ x: this.pressedAction === 'file-import' ? 0.98 : 1, y: this.pressedAction === 'file-import' ? 0.98 : 1 })
          .animation({ duration: this.pressedAction === 'file-import' ? 115 : 245, curve: this.pressCurve })
          .onTouch((event: TouchEvent) => this.pressedAction = event.type === TouchType.Down ? 'file-import' : '')
          .opacity(0.16 + 0.84 * this.pageEntrance).translate({ y: 30 * (1 - this.pageEntrance) }).animation(this.entryAnimation(4));
        Row({ space: 14 }) {
          Stack() { Image($rawfile('ic_text.svg')).width(28).height(28).fillColor(this.palette().primary); }
            .width(52).height(52).backgroundColor(this.palette().container).borderRadius(14);
          Column({ space: 4 }) {
            Text('从文本导入').fontSize(16).fontWeight(FontWeight.Bold).fontColor(this.palette().text).width('100%');
            Text('粘贴课程信息，确认预览后再写入').fontSize(12).fontColor(this.palette().secondary).width('100%');
          }.layoutWeight(1);
          Image($rawfile('ic_chevron_right.svg')).width(20).height(20).fillColor(this.palette().tertiary);
        }.height(96).padding({ left: 16, right: 16 }).width('100%').backgroundColor(this.palette().surface)
          .border({ width: 1, radius: 18, color: this.palette().divider }).onClick(() => this.openTextImport()).scale({ x: this.pressedAction === 'text-import' ? 0.98 : 1, y: this.pressedAction === 'text-import' ? 0.98 : 1 })
          .animation({ duration: this.pressedAction === 'text-import' ? 115 : 245, curve: this.pressCurve })
          .onTouch((event: TouchEvent) => this.pressedAction = event.type === TouchType.Down ? 'text-import' : '')
          .opacity(0.16 + 0.84 * this.pageEntrance).translate({ y: 30 * (1 - this.pageEntrance) }).animation(this.entryAnimation(5));
        Column({ space: 7 }) {
          Text('导入前请确认').fontSize(13).fontWeight(FontWeight.Bold).fontColor(this.palette().primary).width('100%');
          Text('Excel 首行建议包含课程名称、星期、开始节次和结束节次。导入前会预览并检查重复与时间冲突，不会直接覆盖已有课程。')
            .fontSize(12).lineHeight(19).fontColor(this.palette().secondary).width('100%');
        }.padding(16).borderRadius(16).backgroundColor(this.palette().variant).margin({ top: 12 })
          .opacity(0.16 + 0.84 * this.pageEntrance).translate({ y: 30 * (1 - this.pageEntrance) }).animation(this.entryAnimation(6));
      }.padding({ left: 20, right: 20, top: 18, bottom: 32 }).width('100%');
    }.layoutWeight(1).scrollBar(BarState.Off).width('100%');
  }

  @Builder
  private confirmImportPage() {
    this.modalTitle('导入预览 · ' + this.pendingImport.sourceLabel);
    Scroll() {
      Column({ space: 14 }) {
        Text(`可导入 ${this.importAnalysis.accepted.length} · 冲突 ${this.importAnalysis.conflicts.length} · 重复 ${this.importAnalysis.duplicates.length} · 无效 ${this.importAnalysis.invalid.length}`)
          .fontSize(14).fontColor(this.palette().text).width('100%');
        Text(`教室 ${this.pendingImport.courses.filter((c: Course) => c.classroom.length > 0).length}/${this.pendingImport.courses.length} · 教师 ${this.pendingImport.courses.filter((c: Course) => c.teacher.length > 0).length}`)
          .fontSize(12).fontColor(this.palette().secondary).width('100%');
        Scroll() {
          Column({ space: 8 }) {
          ForEach(this.importAnalysis.accepted.concat(this.importAnalysis.conflicts, this.importAnalysis.duplicates).slice(0, 30), (course: Course) => {
          Text(`${this.importAnalysis.conflicts.includes(course) ? '[冲突]' : this.importAnalysis.duplicates.includes(course) ? '[重复]' : '•'} ${course.courseName} · ${DAYS[course.dayOfWeek - 1]} · ${course.startSection}-${course.endSection}节`)
            .fontSize(13).fontColor(this.palette().secondary).width('100%');
          });
          }.padding(14).width('100%');
        }.height(180).width('100%').align(Alignment.TopStart).backgroundColor(this.palette().variant).scrollBar(BarState.Off);
        if (this.importAnalysis.conflicts.length) {
          Row() {
            Checkbox({ name: 'conflicts' }).select(this.includeConflicts).selectedColor(this.palette().primary)
              .onChange((value: boolean) => this.includeConflicts = value);
            Text('同时导入冲突课程').fontSize(14).fontColor(this.palette().text).onClick(() => this.includeConflicts = !this.includeConflicts);
          }.width('100%');
        }
        if (this.pendingImport.semester || this.pendingImport.settings) {
          Row() {
            Checkbox({ name: 'metadata' }).select(this.restoreMetadata).selectedColor(this.palette().primary)
              .onChange((value: boolean) => { this.restoreMetadata = value; this.refreshImportAnalysis(); });
            Text('恢复备份中的学期与显示设置').fontSize(14).fontColor(this.palette().text)
              .onClick(() => { this.restoreMetadata = !this.restoreMetadata; this.refreshImportAnalysis(); });
          }.width('100%');
        }
        Row() {
          Checkbox({ name: 'replace' }).select(this.replaceExisting).selectedColor(this.palette().primary)
            .onChange((value: boolean) => { this.replaceExisting = value; this.refreshImportAnalysis(); });
          Text('替换本学期已有课程').fontSize(14).fontColor(this.palette().text)
            .onClick(() => { this.replaceExisting = !this.replaceExisting; this.refreshImportAnalysis(); });
        }.width('100%');
      }.width('100%').padding(20);
    }.layoutWeight(1).width('100%').align(Alignment.TopStart);
    Row() {
      Blank(); Button('取消').backgroundColor(Color.Transparent).fontColor(this.palette().primary).onClick(() => this.goBack());
      Button('确认导入', { type: ButtonType.Normal }).height(48).id('confirm-import')
        .backgroundColor(Color.Transparent).fontColor(this.palette().primary).onClick(() => this.confirmImport());
    }.width('100%').padding({ right: 12, bottom: 8 });
  }

  @Builder
  private textImportPage() {
    this.modalTitle('从文本导入');
    Column({ space: 16 }) {
      Text('每行一门课程，建议按“课程名｜教师｜教室｜星期｜节次｜周次”的顺序填写，也支持 JSON 数据。')
        .fontSize(13).fontColor(this.palette().secondary).width('100%');
      TextArea({ text: this.importText, placeholder: '课程表文本' }).id('import-text').height(180).width('100%')
        .fontColor(this.palette().text).backgroundColor(this.palette().surface).borderRadius(12)
        .onChange((value: string) => this.importText = value);
      Row() {
        Blank(); Button('取消').backgroundColor(Color.Transparent).fontColor(this.palette().primary).onClick(() => this.goBack());
        Button('预览导入', { type: ButtonType.Normal }).id('preview-import').height(48).borderRadius(8)
          .backgroundColor(Color.Transparent).fontColor(this.palette().primary).onClick(() => this.parseImportText());
      }.width('100%');
    }.padding(20).layoutWeight(1).width('100%');
  }

  @Builder
  private schoolsPage() {
    this.header('选择学校');
    Column({ space: 12 }) {
      TextInput({ text: this.schoolQuery, placeholder: '搜索学校名称、别名或教务网址' }).id('school-search').height(48).borderRadius(12)
        .fontColor(this.palette().text).backgroundColor(this.palette().surface).onChange((value: string) => { this.schoolQuery = value; this.schoolVisibleCount = 80; });
      Row({ space: 8 }) {
        ForEach(['', 'undergraduate', 'graduate', 'common'], (category: string) => {
          Button(category === '' ? '全部' : category === 'undergraduate' ? '本科/专科' : category === 'graduate' ? '研究生' : '通用', { type: ButtonType.Normal })
            .height(36).fontSize(12).layoutWeight(1).borderRadius(18)
            .backgroundColor(this.schoolCategory === category ? this.palette().container : this.palette().surface)
            .fontColor(this.schoolCategory === category ? this.palette().primary : this.palette().secondary)
            .onClick(() => { this.schoolCategory = category; this.schoolVisibleCount = 80; });
        });
      }.width('100%');
      Text(`${this.filteredSchools().length} 个条目`).fontSize(12).fontColor(this.palette().secondary).width('100%');
    }.padding(16).width('100%');
    List({ space: 10 }) {
      ForEach(this.filteredSchools().slice(0, this.schoolVisibleCount), (entry: SchoolEntry) => {
        ListItem() {
          Column({ space: 6 }) {
            Text(entry.name).fontSize(16).fontWeight(FontWeight.Bold).fontColor(this.palette().text).width('100%');
            Text(`${entry.category === 'graduate' ? '研究生' : entry.category === 'common' ? '通用' : '本科/专科'} · ${this.schoolDefinitions.profiles.find(p => p.id === entry.profile)?.label || entry.profile} · ${webAddress(entry.url, true, !!entry.allowIpAddress)?.host || '需填写教务地址'}`)
              .fontSize(11).fontColor(this.palette().secondary).width('100%');
            Text(entry.support === 'verified' ? '已验证' : entry.support === 'compatible' ? '兼容解析' : entry.support === 'experimental' ? '实验适配' : '需填写地址 / 学校适配')
              .fontSize(11).fontColor(this.palette().primary).width('100%');
          }.padding(16).width('100%').borderRadius(16).backgroundColor(this.palette().surface)
            .opacity(entry.support === 'adapter_required' ? 0.72 : 1).onClick(() => this.selectSchool(entry));
        }
      }, (entry: SchoolEntry) => entry.id);
    }.layoutWeight(1).width('100%').padding({ left: 16, right: 16 }).scrollBar(BarState.Off)
      .onReachEnd(() => this.schoolVisibleCount += 80);
  }

  @Builder
  private schoolAddressPage() {
    this.modalTitle('填写教务地址');
    Column({ space: 16 }) {
      Text(this.configuredEntry ? this.configuredEntry.name : '').fontSize(20).fontWeight(FontWeight.Bold).fontColor(this.palette().text).width('100%');
      Text(this.schoolDefinitions.profiles.find(p => p.id === this.configuredEntry?.profile)?.instructions || '请填写学校教务系统的网址')
        .fontSize(14).fontColor(this.palette().secondary).width('100%');
      if (this.configuredEntry?.referenceUrl) {
        Text('原记录地址：' + this.configuredEntry.referenceUrl).fontSize(12).fontColor(this.palette().secondary).copyOption(CopyOptions.LocalDevice).width('100%');
      }
      TextInput({ text: this.configuredAddress, placeholder: 'https://学校教务网址' }).height(56).borderRadius(8)
        .backgroundColor(this.palette().surface).fontColor(this.palette().text).onChange((value: string) => this.configuredAddress = value);
      Button('确认并打开', { type: ButtonType.Normal }).height(48).width('100%').borderRadius(8)
        .backgroundColor(this.palette().primary).fontColor(Color.White).onClick(() => this.configureSchool());
    }.padding(20).width('100%');
    Button('取消').backgroundColor(Color.Transparent).fontColor(this.palette().primary).onClick(() => this.goBack());
  }

  @Builder
  private weekPickerDialog() {
    Stack({ alignContent: Alignment.Center }) {
      Column() {
        Text('选择周次').fontSize(20).fontWeight(FontWeight.Bold).fontColor(this.palette().text).padding(20);
        Scroll() {
          Column() {
            ForEach(this.weeks(), (value: number) => {
              Button(`第${value}周`, { type: ButtonType.Normal }).height(44).width('100%').fontSize(15)
                .backgroundColor(value === this.week ? this.palette().container : Color.Transparent)
                .fontColor(value === this.week ? this.palette().primary : this.palette().text)
                .onClick(() => this.selectWeek(value));
            });
          }.width('100%');
        }.height(280).width('100%');
        Button('取消', { type: ButtonType.Normal }).height(48).width('100%').backgroundColor(Color.Transparent)
          .fontColor(this.palette().primary).onClick(() => this.setWeekPicker(false));
      }.width('80%').borderRadius(20).backgroundColor(this.palette().surface).clip(true)
        .transition(TransitionEffect.OPACITY.combine(TransitionEffect.scale({ x: 0.94, y: 0.94 })));
    }.width('100%').height('100%').backgroundColor('#66000000');
  }

  build() {
    Stack() {
      Column() {
        if (this.baseScreen() === 'schedule') {
          this.schedulePage();
        } else if (this.baseScreen() === 'course') {
          this.coursePage();
        } else if (this.baseScreen() === 'settings') {
          this.settingsPage();
        } else if (this.baseScreen() === 'backup') {
          this.backupPage();
        } else if (this.baseScreen() === 'schools') {
          this.schoolsPage();
        } else if (this.baseScreen() === 'assistant') {
          CourseAssistant({ dark: this.dark, displayedWeek: this.week, reminderStatus: this.reminderStatus,
            onClose: () => this.closeAssistant(this.assistantReturn), onSettings: () => this.closeAssistant('settings'),
            onBackHandler: (handler: () => boolean) => this.assistantBack = handler });
        } else if (this.baseScreen() === 'academic') {
          AcademicImport({ school: this.selectedSchool, definitions: this.schoolDefinitions, totalWeeks: this.state.semester.totalWeeks,
            dark: this.dark, onImported: (parsed: ParsedImport) => this.previewImport(parsed), onClose: () => this.navigate('schools'),
            onBackHandler: (handler: () => boolean) => this.academicBack = handler });
        }
        if (this.message) {
          Text(this.message).fontSize(12).fontColor(this.palette().secondary).width('100%').padding(10);
        }
        if (this.undoState && this.screen === 'backup') {
          Row() {
            Text('课程导入成功').layoutWeight(1).fontColor(this.palette().text);
            Button('撤销').fontColor(this.palette().primary).backgroundColor(Color.Transparent).onClick(() => this.undoImport());
            Button('×').fontColor(this.palette().secondary).backgroundColor(Color.Transparent).onClick(() => this.undoState = null);
          }.padding(8).width('100%').backgroundColor(this.palette().container);
        }
        if (this.baseScreen() === 'schedule' || this.baseScreen() === 'settings' || this.baseScreen() === 'backup') {
          this.bottomNavigation();
        }
      }.height('100%').width('100%').backgroundColor(this.palette().background)
        .linearGradient({ direction: GradientDirection.Bottom, colors: [
          [this.baseScreen() === 'schedule' ? this.palette().gradientStart : this.palette().background, 0],
          [this.baseScreen() === 'schedule' ? this.palette().gradientEnd : this.palette().background, 1]
        ] });
      if (this.modal()) {
        Stack({ alignContent: Alignment.Center }) {
          Column() {
            if (this.screen === 'semester') this.semesterPage();
            else if (this.screen === 'times') this.timesPage();
            else if (this.screen === 'confirmImport') this.confirmImportPage();
            else if (this.screen === 'textImport') this.textImportPage();
            else if (this.screen === 'schoolAddress') this.schoolAddressPage();
            if (this.message) Text(this.message).fontSize(12).fontColor('#BA5252').padding(12);
          }.width('90%').height((this.screen === 'times' ? 570 : this.screen === 'confirmImport' ? 510 : this.screen === 'textImport' ? 400 : this.screen === 'semester' ? 320 : 440) + (this.message ? 48 : 0))
            .constraintSize({ maxHeight: '90%' }).backgroundColor(this.palette().surface).borderRadius(24).clip(true)
            .transition(TransitionEffect.OPACITY.combine(TransitionEffect.scale({ x: 0.94, y: 0.94 })).combine(TransitionEffect.translate({ y: 12 })));
        }.width('100%').height('100%').backgroundColor('#66000000');
      }
      if (this.weekPicker) { this.weekPickerDialog(); }
      if (this.semesterPicker) { this.semesterPickerDialog(); }
      if (this.detailCourse) { this.courseDetails(this.detailCourse); }
      if (this.semesterDatePicker) {
        Stack({ alignContent: Alignment.Center }) {
          Column().width('100%').height('100%').backgroundColor('#66000000')
            .onClick(() => this.semesterDatePicker = false);
          SemesterCalendar({ dateText: this.semesterDate, palette: this.palette(),
            onCancel: () => this.semesterDatePicker = false,
            onAccept: (date: Date) => {
              this.semesterDate = calendarDateText(mondayOf(date));
              this.semesterDatePicker = false;
            } });
        }.width('100%').height('100%');
      }
    }.height('100%').width('100%').backgroundColor(this.dark ? '#121212' : '#F4F7FC')
      .expandSafeArea([SafeAreaType.SYSTEM], [SafeAreaEdge.BOTTOM]);
  }
}
