import { common } from '@kit.AbilityKit';
import { KeyboardAvoidMode } from '@kit.ArkUI';
import { CourseAssistantEngine } from '../model/CourseAssistantEngine';
import { AssistantConfig, AssistantChat, AssistantMessage, AssistantPlan, defaultAssistantConfig,
  assistantQuickQuery, assistantPlanSummary } from '../model/CourseAssistant';
import { defaultState, currentSemesterId } from '../model/ScheduleCore';
import { loadState, saveState } from '../data/ScheduleStore';
import { AssistantClient } from '../data/AssistantClient';
import { loadAssistantConfig, saveAssistantConfig, clearAssistantConfig } from '../data/AssistantCredentials';
import { loadAiKey } from '../data/AiCredentials';
import { syncReminders } from '../data/CourseReminders';
import { LIGHT, DARK, SchedulePalette } from './ScheduleTheme';

@Component
export struct CourseAssistant {
  @Prop dark: boolean = false;
  @Prop displayedWeek: number = 1;
  @Prop reminderStatus: string = '';
  onClose: () => void = () => {};
  onSettings: () => void = () => {};
  onBackHandler: (handler: () => boolean) => void = (_: () => boolean) => {};
  @State private ready: boolean = false;
  @State private messages: AssistantMessage[] = [];
  @State private chats: AssistantChat[] = [];
  @State private title: string = '新对话';
  @State private semesterName: string = '';
  @State private draft: string = '';
  @State private pending: AssistantPlan | null = null;
  @State private summary: string = '';
  @State private busy: boolean = false;
  @State private canStop: boolean = false;
  @State private stage: string = '';
  @State private retryAvailable: boolean = false;
  @State private undoAvailable: boolean = false;
  @State private undoReason: string = '';
  @State private notice: string = '';
  @State private reminderNotice: string = '';
  @State private configured: boolean = false;
  @State private modelName: string = 'deepseek-flash';
  @State private historyOpen: boolean = false;
  @State private configOpen: boolean = false;
  @State private settingsBusy: boolean = false;
  @State private apiUrl: string = 'https://api.deepseek.com';
  @State private apiModel: string = 'deepseek-flash';
  @State private apiKey: string = '';
  @State private jsonMode: boolean = true;
  @State private rememberKey: boolean = false;
  @State private configError: string = '';
  @State private focused: boolean = false;
  @State private viewHeight: number = 720;
  @State private hasOlder: boolean = false;
  private config: AssistantConfig = defaultAssistantConfig();
  private engine: CourseAssistantEngine | null = null;
  private client: AssistantClient = new AssistantClient();
  private listScroller: Scroller = new Scroller();
  private previewScroller: Scroller = new Scroller();
  private inputController: TextAreaController = new TextAreaController();
  private visibleCount: number = 60;
  private activeId: number = 0;
  private messageCount: number = 0;
  private draftTimer: number = -1;
  private alive: boolean = false;
  private closing: boolean = false;

