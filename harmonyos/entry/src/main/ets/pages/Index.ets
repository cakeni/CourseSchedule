import { common } from '@kit.AbilityKit';
import { pickBackup, saveBackup } from '../data/BackupFiles';
import { loadState, saveState } from '../data/ScheduleStore';
import {
  Course, ScheduleCell, ScheduleState, dayCells, defaultState, exportJson, importJson,
  mondayOf, removeCourse, semesterWeek, upsertCourse, weekStart
} from '../model/ScheduleCore';

const DAYS: string[] = ['周一', '周二', '周三', '周四', '周五', '周六', '周日'];
const DAY_OPTIONS: SelectOption[] = [
  { value: '周一' }, { value: '周二' }, { value: '周三' }, { value: '周四' },
  { value: '周五' }, { value: '周六' }, { value: '周日' }
];
const COLORS: string[] = ['#D8EDE4', '#DDE8F8', '#FBE6D8', '#EDE2F6', '#F8E9B9', '#DDEDEC'];
const SECTION_HEIGHT = 64;

@Entry
@Component
struct Index {
  @State private state: ScheduleState = defaultState();
  @State private week: number = 1;
  @State private screen: string = 'schedule';
  @State private message: string = '';
  @State private ready: boolean = false;
  @State private pendingImport: ScheduleState | null = null;
  @State private deleteArmed: boolean = false;

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

  private days(): number[] {
    return this.state.settings.showWeekend ? [1, 2, 3, 4, 5, 6, 7] : [1, 2, 3, 4, 5];
  }

