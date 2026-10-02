import { ScheduleState, Course, currentSemesterId } from './ScheduleCore';
import { AssistantConfig, AssistantHistory, AssistantChat, AssistantPlan, AssistantRetry, AssistantMessage,
  restoreAssistantHistory, newAssistantChat, appendAssistantMessage, assistantQuickQuery, assistantQuery,
  createAssistantRequest, parseAssistantResponse, applyAssistantPlan, undoAssistantChange, assistantUndoReason,
  assistantNeedsConfirmation, describeAssistantCourse, assistantEndpoint } from './CourseAssistant';

export interface AssistantTransport {
  request(config: AssistantConfig, body: string): Promise<string>;
  cancel(): void;
}
export class CourseAssistantEngine {
  state: ScheduleState;
  history: AssistantHistory = { version: 1, activeId: 0, revision: 0, chats: [] };
  busy: boolean = false;
  canStop: boolean = false;
  stage: string = '';
  notice: string = '';
  onChange: () => void = () => {};
  onCoursesChanged: (state: ScheduleState) => Promise<void> = async (_: ScheduleState) => {};
  private generation: number = 0;
  private draftWrite: Promise<void> = Promise.resolve();

  constructor(initial: ScheduleState, private load: () => Promise<ScheduleState>,
    private save: (state: ScheduleState) => Promise<void>, private transport: AssistantTransport) { this.state = initial; }
  get chat(): AssistantChat { return this.history.chats.find(chat => chat.id === this.history.activeId)!; }
  get undoReason(): string {
    if (!this.chat) return '本对话没有可撤销的操作';
    if (this.chat.pending) return '请先确认或取消当前方案';
    return assistantUndoReason(this.state, this.chat.undo);
  }
  get canUndo(): boolean { return !this.busy && !this.undoReason; }
  get messages(): AssistantMessage[] { return this.chat ? this.chat.messages : []; }
  private copy(): AssistantHistory { return JSON.parse(JSON.stringify(this.history)) as AssistantHistory; }
  private current(history: AssistantHistory): AssistantChat { return history.chats.find(chat => chat.id === history.activeId)!; }
  private scheduleText(state: ScheduleState): string { return JSON.stringify({ ...state, assistantChats: undefined }); }

