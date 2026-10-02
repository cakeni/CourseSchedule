const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { applyLocalSigning } = require('../scripts/local-signing.cjs');

const folder = fs.mkdtempSync(path.join(os.tmpdir(), 'courseschedule-signing-check-'));
const fixture = path.join(folder, 'private');
fs.mkdirSync(fixture);
for (const name of ['debug.p12', 'debug.cer', 'debug.p7b']) fs.writeFileSync(path.join(fixture, name), 'local-test-fixture');
test.after(() => {
  // Delete only this test's verified temporary directory.
  assert.equal(path.dirname(folder), path.resolve(os.tmpdir()));
  assert.ok(path.basename(folder).startsWith('courseschedule-signing-check-'));
  fs.rmSync(folder, { recursive: true, force: true });
});
const profile = { app: { signingConfigs: [], products: [{ name: 'default', runtimeOS: 'HarmonyOS' }, { name: 'other' }] }, modules: [] };
const config = { name: 'device-debug', type: 'HarmonyOS', material: {
  storeFile: 'debug.p12', certpath: 'debug.cer', profile: 'debug.p7b', keyAlias: 'debugKey', signAlg: 'SHA256withECDSA',
  storePassword: '000000' + 'AB'.repeat(25), keyPassword: '000000' + 'CD'.repeat(25)
} };
function write(value, name = 'config.json') {
  const file = path.join(fixture, name);
  fs.writeFileSync(file, typeof value === 'string' ? value : JSON.stringify(value));
  return { HARMONY_SIGNING_CONFIG: file, HARMONY_REQUIRE_SIGNING: '1' };
}
test('模拟器构建不依赖签名，要求签名时缺配置必须失败', () => {
  assert.equal(applyLocalSigning(profile, folder, {}), profile);
  assert.throws(() => applyLocalSigning(profile, folder, { HARMONY_REQUIRE_SIGNING: '1' }), /要求签名/);
});
test('指定配置不能静默回退，JSON 错误不暴露原文', () => {
  assert.throws(() => applyLocalSigning(profile, folder, { HARMONY_SIGNING_CONFIG: 'missing.json' }), /无法读取/);
  const env = write('{ "private-password": "never-echo-secret", broken }');
  assert.throws(() => applyLocalSigning(profile, folder, env), error => /无法读取/.test(error.message) && !error.message.includes('never-echo-secret'));
});
test('凭证路径相对本地配置解析，仅设置选定产品，原文件不被修改', () => {
  const before = JSON.stringify(profile);
  const result = applyLocalSigning(profile, folder, write(config));
  assert.equal(result.app.products[0].signingConfig, config.name);
  assert.equal(result.app.products[1].signingConfig, undefined);
  assert.equal(result.app.signingConfigs[0].material.storeFile, path.join(fixture, 'debug.p12'));
  assert.equal(JSON.stringify(profile), before);
  assert.equal(JSON.parse(fs.readFileSync(path.join(fixture, 'config.json'))).material.storeFile, 'debug.p12');
});
test('缺失证书和明文密码会阻止构建，错误不含密码或私人路径', () => {
  const bad = { ...config, material: { ...config.material, keyPassword: 'private-password-value' } };
  assert.throws(() => applyLocalSigning(profile, folder, write(bad)), error => /加密值/.test(error.message) && !error.message.includes('private-password-value'));
  const missing = { ...config, material: { ...config.material, certpath: 'private-personal-name.cer' } };
  assert.throws(() => applyLocalSigning(profile, folder, write(missing)), error => /certpath/.test(error.message) && !error.message.includes('private-personal-name'));
});
test('不接受 OpenHarmony 测试签名来冒充 HarmonyOS 真机签名', () => {
  assert.throws(() => applyLocalSigning(profile, folder, write({ ...config, type: 'OpenHarmony' })), /HarmonyOS/);
});
test('DevEco 原生签名仍可使用，要求签名时必须匹配当前产品', () => {
  const native = { app: { signingConfigs: [config], products: [{ name: 'default', signingConfig: config.name }, { name: 'other' }] } };
  assert.equal(applyLocalSigning(native, folder, { HARMONY_REQUIRE_SIGNING: '1' }), native);
  assert.throws(() => applyLocalSigning(native, folder, { HARMONY_REQUIRE_SIGNING: '1' }, 'other'), /要求签名/);
});
