const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const Module = require('node:module');
const ts = require(process.env.HARMONY_TYPESCRIPT || 'D:/devco/DevEco Studio/tools/hvigor/hvigor/node_modules/typescript/lib/typescript.js');
for (const ext of ['.ts', '.ets']) require.extensions[ext] = (module, filename) => module._compile(ts.transpileModule(fs.readFileSync(filename, 'utf8'), {
  compilerOptions: { target: ts.ScriptTarget.ES2020, module: ts.ModuleKind.CommonJS }
}).outputText, filename);
const root = path.resolve(__dirname, '../entry/src/main/ets');
const core = require(path.join(root, 'model/ScheduleCore.ts'));
const api = require(path.join(root, 'model/CourseAssistant.ts'));
let nextResponse, calls, destroyed, assetFailure, flushFailure;
const prefs = new Map(), assets = new Map();
const tags = { ALIAS: 1, SECRET: 2, ACCESSIBILITY: 3, SYNC_TYPE: 4, CONFLICT_RESOLUTION: 5, RETURN_TYPE: 6 };
const decode = bytes => new TextDecoder().decode(bytes);
const mocks = {
  '@kit.NetworkKit': { http: { RequestMethod: { POST: 'POST' }, HttpDataType: { STRING: 'STRING' }, createHttp: () => ({
    request: async (url, options) => { calls.push({ url, options }); if (nextResponse instanceof Error) throw nextResponse;
      return typeof nextResponse === 'function' ? nextResponse() : nextResponse; }, destroy: () => destroyed++
  }) } },
  '@kit.AssetStoreKit': { asset: {
    Tag: tags, ReturnType: { ALL: 0 }, ErrorCode: { NOT_FOUND: 24000002 }, Accessibility: { DEVICE_UNLOCKED: 2 }, SyncType: { NEVER: 0 }, ConflictResolution: { OVERWRITE: 0 },
    add: async attrs => { if(assetFailure)throw {code:24000004}; assets.set(decode(attrs.get(tags.ALIAS)), new Map(attrs)); },
    query: async query => { if(assetFailure)throw {code:24000004}; const row=assets.get(decode(query.get(tags.ALIAS))); if(!row)throw {code:24000002}; return [row]; },
    remove: async query => { if(assetFailure)throw {code:24000004}; if(!assets.delete(decode(query.get(tags.ALIAS))))throw {code:24000002}; }
  } },
  '@kit.ArkTS': { util: { TextEncoder: class { encodeInto(text) { return new TextEncoder().encode(text); } }, TextDecoder: { create: () => ({ decodeToString: decode }) } } },
  '@kit.AbilityKit': { ConfigurationConstant: {ColorMode:{COLOR_MODE_DARK:0}} },
  '@kit.ArkData': { preferences: { getPreferences: async () => ({ get: async (key, fallback) => prefs.get(key) ?? fallback,
    put: async (key,value) => prefs.set(key,value), delete:async key=>prefs.delete(key), flush:async()=>{if(flushFailure)throw new Error('测试 flush 失败');} }) } }
};
const originalLoad=Module._load;
Module._load=function(name,parent,isMain){return mocks[name]??originalLoad.call(this,name,parent,isMain);};
const {AssistantClient}=require(path.join(root,'data/AssistantClient.ets'));
const credentials=require(path.join(root,'data/AssistantCredentials.ets'));
const store=require(path.join(root,'data/ScheduleStore.ets'));
Module._load=originalLoad;
const config={...api.defaultAssistantConfig(),apiKey:'test-secret-only'};
test.beforeEach(()=>{nextResponse={responseCode:200,result:'{}'};calls=[];destroyed=0;assetFailure=false;flushFailure=false;prefs.clear();assets.clear();});
test('请求发送鉴权及明确的时限、大小限制，成功释放 native handle',async()=>{
  const client=new AssistantClient();assert.equal(await client.request(config,'{"test":true}'),'{}');
  assert.equal(calls.length,1);assert.equal(calls[0].url,'https://api.deepseek.com/chat/completions');
  assert.equal(calls[0].options.header.Authorization,'Bearer test-secret-only');
  assert.equal(calls[0].options.readTimeout,60000);assert.equal(calls[0].options.maxLimit,256000);
  assert.equal(calls[0].options.usingCache,false);assert.equal(destroyed,1);
});
for(const [status,reason] of [[401,/Key/],[403,/权限/],[429,/额度/],[400,/格式/],[404,/地址/],[302,/重定向/],[503,/503/]])
  test('HTTP '+status+' 映射公开错误，不泄漏响应与 Key',async()=>{
    nextResponse={responseCode:status,result:'test-secret-only private server error'};
    const client=new AssistantClient();let error;try{await client.request(config,'{}');}catch(value){error=value;}
    assert.match(error.message,reason);assert.equal(error.message.includes(config.apiKey),false);assert.equal(error.message.includes('private'),false);assert.equal(destroyed,1);
  });
