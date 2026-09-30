import { common } from '@kit.AbilityKit';
import { preferences } from '@kit.ArkData';
import { ConfigurationConstant } from '@kit.AbilityKit';
import { defaultState, exportJson, importJson, ScheduleState } from '../model/ScheduleCore';

const STORE_NAME = 'qing_schedule';
const STATE_KEY = 'schedule_v2';

export async function loadState(context: common.UIAbilityContext): Promise<ScheduleState> {
  const store = await preferences.getPreferences(context, STORE_NAME);
  const text = await store.get(STATE_KEY, '') as string;
  return text ? importJson(text, defaultState()) : defaultState();
}

export async function saveState(context: common.UIAbilityContext, state: ScheduleState): Promise<void> {
  const store = await preferences.getPreferences(context, STORE_NAME);
  await store.put(STATE_KEY, exportJson(state));
  await store.flush();
}

export async function loadDarkMode(context: common.UIAbilityContext): Promise<boolean> {
  const store = await preferences.getPreferences(context, STORE_NAME);
  return await store.get('dark_mode', context.config.colorMode === ConfigurationConstant.ColorMode.COLOR_MODE_DARK) as boolean;
}

export async function saveDarkMode(context: common.UIAbilityContext, dark: boolean): Promise<void> {
  const store = await preferences.getPreferences(context, STORE_NAME);
  await store.put('dark_mode', dark);
  await store.flush();
}
