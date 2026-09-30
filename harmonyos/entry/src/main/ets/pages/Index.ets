import { common, ConfigurationConstant } from '@kit.AbilityKit';
import { window } from '@kit.ArkUI';
import { pickBackup, saveBackup } from '../data/BackupFiles';
import { loadState, saveState, loadDarkMode, saveDarkMode } from '../data/ScheduleStore';
import { LIGHT, DARK, SchedulePalette, DAY_NAMES, SECTIONS, COLOR_INDEXES, START_TIMES, END_TIMES } from './ScheduleTheme';
import {
  Course, ScheduleCell, ScheduleState, dayCells, defaultState, exportJson, importJson,
  mondayOf, removeCourse, semesterWeek, upsertCourse, weekStart, courseInWeek
} from '../model/ScheduleCore';

const DAYS: string[] = ['周一', '周二', '周三', '周四', '周五', '周六', '周日'];

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
  private gridScroller: Scroller = new Scroller();
  private progressWidth: number = 1;
  private returnScreen: string = 'schedule';
  @State private pendingImport: ScheduleState | null = null;

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

  @State private semesterName: string = '';
  @State private semesterDate: string = '';
  @State private semesterWeeks: string = '20';

  aboutToAppear(): void {
    this.load();
  }

  private abilityContext(): common.UIAbilityContext {
    return this.getUIContext().getHostContext() as common.UIAbilityContext;
  }

  private async load(): Promise<void> {
    try {
      const loaded = await loadState(this.abilityContext());
      this.state = loaded;
      this.week = semesterWeek(loaded.semester);
      this.dark = await loadDarkMode(this.abilityContext());
      this.updateSystemBars();
      this.ready = true;
    } catch (error) {
      this.message = '本地课表读取失败；可从备份恢复：' + String(error);
      this.ready = false;
    }
  }

  private async persist(next: ScheduleState): Promise<boolean> {
    try {
      await saveState(this.abilityContext(), next);
      this.state = next;
      this.ready = true;
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

  private openCourse(course: Course | null, day: number = 1, section: number = 1): void {
    if (!this.ready) return;
    this.editingId = course ? course.id : 0;
    this.formName = course ? course.courseName : '';
    this.formTeacher = course ? course.teacher : '';
    this.formRoom = course ? course.classroom : '';
    this.formDay = course ? course.dayOfWeek : day;
    this.formStartSection = String(course ? course.startSection : section);
    this.formEndSection = String(course ? course.endSection : Math.min(12, section + 1));
    this.formStartWeek = String(course ? course.startWeek : this.week);
    this.formEndWeek = String(course ? course.endWeek : this.state.semester.totalWeeks);
    this.formWeekType = course ? course.weekType : 0;
    this.formNote = course ? course.note : '';
    this.formColor = course ? course.colorIndex : 0;
    this.message = '';
    this.screen = 'course';
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
      semesterId: 1,
      colorIndex: this.formColor,
      note: this.formNote,
      reminderMinutes: original ? original.reminderMinutes : -1,
      createTime: original ? original.createTime : Date.now()
    };
    try {
      const next = upsertCourse(this.state, course);
      if (await this.persist(next)) {
        this.toast('课程已保存');
        this.screen = 'schedule';
      }
    } catch (error) {
      this.message = String(error);
    }
  }

  private async deleteCourse(): Promise<void> {
    if (await this.persist(removeCourse(this.state, this.editingId))) {
      this.toast('课程已删除');
      this.screen = 'schedule';
    }
  }

  private openSemester(): void {
    const date = new Date(this.state.semester.startDate);
    this.semesterName = this.state.semester.name;
    this.semesterDate = `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;
    this.semesterWeeks = String(this.state.semester.totalWeeks);
    this.message = '';
    this.returnScreen = this.screen;
    this.screen = 'semester';
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
    if (this.state.courses.some(course => course.endWeek > weeks)) {
      this.message = '已有课程超出新的总周数';
      return;
    }
    const next: ScheduleState = {
      semester: { name: this.semesterName.trim(), startDate: mondayOf(date).getTime(), totalWeeks: weeks },
      settings: this.state.settings,
      courses: this.state.courses
    };
    if (await this.persist(next)) {
      this.week = semesterWeek(next.semester);
      this.toast('学期已保存');
      this.screen = this.returnScreen;
    }
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
      const text = await pickBackup(this.abilityContext());
      if (text === null) return;
      this.pendingImport = importJson(text, this.state);
      this.message = '';
      this.screen = 'confirmImport';
    } catch (error) {
      this.message = '导入失败：' + String(error);
    }
  }

  private async confirmImport(): Promise<void> {
    if (!this.pendingImport) return;
    if (await this.persist(this.pendingImport)) {
      this.week = semesterWeek(this.pendingImport.semester);
      this.pendingImport = null;
      this.toast('备份已导入');
      this.screen = 'schedule';
    }
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
    this.week = Math.max(1, Math.min(this.state.semester.totalWeeks, value));
    this.weekPicker = false;
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
    return this.days().some(day => dayCells(this.state, this.week, day).some(cell => cell.course !== null));
  }

  private timeLabel(section: number, end: boolean): string {
    const times = end ? this.state.settings.sectionEndTimes : this.state.settings.sectionTimes;
    return times.length === 12 ? times[section - 1] : (end ? END_TIMES : START_TIMES)[section - 1];
  }

  private color(index: number): string {
    return this.palette().courses[((Math.floor(index) % 16) + 16) % 16];
  }

  private showCourse(course: Course): void {
    this.getUIContext().showAlertDialog({
      title: course.courseName,
      message: `${DAYS[course.dayOfWeek - 1]} · 第${course.startSection}-${course.endSection}节\n` +
        `第${course.startWeek}-${course.endWeek}周 · ${['每周', '单周', '双周'][course.weekType]}\n` +
        `${course.classroom ? '地点：' + course.classroom + '\n' : ''}` +
        `${course.teacher ? '教师：' + course.teacher + '\n' : ''}${course.note}`,
      primaryButton: { value: '关闭', action: () => {} },
      secondaryButton: { value: '编辑课程', action: () => this.openCourse(course) }
    });
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
    this.screen = this.screen === 'semester' ? this.returnScreen :
      this.screen === 'confirmImport' ? 'backup' : 'schedule';
  }

  onBackPress(): boolean {
    if (this.weekPicker) { this.weekPicker = false; return true; }
    if (this.screen !== 'schedule') { this.goBack(); return true; }
    return false;
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
        Column({ space: 4 }) {
          Text(this.dateTitle()).fontSize(26).fontWeight(FontWeight.Bold).fontColor(this.palette().text);
          Text(`第${this.week}周 · ${DAYS[(this.displayedDate().getDay() + 6) % 7]}`)
            .fontSize(14).fontColor(this.palette().secondary);
        }.alignItems(HorizontalAlign.Start).layoutWeight(1);
        Button({ type: ButtonType.Normal }) { Image($rawfile('ic_add.svg')).width(24).height(24).fillColor(this.palette().primary).objectFit(ImageFit.Contain); }
          .id('add-course').width(48).height(48).backgroundColor(Color.Transparent)
          .accessibilityText('添加课程').enabled(this.ready).onClick(() => this.openCourse(null));
        Button({ type: ButtonType.Normal }) {
          Image($rawfile('course_duck.png')).width(46).height(46).objectFit(ImageFit.Contain);
        }.id('current-week').width(48).height(48).backgroundColor(Color.Transparent)
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
        .accessibilityText('上一周').onClick(() => this.selectWeek(this.week - 1));
      Column({ space: 3 }) {
        Text(`第${this.week}周`).fontSize(15).fontWeight(FontWeight.Bold).fontColor(this.palette().text);
        Text(this.weekContext()).fontSize(10).fontColor(this.palette().secondary).maxLines(1);
      }.layoutWeight(1).onClick(() => this.weekPicker = true).accessibilityText('选择周次');
      Button({ type: ButtonType.Normal }) { Image($rawfile('ic_chevron_right.svg')).width(24).height(24).fillColor(this.palette().secondary).objectFit(ImageFit.Contain); }
        .id('next-week').width(40).height(40).backgroundColor(Color.Transparent)
        .opacity(this.week < this.state.semester.totalWeeks ? 1 : 0.35)
        .enabled(this.week < this.state.semester.totalWeeks)
        .accessibilityText('下一周').onClick(() => this.selectWeek(this.week + 1));
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
    }.height(60).width('100%').padding({ bottom: 4 });
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
      Text((active ? '' : '[非本周]\n') + course.courseName)
        .fontSize(course.courseName.length >= 18 ? 9 : course.courseName.length >= 12 ? 10 : 11.5)
        .fontWeight(FontWeight.Bold).fontColor(Color.White).width('100%').maxLines(span === 1 ? 3 : 7)
        .textOverflow({ overflow: TextOverflow.Ellipsis });
      if (span >= 2 && course.classroom) {
        Text('@' + course.classroom).fontSize(9).fontColor('#DCFFFFFF').width('100%')
          .maxLines(2).textOverflow({ overflow: TextOverflow.Ellipsis }).margin({ top: 3 });
      }
      if (span >= 2 && course.teacher) {
        Text(course.teacher).fontSize(8.5).fontColor('#CDFFFFFF').width('100%')
          .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
          .margin({ top: course.classroom ? 1 : 3 });
      }
    }.alignItems(HorizontalAlign.Start).justifyContent(FlexAlign.Center)
      .padding({ left: 6, right: 6, top: 5, bottom: 5 })
      .width('calc(100% - 6vp)').height(span * this.sectionHeight() - 6)
      .margin(3).backgroundColor(this.color(course.colorIndex)).borderRadius(10)
      .border({ width: this.dark ? 0 : 1.25, color: '#BEFFFFFF' })
      .opacity(active ? 1 : 0.45).accessibilityText(`${course.courseName}，${DAYS[course.dayOfWeek - 1]}，第${course.startSection}到${course.endSection}节`)
      .onClick(() => this.showCourse(course));
  }

  @Builder
  private dayColumn(day: number) {
    Column() {
      ForEach(dayCells(this.state, this.week, day), (cell: ScheduleCell) => {
        if (cell.course) {
          this.courseCard(cell.course, cell.span, cell.active);
        } else {
          Column() {}.width('100%').height(this.sectionHeight())
            .gesture(LongPressGesture().onAction(() => this.openCourse(null, day, cell.section)));
        }
      }, (cell: ScheduleCell) => `${cell.section}:${cell.active}:${cell.course ? JSON.stringify(cell.course) : 'empty'}`);
    }.layoutWeight(1);
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
      }.scrollable(ScrollDirection.Vertical).scrollBar(BarState.Off).width('100%').height('100%');
      if (this.ready && !this.visibleCourses()) {
        Column() {
          Stack() { Image($rawfile('ic_schedule.svg')).width(34).height(34).fillColor(this.palette().primary).objectFit(ImageFit.Contain); }
            .width(72).height(72).borderRadius(36).backgroundColor(this.palette().container);
          Text('这一周还没有课程').fontSize(17).fontWeight(FontWeight.Bold)
            .fontColor(this.palette().text).margin({ top: 12 });
          Button('添加课程', { type: ButtonType.Normal }).height(44).width(128).borderRadius(8)
            .backgroundColor(this.palette().primary).fontColor(Color.White).fontSize(14)
            .margin({ top: 16 }).onClick(() => this.openCourse(null));
        }.margin({ top: 96 }).width(280);
      }
    }.layoutWeight(1).width('100%')
      .parallelGesture(PanGesture({ direction: PanDirection.Horizontal, distance: 35 })
        .onActionEnd((event: GestureEvent) => { this.selectWeek(this.week + (event.offsetX < 0 ? 1 : -1)); }));
  }

  @Builder
  private navItem(label: string, target: string, source: Resource) {
    Column({ space: 6 }) {
      Stack() { Image(source).width(22).height(22).fillColor(this.screen === target ? this.palette().primary : this.palette().tertiary).objectFit(ImageFit.Contain); }
        .width(58).height(34).borderRadius(17)
        .backgroundColor(this.screen === target ? this.palette().container : Color.Transparent);
      Text(label).fontSize(12).fontColor(this.screen === target ? this.palette().primary : this.palette().tertiary)
        .fontWeight(this.screen === target ? FontWeight.Bold : FontWeight.Normal);
    }.layoutWeight(1).height(72).justifyContent(FlexAlign.Center)
      .id('nav-' + target).accessibilityText(label)
      .onClick(() => { this.screen = target; this.message = ''; });
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
      .width('100%').margin({ top: 8, bottom: 10 });
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
  private coursePage() {
    this.header(this.editingId ? '编辑课程' : '添加课程');
    Divider().color(this.palette().divider).strokeWidth(1);
    Scroll() {
      Column({ space: 12 }) {
        this.sectionTitle('基本信息');
        TextInput({ text: this.formName, placeholder: '课程名称 *' }).id('course-name')
          .height(56).fontSize(15).fontColor(this.palette().text).placeholderColor(this.palette().tertiary)
          .backgroundColor(this.palette().surface).borderRadius(8).border({ width: 1, color: this.palette().divider })
          .onChange(value => this.formName = value);
        TextInput({ text: this.formTeacher, placeholder: '教师' }).id('course-teacher')
          .height(56).fontSize(15).fontColor(this.palette().text).placeholderColor(this.palette().tertiary)
          .backgroundColor(this.palette().surface).borderRadius(8).border({ width: 1, color: this.palette().divider })
          .onChange(value => this.formTeacher = value);
        TextInput({ text: this.formRoom, placeholder: '教室 / 地点' }).id('course-room')
          .height(56).fontSize(15).fontColor(this.palette().text).placeholderColor(this.palette().tertiary)
          .backgroundColor(this.palette().surface).borderRadius(8).border({ width: 1, color: this.palette().divider })
          .onChange(value => this.formRoom = value);
        this.sectionTitle('上课时间');
        this.fieldLabel('星期');
        Scroll() {
          Row({ space: 6 }) { ForEach([1, 2, 3, 4, 5, 6, 7], (day: number) => this.dayChoice(day)); }
        }.scrollable(ScrollDirection.Horizontal).scrollBar(BarState.Off).width('100%');
        Row({ space: 12 }) {
          Column() {
            this.fieldLabel('开始节次');
            TextInput({ text: this.formStartSection }).type(InputType.Number).height(52).borderRadius(8)
              .fontColor(this.palette().text).backgroundColor(this.palette().surface)
              .border({ width: 1, color: this.palette().divider }).onChange(value => this.formStartSection = value);
          }.layoutWeight(1);
          Column() {
            this.fieldLabel('结束节次');
            TextInput({ text: this.formEndSection }).type(InputType.Number).height(52).borderRadius(8)
              .fontColor(this.palette().text).backgroundColor(this.palette().surface)
              .border({ width: 1, color: this.palette().divider }).onChange(value => this.formEndSection = value);
          }.layoutWeight(1);
        }
        Row({ space: 12 }) {
          Column() {
            this.fieldLabel('开始周');
            TextInput({ text: this.formStartWeek }).type(InputType.Number).height(52).borderRadius(8)
              .fontColor(this.palette().text).backgroundColor(this.palette().surface)
              .border({ width: 1, color: this.palette().divider }).onChange(value => this.formStartWeek = value);
          }.layoutWeight(1);
          Column() {
            this.fieldLabel('结束周');
            TextInput({ text: this.formEndWeek }).type(InputType.Number).height(52).borderRadius(8)
              .fontColor(this.palette().text).backgroundColor(this.palette().surface)
              .border({ width: 1, color: this.palette().divider }).onChange(value => this.formEndWeek = value);
          }.layoutWeight(1);
        }
        this.fieldLabel('周类型');
        Row({ space: 8 }) {
          ForEach([0, 1, 2], (type: number) => {
            Button(['每周', '单周', '双周'][type], { type: ButtonType.Normal }).height(40).borderRadius(8)
              .layoutWeight(1).fontSize(13).fontColor(this.formWeekType === type ? this.palette().primary : this.palette().secondary)
              .backgroundColor(this.formWeekType === type ? this.palette().container : Color.Transparent)
              .border({ width: 1, radius: 8, color: this.formWeekType === type ? this.palette().primary : this.palette().divider })
              .onClick(() => this.formWeekType = type);
          });
        }.width('100%');
        this.sectionTitle('显示与备注');
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
        TextArea({ text: this.formNote, placeholder: '备注' }).height(88).fontSize(14)
          .fontColor(this.palette().text).placeholderColor(this.palette().tertiary).backgroundColor(this.palette().surface)
          .borderRadius(8).border({ width: 1, color: this.palette().divider }).onChange(value => this.formNote = value);
      }.padding({ left: 16, right: 16, top: 8, bottom: 24 }).width('100%');
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
    this.header('学期设置');
    Column({ space: 14 }) {
      this.fieldLabel('学期名称');
      TextInput({ text: this.semesterName }).height(52).borderRadius(8).fontColor(this.palette().text)
        .backgroundColor(this.palette().surface).onChange(value => this.semesterName = value);
      this.fieldLabel('开学日期（YYYY-MM-DD）');
      TextInput({ text: this.semesterDate }).height(52).borderRadius(8).fontColor(this.palette().text)
        .backgroundColor(this.palette().surface).onChange(value => this.semesterDate = value);
      this.fieldLabel('总周数');
      TextInput({ text: this.semesterWeeks }).type(InputType.Number).height(52).borderRadius(8)
        .fontColor(this.palette().text).backgroundColor(this.palette().surface).onChange(value => this.semesterWeeks = value);
      Button('保存学期', { type: ButtonType.Normal }).width('100%').height(48).borderRadius(8)
        .backgroundColor(this.palette().primary).fontColor(Color.White).onClick(() => this.saveSemesterForm());
    }.width('100%').padding(16);
    Blank();
  }

  @Builder
  private settingsSection(title: string) {
    Text(title).fontSize(12).fontColor(this.palette().secondary).width('100%')
      .padding({ top: 20, bottom: 7, left: 4 });
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
        Column({ space: 6 }) {
          Text('开源项目').fontSize(16).fontWeight(FontWeight.Bold).fontColor(this.palette().text).width('100%');
          Text('晴课表的源代码公开，欢迎查看项目与反馈问题。').fontSize(12).fontColor(this.palette().secondary).width('100%');
          Text('github.com/cakeni/CourseSchedule').fontSize(12).fontColor(this.palette().primary).width('100%').margin({ top: 2 });
        }.padding(16).width('100%').borderRadius(20).backgroundColor(this.palette().container).margin({ top: 12 });
        this.settingsSection('外观');
        Row() {
          Column({ space: 3 }) {
            Text('深色模式').fontSize(15).fontColor(this.palette().text).width('100%');
            Text('在暗光环境下使用低亮度界面').fontSize(12).fontColor(this.palette().secondary).width('100%');
          }.layoutWeight(1);
          Toggle({ type: ToggleType.Switch, isOn: this.dark }).selectedColor(this.palette().primary)
            .id('dark-mode').onChange((value: boolean) => this.setDark(value));
        }.height(72).padding({ left: 16, right: 16 }).width('100%').borderRadius(20).backgroundColor(this.palette().surface);
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
          .backgroundColor(this.palette().surface).onClick(() => this.openSemester());
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
            Select([{ value: '56 dp' }, { value: '64 dp' }, { value: '72 dp' }, { value: '88 dp' }, { value: '104 dp' }])
              .value(String(this.sectionHeight()) + ' dp').font({ size: 13 }).fontColor(this.palette().primary)
              .backgroundColor(Color.Transparent).onSelect((index: number) => this.setDisplaySettings(
                this.state.settings.showWeekend, this.state.settings.showInactiveCourses,
                this.state.settings.showTime, [56, 64, 72, 88, 104][index]));
          }.height(62).padding({ left: 16, right: 16 }).width('100%');
        }.width('100%').backgroundColor(this.palette().surface).borderRadius(20);
        this.settingsSection('数据管理');
        Column() {
          Row() {
            Text('导出完整备份').layoutWeight(1).fontSize(15).fontColor(this.palette().text);
            Image($rawfile('ic_chevron_right.svg')).width(20).height(20).fillColor(this.palette().tertiary).objectFit(ImageFit.Contain);
          }.height(62).padding({ left: 16, right: 16 }).width('100%').onClick(() => this.exportBackup());
          this.settingsDivider();
          Row() {
            Text('导入 / 恢复课表').layoutWeight(1).fontSize(15).fontColor(this.palette().text);
            Image($rawfile('ic_chevron_right.svg')).width(20).height(20).fillColor(this.palette().tertiary).objectFit(ImageFit.Contain);
          }.height(62).padding({ left: 16, right: 16 }).width('100%').onClick(() => this.screen = 'backup');
        }.width('100%').backgroundColor(this.palette().surface).borderRadius(20);
        this.settingsSection('关于');
        Row() {
          Text('晴课表').layoutWeight(1).fontSize(15).fontColor(this.palette().text);
          Text('0.1.0').fontSize(13).fontColor(this.palette().secondary);
        }.height(62).padding({ left: 16, right: 16 }).width('100%').backgroundColor(this.palette().surface).borderRadius(20);
      }.padding({ left: 16, right: 16, bottom: 32 }).width('100%');
    }.layoutWeight(1).scrollBar(BarState.Off).width('100%');
  }

  @Builder
  private backupPage() {
    this.header('导入课程', false);
    Scroll() {
      Column({ space: 10 }) {
        Text('选择导入方式').fontSize(24).fontWeight(FontWeight.Bold).fontColor(this.palette().text).width('100%');
        Text('将导入到 ' + this.state.semester.name).fontSize(13).fontColor(this.palette().secondary).width('100%');
        Row({ space: 14 }) {
          Stack() { Image($rawfile('ic_file_json.svg')).width(28).height(28).fillColor(this.palette().primary).objectFit(ImageFit.Contain); }
            .width(52).height(52).backgroundColor(this.palette().container).borderRadius(14);
          Column({ space: 4 }) {
            Text('从文件导入').fontSize(16).fontWeight(FontWeight.Bold).fontColor(this.palette().text).width('100%');
            Text('JSON 完整备份').fontSize(12).fontColor(this.palette().secondary).width('100%');
          }.layoutWeight(1);
          Image($rawfile('ic_chevron_right.svg')).width(20).height(20).fillColor(this.palette().tertiary).objectFit(ImageFit.Contain);
        }.height(96).padding({ left: 16, right: 16 }).width('100%').backgroundColor(this.palette().surface)
          .borderRadius(18).border({ width: 1, color: this.palette().divider }).margin({ top: 14 })
          .onClick(() => this.selectImport());
        Column({ space: 7 }) {
          Text('导入提示').fontSize(13).fontWeight(FontWeight.Bold).fontColor(this.palette().primary).width('100%');
          Text('选择 Android 晴课表导出的 JSON 备份。导入前会显示学期和课程数量，确认后替换当前课表。')
            .fontSize(12).lineHeight(19).fontColor(this.palette().secondary).width('100%');
        }.padding(16).borderRadius(16).backgroundColor(this.palette().variant).margin({ top: 12 });
      }.padding({ left: 20, right: 20, top: 18, bottom: 32 }).width('100%');
    }.layoutWeight(1).scrollBar(BarState.Off).width('100%');
  }

  @Builder
  private confirmImportPage() {
    this.header('确认导入');
    Column({ space: 18 }) {
      Text(this.pendingImport ? this.pendingImport.semester.name : '').fontSize(20)
        .fontWeight(FontWeight.Bold).fontColor(this.palette().text);
      Text(this.pendingImport ? `${this.pendingImport.courses.length} 门课程 · ${this.pendingImport.semester.totalWeeks} 周` : '')
        .fontColor(this.palette().secondary);
      Text('确认后将替换本机当前课表。建议先导出备份。').fontSize(14).fontColor(this.palette().secondary);
      Button('确认替换', { type: ButtonType.Normal }).height(48).borderRadius(8).width('100%')
        .backgroundColor(this.palette().primary).fontColor(Color.White).onClick(() => this.confirmImport());
      Button('取消', { type: ButtonType.Normal }).height(48).borderRadius(8).width('100%')
        .backgroundColor(this.palette().container).fontColor(this.palette().primary)
        .onClick(() => { this.pendingImport = null; this.screen = 'backup'; });
    }.width('100%').padding(20);
    Blank();
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
          .fontColor(this.palette().primary).onClick(() => this.weekPicker = false);
      }.width('80%').borderRadius(20).backgroundColor(this.palette().surface).clip(true);
    }.width('100%').height('100%').backgroundColor('#66000000');
  }

  build() {
    Stack() {
      Column() {
        if (this.screen === 'schedule') {
          this.schedulePage();
        } else if (this.screen === 'course') {
          this.coursePage();
        } else if (this.screen === 'semester') {
          this.semesterPage();
        } else if (this.screen === 'settings') {
          this.settingsPage();
        } else if (this.screen === 'backup') {
          this.backupPage();
        } else if (this.screen === 'confirmImport') {
          this.confirmImportPage();
        }
        if (this.message) {
          Text(this.message).fontSize(12).fontColor(this.palette().secondary).width('100%').padding(10);
        }
        if (this.screen === 'schedule' || this.screen === 'settings' || this.screen === 'backup') {
          this.bottomNavigation();
        }
      }.height('100%').width('100%').backgroundColor(this.palette().background)
        .linearGradient({ direction: GradientDirection.Bottom, colors: [
          [this.screen === 'schedule' ? this.palette().gradientStart : this.palette().background, 0],
          [this.screen === 'schedule' ? this.palette().gradientEnd : this.palette().background, 1]
        ] });
      if (this.weekPicker) { this.weekPickerDialog(); }
    }.height('100%').width('100%').backgroundColor(this.dark ? '#121212' : '#F4F7FC')
      .expandSafeArea([SafeAreaType.SYSTEM], [SafeAreaEdge.BOTTOM]);
  }
}
