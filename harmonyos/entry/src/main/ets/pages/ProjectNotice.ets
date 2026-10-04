/*
 * Press envelope and soft-circle profile adapted from Android RippleShader and
 * RippleAnimationSession, Copyright (C) 2021 The Android Open Source Project.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import { AnimatorResult, curves } from '@kit.ArkUI';
import { LIGHT, SchedulePalette } from './ScheduleTheme';

function clampUnit(value: number): number { return Math.max(0, Math.min(1, value)); }
function softCircle(distance: number, radius: number): number {
  if (radius <= 0) return 0;
  const t = clampUnit(distance / radius - 0.5);
  return 1 - t * t * (3 - 2 * t);
}

function noticeGlowGradient(progress: number, touchX: number, touchY: number, width: number, height: number,
  color: number, grainMask: number): RadialGradientOptions {
  const maximum = Math.hypot(width, height) * 1.15;
  const waveRadius = maximum * progress;
  const thickness = maximum * 0.05;
  const radius = Math.max(0.01, (waveRadius + (grainMask ? thickness : 0)) * 1.5);
  const fade = Math.min(clampUnit(progress / 0.13), 1 - clampUnit((progress - 0.4) / (grainMask ? 0.1 : 0.6)));
  const centerFraction = Math.min(1, progress * 2);
  const colors: [ResourceColor, number][] = [];
  for (let index = 0; index <= 32; index++) {
    const fraction = index / 32;
    const distance = fraction * radius;
    const alpha = fade * (grainMask ? Math.max(0, softCircle(distance, waveRadius + thickness) -
      softCircle(distance, Math.max(0, waveRadius - thickness))) : softCircle(distance, waveRadius));
    colors.push(['#' + Math.round(alpha * 255).toString(16).padStart(2, '0') + Math.round(color).toString(16).padStart(6, '0'), fraction]);
  }
  return { center: [touchX + (width / 2 - touchX) * centerFraction,
    touchY + (height / 2 - touchY) * centerFraction], radius, colors };
}

@Component
export struct ProjectNotice {
  @Prop palette: SchedulePalette = LIGHT;
  onOpen: () => void = () => {};
  onCopy: () => void = () => {};
  @State private pressed: boolean = false;
  @State private progress: number = 1;
  @State private touchX: number = 0;
  @State private touchY: number = 0;
  @State private cardWidth: number = 0;
  @State private cardHeight: number = 0;
  private generation: number = 0;
  private pressStarted: number = 0;
  private glowAnimator: AnimatorResult | undefined = undefined;
  private readonly pressCurve = curves.cubicBezierCurve(0.22, 1, 0.36, 1);

  private playGlow(begin: number, end: number, duration: number, easing: string): void {
    this.glowAnimator?.cancel();
    const motion = this.getUIContext().createAnimator({ begin, end, duration, easing,
      delay: 0, fill: 'forwards', direction: 'normal', iterations: 1 });
    motion.onFrame = (progress: number) => this.progress = progress;
    motion.onFinish = () => this.progress = end;
    this.glowAnimator = motion;
    motion.play();
  }

  private handleTouch(event: TouchEvent): void {
    if (event.type === TouchType.Down && event.changedTouches.length) {
      const touch = event.changedTouches[0];
      this.generation++; this.pressStarted = Date.now(); this.pressed = true;
      this.touchX = touch.x; this.touchY = touch.y; this.progress = 0;
      this.playGlow(0, 0.5, 450, 'cubic-bezier(0.4, 0, 0.2, 1)');
    } else if (this.pressed && (event.type === TouchType.Up || event.type === TouchType.Cancel ||
      (event.type === TouchType.Move && event.changedTouches.some(touch => touch.x < 0 || touch.y < 0 ||
        touch.x > this.cardWidth || touch.y > this.cardHeight)))) {
      this.pressed = false;
      const generation = this.generation;
      setTimeout(() => {
        if (generation !== this.generation) return;
        this.playGlow(0.5, 1, 375, 'linear');
      }, Math.max(0, 450 - (Date.now() - this.pressStarted)));
    }
  }

  aboutToDisappear(): void { this.generation++; this.glowAnimator?.cancel(); }

  @Builder
  private glow() {
    Stack() {
      Column().width(this.cardWidth).height(this.cardHeight)
        .radialGradient(noticeGlowGradient(this.progress, this.touchX, this.touchY, this.cardWidth, this.cardHeight,
          Number.parseInt(this.palette.surface.substring(1), 16), 0));
      Stack() {
        Column().width(this.cardWidth).height(this.cardHeight)
          .radialGradient(noticeGlowGradient(this.progress, this.touchX, this.touchY, this.cardWidth, this.cardHeight, 0xFFFFFF, 1));
        // ponytail: fixed grain samples; the animated mask supplies the moving band.
        // Use a time-varying shader if individual-grain twinkle needs to match too.
        Image($rawfile('press_grain.png')).width(this.cardWidth).height(this.cardHeight).objectFit(ImageFit.Fill)
          .blendMode(BlendMode.SRC_IN);
      }.width(this.cardWidth).height(this.cardHeight).blendMode(BlendMode.SRC_OVER, BlendApplyType.OFFSCREEN);
    }.width(this.cardWidth).height(this.cardHeight).hitTestBehavior(HitTestMode.None).accessibilityLevel('no');
  }

  build() {
    Stack() {
      Column({ space: 6 }) {
        Text('项目公告 · 晴课表已开源').fontSize(16).fontWeight(FontWeight.Bold).fontColor(this.palette.text).width('100%');
        Text('采用 MIT 许可证。点击查看源码、反馈问题或参与贡献；长按可复制项目地址。')
          .fontSize(12).fontColor(this.palette.secondary).width('100%');
        Text('https://github.com/cakeni/CourseSchedule').fontSize(12).fontColor(this.palette.primary).width('100%').margin({ top: 2 });
      }.padding(16).width('100%');
    }.width('100%').borderRadius(20).backgroundColor(this.palette.container).clip(true)
      .overlay(this.glow(), { align: Alignment.TopStart }).id('project-notice')
      .onAreaChange((_old: Area, area: Area) => { this.cardWidth = Number(area.width); this.cardHeight = Number(area.height); })
      .scale({ x: this.pressed ? 0.98 : 1, y: this.pressed ? 0.98 : 1 })
      .animation({ duration: this.pressed ? 115 : 245, curve: this.pressCurve })
      .onTouch((event: TouchEvent) => this.handleTouch(event))
      .onClick(() => this.onOpen()).gesture(LongPressGesture().onAction(() => this.onCopy()));
  }
}