  private context(): common.UIAbilityContext { return this.getUIContext().getHostContext() as common.UIAbilityContext; }
  private palette(): SchedulePalette { return this.dark ? DARK : LIGHT; }
  private accent(): string { return this.dark ? '#8BB7E7' : LIGHT.primary; }
  private muted(): string { return this.dark ? '#95A4B7' : '#667487'; }
  aboutToAppear(): void {
    this.alive = true; this.reminderNotice = this.reminderStatus;
    this.getUIContext().setKeyboardAvoidMode(KeyboardAvoidMode.RESIZE);
    this.onBackHandler(() => {
      if (this.configOpen) { this.dismissConfig(); return true; }
      if (this.historyOpen) { this.historyOpen = false; return true; }
      this.leave(); return true;
    });
    this.initialize();
  }
  aboutToDisappear(): void {
    this.alive = false; if (this.draftTimer >= 0) clearTimeout(this.draftTimer);
    if (!this.closing && this.engine) {
      if (this.engine.canStop) this.engine.stop().catch(() => {});
      else this.engine.persistDraft().catch(() => {});
    }
    this.client.cancel(); this.config.apiKey = ''; this.apiKey = ''; this.onBackHandler(() => false);
  }
  private async initialize(): Promise<void> {
    this.notice = '';
    try {
      const context = this.context(); const state = await loadState(context);
      this.engine = new CourseAssistantEngine(state, () => loadState(context), next => saveState(context, next), this.client);
      this.engine.onChange = () => this.refresh();
      this.engine.onCoursesChanged = async (next) => { this.reminderNotice = await syncReminders(context, next); };
      await this.engine.initialize();
      try { this.config = await loadAssistantConfig(context); }
      catch (error) { this.notice = (error as Error).message; this.config = defaultAssistantConfig(); }
      this.configured = !!this.config.apiKey; this.modelName = this.config.model; this.ready = true; this.refresh();
    } catch (error) { this.notice = '对话读取失败：' + (error as Error).message; }
  }
  private refresh(): void {
    if (!this.alive || !this.engine || !this.engine.chat) return;
    const chat = this.engine.chat; const changed = this.activeId !== chat.id;
    const countChanged = this.messageCount !== chat.messages.length;
    if (changed) { this.visibleCount = 60; this.draft = chat.draft; }
    else if (this.engine.busy && countChanged) this.draft = chat.draft;
    const wasPending = this.pending;
    this.activeId = chat.id; this.messageCount = chat.messages.length; this.title = chat.title;
    this.semesterName = this.engine.state.semester.name;
    const visible = chat.messages.filter((item: AssistantMessage, index: number) =>
      !(chat.pending && index === chat.messages.length - 1 && item.kind === 'confirmation'));
    this.messages = visible.slice(-this.visibleCount); this.hasOlder = visible.length > this.visibleCount;
    this.chats = this.engine.history.chats.filter(item => item.semesterId === currentSemesterId(this.engine!.state))
      .sort((a, b) => b.updatedAt - a.updatedAt);
    this.pending = chat.pending; this.summary = chat.pending ? assistantPlanSummary(chat.pending) : '';
    this.busy = this.engine.busy; this.canStop = this.engine.canStop; this.stage = this.engine.stage;
    this.retryAvailable = !!chat.retry; this.undoAvailable = this.engine.canUndo; this.undoReason = this.engine.undoReason;
    if (this.engine.notice) this.notice = this.engine.notice;
    if (chat.pending && (changed || countChanged)) {
      this.inputController.stopEditing(); this.focused = false;
      setTimeout(() => { if (this.alive) this.previewScroller.scrollTo({ xOffset: 0, yOffset: 0 }); }, 30);
    }
    if (changed || (countChanged && (this.busy || !wasPending))) this.scrollLatest();
  }
  private scrollLatest(): void {
    setTimeout(() => {
      if (!this.alive) return;
      if (this.messages.length) this.listScroller.scrollToIndex(this.messages.length - 1 + (this.hasOlder ? 1 : 0), false, ScrollAlign.START);
      else this.listScroller.scrollEdge(Edge.Top);
    }, 45);
  }
  private loadOlder(): void {
    const before = this.messages.length; this.visibleCount += 60; this.refresh();
    const added = this.messages.length - before;
    setTimeout(() => { if (this.alive) this.listScroller.scrollToIndex(added + (this.hasOlder ? 1 : 0), false, ScrollAlign.START); }, 40);
  }
  private setDraft(value: string): void {
    this.draft = value.slice(0, 2000); this.engine?.setDraft(this.draft);
    if (this.draftTimer >= 0) clearTimeout(this.draftTimer);
    this.draftTimer = setTimeout(() => {
      if (this.alive && !this.busy) this.engine?.persistDraft().catch((error: Error) => this.notice = error.message);
    }, 400);
  }
  private async perform(action: () => Promise<void>): Promise<void> {
    if (!this.ready || !this.engine) return;
    this.notice = '';
    try { await action(); this.refresh(); }
    catch (error) { this.notice = (error as Error).message; }
  }
  private async send(): Promise<void> {
    if (!this.ready || this.busy || !this.draft.trim() || !this.engine) return;
    if (!this.configured && (this.pending || !assistantQuickQuery(this.draft))) { this.openConfig(); return; }
    if (this.draftTimer >= 0) clearTimeout(this.draftTimer);
    this.inputController.stopEditing(); this.focused = false;
    await this.perform(() => this.engine!.send(this.draft, this.config, this.displayedWeek));
  }
  private async leave(settings: boolean = false): Promise<void> {
    if (this.closing || this.settingsBusy || (this.busy && !this.canStop)) return;
    this.closing = true;
    try {
      if (this.draftTimer >= 0) clearTimeout(this.draftTimer);
      if (this.engine?.canStop) await this.engine.stop();
      this.engine?.setDraft(this.draft); await this.engine?.persistDraft();
      this.inputController.stopEditing(); if (settings) this.onSettings(); else this.onClose();
    } catch (error) { this.closing = false; this.notice = (error as Error).message; }
  }
  private time(timestamp: number): string {
    const date = new Date(timestamp); return `${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`;
  }
  private openConfig(): void {
    if (this.busy) return;
    this.inputController.stopEditing(); this.focused = false;
    this.apiUrl = this.config.baseUrl; this.apiModel = this.config.model; this.apiKey = '';
    this.jsonMode = this.config.jsonMode; this.rememberKey = this.config.rememberKey; this.configError = ''; this.configOpen = true;
  }
  private dismissConfig(): void { if (!this.settingsBusy) { this.apiKey = ''; this.configOpen = false; } }
  private async saveConfig(): Promise<void> {
    if (this.settingsBusy) return;
    this.settingsBusy = true; this.configError = '';
    try {
      const config: AssistantConfig = { baseUrl: this.apiUrl, model: this.apiModel, apiKey: this.apiKey.trim() || this.config.apiKey,
        jsonMode: this.jsonMode, rememberKey: this.rememberKey };
      if (config.baseUrl !== this.config.baseUrl && !this.apiKey.trim()) throw new Error('更换服务地址时请重新填写对应 API Key');
      await saveAssistantConfig(this.context(), config); this.config = config;
      this.configured = true; this.modelName = config.model; this.apiKey = ''; this.configOpen = false;
    } catch (error) { this.configError = (error as Error).message; }
    finally { this.settingsBusy = false; }
  }
  private async clearConfig(): Promise<void> {
    if (this.settingsBusy) return;
    this.settingsBusy = true;
    try { await clearAssistantConfig(this.context()); this.config = defaultAssistantConfig(); this.configured = false; this.modelName = this.config.model; this.apiKey = ''; this.configOpen = false; }
    catch (error) { this.configError = (error as Error).message; }
    finally { this.settingsBusy = false; }
  }
  private async reuseDeepSeek(): Promise<void> {
    try { const key = await loadAiKey('DeepSeek'); if (!key) throw new Error('网页识别尚未保存 DeepSeek Key'); this.apiKey = key; this.configError = ''; }
    catch (error) { this.configError = (error as Error).message; }
  }
  private deleteChat(): void {
    this.getUIContext().showAlertDialog({ title: '删除当前对话', message: '删除消息与操作记录，课程表不会被删除。',
      primaryButton: { value: '保留', action: () => {} }, secondaryButton: { value: '删除对话', action: () => {
        this.historyOpen = false; this.perform(() => this.engine!.deleteConversation());
      } } });
  }
  private resetHistory(): void {
    this.getUIContext().showAlertDialog({ title: '重置对话记录', message: '无法读取的历史对话将被清除。课程和 API 配置会保留。',
      primaryButton: { value: '保留', action: () => {} }, secondaryButton: { value: '重置对话', action: async () => {
        try { const state = await loadState(this.context()); state.assistantChats = undefined; await saveState(this.context(), state); await this.initialize(); }
        catch (error) { this.notice = '重置失败：' + (error as Error).message; }
      } } });
  }

