const fs = require('node:fs');
const path = require('node:path');

// Passwords are opaque DevEco ciphertext. Never print the loaded configuration.
function applyLocalSigning(profile, projectPath, env = process.env, productName = 'default') {
  const selected = env.HARMONY_SIGNING_CONFIG;
  const configPath = path.resolve(projectPath, selected || 'signing.local.json');
  let result = profile;
  if (selected || fs.existsSync(configPath)) {
    let config;
    try { config = JSON.parse(fs.readFileSync(configPath, 'utf8').replace(/^\uFEFF/, '')); }
    catch { throw new Error('无法读取本地签名 JSON，请检查 HARMONY_SIGNING_CONFIG 或 signing.local.json。'); }
    if (!config || typeof config.name !== 'string' || !/^[\w-]+$/.test(config.name) || config.type !== 'HarmonyOS') {
      throw new Error('本地签名需包含有效 name 和 type: HarmonyOS。');
    }
    const source = config.material;
    const material = {};
    for (const field of ['storeFile', 'certpath', 'profile', 'storePassword', 'keyPassword', 'keyAlias', 'signAlg']) {
      if (!source || typeof source[field] !== 'string' || !source[field].trim()) {
        throw new Error('本地签名缺少字段：' + field + '。');
      }
      material[field] = source[field];
    }
    if (material.signAlg !== 'SHA256withECDSA') throw new Error('本地签名算法须为 SHA256withECDSA。');
    for (const field of ['storePassword', 'keyPassword']) {
      if (!/^000000[0-9a-f]{26,}$/i.test(material[field])) {
        throw new Error('请从 DevEco 复制 ' + field + ' 的加密值，不要填写明文密码。');
      }
    }
    for (const [field, extension] of [['storeFile', '.p12'], ['certpath', '.cer'], ['profile', '.p7b']]) {
      material[field] = path.resolve(path.dirname(configPath), material[field]);
      let valid = false;
      try { const stat = fs.statSync(material[field]); valid = stat.isFile() && stat.size > 0; } catch {}
      if (!valid || path.extname(material[field]).toLowerCase() !== extension) {
        throw new Error('本地签名文件缺失、为空或扩展名不符：' + field + '。');
      }
    }
    const products = profile.app.products || [];
    if (!products.some(product => product.name === productName)) throw new Error('未找到待签名的构建产品。');
    result = { ...profile, app: { ...profile.app,
      signingConfigs: [...(profile.app.signingConfigs || []).filter(item => item.name !== config.name),
        { name: config.name, type: config.type, material }],
      products: products.map(product => product.name === productName ? { ...product, signingConfig: config.name } : product)
    } };
  }
  if (env.HARMONY_REQUIRE_SIGNING === '1') {
    const name = result.app.products?.find(product => product.name === productName)?.signingConfig;
    if (!name || !result.app.signingConfigs?.some(config => config.name === name)) {
      throw new Error('本次构建要求签名，但未配置对应的证书和 Profile；停止生成未签名包。');
    }
  }
  return result;
}

module.exports = { applyLocalSigning };
