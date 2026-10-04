import { curves } from '@kit.ArkUI';

const PRESS_CURVE = curves.cubicBezierCurve(0.22, 1, 0.36, 1);
function overshoot(tension: number): curves.ICurve {
  return curves.customCurve((fraction: number) => { const t = fraction - 1; return t * t * ((tension + 1) * t + tension) + 1; });
}

@Component
export struct NavigationItem {
  @Prop label: string = '';
  @Prop target: string = '';
  @Prop source: Resource = $rawfile('ic_schedule.svg');
  @Prop selected: boolean = false;
  @Prop selectedColor: string = '#416795';
  @Prop inactiveColor: string = '#8A96A6';
  @Prop indicatorColor: string = '#DEEAF7';
  onSelected: () => void = () => {};
  @State private iconScale: number = 1;
  @State private iconAngle: number = 0;
  @State private iconOffset: number = 0;
  private generation: number = 0;

  private playMotion(): void {
    const generation = ++this.generation;
    const finish = (): void => {
      if (generation !== this.generation) return;
      this.getUIContext().animateTo({ duration: this.target === 'backup' ? 430 : 520,
        curve: overshoot(this.target === 'backup' ? 0.68 : this.target === 'schedule' ? 0.7 : 0.6),
        expectedFrameRateRange: { min: 30, max: 120, expected: 60 } }, () => {
        this.iconScale = 1; this.iconAngle = 0; this.iconOffset = 0;
      });
    };
    this.getUIContext().animateTo({ duration: this.target === 'schedule' ? 280 : this.target === 'backup' ? 220 : 400,
      curve: PRESS_CURVE, expectedFrameRateRange: { min: 30, max: 120, expected: 60 }, onFinish: () => {
        if (generation !== this.generation) return;
        if (this.target === 'backup') this.getUIContext().animateTo({ duration: 300, curve: PRESS_CURVE, onFinish: finish }, () => {
          this.iconOffset = -8; this.iconScale = 1.07;
        });
        else finish();
      }
    }, () => {
      if (this.target === 'schedule') { this.iconAngle = -38; this.iconScale = 0.91; }
      else if (this.target === 'backup') { this.iconOffset = 4; this.iconScale = 0.9; }
      else { this.iconAngle = 88; this.iconScale = 0.93; }
    });
  }

  aboutToDisappear(): void { this.generation++; }

  build() {
    Column({ space: 6 }) {
      Stack() {
        Image(this.source).width(22).height(22).fillColor(this.selected ? this.selectedColor : this.inactiveColor).objectFit(ImageFit.Contain)
          .scale({ x: this.iconScale, y: this.iconScale })
          .rotate({ x: 0, y: this.target === 'schedule' ? 1 : 0, z: this.target === 'schedule' ? 0 : 1, angle: this.iconAngle })
          .translate({ y: this.iconOffset });
      }.width(58).height(34).borderRadius(17).backgroundColor(this.selected ? this.indicatorColor : Color.Transparent);
      Text(this.label).fontSize(12).fontColor(this.selected ? this.selectedColor : this.inactiveColor)
        .fontWeight(this.selected ? FontWeight.Bold : FontWeight.Normal);
    }.height(72).width('100%').justifyContent(FlexAlign.Center)
      .id('nav-' + this.target).accessibilityText(this.label).onClick(() => { this.onSelected(); this.playMotion(); });
  }
}
