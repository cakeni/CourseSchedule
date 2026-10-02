import { asset } from '@kit.AssetStoreKit';
import { util } from '@kit.ArkTS';
import { common } from '@kit.AbilityKit';
import { preferences } from '@kit.ArkData';
import { BusinessError } from '@kit.BasicServicesKit';
import { AssistantConfig, defaultAssistantConfig, assistantEndpoint } from '../model/CourseAssistant';

interface AssistantMetadata { baseUrl: string; model: string; jsonMode: boolean; rememberKey: boolean }
interface StoredAssistantKey { endpoint: string; key: string }
function keyAlias(): Uint8Array { return new util.TextEncoder().encodeInto('qing_schedule_course_assistant_api'); }
function keyQuery(): asset.AssetMap {
  const query: asset.AssetMap = new Map(); query.set(asset.Tag.ALIAS, keyAlias()); return query;
}
export async function loadAssistantConfig(context: common.UIAbilityContext): Promise<AssistantConfig> {
  const prefs = await preferences.getPreferences(context, 'qing_schedule');
  const raw = await prefs.get('assistant_api_metadata', '') as string;
  const config = defaultAssistantConfig();
  if (raw) {
    const metadata: AssistantMetadata = JSON.parse(raw);
    config.baseUrl = metadata.baseUrl; config.model = metadata.model;
    config.jsonMode = metadata.jsonMode; config.rememberKey = metadata.rememberKey;
    if (typeof config.jsonMode !== 'boolean' || typeof config.rememberKey !== 'boolean') throw new Error('API 配置格式无效，请重新填写');
    assistantEndpoint(config);
  }
  if (config.rememberKey) {
    const query = keyQuery(); query.set(asset.Tag.RETURN_TYPE, asset.ReturnType.ALL);
    try {
      const entries = await asset.query(query); const bytes = entries[0]?.get(asset.Tag.SECRET) as Uint8Array;
      if (bytes) {
        const saved: StoredAssistantKey = JSON.parse(util.TextDecoder.create('utf-8').decodeToString(bytes));
        if (saved.endpoint !== assistantEndpoint(config) || typeof saved.key !== 'string') {
          throw new Error('已保存 Key 与 API 地址不匹配，请重新填写');
        }
        config.apiKey = saved.key;
      }
    } catch (error) {
      if ((error as BusinessError).code !== asset.ErrorCode.NOT_FOUND) throw new Error('无法读取匹配的 Key，请解锁设备或重新填写');
    }
  }
  return config;
}
async function removeAssistantKey(): Promise<void> {
  try { await asset.remove(keyQuery()); }
  catch (error) { if ((error as BusinessError).code !== asset.ErrorCode.NOT_FOUND) throw new Error('无法清除已保存的 Key，请重试'); }
}
export async function saveAssistantConfig(context: common.UIAbilityContext, config: AssistantConfig): Promise<void> {
  assistantEndpoint(config); if (!config.apiKey.trim()) throw new Error('请填写 API Key');
  if (config.rememberKey) {
    const saved: StoredAssistantKey = { endpoint: assistantEndpoint(config), key: config.apiKey.trim() };
    const attributes = keyQuery(); attributes.set(asset.Tag.SECRET, new util.TextEncoder().encodeInto(JSON.stringify(saved)));
    attributes.set(asset.Tag.ACCESSIBILITY, asset.Accessibility.DEVICE_UNLOCKED);
    attributes.set(asset.Tag.SYNC_TYPE, asset.SyncType.NEVER);
    attributes.set(asset.Tag.CONFLICT_RESOLUTION, asset.ConflictResolution.OVERWRITE);
    try { await asset.add(attributes); } catch (_) { throw new Error('Key 保存失败，请解锁设备后重试'); }
  } else await removeAssistantKey();
  const metadata: AssistantMetadata = { baseUrl: config.baseUrl.trim(), model: config.model.trim(), jsonMode: config.jsonMode, rememberKey: config.rememberKey };
  const prefs = await preferences.getPreferences(context, 'qing_schedule');
  const previous = await prefs.get('assistant_api_metadata', '') as string;
  try { await prefs.put('assistant_api_metadata', JSON.stringify(metadata)); await prefs.flush(); }
  catch (error) {
    await prefs.put('assistant_api_metadata', previous);
    try { await prefs.flush(); } catch (_) {}
    // Endpoint-bound encrypted keys prevent a partial save from sending a new key to the previous host.
    throw new Error('配置保存未完成，请重试或重新填写 Key');
  }
}
export async function clearAssistantConfig(context: common.UIAbilityContext): Promise<void> {
  await removeAssistantKey(); const prefs = await preferences.getPreferences(context, 'qing_schedule');
  await prefs.delete('assistant_api_metadata'); await prefs.flush();
}
