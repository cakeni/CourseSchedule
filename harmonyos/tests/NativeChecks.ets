import { common } from '@kit.AbilityKit';
import { util } from '@kit.ArkTS';
import { fileIo } from '@kit.CoreFileKit';
import { webview } from '@kit.ArkWeb';
import { parseImportBytes } from '../data/BackupFiles';
import { defaultState } from '../model/ScheduleCore';
import { AcademicSchool, captureScript, AcademicDefinitions, decodeWebResult } from '../model/AcademicSchools';
import { parseAcademic } from '../model/AcademicParser';

@Entry
@Component
struct NativeChecks {
  @State report: string = '正在运行本机平台校验…';
  private controller: webview.WebviewController = new webview.WebviewController();
  private school: AcademicSchool = { id: 'fixture', name: '本机网页验证', system: 'ZHENGFANG_HTML', profile: 'zhengfang', adapterId: 'zhengfang_jwglxt',
    loginUrl: 'http://127.0.0.1:18766/', trustedHosts: ['127.0.0.1'], loginScopes: ['http://127.0.0.1:18766/'], timetableScopes: ['http://127.0.0.1:18766/'],
    authScopes: [], cleartextHosts: ['127.0.0.1'], allowIpAddress: true, scoped: true, instructions: '' };
  private context(): common.UIAbilityContext { return this.getUIContext().getHostContext() as common.UIAbilityContext; }
  private record(text: string): void {
    this.report += '\n' + text;
    const fd = fileIo.openSync(this.context().filesDir + '/native-validation.txt', fileIo.OpenMode.CREATE | fileIo.OpenMode.WRITE_ONLY | fileIo.OpenMode.TRUNC);
    fileIo.writeSync(fd.fd, this.report); fileIo.closeSync(fd);
  }
  aboutToAppear(): void { this.checkFiles(); }
  private async checkFiles(): Promise<void> {
    for (const name of ['native-deflate.xlsx', 'native-stored.xlsx']) {
      try {
        const bytes = await this.context().resourceManager.getRawFileContent(name);
        const path = this.context().cacheDir + '/' + name;
        const fd = fileIo.openSync(path, fileIo.OpenMode.CREATE | fileIo.OpenMode.READ_WRITE | fileIo.OpenMode.TRUNC);
        fileIo.writeSync(fd.fd, bytes.buffer); const buffer = new ArrayBuffer(bytes.length);
        fileIo.readSync(fd.fd, buffer, { offset: 0 }); fileIo.closeSync(fd);
        const parsed = await parseImportBytes(buffer, name, defaultState());
        if (parsed.courses.length !== 1 || parsed.courses[0].courseName !== '本机Excel验证' || parsed.courses[0].endWeek !== 8) throw new Error('解析结果不一致');
        this.record(name + '：通过'); fileIo.unlinkSync(path);
      } catch (error) { this.record(name + '：失败 ' + JSON.stringify(error)); }
    }
  }
  private async checkCapture(): Promise<void> {
    try {
      const script = util.TextDecoder.create('utf-8').decodeToString(await this.context().resourceManager.getRawFileContent('AcademicCaptureScript.js'));
      const definitions: AcademicDefinitions = JSON.parse(util.TextDecoder.create('utf-8').decodeToString(await this.context().resourceManager.getRawFileContent('academic_profiles.json')));
      const school = this.school;
      const payload = decodeWebResult(await this.controller.runJavaScript(captureScript(script, school, definitions)));
      const parsed = parseAcademic(school, payload, 20);
      if (parsed.courses[0].courseName !== '本机网页验证' || parsed.courses[0].endWeek !== 8) throw new Error('网页解析结果不一致：' + payload.slice(0, 100));
      this.record('ArkWeb 课表采集、来源检查、解析：通过');
    } catch (error) { this.record('ArkWeb 课表采集：失败 ' + String(error)); }
  }
  build() {
    Column() {
      Text(this.report).fontSize(16).fontColor(Color.White).padding(20).width('100%');
      Web({ src: this.school.loginUrl, controller: this.controller }).javaScriptAccess(true).width('100%').layoutWeight(1)
        .onPageEnd((event) => { if (event && event.url !== 'about:blank') this.checkCapture(); });
    }.width('100%').height('100%').backgroundColor('#121212');
  }
}
