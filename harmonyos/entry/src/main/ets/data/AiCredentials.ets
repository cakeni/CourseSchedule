import { asset } from '@kit.AssetStoreKit';
import { util } from '@kit.ArkTS';
import { common } from '@kit.AbilityKit';
import { preferences } from '@kit.ArkData';
import { BusinessError } from '@kit.BasicServicesKit';

function alias(provider: string): Uint8Array { return new util.TextEncoder().encodeInto('qing_schedule_ai_' + provider); }

export async function loadAiProvider(context: common.UIAbilityContext): Promise<string> {
  const store = await preferences.getPreferences(context, 'qing_schedule');
  return await store.get('ai_provider', 'DeepSeek') as string;
}

export async function loadAiKey(provider: string): Promise<string> {
  const query: asset.AssetMap = new Map();
  query.set(asset.Tag.ALIAS, alias(provider)); query.set(asset.Tag.RETURN_TYPE, asset.ReturnType.ALL);
  try {
    const entries = await asset.query(query);
    const bytes = entries[0]?.get(asset.Tag.SECRET) as Uint8Array;
    return bytes ? util.TextDecoder.create('utf-8').decodeToString(bytes) : '';
  } catch (error) {
    if ((error as BusinessError).code === asset.ErrorCode.NOT_FOUND) return '';
    throw new Error('无法读取已保存的 Key，请解锁设备后重试');
  }
}

export async function saveAiKey(context: common.UIAbilityContext, provider: string, key: string): Promise<void> {
  if (!key.trim()) throw new Error('请输入 API Key');
  const attributes: asset.AssetMap = new Map();
  attributes.set(asset.Tag.ALIAS, alias(provider));
  attributes.set(asset.Tag.SECRET, new util.TextEncoder().encodeInto(key.trim()));
  attributes.set(asset.Tag.ACCESSIBILITY, asset.Accessibility.DEVICE_UNLOCKED);
  attributes.set(asset.Tag.SYNC_TYPE, asset.SyncType.NEVER);
  attributes.set(asset.Tag.CONFLICT_RESOLUTION, asset.ConflictResolution.OVERWRITE);
  try { await asset.add(attributes); }
  catch (_) { throw new Error('Key 保存失败，请解锁设备后重试'); }
  const store = await preferences.getPreferences(context, 'qing_schedule');
  await store.put('ai_provider', provider); await store.flush();
}

export async function removeAiKey(provider: string): Promise<void> {
  const query: asset.AssetMap = new Map(); query.set(asset.Tag.ALIAS, alias(provider));
  try { await asset.remove(query); }
  catch (error) { if ((error as BusinessError).code !== asset.ErrorCode.NOT_FOUND) throw new Error('无法清除已保存的 Key，请重试'); }
}
