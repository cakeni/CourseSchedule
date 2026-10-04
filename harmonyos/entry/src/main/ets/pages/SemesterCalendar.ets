import { calendarMonthDays } from '../model/CalendarDates';
import { LIGHT, SchedulePalette, DAY_NAMES } from './ScheduleTheme';

@Component
export struct SemesterCalendar {
  @Prop dateText: string = '';
  @Prop palette: SchedulePalette = LIGHT;
  onCancel: () => void = () => {};
  onAccept: (date: Date) => void = () => {};
  @State private selectedDate: number = Date.now();
  @State private month: number = Date.now();

  aboutToAppear(): void {
    const date = new Date(this.dateText + 'T00:00:00');
    this.selectedDate = date.getTime();
    this.month = new Date(date.getFullYear(), date.getMonth(), 1).getTime();
  }

  private changeMonth(offset: number): void {
    const month = new Date(this.month);
    this.month = new Date(month.getFullYear(), month.getMonth() + offset, 1).getTime();
  }

  private dayDate(day: number): number {
    const month = new Date(this.month);
    return new Date(month.getFullYear(), month.getMonth(), day).getTime();
  }

  private selectedLabel(): string {
    const date = new Date(this.selectedDate);
    return `${date.getMonth() + 1}月${date.getDate()}日周${DAY_NAMES[(date.getDay() + 6) % 7]}`;
  }

  @Builder
  private monthArrow(previous: boolean) {
    Button({ type: ButtonType.Normal }) {
      Image(previous ? $rawfile('ic_chevron_left.svg') : $rawfile('ic_chevron_right.svg'))
        .width(24).height(24).fillColor(this.palette.text).objectFit(ImageFit.Contain);
    }.id(previous ? 'calendar-previous-month' : 'calendar-next-month').width(48).height(48)
      .backgroundColor(Color.Transparent).accessibilityText(previous ? '上个月' : '下个月')
      .onClick(() => this.changeMonth(previous ? -1 : 1));
  }

  build() {
    Column() {
      Column({ space: 8 }) {
        Text(`${new Date(this.selectedDate).getFullYear()}年`).id('calendar-selected-year')
          .fontSize(18).fontColor('#CCFFFFFF').width('100%');
        Text(this.selectedLabel()).id('calendar-selected-date').fontSize(32).minFontSize(22).maxFontSize(32)
          .maxLines(1).fontColor(Color.White).width('100%');
      }.height(104).width('100%').padding({ left: 24, right: 24, top: 18, bottom: 16 })
        .alignItems(HorizontalAlign.Start).backgroundColor(this.palette.primary);
      Row() {
        this.monthArrow(true);
        Text(`${new Date(this.month).getFullYear()}年${new Date(this.month).getMonth() + 1}月`)
          .id('calendar-month').fontSize(16).fontWeight(FontWeight.Medium).fontColor(this.palette.text)
          .textAlign(TextAlign.Center).layoutWeight(1);
        this.monthArrow(false);
      }.height(64).width('100%').padding({ left: 12, right: 12 });
      Row() {
        ForEach(DAY_NAMES, (name: string) => {
          Text(name).fontSize(14).fontColor(this.palette.secondary).textAlign(TextAlign.Center).layoutWeight(1);
        });
      }.height(32).width('100%').padding({ left: 16, right: 16 });
      Column() {
        ForEach([0, 1, 2, 3, 4, 5], (row: number) => {
          Row() {
            ForEach(calendarMonthDays(new Date(this.month).getFullYear(), new Date(this.month).getMonth()).slice(row * 7, row * 7 + 7), (day: number) => {
              Column() {
                if (day > 0) {
                  Button(String(day), { type: ButtonType.Circle }).id(`calendar-day-${day}`)
                    .width('100%').aspectRatio(1).constraintSize({ maxWidth: 44, maxHeight: 44 }).fontSize(14)
                    .fontWeight(FontWeight.Normal)
                    .backgroundColor(this.selectedDate === this.dayDate(day) ? this.palette.primary : Color.Transparent)
                    .fontColor(this.selectedDate === this.dayDate(day) ? Color.White : this.palette.text)
                    .accessibilityText(`${new Date(this.month).getFullYear()}年${new Date(this.month).getMonth() + 1}月${day}日` +
                      (this.selectedDate === this.dayDate(day) ? '，已选择' : ''))
                    .onClick(() => this.selectedDate = this.dayDate(day));
                }
              }.layoutWeight(1).height(48).justifyContent(FlexAlign.Center);
            }, (_day: number, index: number) => String(index));
          }.width('100%').height(48);
        }, (row: number) => `${this.month}:${row}`);
      }.width('100%').padding({ left: 16, right: 16 });
      Row() {
        Blank();
        Button('取消', { type: ButtonType.Normal }).id('calendar-cancel').width(80).height(48)
          .fontSize(16).fontWeight(FontWeight.Normal).fontColor(this.palette.primary).backgroundColor(Color.Transparent).onClick(this.onCancel);
        Button('确定', { type: ButtonType.Normal }).id('calendar-confirm').width(80).height(48)
          .fontSize(16).fontWeight(FontWeight.Normal).fontColor(this.palette.primary).backgroundColor(Color.Transparent)
          .onClick(() => this.onAccept(new Date(this.selectedDate)));
      }.width('100%').height(64).padding({ left: 8, right: 12, top: 8 });
    }.id('semester-calendar').width('82%').constraintSize({ maxWidth: 360, maxHeight: '90%' })
      .backgroundColor(this.palette.surface).borderRadius(4).clip(true);
  }
}