  @Builder private icon(source: Resource, id: string, label: string, action: () => void) {
    Button({ type: ButtonType.Normal }) { Image(source).width(22).height(22).fillColor(this.palette().secondary); }
      .id(id).width(42).height(42).backgroundColor(Color.Transparent).borderRadius(21)
      .accessibilityText(label).enabled(this.ready && !this.busy).onClick(action);
  }
  @Builder private header() {
    Row({ space: 4 }) {
      Button({ type: ButtonType.Normal }) { Image($rawfile('ic_back.svg')).width(23).height(23).fillColor(this.palette().text); }
        .id('assistant-back').width(40).height(44).backgroundColor(Color.Transparent).accessibilityText('返回').onClick(() => this.leave());
      Column({ space: 3 }) {
        Text('COURSE ASSISTANT').fontSize(9).letterSpacing(1.4).fontWeight(FontWeight.Medium).fontColor(this.palette().secondary);
        Text('课程助手').fontSize(22).fontWeight(FontWeight.Bold).fontColor(this.palette().text);
      }.alignItems(HorizontalAlign.Start).layoutWeight(1);
      this.icon($rawfile('ic_assistant_history.svg'), 'assistant-history', '历史对话', () => { this.inputController.stopEditing(); this.focused = false; this.historyOpen = true; });
      this.icon($rawfile('ic_add.svg'), 'assistant-new', '新对话', () => this.perform(() => this.engine!.newConversation()));
    }.height(this.viewHeight < 620 ? 62 : 74).width('100%').padding({ left: 14, right: 16 });
    if (!this.focused) {
      Row({ space: 8 }) {
        Stack() {}.width(6).height(6).borderRadius(3).backgroundColor(this.configured ? '#3F8B77' : this.palette().tertiary);
        Text(this.modelName).fontSize(11).fontWeight(FontWeight.Medium).fontColor(this.palette().secondary).maxLines(1).layoutWeight(1);
        Button(this.configured ? '已配置' : '连接模型', { type: ButtonType.Normal }).id('assistant-config').height(32).fontSize(11)
          .fontColor(this.accent()).backgroundColor(this.palette().container).borderRadius(16).padding({ left: 12, right: 12 })
          .enabled(this.ready && !this.busy).onClick(() => this.openConfig());
      }.padding({ left: 22, right: 22 }).width('100%');
      if (!this.pending || this.viewHeight >= 620) Row({ space: 5 }) {
        Text(this.semesterName || '正在读取课表').fontSize(10).fontColor(this.muted()).maxLines(1).layoutWeight(1);
        Text(`查看第${this.displayedWeek}周`).fontSize(10).fontColor(this.muted());
      }.padding({ left: 22, right: 22, top: 8, bottom: 10 }).width('100%');
    }
  }
  @Builder private starter() {
    Column({ space: 18 }) {
      Stack() { Image($rawfile('ic_assistant.svg')).width(28).height(28).fillColor(this.accent()); }
        .width(60).height(60).borderRadius(20).backgroundColor(this.palette().container).margin({ top: 20 });
      Column({ space: 10 }) {
        Text('一句话，\n理顺你的课表。').fontSize(30).fontWeight(FontWeight.Bold).lineHeight(40).fontColor(this.palette().text).width('100%');
        Text('查询在本地完成。\n修改与删除，先核对再执行。').fontSize(13).lineHeight(21).fontColor(this.palette().secondary).width('100%');
      }.width('100%');
      Column({ space: 10 }) {
        Row({ space: 10 }) {
          this.example('今天有哪些课', '今天有哪些课？', 'assistant-example-query');
          this.example('调整上课时间', '把周二那门课移到周四第5节', 'assistant-example-move');
        }.width('100%');
        Row({ space: 10 }) {
          this.example('添加一门课程', '周三第1–2节添加高等数学，第1–16周，在A101', 'assistant-example-add');
          this.example('设置课前提醒', '把高等数学改为提前10分钟提醒', 'assistant-example-reminder');
        }.width('100%');
      }.width('100%').margin({ top: 5 });
      Text('其它请求会将候选课程信息发送至你配置的 API。').fontSize(10).lineHeight(16).fontColor(this.muted()).width('100%');
    }.padding({ left: 24, right: 24, bottom: 24 }).alignItems(HorizontalAlign.Start).width('100%');
  }
  @Builder private example(label: string, value: string, id: string) {
    Button(label, { type: ButtonType.Normal }).id(id).layoutWeight(1).height(46).fontSize(12).fontColor(this.palette().text)
      .backgroundColor(this.palette().surface).border({ width: 1, radius: 14, color: this.palette().divider })
      .enabled(!this.busy).onClick(() => this.setDraft(value));
  }
  @Builder private message(item: AssistantMessage) {
    Row() {
      if (item.role === 'user') Blank().layoutWeight(1);
      Column({ space: 8 }) {
        Row({ space: 6 }) {
          if (item.role !== 'user') Image($rawfile('ic_assistant.svg')).width(13).height(13).fillColor(this.accent());
          Text(item.role === 'user' ? '你' : '课程助手').fontSize(10).fontWeight(FontWeight.Medium).fontColor(this.palette().secondary);
          Text(this.time(item.createdAt)).fontSize(9).fontColor(this.muted());
          if (item.kind === 'confirmation') Text('待确认').fontSize(9).fontColor(this.accent());
        }.width('100%');
        Text(item.content).id('assistant-message-' + item.id).fontSize(15).lineHeight(24)
          .fontColor(item.role === 'user' && this.dark ? '#EFF5FF' : this.palette().text).width('100%')
          .copyOption(CopyOptions.LocalDevice).selectedBackgroundColor(item.role === 'user' ?
            (this.dark ? '#385D8A' : '#ACCBEF') : (this.dark ? '#315A86' : '#B9D4F2'));
      }.padding(16).width(item.role === 'user' ? '87%' : '93%').alignItems(HorizontalAlign.Start)
        .backgroundColor(item.role === 'user' ? (this.dark ? '#173556' : '#E6EEF8') : this.palette().surface)
        .borderRadius(item.role === 'user' ? { topLeft: 20, topRight: 20, bottomLeft: 20, bottomRight: 5 } :
          { topLeft: 5, topRight: 20, bottomLeft: 20, bottomRight: 20 });
      if (item.role !== 'user') Blank().layoutWeight(1);
    }.width('100%');
  }
  @Builder private preview() {
    Column({ space: 12 }) {
      Row({ space: 10 }) {
        Stack() { Image($rawfile('ic_schedule.svg')).width(18).height(18).fillColor(this.accent()); }
          .width(34).height(34).borderRadius(11).backgroundColor(this.palette().container);
        Column({ space: 3 }) {
          Text('核对课程方案').fontSize(15).fontWeight(FontWeight.Bold).fontColor(this.palette().text);
          Text(this.draft.trim() ? '先发送补充要求，再执行修正后的方案' : '向上滑动查看完整安排').fontSize(10).fontColor(this.palette().secondary);
        }.alignItems(HorizontalAlign.Start).layoutWeight(1);
        if (this.focused) Button('展开', { type: ButtonType.Normal }).fontSize(11).height(32).fontColor(this.accent())
          .backgroundColor(Color.Transparent).onClick(() => { this.inputController.stopEditing(); this.focused = false; });
      }.width('100%');
      if (!this.focused) {
        Divider().color(this.palette().divider).strokeWidth(1);
        Scroll(this.previewScroller) {
          Text(this.summary).id('assistant-preview-text').fontSize(13).lineHeight(22).fontColor(this.palette().text).width('100%')
            .copyOption(CopyOptions.LocalDevice).selectedBackgroundColor(this.dark ? '#315A86' : '#B9D4F2');
        }.id('assistant-preview-scroll').height(Math.max(72, Math.min(168, this.viewHeight * (this.viewHeight < 620 ? 0.18 : 0.22)))).width('100%').align(Alignment.TopStart)
          .scrollBar(BarState.On).nestedScroll({ scrollForward: NestedScrollMode.SELF_ONLY, scrollBackward: NestedScrollMode.SELF_ONLY });
      }
      Row({ space: 10 }) {
        Button('取消方案', { type: ButtonType.Normal }).id('assistant-cancel').height(44).layoutWeight(1).fontSize(13)
          .fontColor(this.palette().secondary).backgroundColor(this.palette().variant).borderRadius(13)
          .enabled(!this.busy).onClick(() => this.perform(() => this.engine!.cancelPending()));
        Button('确认执行', { type: ButtonType.Normal }).id('assistant-confirm').height(44).layoutWeight(1).fontSize(13)
          .fontColor(Color.White).backgroundColor(this.palette().primary).borderRadius(13)
          .enabled(!this.busy && !this.draft.trim()).onClick(() => this.perform(() => this.engine!.confirm()));
      }.width('100%');
    }.padding(16).margin({ left: 16, right: 16, top: 8 }).width('calc(100% - 32vp)').borderRadius(22)
      .border({ width: 1, radius: 22, color: this.palette().divider }).backgroundColor(this.palette().surface);
  }
  @Builder private composer() {
    Column({ space: 6 }) {
      if (this.busy) {
        Row({ space: 8 }) {
          LoadingProgress().width(16).height(16).color(this.palette().primary);
          Text(this.stage || '正在保存').fontSize(11).fontColor(this.palette().secondary).layoutWeight(1);
          if (this.canStop) Button('停止', { type: ButtonType.Normal }).id('assistant-stop').height(30).fontSize(11)
            .backgroundColor(Color.Transparent).fontColor(this.accent()).onClick(() => this.perform(() => this.engine!.stop()));
        }.width('100%').padding({ left: 6, right: 6 });
      }
      Row({ space: 8 }) {
        TextArea({ text: this.draft, placeholder: this.pending ? '补充要调整的内容…' : '想对课表做些什么？', controller: this.inputController })
          .id('assistant-input').layoutWeight(1).height(52).fontSize(14).fontColor(this.palette().text).placeholderColor(this.muted())
          .backgroundColor(Color.Transparent).padding({ top: 15, bottom: 10, left: 12, right: 4 }).maxLength(2000)
          .onChange((value: string) => this.setDraft(value)).onFocus(() => this.focused = true).onBlur(() => this.focused = false)
          .enabled(this.ready && !this.busy);
        Button({ type: ButtonType.Normal }) {
          Image($rawfile('ic_assistant_send.svg')).width(20).height(20).fillColor(Color.White);
        }.id('assistant-send').width(42).height(42).backgroundColor(this.palette().primary).borderRadius(15)
          .accessibilityText(this.pending ? '修改方案' : '发送').enabled(this.ready && !this.busy && !!this.draft.trim()).onClick(() => this.send());
      }.width('100%').padding(6).backgroundColor(this.palette().surface).border({ width: 1, radius: 22, color: this.palette().divider });
    }.padding({ left: 16, right: 16, top: 12, bottom: 24 }).width('100%');
  }
  @Builder private configDialog() {
    Stack({ alignContent: Alignment.Center }) {
      Column({ space: 0 }) {
        Row() {
          Column({ space: 5 }) {
            Text('连接你的模型').fontSize(21).fontWeight(FontWeight.Bold).fontColor(this.palette().text);
            Text('默认 DeepSeek，也支持兼容服务').fontSize(11).fontColor(this.palette().secondary);
          }.alignItems(HorizontalAlign.Start).layoutWeight(1);
          Button('关闭', { type: ButtonType.Normal }).height(36).fontSize(12).backgroundColor(Color.Transparent)
            .fontColor(this.palette().secondary).enabled(!this.settingsBusy).onClick(() => this.dismissConfig());
        }.padding(22).width('100%');
        Scroll() {
          Column({ space: 10 }) {
            Text('API 地址').fontSize(11).fontColor(this.palette().secondary).width('100%');
            TextInput({ text: this.apiUrl, placeholder: 'https://api.deepseek.com' }).id('assistant-api-url').height(48).fontSize(13)
              .fontColor(this.palette().text).backgroundColor(this.palette().variant).borderRadius(13).onChange((value: string) => this.apiUrl = value);
            Text('模型名称').fontSize(11).fontColor(this.palette().secondary).width('100%').margin({ top: 8 });
            TextInput({ text: this.apiModel, placeholder: 'deepseek-flash' }).id('assistant-api-model').height(48).fontSize(13)
              .fontColor(this.palette().text).backgroundColor(this.palette().variant).borderRadius(13).onChange((value: string) => this.apiModel = value);
            Text('API Key').fontSize(11).fontColor(this.palette().secondary).width('100%').margin({ top: 8 });
            TextInput({ text: this.apiKey, placeholder: this.configured ? '已配置，留空保留当前 Key' : '填写服务商的 API Key' })
              .id('assistant-api-key').type(InputType.Password).height(48).fontSize(13).fontColor(this.palette().text)
              .backgroundColor(this.palette().variant).borderRadius(13).onChange((value: string) => this.apiKey = value);
            if (this.apiUrl.replace(/\/+$/, '') === 'https://api.deepseek.com') {
              Button('使用网页识别已保存的 DeepSeek Key', { type: ButtonType.Normal }).height(36).fontSize(10)
                .backgroundColor(Color.Transparent).fontColor(this.accent()).onClick(() => this.reuseDeepSeek());
            }
            Row({ space: 6 }) {
              Checkbox({ name: 'json' }).id('assistant-json-mode').select(this.jsonMode).selectedColor(this.palette().primary).onChange((value: boolean) => this.jsonMode = value);
              Text('约束 JSON 回复格式').fontSize(12).fontColor(this.palette().text);
            }.width('100%');
            Row({ space: 6 }) {
              Checkbox({ name: 'remember' }).id('assistant-remember-key').select(this.rememberKey).selectedColor(this.palette().primary).onChange((value: boolean) => this.rememberKey = value);
              Text('在本机保存 Key').fontSize(12).fontColor(this.palette().text);
            }.width('100%');
            Text('Key 不进入课表备份。快捷查课在本地完成，其它请求会发送候选课程信息。').fontSize(10).lineHeight(17).fontColor(this.palette().secondary).width('100%');
            if (this.configError) Text(this.configError).fontSize(12).fontColor(this.dark ? '#FF9696' : '#B3261E').width('100%');
            Button('清除已保存配置', { type: ButtonType.Normal }).id('assistant-clear-config').height(36).fontSize(11)
              .fontColor(this.palette().secondary).backgroundColor(Color.Transparent).enabled(!this.settingsBusy).onClick(() => this.clearConfig());
          }.padding({ left: 22, right: 22, bottom: 12 }).width('100%');
        }.layoutWeight(1).width('100%').scrollBar(BarState.Auto);
        Button(this.settingsBusy ? '正在保存…' : '保存配置', { type: ButtonType.Normal }).id('assistant-save-config').height(46)
          .width('calc(100% - 44vp)').margin({ left: 22, right: 22, top: 10, bottom: 22 }).fontSize(14).fontColor(Color.White)
          .backgroundColor(this.palette().primary).borderRadius(14).enabled(!this.settingsBusy).onClick(() => this.saveConfig());
      }.width('92%').height('84%').backgroundColor(this.palette().surface).borderRadius(26).clip(true);
    }.width('100%').height('100%').backgroundColor('#660D1726');
  }
  @Builder private historyDialog() {
    Stack({ alignContent: Alignment.Center }) {
      Column({ space: 12 }) {
        Row() {
          Column({ space: 4 }) {
            Text('历史对话').fontSize(22).fontWeight(FontWeight.Bold).fontColor(this.palette().text);
            Text('仅显示当前学期的对话').fontSize(11).fontColor(this.palette().secondary);
          }.alignItems(HorizontalAlign.Start).layoutWeight(1);
          Button('关闭', { type: ButtonType.Normal }).height(36).fontSize(12).fontColor(this.palette().secondary)
            .backgroundColor(Color.Transparent).onClick(() => this.historyOpen = false);
        }.width('100%');
        List({ space: 10 }) {
          ForEach(this.chats, (chat: AssistantChat) => {
            ListItem() {
              Column({ space: 8 }) {
                Text(chat.title).fontSize(15).fontWeight(FontWeight.Medium).fontColor(this.palette().text).maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis }).width('100%');
                Row() {
                  Text(`${chat.messages.length}条消息 · ${this.time(chat.updatedAt)}`).fontSize(11).fontColor(this.palette().secondary).layoutWeight(1);
                  if (chat.pending) Text('待确认').fontSize(10).fontColor(this.accent());
                }.width('100%');
              }.padding(16).width('100%').backgroundColor(chat.id === this.activeId ? this.palette().container : this.palette().variant).borderRadius(16)
                .onClick(() => { this.historyOpen = false; this.perform(() => this.engine!.openConversation(chat.id)); });
            }
          }, (chat: AssistantChat) => String(chat.id));
        }.layoutWeight(1).width('100%').scrollBar(BarState.Auto);
        Button('删除当前对话', { type: ButtonType.Normal }).id('assistant-delete-chat').height(42).width('100%').fontSize(12)
          .fontColor(this.dark ? '#FF9696' : '#B3261E').backgroundColor(Color.Transparent).onClick(() => this.deleteChat());
      }.padding(22).width('92%').height('82%').backgroundColor(this.palette().surface).borderRadius(26);
    }.width('100%').height('100%').backgroundColor('#660D1726');
  }
  build() {
    Stack() {
      Column() {
        this.header();
        List({ scroller: this.listScroller, space: 16 }) {
          if (!this.messages.length && !this.pending) ListItem() { this.starter(); }
          if (this.hasOlder) ListItem() {
            Button('加载更早消息', { type: ButtonType.Normal }).id('assistant-older').width('100%').height(38).fontSize(11)
              .backgroundColor(Color.Transparent).fontColor(this.palette().secondary).onClick(() => this.loadOlder());
          }
          ForEach(this.messages, (item: AssistantMessage) => { ListItem() { this.message(item); } }, (item: AssistantMessage) => String(item.id));
        }.layoutWeight(1).width('100%').padding({ left: 18, right: 18, top: 8, bottom: 10 }).scrollBar(BarState.Off);
        if (this.notice) Text(this.notice).fontSize(11).fontColor(this.dark ? '#FF9696' : '#B3261E').lineHeight(17).width('100%').padding({ left: 22, right: 22, top: 6 });
        if (!this.ready && this.notice) {
          Row({ space: 10 }) {
            Button('重新读取').onClick(() => this.initialize());
            Button('重置对话记录').onClick(() => this.resetHistory());
          }.padding(16).width('100%');
        }
        if (this.pending) this.preview();
        if (!this.focused && !this.pending && !this.busy && (this.retryAvailable || this.undoAvailable || this.reminderNotice)) {
          Row({ space: 8 }) {
            if (this.retryAvailable) Button('重试请求', { type: ButtonType.Normal }).id('assistant-retry').height(34).fontSize(11)
              .fontColor(this.accent()).backgroundColor(this.palette().container).onClick(() => this.perform(() => this.engine!.retry(this.config)));
            if (this.undoAvailable) Button('撤销上次操作', { type: ButtonType.Normal }).id('assistant-undo').height(34).fontSize(11)
              .fontColor(this.accent()).backgroundColor(this.palette().container).onClick(() => this.perform(() => this.engine!.undo()));
            Blank().layoutWeight(1);
            if (this.reminderNotice) Button('提醒状态', { type: ButtonType.Normal }).height(34).fontSize(10).fontColor(this.palette().secondary)
              .backgroundColor(Color.Transparent).onClick(() => this.getUIContext().showAlertDialog({ title: '课程提醒', message: this.reminderNotice,
                primaryButton: { value: '知道了', action: () => {} }, secondaryButton: { value: '提醒设置', action: () => this.leave(true) } }));
          }.width('100%').padding({ left: 18, right: 18, top: 8 });
        }
        this.composer();
      }.width('100%').height('100%').backgroundColor(this.dark ? '#080C12' : '#F4F6FA');
      if (this.historyOpen) this.historyDialog();
      if (this.configOpen) this.configDialog();
    }.width('100%').height('100%').onAreaChange((_: Area, area: Area) => this.viewHeight = Number(area.height));
  }
}
