const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const ts = require(process.env.HARMONY_TYPESCRIPT || 'D:/devco/DevEco Studio/tools/hvigor/hvigor/node_modules/typescript/lib/typescript.js');
require.extensions['.ts'] = (module, filename) => module._compile(ts.transpileModule(fs.readFileSync(filename, 'utf8'), {
  compilerOptions: { target: ts.ScriptTarget.ES2020, module: ts.ModuleKind.CommonJS }
}).outputText, filename);
const root = path.resolve(__dirname, '../entry/src/main/ets');
const core = require(path.join(root, 'model/ScheduleCore.ts'));
const api = require(path.join(root, 'model/CourseAssistant.ts'));
const { CourseAssistantEngine } = require(path.join(root, 'model/CourseAssistantEngine.ts'));
const clone = value => JSON.parse(JSON.stringify(value));
const now = new Date(2026, 9, 2, 12);
const course = { id: 1, courseName: '高等数学', teacher: '李老师', classroom: 'A101', dayOfWeek: 2,
  startSection: 3, endSection: 5, startWeek: 1, endWeek: 16, weekType: 0, semesterId: 1,
  colorIndex: 7, note: '本地原备注 PRIVATE_NOTE', reminderMinutes: 10, createTime: 123 };
function state(courses = [course]) { return { ...core.defaultState(new Date(2026, 8, 28).getTime()), courses: clone(courses) }; }
function response(raw, finish = 'stop') { return JSON.stringify({ choices: [{ finish_reason: finish, message: { content: JSON.stringify(raw) } }] }); }
function parse(raw, current = state(), candidates) { return api.parseAssistantResponse(response({ version: 1, reply: '待核对', ...raw }), current, 2, candidates, now); }
function update(patch, current = state()) { return parse({ action: 'change', updates: [{ id: 1, ...patch }] }, current); }
function harness(initial = state(), request = async () => response({ version: 1, action: 'clarify', reply: '请补充课名' })) {
  let stored = clone(initial), fail = false, writes = 0, network = 0, cancelled = 0, blocker = null;
  const engine = new CourseAssistantEngine(clone(stored), async () => clone(stored), async value => {
    if (blocker) await blocker;
    if (fail) throw new Error('测试保存失败');
    stored = clone(value); writes++;
  }, { request: async (...args) => { network++; return request(...args); }, cancel: () => cancelled++ });
  return { engine, get stored() { return stored; }, get writes() { return writes; }, get network() { return network; }, get cancelled() { return cancelled; },
    fail(value) { fail = value; }, block(value) { blocker = value; }, change(value) { stored = clone(value); } };
}
const config = { ...api.defaultAssistantConfig(), apiKey: 'test-only-key' };
async function until(predicate) { for (let i = 0; i < 40 && !predicate(); i++) await new Promise(resolve => setImmediate(resolve)); assert.ok(predicate(), '异步操作未到达预期状态'); }