  private dateLabel(day: number): string {
    const date = weekStart(this.state.semester, this.week);
    date.setDate(date.getDate() + day - 1);
    return `${DAYS[day - 1]} ${date.getMonth() + 1}/${date.getDate()}`;
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
    this.deleteArmed = false;
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
        this.message = '课程已保存';
        this.screen = 'schedule';
      }
    } catch (error) {
      this.message = String(error);
    }
  }

  private async deleteCourse(): Promise<void> {
    if (!this.deleteArmed) {
      this.deleteArmed = true;
      return;
    }
    if (await this.persist(removeCourse(this.state, this.editingId))) {
      this.message = '课程已删除';
      this.screen = 'schedule';
    }
  }

  private openSemester(): void {
    const date = new Date(this.state.semester.startDate);
    this.semesterName = this.state.semester.name;
    this.semesterDate = `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;
    this.semesterWeeks = String(this.state.semester.totalWeeks);
    this.message = '';
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
      this.message = '学期已保存';
      this.screen = 'schedule';
    }
  }

  private async setDisplaySettings(showWeekend: boolean, showInactiveCourses: boolean): Promise<void> {
    const next: ScheduleState = {
      semester: this.state.semester,
      settings: {
        showWeekend,
        showTime: this.state.settings.showTime,
        showInactiveCourses,
        sectionHeightDp: this.state.settings.sectionHeightDp,
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
      this.message = '备份已导入';
      this.screen = 'schedule';
    }
  }

  private async exportBackup(): Promise<void> {
    try {
      if (await saveBackup(this.abilityContext(), exportJson(this.state))) this.message = '备份已保存';
    } catch (error) {
      this.message = '导出失败：' + String(error);
    }
  }

  @Builder
  private header(title: string) {
    Row() {
      if (this.screen !== 'schedule') {
        Button('返回')
          .fontSize(14)
          .backgroundColor('#E4EEE9')
          .fontColor('#245642')
          .onClick(() => { this.screen = 'schedule'; this.message = ''; });
      }
      Text(title).fontSize(22).fontWeight(FontWeight.Bold).fontColor('#204F3D').margin({ left: 12 });
    }
    .height(56)
    .width('100%')
    .padding({ left: 16, right: 16 });
  }

  @Builder
  private dayColumn(day: number) {
    Column() {
      Text(this.dateLabel(day))
        .fontSize(12)
        .fontWeight(FontWeight.Medium)
        .fontColor('#315E4D')
        .height(44)
        .textAlign(TextAlign.Center)
        .width('100%');
      ForEach(dayCells(this.state, this.week, day), (cell: ScheduleCell) => {
        if (cell.course) {
          Column() {
            Text(cell.course.courseName)
              .fontSize(12)
              .fontWeight(FontWeight.Medium)
              .maxLines(2)
              .textOverflow({ overflow: TextOverflow.Ellipsis });
            Text(cell.course.classroom || cell.course.teacher)
              .fontSize(10)
              .maxLines(2)
              .textOverflow({ overflow: TextOverflow.Ellipsis });
          }
          .alignItems(HorizontalAlign.Start)
          .justifyContent(FlexAlign.Center)
          .padding(5)
          .width('100%')
          .height(cell.span * SECTION_HEIGHT)
          .backgroundColor(COLORS[cell.course.colorIndex % COLORS.length] || COLORS[0])
          .border({ width: 2, color: '#F6F8F5' })
          .borderRadius(8)
          .opacity(cell.active ? 1 : 0.48)
          .onClick(() => this.openCourse(cell.course));
        } else {
          Column() { Text('+').fontSize(16).fontColor('#C1CFC6'); }
            .width('100%')
            .height(SECTION_HEIGHT)
            .justifyContent(FlexAlign.Center)
            .border({ width: 1, color: '#E6EDE8' })
            .onClick(() => this.openCourse(null, day, cell.section));
        }
      }, (cell: ScheduleCell) => String(cell.section));
    }
    .width(86);
  }

  @Builder
  private schedulePage() {
    this.header('晴课表');
    Row() {
      Column() {
        Text(this.state.semester.name).fontSize(15).fontColor('#315E4D');
        Text(`第 ${this.week} / ${this.state.semester.totalWeeks} 周`).fontSize(19).fontWeight(FontWeight.Bold);
      }.alignItems(HorizontalAlign.Start).layoutWeight(1);
      Button('‹').fontSize(24).backgroundColor('#E4EEE9').fontColor('#245642')
        .enabled(this.week > 1).onClick(() => this.week--);
      Button('今').fontSize(14).backgroundColor('#E4EEE9').fontColor('#245642')
        .margin({ left: 6, right: 6 }).onClick(() => this.week = semesterWeek(this.state.semester));
      Button('›').fontSize(24).backgroundColor('#E4EEE9').fontColor('#245642')
        .enabled(this.week < this.state.semester.totalWeeks).onClick(() => this.week++);
    }
    .width('100%')
    .padding({ left: 16, right: 16, bottom: 10 });

    Scroll() {
      Scroll() {
        Row() {
          Column() {
            Text('节次').fontSize(11).height(44).width(34).textAlign(TextAlign.Center);
            ForEach([1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12], (section: number) => {
              Text(String(section)).fontSize(11).fontColor('#708579')
                .height(SECTION_HEIGHT).width(34).textAlign(TextAlign.Center);
            });
          }
          ForEach(this.days(), (day: number) => { this.dayColumn(day); }, (day: number) => String(day));
        }
      }
      .scrollable(ScrollDirection.Horizontal)
      .height(44 + 12 * SECTION_HEIGHT)
      .width('100%');
    }
    .layoutWeight(1)
    .width('100%')
    .backgroundColor('#FFFFFF');

    Row({ space: 8 }) {
      Button('添加').onClick(() => this.openCourse(null)).enabled(this.ready);
      Button('学期').onClick(() => this.openSemester()).enabled(this.ready);
      Button('设置').onClick(() => this.screen = 'settings').enabled(this.ready);
      Button('备份').onClick(() => this.screen = 'backup');
    }
    .width('100%')
    .justifyContent(FlexAlign.SpaceAround)
    .padding(12);
  }

  @Builder
  private coursePage() {
    this.header(this.editingId ? '编辑课程' : '添加课程');
    Scroll() {
      Column({ space: 12 }) {
        TextInput({ text: this.formName, placeholder: '课程名称' })
          .onChange(value => this.formName = value);
        TextInput({ text: this.formTeacher, placeholder: '教师' })
          .onChange(value => this.formTeacher = value);
        TextInput({ text: this.formRoom, placeholder: '教室 / 地点' })
          .onChange(value => this.formRoom = value);
        Text('星期').width('100%').fontSize(14);
        Select(DAY_OPTIONS)
          .selected(this.formDay - 1)
          .onSelect((index: number) => this.formDay = index + 1)
          .width('100%');
        Row({ space: 10 }) {
          Column() {
            Text('开始节次').fontSize(14);
            TextInput({ text: this.formStartSection }).type(InputType.Number)
              .onChange(value => this.formStartSection = value);
          }.layoutWeight(1);
          Column() {
            Text('结束节次').fontSize(14);
            TextInput({ text: this.formEndSection }).type(InputType.Number)
              .onChange(value => this.formEndSection = value);
          }.layoutWeight(1);
        }
        Row({ space: 10 }) {
          Column() {
            Text('开始周').fontSize(14);
            TextInput({ text: this.formStartWeek }).type(InputType.Number)
              .onChange(value => this.formStartWeek = value);
          }.layoutWeight(1);
          Column() {
            Text('结束周').fontSize(14);
            TextInput({ text: this.formEndWeek }).type(InputType.Number)
              .onChange(value => this.formEndWeek = value);
          }.layoutWeight(1);
        }
        Text('周类型').width('100%').fontSize(14);
        Select([{ value: '每周' }, { value: '单周' }, { value: '双周' }])
          .selected(this.formWeekType)
          .onSelect((index: number) => this.formWeekType = index)
          .width('100%');
        Text('颜色').width('100%').fontSize(14);
        Row({ space: 8 }) {
          ForEach([0, 1, 2, 3, 4, 5], (index: number) => {
            Button('  ')
              .width(36).height(36)
              .backgroundColor(COLORS[index])
              .border({ width: this.formColor === index ? 3 : 0, color: '#245642' })
              .onClick(() => this.formColor = index);
          });
        }
        TextInput({ text: this.formNote, placeholder: '备注' })
          .onChange(value => this.formNote = value);
        Button('保存课程').width('100%').onClick(() => this.saveCourseForm());
        if (this.editingId > 0) {
          Button(this.deleteArmed ? '再次点击确认删除' : '删除课程')
            .width('100%')
            .backgroundColor('#FBE5E2')
            .fontColor('#8B3430')
            .onClick(() => this.deleteCourse());
        }
      }
      .padding(16)
      .width('100%');
    }
    .layoutWeight(1);
  }

  @Builder
  private semesterPage() {
    this.header('学期设置');
    Column({ space: 14 }) {
      Text('学期名称').width('100%');
      TextInput({ text: this.semesterName }).onChange(value => this.semesterName = value);
      Text('开学日期（YYYY-MM-DD）').width('100%');
      TextInput({ text: this.semesterDate }).onChange(value => this.semesterDate = value);
      Text('总周数').width('100%');
      TextInput({ text: this.semesterWeeks }).type(InputType.Number)
        .onChange(value => this.semesterWeeks = value);
      Button('保存学期').width('100%').onClick(() => this.saveSemesterForm());
    }
    .width('100%')
    .padding(16);
  }

  @Builder
  private settingsPage() {
    this.header('显示设置');
    Column({ space: 18 }) {
      Row() {
        Text('显示周末').layoutWeight(1);
        Toggle({ type: ToggleType.Switch, isOn: this.state.settings.showWeekend })
          .onChange((value: boolean) => this.setWeekend(value));
      }
      Row() {
        Text('显示尚未开课的课程').layoutWeight(1);
        Toggle({ type: ToggleType.Switch, isOn: this.state.settings.showInactiveCourses })
          .onChange((value: boolean) => this.setInactive(value));
      }
    }
    .width('100%')
    .padding(20);
  }

  @Builder
  private backupPage() {
    this.header('备份与迁移');
    Column({ space: 18 }) {
      Text('可导入 Android 晴课表导出的 JSON 完整备份，也可导出当前鸿蒙课表。')
        .fontSize(15).width('100%');
      Button('选择 JSON 备份').width('100%').onClick(() => this.selectImport());
      Button('导出 JSON 备份').width('100%').enabled(this.ready)
        .onClick(() => this.exportBackup());
      Text('导入前会显示学期和课程数量；确认后替换当前课表。')
        .fontSize(13).fontColor('#6B8175').width('100%');
    }
    .width('100%')
    .padding(16);
  }

  @Builder
  private confirmImportPage() {
    this.header('确认导入');
    Column({ space: 18 }) {
      Text(this.pendingImport ? this.pendingImport.semester.name : '')
        .fontSize(20).fontWeight(FontWeight.Bold);
      Text(this.pendingImport ? `${this.pendingImport.courses.length} 门课程 · ${this.pendingImport.semester.totalWeeks} 周` : '');
      Text('确认后将替换本机当前课表。建议先导出备份。')
        .fontColor('#8B3430');
      Button('确认替换').width('100%').onClick(() => this.confirmImport());
      Button('取消').width('100%').backgroundColor('#E4EEE9').fontColor('#245642')
        .onClick(() => { this.pendingImport = null; this.screen = 'backup'; });
    }
    .width('100%')
    .padding(16);
  }

  build() {
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
        Text(this.message)
          .fontSize(12)
          .fontColor('#8B3430')
          .width('100%')
          .padding(10);
      }
    }
    .height('100%')
    .width('100%')
    .backgroundColor('#F6F8F5');
  }
}
