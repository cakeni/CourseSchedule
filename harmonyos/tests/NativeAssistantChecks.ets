import { common } from '@kit.AbilityKit';
import { router } from '@kit.ArkUI';
import { fileIo } from '@kit.CoreFileKit';
import { Course, ScheduleState, defaultState, exportJson } from '../model/ScheduleCore';
import { AssistantConfig, AssistantHistory, AssistantPlan, defaultAssistantConfig, parseAssistantResponse,
  applyAssistantPlan, undoAssistantChange, restoreAssistantHistory, assistantPlanSummary } from '../model/CourseAssistant';
import { CourseAssistantEngine, AssistantTransport } from '../model/CourseAssistantEngine';
import { loadState, saveState, saveDarkMode } from '../data/ScheduleStore';
import { loadAssistantConfig, saveAssistantConfig, clearAssistantConfig } from '../data/AssistantCredentials';

class NoNetwork implements AssistantTransport {
  calls: number = 0;
  async request(_: AssistantConfig, __: string): Promise<string> { this.calls++; throw new Error('fixture network disabled'); }
  cancel(): void {}
}
interface NativeMessage { content: string }
interface NativeChoice { finish_reason: string; message: NativeMessage }
interface NativeResponse { choices: NativeChoice[] }
@Entry
@Component
struct NativeAssistantChecks {
  @State report: string = '本机课程助手检查';
  @State ready: boolean = false;
  private context(): common.UIAbilityContext { return this.getUIContext().getHostContext() as common.UIAbilityContext; }
  private course(): Course {
    return { id: 1, courseName: '神经网络与深度学习导论', teacher: '郑津', classroom: '明理楼B407', dayOfWeek: 2,
      startSection: 3, endSection: 5, startWeek: 1, endWeek: 7, weekType: 0, semesterId: 1, colorIndex: 4,
      note: 'DeepSeek 网页识别，请核对', reminderMinutes: -1, createTime: 123 };
  }
  private initial(): ScheduleState { const value = defaultState(); value.courses = [this.course()]; return value; }
  private plan(state: ScheduleState, occurrence: boolean = false): AssistantPlan {
    const content = occurrence ? '{"version":1,"action":"change","reply":"单次调课","occurrences":[{"id":1,"week":2,"dayOfWeek":4,"startSection":5}]}' :
      '{"version":1,"action":"change","reply":"调课","updates":[{"id":1,"dayOfWeek":4,"startSection":5}]}';
    const response: NativeResponse = { choices: [{ finish_reason: 'stop', message: { content } }] };
    return parseAssistantResponse(JSON.stringify(response), state, 1);
  }
  private assert(value: boolean, label: string): void { if (!value) throw new Error(label); }
  private record(value: string): void {
    this.report += '\n' + value;
    const file = fileIo.openSync(this.context().filesDir + '/assistant-native-checks.txt', fileIo.OpenMode.CREATE | fileIo.OpenMode.WRITE_ONLY | fileIo.OpenMode.TRUNC);
    fileIo.writeSync(file.fd, this.report); fileIo.closeSync(file);
  }
  private async check(label: string, action: () => Promise<void>): Promise<void> {
    try { await action(); this.record('PASS ' + label); } catch (error) { this.record('FAIL ' + label + ': ' + (error as Error).message); }
  }
  aboutToAppear(): void { this.run(); }
  private async run(): Promise<void> {
    const context = this.context(); const initial = this.initial();
    await this.check('Preferences 课表和对话往返', async () => {
      initial.assistantChats = 'native roundtrip'; await saveState(context, initial);
      this.assert((await loadState(context)).assistantChats === 'native roundtrip', '本机持久化不一致');
      initial.assistantChats = undefined;
    });
    await saveState(context, initial);
    const transport = new NoNetwork(); const engine = new CourseAssistantEngine(initial, () => loadState(context), next => saveState(context, next), transport);
    await engine.initialize();
    await this.check('本地查询无需 Key 或网络', async () => {
      await engine.send('周二有哪些课', defaultAssistantConfig(), 1);
      this.assert(transport.calls === 0 && engine.messages[engine.messages.length - 1].content.includes('神经网络'), '本地查询结果错误');
    });
    await this.check('确认前不写入课程', async () => {
      await engine.receivePlan(this.plan(initial), 1);
      this.assert((await loadState(context)).courses[0].dayOfWeek === 2 && engine.chat.pending !== null, '确认前发生写入');
    });
    await this.check('原生重启保留待确认', async () => {
      await engine.initialize(); this.assert(engine.chat.pending !== null, '待确认丢失');
    });
    await this.check('确认保存课时和备注', async () => {
      await engine.confirm(); const value = (await loadState(context)).courses[0];
      this.assert(value.dayOfWeek === 4 && value.startSection === 5 && value.endSection === 7 && value.note === this.course().note, '字段未保留');
    });
    await this.check('回执与撤销记录和课程同步保存', async () => {
      const value = await loadState(context); const history = restoreAssistantHistory(value);
      this.assert(value.courses[0].dayOfWeek === 4 && history.chats[0].undo !== null && history.chats[0].pending === null, '原子回执不一致');
    });
    await this.check('重启不重放且撤销恢复原安排', async () => {
      await engine.initialize(); await engine.undo(); this.assert((await loadState(context)).courses[0].dayOfWeek === 2, '撤销错误');
    });
    await this.check('单次调课拆分及撤销', async () => {
      const change = applyAssistantPlan(initial, this.plan(initial, true));
      this.assert(change.state.courses.length > 1 && undoAssistantChange(change.state, change.undo).courses.length === 1, '单次调课不正确');
    });
    await this.check('备份排除会话', async () => {
      initial.assistantChats = 'private history';
      this.assert(!exportJson(initial).includes('private history'), '备份包含会话'); initial.assistantChats = undefined;
    });
    await this.check('AssetStore 保存与读取独立 Key', async () => {
      const config = defaultAssistantConfig(); config.apiKey = 'native-test-only'; config.rememberKey = true;
      await saveAssistantConfig(context, config); this.assert((await loadAssistantConfig(context)).apiKey === config.apiKey, 'AssetStore 不一致');
    });
    await this.check('清除 Key 恢复默认配置', async () => {
      await clearAssistantConfig(context); this.assert((await loadAssistantConfig(context)).apiKey === '', 'Key 未清除');
    });
    await saveState(context, initial); this.ready = true;
  }
  private async seed(dark: boolean, empty: boolean = false): Promise<void> {
    const context = this.context(); const state = this.initial();
    if (empty) state.courses = [];
    else state.courses[0].note = 'DeepSeek 网页识别，请核对。\n' + '课程资料与作业安排完整保留，请阅读后核对。\n'.repeat(30) + '末尾校验：原备注保留。';
    await saveState(context, state); await saveDarkMode(context, dark);
    if (!empty) {
      const engine = new CourseAssistantEngine(state, () => loadState(context), next => saveState(context, next), new NoNetwork());
      await engine.initialize(); await engine.receivePlan(this.plan(state), 1);
      this.assert(assistantPlanSummary(engine.chat.pending!).includes('末尾校验'), '预览不完整');
    }
    router.replaceUrl({ url: 'pages/Index' });
  }
  build() {
    Column({ space: 10 }) {
      Scroll() { Text(this.report).id('native-report').fontSize(14).width('100%'); }.layoutWeight(1).width('100%');
      Button('长方案 · 浅色').id('seed-light').enabled(this.ready).onClick(() => this.seed(false));
      Button('长方案 · 深色').id('seed-dark').enabled(this.ready).onClick(() => this.seed(true));
      Button('清空测试课表').id('seed-empty').enabled(this.ready).onClick(() => this.seed(false, true));
    }.width('100%').height('100%').padding(20).backgroundColor('#FFFFFF');
  }
}