  // One Preferences value commits the course change, receipt and undo snapshot together.
  private async commit(history: AssistantHistory, changed: ScheduleState | null = null, expected: ScheduleState | null = null): Promise<void> {
    const latest = await this.load(); const stored = restoreAssistantHistory(latest);
    if (stored.revision !== history.revision) throw new Error('对话已在其他页面变化，请重新打开后再操作');
    if (expected && this.scheduleText(latest) !== this.scheduleText(expected)) throw new Error('校验期间课表发生变化，本次未更改，请重试');
    history.revision = stored.revision + 1;
    const next: ScheduleState = { ...latest, ...(changed ? { courses: changed.courses } : {}), assistantChats: JSON.stringify(history) };
    await this.save(next);
    // Keep typing that arrived during a draft save; the next debounce persists it.
    const liveDraft = !this.busy && this.chat?.id === history.activeId ? this.chat.draft : null;
    this.state = next; this.history = history;
    if (liveDraft !== null) this.current(history).draft = liveDraft;
    this.onChange();
    if (changed) {
      try { await this.onCoursesChanged(next); } catch (_) { this.notice = '课程已保存，提醒同步暂未完成，可在设置中重试'; }
      this.onChange();
    }
  }
  async initialize(): Promise<void> {
    this.state = await this.load(); this.history = restoreAssistantHistory(this.state);
    const history = this.copy(); const semesterId = currentSemesterId(this.state);
    let current = history.chats.find(chat => chat.id === history.activeId && chat.semesterId === semesterId);
    if (!current) {
      current = history.chats.filter(chat => chat.semesterId === semesterId).sort((a, b) => b.updatedAt - a.updatedAt)[0];
      if (!current) { current = newAssistantChat(this.state, history); history.chats.push(current); }
      history.activeId = current.id;
    }
    for (const chat of history.chats) {
      if (chat.running) { chat.running = false; appendAssistantMessage(chat, 'assistant', '上次请求已中断，没有自动重试或重放课程操作。', 'interrupted'); }
    }
    if (current.pending) {
      try {
        if (current.pendingStart !== this.state.semester.startDate || current.pendingWeeks !== this.state.semester.totalWeeks) throw new Error('学期设置已变化');
        applyAssistantPlan(this.state, current.pending);
      } catch (_) { current.pending = null; current.retry = null; appendAssistantMessage(current, 'assistant', '待确认方案已过期或无法读取，请重新描述。本次未更改课表。', 'error'); }
    }
    if (current.undo && (!Array.isArray(current.undo.before) || !Array.isArray(current.undo.after) || !Array.isArray(current.undo.baseline))) {
      current.undo = null; appendAssistantMessage(current, 'assistant', '撤销记录无法读取，课表未更改。', 'error');
    }
    await this.commit(history);
  }
  setDraft(value: string): void { if (this.chat && !this.busy) this.chat.draft = value.slice(0, 2000); }
  async persistDraft(): Promise<void> {
    if (!this.busy && this.chat) {
      await this.draftWrite.catch(() => {});
      if (this.busy) return;
      this.draftWrite = this.commit(this.copy()); await this.draftWrite;
    }
  }
  async newConversation(): Promise<void> {
    if (this.busy) return;
    const departingId = this.chat.id; const departingDraft = this.chat.draft;
    this.busy = true; this.onChange();
    try {
      await this.draftWrite.catch(() => {});
      const history = this.copy(); this.state = await this.load(); const chat = newAssistantChat(this.state, history);
      const departing = history.chats.find(item => item.id === departingId); if (departing) departing.draft = departingDraft;
      history.chats.push(chat); history.activeId = chat.id; await this.commit(history);
    } finally { this.busy = false; this.onChange(); }
  }
  async openConversation(id: number): Promise<void> {
    if (this.busy) return;
    const departingId = this.chat.id; const departingDraft = this.chat.draft;
    this.busy = true; this.onChange();
    try {
      await this.draftWrite.catch(() => {});
      const history = this.copy(); const chat = history.chats.find(chat => chat.id === id && chat.semesterId === currentSemesterId(this.state));
      const departing = history.chats.find(item => item.id === departingId); if (departing) departing.draft = departingDraft;
      if (!chat) throw new Error('当前学期找不到该对话'); history.activeId = id; await this.commit(history); await this.initialize();
    } finally { this.busy = false; this.onChange(); }
  }
  async deleteConversation(): Promise<void> {
    if (this.busy || !this.chat) return;
    this.busy = true; this.onChange();
    try {
      await this.draftWrite.catch(() => {});
      const history = this.copy(); history.chats = history.chats.filter(chat => chat.id !== history.activeId);
      const current = newAssistantChat(this.state, history); history.chats.push(current); history.activeId = current.id;
      await this.commit(history);
    } finally { this.busy = false; this.onChange(); }
  }
  async send(value: string, config: AssistantConfig, displayedWeek: number): Promise<void> {
    if (this.busy || !this.chat) return;
    const request = value.trim(); if (!request || request.length > 2000) throw new Error('请填写2000字以内的请求');
    const quick = !this.chat.pending ? assistantQuickQuery(request) : null;
    if (!quick) { assistantEndpoint(config); if (!config.apiKey.trim()) throw new Error('请先填写 API Key'); }
    const generation = ++this.generation; this.busy = true; this.stage = '正在整理课程上下文'; this.onChange();
    let started = false;
    try {
      await this.draftWrite.catch(() => {});
      this.state = await this.load();
      if (this.chat.semesterId !== currentSemesterId(this.state)) throw new Error('当前学期已变化，请重新打开助手');
      const history = this.copy(); const chat = this.current(history); chat.draft = '';
      appendAssistantMessage(chat, 'user', request);
      chat.retry = quick ? null : { text: request, displayedWeek }; chat.running = !quick;
      if (quick) {
        try { this.appendQuery(chat, assistantQuery(this.state, quick, displayedWeek)); }
        catch (error) { appendAssistantMessage(chat, 'assistant', (error as Error).message + '。本次未更改课表。', 'error'); }
        await this.commit(history); return;
      }
      await this.commit(history); started = true;
      const snapshot = this.state;
      const payload = createAssistantRequest(config, snapshot, this.chat, displayedWeek, !this.undoReason);
      this.canStop = true; this.stage = '正在等待模型回复'; this.onChange();
      const response = await this.transport.request(config, payload.body);
      if (generation !== this.generation) return;
      this.canStop = false; this.stage = '正在校验课程方案'; this.onChange();
      const plan = parseAssistantResponse(response, snapshot, displayedWeek, payload.candidateIds);
      await this.receivePlan(plan, displayedWeek);
    } catch (error) {
      if (generation !== this.generation) return;
      if (!started) throw error;
      const history = this.copy(); const chat = this.current(history); chat.running = false;
      appendAssistantMessage(chat, 'assistant', (error as Error).message + '。本次未更改课表，可重试。', 'error');
      try { await this.commit(history); } catch (saveError) { this.notice = '结果保存失败，请重新打开助手：' + (saveError as Error).message; }
    } finally {
      if (generation === this.generation) { this.busy = false; this.canStop = false; this.stage = ''; this.onChange(); }
    }
  }
  private appendQuery(chat: AssistantChat, found: Course[]): void {
    chat.recentIds = found.slice(0, 20).map(course => course.id);
    appendAssistantMessage(chat, 'assistant', found.length ? `找到${found.length}项课程：\n\n` + found.slice(0, 200).map(describeAssistantCourse).join('\n\n') +
      (found.length > 200 ? '\n\n已显示前200项，请补充课名或日期缩小范围。' : '') : '没有找到符合条件的课程。本次未更改课表。', 'result');
  }
  async receivePlan(plan: AssistantPlan, displayedWeek: number): Promise<void> {
    const state = await this.load(); const history = this.copy(); const chat = this.current(history);
    if (chat.semesterId !== currentSemesterId(state)) throw new Error('学期已经变化，本次未更改');
    if (chat.pending && (plan.undo || plan.query || plan.queryIds.length)) throw new Error('修改方案时请明确调整字段，或取消后再查询');
    chat.running = false; chat.retry = null;
    if (plan.undo) {
      const next = undoAssistantChange(state, chat.undo); chat.undo = null; chat.recentIds = [];
      appendAssistantMessage(chat, 'assistant', '已撤销本对话最近一次操作，恢复原来的课程安排。', 'result');
      await this.commit(history, next, state); return;
    }
    if (plan.query || plan.queryIds.length) {
      const found = plan.query ? assistantQuery(state, plan.query, displayedWeek) : plan.queryIds.map(id => {
        const course = state.courses.find(course => course.id === id); if (!course) throw new Error('查询期间课程已变化'); return course;
      });
      this.appendQuery(chat, found); await this.commit(history); return;
    }
    if (plan.courses.length || plan.updates.length || plan.deletions.length) {
      const change = applyAssistantPlan(state, plan);
      if (assistantNeedsConfirmation(plan) || chat.pending) {
        chat.pending = plan; chat.pendingStart = state.semester.startDate; chat.pendingWeeks = state.semester.totalWeeks;
        appendAssistantMessage(chat, 'assistant', '已整理好课程方案。请核对原安排和新安排，确认后执行；也可以继续补充修改要求。', 'confirmation');
        await this.commit(history);
      } else {
        chat.undo = change.undo; chat.recentIds = change.ids;
        appendAssistantMessage(chat, 'assistant', `已添加${change.undo.after.length}项课程安排。\n\n` + change.undo.after.map(describeAssistantCourse).join('\n\n'), 'result');
        await this.commit(history, change.state, state);
      }
      return;
    }
    appendAssistantMessage(chat, 'assistant', '本次未更改课表。\n' + plan.reply); await this.commit(history);
  }
  async confirm(): Promise<void> {
    if (this.busy || !this.chat?.pending) return;
    this.busy = true; this.stage = '正在校验并保存课程'; this.onChange();
    try {
      await this.draftWrite.catch(() => {});
      const state = await this.load(); const history = this.copy(); const chat = this.current(history);
      if (chat.pendingStart !== state.semester.startDate || chat.pendingWeeks !== state.semester.totalWeeks) throw new Error('学期设置已变化');
      const change = applyAssistantPlan(state, chat.pending!);
      chat.pending = null; chat.undo = change.undo; chat.recentIds = change.ids; chat.retry = null;
      appendAssistantMessage(chat, 'assistant', `操作已完成：保存${change.undo.after.length}项安排，处理${change.undo.before.length}项原安排。`, 'result');
      await this.commit(history, change.state, state);
    } catch (error) {
      const history = this.copy(); const chat = this.current(history); chat.pending = null; chat.retry = null;
      appendAssistantMessage(chat, 'assistant', (error as Error).message + '。本次未更改，请重新描述。', 'error');
      try { await this.commit(history); } catch (_) { this.notice = '保存失败，课程未更改。请重新打开助手。'; }
    } finally { this.busy = false; this.stage = ''; this.onChange(); }
  }
  async cancelPending(): Promise<void> {
    if (this.busy || !this.chat?.pending) return;
    this.busy = true; this.onChange();
    try {
      await this.draftWrite.catch(() => {});
      const history = this.copy(); const chat = this.current(history); chat.pending = null; chat.retry = null;
      appendAssistantMessage(chat, 'assistant', '已取消待执行方案，没有更改课表。', 'cancel'); await this.commit(history);
    } finally { this.busy = false; this.onChange(); }
  }
  async undo(): Promise<void> {
    if (this.busy || !this.chat) return;
    if (this.undoReason) throw new Error(this.undoReason);
    this.busy = true; this.onChange();
    try {
      await this.draftWrite.catch(() => {});
      const plan: AssistantPlan = { reply: '撤销', courses: [], updates: [], deletions: [], query: null, queryIds: [], undo: true };
      await this.receivePlan(plan, 1);
    } finally { this.busy = false; this.onChange(); }
  }
  async retry(config: AssistantConfig): Promise<void> {
    const retry = this.chat?.retry; if (retry && !this.busy) await this.send(retry.text, config, retry.displayedWeek);
  }
  async stop(): Promise<void> {
    if (!this.canStop) return;
    ++this.generation; this.canStop = false; this.transport.cancel();
    const history = this.copy(); const chat = this.current(history); chat.running = false;
    appendAssistantMessage(chat, 'assistant', '请求已停止，没有写入课程。可修改后重试，待确认方案已保留。', 'interrupted');
    try { await this.commit(history); } finally { this.busy = false; this.stage = ''; this.onChange(); }
  }
}
