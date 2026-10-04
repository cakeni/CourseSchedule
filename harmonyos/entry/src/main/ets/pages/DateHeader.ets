import { curves } from '@kit.ArkUI';
import measure from '@ohos.measure';
import { DateGlyph, dateGlyphLayout, retargetDateGlyphs } from '../model/DateDigitMotion';

@Component
export struct DateHeader {
  @Prop @Watch('update') date: string = '';
  @Prop @Watch('update') summary: string = '';
  @Prop forward: boolean = true;
  @Prop color: string = '#202A36';
  @Prop secondary: string = '#68778B';
  @State private dateGlyphs: DateGlyph[] = [];
  @State private summaryGlyphs: DateGlyph[] = [];
  @State private progress: number = 1;
  private previousDate: string = '';
  private previousSummary: string = '';
  private generation: number = 0;

  private layout(text: string, size: number, bold: boolean): DateGlyph[] {
    const widths = Array.from(text, c => px2vp(Number(measure.measureTextSize({ textContent: c, fontSize: size, fontWeight: bold ? 'bold' : 'normal' }).width)));
    return dateGlyphLayout(text, widths);
  }
  aboutToAppear(): void {
    this.dateGlyphs = this.layout(this.date, 26, true); this.summaryGlyphs = this.layout(this.summary, 14, false);
    this.previousDate = this.date; this.previousSummary = this.summary;
  }
  private update(): void {
    if (this.date === this.previousDate && this.summary === this.previousSummary) return;
    const nextDate = this.layout(this.date, 26, true); const nextSummary = this.layout(this.summary, 14, false);
    const distance = this.forward ? 12 : -12; const generation = ++this.generation;
    this.dateGlyphs = retargetDateGlyphs(this.dateGlyphs, nextDate, this.progress, distance);
    this.summaryGlyphs = retargetDateGlyphs(this.summaryGlyphs, nextSummary, this.progress, distance * 0.6);
    this.previousDate = this.date; this.previousSummary = this.summary; this.progress = 0;
    setTimeout(() => {
      if (generation !== this.generation) return;
      this.getUIContext().animateTo({ duration: 340, curve: curves.cubicBezierCurve(0.2, 0, 0.1, 1), expectedFrameRateRange: { min: 30, max: 120, expected: 60 },
        onFinish: () => { if (generation === this.generation) { this.dateGlyphs = nextDate; this.summaryGlyphs = nextSummary; } }
      }, () => this.progress = 1);
    }, 16);
  }
  @Builder private row(glyphs: DateGlyph[], size: number, bold: boolean, color: string) {
    Stack({ alignContent: Alignment.TopStart }) {
      ForEach(glyphs, (g: DateGlyph) => {
        Text(g.text).fontSize(size).fontWeight(bold ? FontWeight.Bold : FontWeight.Normal).fontColor(color)
          .position({ x: g.fromX + (g.toX - g.fromX) * this.progress, y: g.fromY + (g.toY - g.fromY) * this.progress })
          .opacity(g.fromAlpha + (g.toAlpha - g.fromAlpha) * this.progress);
      }, (g: DateGlyph) => g.slot + ':' + g.text);
    }.width('100%').height(size * 1.35).clip(true);
  }
  build() {
    Column({ space: 4 }) {
      this.row(this.dateGlyphs, 26, true, this.color);
      this.row(this.summaryGlyphs, 14, false, this.secondary);
    }.width('100%').height(58).accessibilityText(this.date + '，' + this.summary);
  }
}