test('DeepSeek 默认值、HTTPS 路径与 Key 不进入请求 body', () => {
  assert.equal(config.model, 'deepseek-flash');
  assert.equal(api.assistantEndpoint(config), 'https://api.deepseek.com/chat/completions');
  assert.equal(api.assistantEndpoint({ ...config, baseUrl: 'https://example.com/v1/chat/completions/' }), 'https://example.com/v1/chat/completions');
  for (const baseUrl of ['http://example.com', 'https://user@example.com', 'https://example.com?key=x', 'https://example.com/#x'])
    assert.throws(() => api.assistantEndpoint({ ...config, baseUrl }));
  const chat = api.newAssistantChat(state(), { chats: [] });
  api.appendAssistantMessage(chat, 'user', '高等数学的教室改为B201');
  const payload = api.createAssistantRequest(config, state(), chat, 2, false, now);
  assert.equal(payload.body.includes(config.apiKey), false);
  assert.equal(JSON.parse(payload.body).response_format.type, 'json_object');
});
test('实际周次不钳制，日期不存在或星期不一致会拒绝', () => {
  const current = state();
  assert.equal(api.assistantWeek(current.semester, new Date(2026, 8, 27)), null);
  assert.equal(api.assistantWeek(current.semester, new Date(2026, 9, 5)), 2);
  assert.throws(() => parse({ action: 'query', query: { date: '2026-02-30' } }), /不存在/);
  assert.throws(() => parse({ action: 'query', query: { date: '2026-10-06', dayOfWeek: 4 } }), /不一致/);
  const outside = { ...current, semester: { ...current.semester, startDate: new Date(2025, 8, 1).getTime() } };
  assert.throws(() => parse({ action: 'query', query: { weekScope: 'this_week' } }, outside), /学期/);
});
test('夏令时边界按照自然日计算', () => {
  const original = process.env.TZ; process.env.TZ = 'America/New_York';
  try {
    const semester = { ...state().semester, startDate: new Date(2026, 2, 2).getTime() };
    assert.equal(api.assistantWeek(semester, new Date(2026, 2, 9)), 2);
    assert.equal(api.assistantWeek(semester, new Date(2026, 2, 8, 23, 59)), 1);
  } finally { if (original === undefined) delete process.env.TZ; else process.env.TZ = original; }
});
test('部分字段修改保留老师、备注、提醒、颜色、课时及创建时间', () => {
  const plan = update({ dayOfWeek: 4, startSection: 5 });
  const result = api.applyAssistantPlan(state(), plan).state.courses[0];
  assert.deepEqual(result, { ...course, dayOfWeek: 4, startSection: 5, endSection: 7 });
  assert.equal(api.assistantNeedsConfirmation(plan), true);
  assert.throws(() => update({ startSection: 11 }), /范围/);
});
test('补充备注不覆盖旧内容，提醒允许关闭', () => {
  assert.equal(update({ appendNote: '带教材', reminderMinutes: -1 }).updates[0].replacements[0].note, course.note + '\n带教材');
  assert.throws(() => update({ note: '替换', appendNote: '补充' }), /混用/);
});
test('单次调课拆分周次且原 ID 只保留一次，可完整撤销', () => {
  const plan = parse({ action: 'change', occurrences: [{ id: 1, date: '2026-10-06', dayOfWeek: 4, startSection: 5 }] });
  const change = api.applyAssistantPlan(state(), plan);
  const moved = change.state.courses.filter(row => row.dayOfWeek === 4);
  assert.deepEqual(api.assistantWeeks(moved[0]), [2]); assert.equal(moved[0].endSection, 7);
  const untouched = change.state.courses.filter(row => row.dayOfWeek === 2).flatMap(api.assistantWeeks).sort((a,b) => a-b);
  assert.deepEqual(untouched, Array.from({ length: 16 }, (_, i) => i + 1).filter(week => week !== 2));
  assert.equal(change.state.courses.filter(row => row.id === 1).length, 1);
  assert.deepEqual(api.undoAssistantChange(change.state, change.undo).courses, [course]);
});
test('取消单次课只去掉该周，取消最后一次才删除', () => {
  const plan = parse({ action: 'change', occurrences: [{ id: 1, week: 2, cancel: true }] });
  assert.equal(plan.deletions.length, 0);
  assert.equal(api.applyAssistantPlan(state(), plan).state.courses.flatMap(api.assistantWeeks).includes(2), false);
  const single = state([{ ...course, startWeek: 2, endWeek: 2 }]);
  assert.equal(parse({ action: 'change', occurrences: [{ id: 1, week: 2, cancel: true }] }, single).deletions.length, 1);
  assert.throws(() => parse({ action: 'change', occurrences: [{ id: 1, week: 17, cancel: true }] }), /不上课/);
});
test('单双周与不连续周次分组保留真实上课周', () => {
  const weeks = [1, 3, 5, 8, 9, 12, 14, 16];
  assert.deepEqual(api.assistantSplitWeeks(course, weeks).flatMap(api.assistantWeeks), weeks);
});
test('支持 ids 批量修改关联记录，不允许重复选择', () => {
  const current = state([course, { ...course, id: 2, startWeek: 17, endWeek: 20 }]);
  const plan = parse({ action: 'change', updates: [{ ids: [1, 2], classroom: 'B201' }] }, current);
  assert.equal(plan.updates.length, 2);
  assert.equal(api.applyAssistantPlan(current, plan).state.courses.every(row => row.classroom === 'B201'), true);
  assert.throws(() => parse({ action: 'change', updates: [{ ids: [1, 1], classroom: 'B201' }] }, current), /重复/);
});
test('已有冲突不阻止备注修改，新增冲突或扩大原冲突拒绝', () => {
  const neighbor = { ...course, id: 2, courseName: '物理', startSection: 5, endSection: 6 };
  const current = state([course, neighbor]);
  assert.equal(api.applyAssistantPlan(current, update({ classroom: 'B201' }, current)).state.courses.length, 2);
  assert.throws(() => api.applyAssistantPlan(current, update({ endSection: 6 }, current)), /冲突/);
  const clear = state([course, { ...neighbor, dayOfWeek: 4 }]);
  assert.throws(() => api.applyAssistantPlan(clear, update({ dayOfWeek: 4 }, clear)), /冲突/);
});
test('重复新增和已有安排拒绝，未变的旧冲突可撤销恢复', () => {
  const raw = { courseName: '高等数学', dayOfWeek: 2, startSection: 3, endSection: 5, weeks: Array.from({length:16},(_,i)=>i+1) };
  assert.throws(() => api.applyAssistantPlan(state(), parse({ action: 'change', courses: [raw] })), /重复/);
  const current = state([course, { ...course, id: 2, courseName: '物理', endSection: 6 }]);
  const change = api.applyAssistantPlan(current, update({ classroom: 'B201' }, current));
  assert.deepEqual(api.undoAssistantChange(change.state, change.undo).courses.sort((a,b)=>a.id-b.id), current.courses);
});
for (const [name, raw] of [
  ['未知字段', { action: 'change', tool: 'erase', deleteIds: [1] }],
  ['错误动作', { action: 'clarify', deleteIds: [1] }],
  ['混合查询修改', { action: 'change', deleteIds: [1], query: { courseName: '数学' } }],
  ['混合撤销修改', { action: 'change', deleteIds: [1], undo: true }],
  ['重复目标', { action: 'change', updates: [{ id: 1, classroom: 'B201' }], deleteIds: [1] }],
  ['字符串编号', { action: 'change', deleteIds: ['1'] }],
  ['未知课程', { action: 'change', deleteIds: [99] }],
  ['空更新', { action: 'change', updates: [{ id: 1 }] }],
  ['不合法提醒', { action: 'change', updates: [{ id: 1, reminderMinutes: 1441 }] }],
  ['矛盾日期周次', { action: 'query', query: { week: 1, date: '2026-10-06' } }],
]) test('严格协议拒绝：' + name, () => assert.throws(() => parse(raw)));
test('截断、工具调用、额外说明文字与未提供的候选拒绝', () => {
  for (const finish of ['length', 'tool_calls', null]) assert.throws(() => api.parseAssistantResponse(response({ version: 1, action: 'clarify', reply: 'x' }, finish), state(), 1));
  assert.throws(() => api.parseAssistantResponse(JSON.stringify({ choices: [{ finish_reason: 'stop', message: { content: '说明 {"version":1}' } }] }), state(), 1));
  assert.throws(() => parse({ action: 'change', deleteIds: [1] }, state(), []), /未提供/);
});
test('超过20个目标拒绝，学期外新增日期拒绝', () => {
  const current = state(Array.from({length:21},(_,i)=>({...course,id:i+1})));
  assert.throws(() => parse({ action:'change',deleteIds:current.courses.map(row=>row.id) },current), /20/);
  assert.throws(() => parse({action:'change',courses:[{courseName:'数学',date:'2025-01-01',startSection:1}]}), /学期/);
});
test('查询完整课表而不是候选子集，支持日期、教师及周次', () => {
  const current = state(Array.from({length:220},(_,i)=>({...course,id:i+1,courseName:'数学'+i})));
  const plan = parse({action:'query',query:{teacher:'李老师',date:'2026-10-06'}}, current, []);
  assert.equal(api.assistantQuery(current, plan.query, 2, now).length, 220);
  assert.deepEqual(api.assistantQuickQuery('周二有哪些课？', now), {dayOfWeek:2,weekScope:'displayed_week'});
  assert.equal(api.assistantQuickQuery('把周二那门课移到周四'), null);
});
test('候选、历史和待确认方案预算有限，不上传原备注', () => {
  const current = state(Array.from({length:220},(_,i)=>({...course,id:i+1,courseName:'课程'+i,note:'PRIVATE_NOTE'})));
  const chat = api.newAssistantChat(current,{chats:[]});
  api.appendAssistantMessage(chat,'assistant','找到1项课程：\n\nPRIVATE_NOTE','result');
  api.appendAssistantMessage(chat,'user','课程219教室改为B201');
  chat.pending = parse({action:'change',updates:[{id:220,appendNote:'带教材'}]},current);
  const payload = api.createAssistantRequest(config,current,chat,2,false,now);
  assert.ok(payload.candidateIds.includes(220)); assert.ok(payload.candidateIds.length <= 200); assert.equal(payload.body.includes('PRIVATE_NOTE'),false);
  assert.equal(payload.body.includes('带教材'),true); assert.ok(payload.body.length<64000);
});
test('撤销拒绝事后编辑、学期变化、新冲突及编号占用', () => {
  const change = api.applyAssistantPlan(state(),update({dayOfWeek:4}));
  assert.throws(()=>api.undoAssistantChange({...change.state,courses:change.state.courses.map(row=>({...row,classroom:'X'}))},change.undo),/变化/);
  assert.throws(()=>api.undoAssistantChange({...change.state,semester:{...change.state.semester,totalWeeks:19}},change.undo),/学期/);
  assert.throws(()=>api.undoAssistantChange({...change.state,courses:[...change.state.courses,{...course,id:2,courseName:'新课'}]},change.undo),/冲突/);
  const deleted=api.applyAssistantPlan(state(),parse({action:'change',deleteIds:[1]}));
  assert.throws(()=>api.undoAssistantChange({...deleted.state,courses:[{...course,courseName:'占用'}]},deleted.undo),/占用/);
});
test('课表备份排除对话、草稿和密钥配置', () => {
  const value=JSON.parse(core.exportJson({...state(),assistantChats:'PRIVATE_HISTORY'}));
  assert.equal(JSON.stringify(value).includes('PRIVATE_HISTORY'),false);
});
test('修改先确认、执行与回执一次保存、重启与撤销不重放', async () => {
  const h=harness(); await h.engine.initialize();
  await h.engine.receivePlan(update({classroom:'B201'}),2);
  assert.deepEqual(h.stored.courses,[course]); assert.ok(h.engine.chat.pending);
  await h.engine.confirm(); assert.equal(h.stored.courses[0].classroom,'B201');
  const saved=api.restoreAssistantHistory(h.stored); assert.equal(saved.chats[0].pending,null); assert.ok(saved.chats[0].undo);
  await h.engine.initialize(); assert.equal(h.stored.courses[0].classroom,'B201');
  await h.engine.undo(); assert.deepEqual(h.stored.courses,[course]); assert.equal(h.engine.chat.undo,null);
});
test('单条添加直接执行且可撤销，批量添加需确认', async () => {
  const h=harness(state([]));await h.engine.initialize();
  const raw={courseName:'英语',dayOfWeek:1,startSection:1,weeks:[1,2]};
  await h.engine.receivePlan(parse({action:'change',courses:[raw]},state([])),1);
  assert.equal(h.stored.courses.length,1);assert.equal(h.engine.chat.pending,null);
  await h.engine.undo();assert.equal(h.stored.courses.length,0);
  await h.engine.receivePlan(parse({action:'change',courses:[raw,{...raw,dayOfWeek:2}]},state([])),1);
  assert.equal(h.stored.courses.length,0);assert.ok(h.engine.chat.pending);
});
test('纯本地查询不需要 Key、不调用网络，取消方案不改变课程', async () => {
  const h=harness();await h.engine.initialize();
  await h.engine.send('周二有哪些课？',api.defaultAssistantConfig(),1);
  assert.equal(h.network,0);assert.match(h.engine.messages.at(-1).content,/高等数学/);
  await h.engine.receivePlan(update({classroom:'B201'}),1);await h.engine.cancelPending();
  assert.deepEqual(h.stored.courses,[course]);assert.equal(h.engine.chat.pending,null);
});
test('修正失败保留待确认方案，明确重试后才再次请求', async () => {
  const h=harness(state(),async()=>{throw new Error('测试网络断开');});await h.engine.initialize();
  await h.engine.receivePlan(update({classroom:'B201'}),1);
  await h.engine.send('再提前10分钟提醒',config,1);
  assert.equal(h.network,1);assert.ok(h.engine.chat.pending);assert.ok(h.engine.chat.retry);assert.deepEqual(h.stored.courses,[course]);
  await h.engine.retry(config);assert.equal(h.network,2);assert.ok(h.engine.chat.pending);
});
test('停止后到达的回复不能写入，停止保留显式重试', async () => {
  let resolve;const wait=new Promise(done=>resolve=done);
  const h=harness(state(),()=>wait);await h.engine.initialize();
  const pending=h.engine.send('修改高等数学教室',config,1);await until(()=>h.engine.canStop);
  await h.engine.stop();resolve(response({version:1,action:'change',reply:'改教室',updates:[{id:1,classroom:'B201'}]}));await pending;
  assert.equal(h.cancelled,1);assert.equal(h.engine.busy,false);assert.equal(h.engine.chat.pending,null);
  assert.ok(h.engine.chat.retry);assert.deepEqual(h.stored.courses,[course]);
});
test('外部编辑使待确认方案失效，确认不覆盖手动修改', async () => {
  const h=harness();await h.engine.initialize();await h.engine.receivePlan(update({classroom:'B201'}),1);
  h.change({...h.stored,courses:[{...course,teacher:'新老师'}]});await h.engine.confirm();
  assert.equal(h.stored.courses[0].teacher,'新老师');assert.equal(h.stored.courses[0].classroom,'A101');assert.equal(h.engine.chat.pending,null);
});
test('持久化失败时课程与操作回执均不提交，恢复后可继续', async () => {
  const h=harness();await h.engine.initialize();await h.engine.receivePlan(update({classroom:'B201'}),1);
  const before=clone(h.stored);h.fail(true);await h.engine.confirm();assert.deepEqual(h.stored,before);
  h.fail(false);await h.engine.initialize();await h.engine.confirm();assert.equal(h.stored.courses[0].classroom,'B201');
});
test('草稿写入失败不会卡死之后的保存和发送', async () => {
  const h=harness();await h.engine.initialize();h.engine.setDraft('草稿');h.fail(true);
  await assert.rejects(h.engine.persistDraft());h.fail(false);await h.engine.persistDraft();
  assert.equal(api.restoreAssistantHistory(h.stored).chats[0].draft,'草稿');
  await h.engine.send('周二有哪些课',config,1);assert.equal(h.engine.chat.draft,'');
});
test('保存草稿途中继续输入，最新文字不丢失', async () => {
  const h=harness();await h.engine.initialize();let release;h.block(new Promise(done=>release=done));
  h.engine.setDraft('第一版');const saving=h.engine.persistDraft();await new Promise(done=>setImmediate(done));
  h.engine.setDraft('第二版');release();await saving;h.block(null);
  assert.equal(h.engine.chat.draft,'第二版');await h.engine.persistDraft();assert.equal(api.restoreAssistantHistory(h.stored).chats[0].draft,'第二版');
});
test('其他实例改变对话 revision 时拒绝覆写', async () => {
  const h=harness();await h.engine.initialize();const fresh=api.restoreAssistantHistory(h.stored);fresh.revision++;
  h.change({...h.stored,assistantChats:JSON.stringify(fresh)});h.engine.setDraft('过期草稿');
  await assert.rejects(h.engine.persistDraft(),/其他页面/);assert.equal(api.restoreAssistantHistory(h.stored).revision,fresh.revision);
});
test('草稿保存途中切换新对话，离开的草稿仍保留最新输入', async () => {
  const h=harness();await h.engine.initialize();const first=h.engine.chat.id;let release;h.block(new Promise(done=>release=done));
  h.engine.setDraft('第一版');const saving=h.engine.persistDraft();await new Promise(done=>setImmediate(done));
  h.engine.setDraft('第二版');const switching=h.engine.newConversation();release();await saving;await switching;h.block(null);
  assert.equal(h.engine.history.chats.find(chat=>chat.id===first).draft,'第二版');
  await h.engine.openConversation(first);assert.equal(h.engine.chat.draft,'第二版');
});
test('历史恢复中断请求，学期隔离且不重放；删除会话保留课程', async () => {
  const h=harness();await h.engine.initialize();h.engine.setDraft('未发送草稿');await h.engine.persistDraft();
  const first=h.engine.chat.id;const saved=api.restoreAssistantHistory(h.stored);saved.chats[0].running=true;
  h.change({...h.stored,assistantChats:JSON.stringify(saved)});await h.engine.initialize();assert.equal(h.engine.chat.running,false);assert.equal(h.network,0);
  h.change({...h.stored,semester:{...h.stored.semester,id:2}});await h.engine.initialize();assert.notEqual(h.engine.chat.id,first);
  h.change({...h.stored,semester:{...h.stored.semester,id:1}});await h.engine.initialize();assert.equal(h.engine.chat.id,first);assert.equal(h.engine.chat.draft,'未发送草稿');
  await h.engine.deleteConversation();assert.deepEqual(h.stored.courses,[course]);assert.equal(h.engine.messages.length,0);
});
test('损坏历史不会覆盖课表，损坏待确认方案安全失效', async () => {
  const h=harness({...state(),assistantChats:'bad json'});await assert.rejects(h.engine.initialize());assert.deepEqual(h.stored.courses,[course]);
  const clean=harness();await clean.engine.initialize();const saved=api.restoreAssistantHistory(clean.stored);saved.chats[0].pending={invalid:true};
  clean.change({...clean.stored,assistantChats:JSON.stringify(saved)});await clean.engine.initialize();assert.equal(clean.engine.chat.pending,null);assert.deepEqual(clean.stored.courses,[course]);
});
