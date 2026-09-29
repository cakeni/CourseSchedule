import { common } from '@kit.AbilityKit';
import { util } from '@kit.ArkTS';
import { fileIo, picker } from '@kit.CoreFileKit';

const MAX_BACKUP_BYTES = 8 * 1024 * 1024;

export async function pickBackup(context: common.UIAbilityContext): Promise<string | null> {
  const options = new picker.DocumentSelectOptions();
  options.maxSelectNumber = 1;
  const uris = await new picker.DocumentViewPicker(context).select(options);
  if (uris.length === 0) return null;
  const size = fileIo.statSync(uris[0]).size;
  if (size < 1 || size > MAX_BACKUP_BYTES) throw new Error('备份文件大小无效或超过 8 MB');
  const file = fileIo.openSync(uris[0], fileIo.OpenMode.READ_ONLY);
  try {
    const buffer = new ArrayBuffer(size);
    const read = fileIo.readSync(file.fd, buffer);
    if (read !== size) throw new Error('备份文件读取不完整');
    return util.TextDecoder.create('utf-8').decodeToString(new Uint8Array(buffer));
  } finally {
    fileIo.closeSync(file);
  }
}

export async function saveBackup(context: common.UIAbilityContext, text: string): Promise<boolean> {
  const options = new picker.DocumentSaveOptions();
  options.newFileNames = ['晴课表备份.json'];
  const uris = await new picker.DocumentViewPicker(context).save(options);
  if (uris.length === 0) return false;
  const file = fileIo.openSync(uris[0], fileIo.OpenMode.WRITE_ONLY);
  try {
    fileIo.writeSync(file.fd, text);
  } finally {
    fileIo.closeSync(file);
  }
  return true;
}