test('连接失败及过大响应关闭 handle，不自动重试',async()=>{
  nextResponse=new Error('test-secret-only');await assert.rejects(new AssistantClient().request(config,'{}'),/连接/);
  assert.equal(calls.length,1);assert.equal(destroyed,1);
  nextResponse={responseCode:200,result:'x'.repeat(256001)};await assert.rejects(new AssistantClient().request(config,'{}'),/过大/);assert.equal(destroyed,2);
});
test('停止释放一次 handle，晚到回复被拒绝，新请求可恢复',async()=>{
  let release;nextResponse=()=>new Promise(resolve=>release=resolve);const client=new AssistantClient();const waiting=client.request(config,'{}');
  client.cancel();release({responseCode:200,result:'{}'});await assert.rejects(waiting,/停止/);assert.equal(destroyed,1);
  nextResponse={responseCode:200,result:'{}'};assert.equal(await client.request(config,'{}'),'{}');assert.equal(destroyed,2);
});
test('默认不落盘 Key；明确保存后只进入 AssetStore，禁止跨设备同步',async()=>{
  assert.equal((await credentials.loadAssistantConfig({})).apiKey,'');
  await credentials.saveAssistantConfig({},config);assert.equal(assets.size,0);assert.equal(JSON.stringify([...prefs]).includes(config.apiKey),false);
  assert.equal((await credentials.loadAssistantConfig({})).apiKey,'');
  await credentials.saveAssistantConfig({},{...config,rememberKey:true});const attrs=assets.get('qing_schedule_course_assistant_api');
  assert.equal(JSON.parse(decode(attrs.get(tags.SECRET))).key,config.apiKey);assert.equal(attrs.get(tags.SYNC_TYPE),0);assert.equal(attrs.get(tags.ACCESSIBILITY),2);
  assert.equal((await credentials.loadAssistantConfig({})).apiKey,config.apiKey);assert.equal(JSON.stringify([...prefs]).includes(config.apiKey),false);
});
test('网页识别凭证独立，清除助手只移除助手 Key 和 metadata',async()=>{
  assets.set('other-web-import',new Map());prefs.set('other-setting','keep');
  await credentials.saveAssistantConfig({},{...config,rememberKey:true});await credentials.clearAssistantConfig({});
  assert.deepEqual([...assets.keys()],['other-web-import']);assert.equal(prefs.get('other-setting'),'keep');assert.equal(prefs.has('assistant_api_metadata'),false);
});
test('取消记住时清除旧 Key，AssetStore 错误阻止配置显示成功',async()=>{
  await credentials.saveAssistantConfig({},{...config,rememberKey:true});await credentials.saveAssistantConfig({},config);assert.equal(assets.size,0);
  assetFailure=true;await assert.rejects(credentials.saveAssistantConfig({},{...config,rememberKey:true}),/失败/);
  prefs.set('assistant_api_metadata',JSON.stringify({...config,apiKey:undefined,rememberKey:true}));
  await assert.rejects(credentials.loadAssistantConfig({}),/解锁/);
});
test('实际 Preferences 缓存模型：flush 失败回滚课表与对话，恢复后可保存',async()=>{
  const initial=core.defaultState();await store.saveState({},initial);
  flushFailure=true;await assert.rejects(store.saveState({},{...initial,assistantChats:'new history'}));
  assert.deepEqual(await store.loadState({}),initial);flushFailure=false;
  await store.saveState({},{...initial,assistantChats:'saved history'});assert.equal((await store.loadState({})).assistantChats,'saved history');
});
test('更换服务地址时 metadata 保存失败，新 Key 不能被发送至原服务',async()=>{
  await credentials.saveAssistantConfig({},{...config,rememberKey:true});flushFailure=true;
  await assert.rejects(credentials.saveAssistantConfig({},{...config,baseUrl:'https://example.com/v1',apiKey:'new-provider-key',rememberKey:true}),/未完成/);
  assert.equal(JSON.parse(prefs.get('assistant_api_metadata')).baseUrl,config.baseUrl);flushFailure=false;
  await assert.rejects(credentials.loadAssistantConfig({}),/匹配/);
});
