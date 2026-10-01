const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const Module = require('node:module');
const ts = require(process.env.HARMONY_TYPESCRIPT || 'D:/devco/DevEco Studio/tools/hvigor/hvigor/node_modules/typescript/lib/typescript.js');
for (const ext of ['.ts', '.ets']) require.extensions[ext] = (module, filename) => module._compile(ts.transpileModule(fs.readFileSync(filename, 'utf8'), {
  compilerOptions: { target: ts.ScriptTarget.ES2020, module: ts.ModuleKind.CommonJS }
}).outputText, filename);
const root = path.resolve(__dirname, '../entry/src/main/ets');
const ai = require(path.join(root, 'model/AiRecognition.ts'));
const provider = ai.AI_PROVIDERS[0];
const course = { courseName: '高等数学', teacher: '', classroom: 'A101', dayOfWeek: 1, startSection: 1, endSection: 2, weeks: [1, 2, 3] };
const content = JSON.stringify({ courses: [course] });
const response = text => JSON.stringify({ status: 'completed', output: [{ type: 'message', content: [{ type: 'output_text', text }] }] });
const split = JSON.stringify({ status: 'completed', output: [
  { type: 'reasoning', content: [{ type: 'reasoning_text', text: 'not a course' }] },
  { type: 'message', content: [{ type: 'output_text', text: content.slice(0, 40) }, { type: 'output_text', text: content.slice(40) }] }
] });
assert.equal(ai.aiResponse(split, 20, provider).courses[0].courseName, course.courseName);
assert.equal(ai.aiResponse(response('```json\n' + content + '\n```'), 20, provider).courses.length, 1);
assert.equal(ai.aiResponse(JSON.stringify({ output_text: content }), 20, provider).courses.length, 1);
assert.throws(() => ai.aiResponse(JSON.stringify({ status: 'incomplete', incomplete_details: { reason: 'max_output_tokens' }, output: [] }), 20, provider), /截断/);
assert.throws(() => ai.aiResponse('null', 20, provider), ai.AiResponseError);
assert.throws(() => ai.aiResponse(response('{"courses":[]}'), 20, provider), /有效课程/);
assert.throws(() => ai.aiResponse(response(JSON.stringify({ courses: [null, { ...course, dayOfWeek: 9 }] })), 20, provider), /有效课程/);
assert.throws(() => ai.aiResponse(response('explanation ' + content), 20, provider), ai.AiResponseError);
const diagnostic = ai.aiResponseDiagnostic(response('private text sk-do-not-leak'));
assert.equal(diagnostic.includes('private'), false); assert.equal(diagnostic.includes('sk-'), false);
assert.equal(JSON.parse(diagnostic).textParts, 1);
assert.equal(JSON.parse(ai.aiRequest('schedule', 20, provider)).instructions.includes('JSON'), true);
assert.equal(JSON.parse(ai.aiRequest('schedule', 20, provider, true)).text.format.type, 'json_object');

let responses = [], requests = [], destroyed = 0;
const savedAssets = new Map();
const savedPrefs = new Map();
const tags = { ALIAS: 1, SECRET: 2, ACCESSIBILITY: 3, SYNC_TYPE: 4, CONFLICT_RESOLUTION: 5, RETURN_TYPE: 6 };
const mocks = {
  '@kit.NetworkKit': { http: { RequestMethod: { POST: 'POST' }, HttpDataType: { STRING: 'STRING' }, createHttp: () => ({
    request: async (url, options) => { requests.push({ url, options }); return responses.shift(); }, destroy: () => destroyed++
  }) } },
  '@kit.AssetStoreKit': { asset: {
    Tag: tags, ReturnType: { ALL: 0 }, ErrorCode: { NOT_FOUND: 24000002 }, Accessibility: { DEVICE_UNLOCKED: 2 }, SyncType: { NEVER: 0 }, ConflictResolution: { OVERWRITE: 0 },
    add: async attrs => { savedAssets.set(new TextDecoder().decode(attrs.get(tags.ALIAS)), new Map(attrs)); },
    query: async query => { const entry = savedAssets.get(new TextDecoder().decode(query.get(tags.ALIAS))); if (!entry) throw { code: 24000002 }; return [entry]; },
    remove: async query => { if (!savedAssets.delete(new TextDecoder().decode(query.get(tags.ALIAS)))) throw { code: 24000002 }; }
  } },
  '@kit.ArkTS': { util: { TextEncoder: class { encodeInto(text) { return new TextEncoder().encode(text); } }, TextDecoder: { create: () => ({ decodeToString: bytes => new TextDecoder().decode(bytes) }) } } },
  '@kit.ArkData': { preferences: { getPreferences: async () => ({ get: async (key, fallback) => savedPrefs.get(key) ?? fallback,
    put: async (key, value) => savedPrefs.set(key, value), flush: async () => {} }) } }
};
const originalLoad = Module._load;
Module._load = function (name, parent, isMain) { return mocks[name] ?? originalLoad.call(this, name, parent, isMain); };
const api = require(path.join(root, 'data/AiRecognition.ets'));
const credentials = require(path.join(root, 'data/AiCredentials.ets'));
Module._load = originalLoad;

(async () => {
  const diagnostics = [];
  responses = [{ responseCode: 200, result: response('invalid JSON') }, { responseCode: 200, result: response(content) }];
  const result = await api.recognizePage('schedule', 'test-only-key', 20, provider, value => diagnostics.push(value));
  assert.equal(result.courses.length, 1); assert.equal(requests.length, 2); assert.equal(destroyed, 2);
  assert.equal(JSON.parse(requests[1].options.extraData).text.format.type, 'json_object');
  assert.equal(diagnostics.join('').includes('test-only-key'), false);
  responses = [{ responseCode: 401, result: '{}' }]; requests = [];
  await assert.rejects(api.recognizePage('schedule', 'test-only-key', 20, provider), /Key/);
  assert.equal(requests.length, 1);
  responses = [{ responseCode: 200, result: response('invalid JSON') }]; requests = [];
  await assert.rejects(api.recognizePage('schedule', 'test-only-key', 20, ai.AI_PROVIDERS[1]), /JSON/);
  assert.equal(requests.length, 1);
  assert.equal(await credentials.loadAiKey('DeepSeek'), '');
  await credentials.saveAiKey({}, 'DeepSeek', ' test-only-deepseek ');
  await credentials.saveAiKey({}, 'OpenAI', 'test-only-openai');
  assert.equal(await credentials.loadAiKey('DeepSeek'), 'test-only-deepseek');
  assert.equal(await credentials.loadAiKey('OpenAI'), 'test-only-openai');
  assert.equal(await credentials.loadAiProvider({}), 'OpenAI');
  const stored = savedAssets.get('qing_schedule_ai_DeepSeek');
  assert.equal(stored.get(tags.ACCESSIBILITY), 2); assert.equal(stored.get(tags.SYNC_TYPE), 0);
  assert.equal([...savedPrefs.values()].some(value => value.includes('test-only')), false);
  await credentials.saveAiKey({}, 'DeepSeek', 'updated-test-only-key');
  assert.equal(await credentials.loadAiKey('DeepSeek'), 'updated-test-only-key');
  await credentials.removeAiKey('DeepSeek'); await credentials.removeAiKey('DeepSeek');
  assert.equal(await credentials.loadAiKey('DeepSeek'), '');
  assert.equal(await credentials.loadAiKey('OpenAI'), 'test-only-openai');
  console.log('AI response, retry, diagnostics and credential checks passed');
})().catch(error => { console.error(error); process.exitCode = 1; });
