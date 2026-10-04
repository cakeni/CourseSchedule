import { common } from '@kit.AbilityKit';
import { util } from '@kit.ArkTS';
import { zlib } from '@kit.BasicServicesKit';
import { fileIo, picker } from '@kit.CoreFileKit';
import { ParsedImport, ZipEntry, parseTextImport, parseRows, xlsxRows, xlsxSharedStrings, validateXlsxZip, crc32 } from '../model/ImportCore';
import { ScheduleState, Course } from '../model/ScheduleCore';

const MAX_BACKUP_BYTES = 8 * 1024 * 1024;

function readBytes(path: string, limit: number = MAX_BACKUP_BYTES): ArrayBuffer {
  const size = fileIo.statSync(path).size;
  if (size < 1 || size > limit) throw new Error('文件大小无效或超过读取上限');
  const file = fileIo.openSync(path, fileIo.OpenMode.READ_ONLY);
  try {
    const buffer = new ArrayBuffer(size);
    if (fileIo.readSync(file.fd, buffer) !== size) throw new Error('文件读取不完整');
    return buffer;
  } finally { fileIo.closeSync(file); }
}

function decode(buffer: ArrayBuffer): string {
  const utf8 = util.TextDecoder.create('utf-8').decodeToString(new Uint8Array(buffer));
  const charset = /charset\s*=\s*["']?\s*(gbk|gb2312|gb18030)/i.exec(utf8)?.[1];
  return charset ? util.TextDecoder.create('gb18030').decodeToString(new Uint8Array(buffer)) : utf8;
}

async function unzipEntry(bytes: ArrayBuffer, entry: ZipEntry): Promise<string> {
  const input = bytes.slice(entry.offset, entry.offset + entry.compressedSize);
  if (entry.method === 0) { if (input.byteLength !== entry.size || crc32(input) !== entry.crc) throw new Error('Excel 工作表已损坏'); return decode(input); }
  const zip = await zlib.createZip();
  const output = new ArrayBuffer(entry.size + 1);
  const stream: zlib.ZStream = { nextIn: input, availableIn: input.byteLength, nextOut: output, availableOut: output.byteLength };
  await zip.inflateInit2(stream, -15);
  try {
    const status = await zip.inflate(stream, zlib.CompressFlushMode.FINISH);
    const result = await zip.getZStream();
    if (status !== zlib.ReturnStatus.STREAM_END || result.totalOut !== entry.size) throw new Error('Excel 工作表解压失败或超过声明大小');
    const buffer = (result.nextOut || output).slice(0, entry.size);
    if (crc32(buffer) !== entry.crc) throw new Error('Excel 工作表已损坏');
    return decode(buffer);
  } finally { await zip.inflateEnd(stream); }
}

export async function pickImport(context: common.UIAbilityContext, state: ScheduleState): Promise<ParsedImport | null> {
  const options = new picker.DocumentSelectOptions(); options.maxSelectNumber = 1;
  const uris = await new picker.DocumentViewPicker(context).select(options);
  if (!uris.length) return null;
  const bytes = readBytes(uris[0]);
  const name = decodeURIComponent(uris[0]).split('?')[0].toLowerCase();
  return parseImportBytes(bytes, name, state);
}

export async function parseImportBytes(bytes: ArrayBuffer, name: string, state: ScheduleState): Promise<ParsedImport> {
  if (name.endsWith('.xls')) throw new Error('暂不支持旧版 .xls，请另存为 .xlsx 后导入');
  const signature = new Uint8Array(bytes);
  if (name.endsWith('.xlsx') || (signature[0] === 0x50 && signature[1] === 0x4b)) {
    const entries = validateXlsxZip(bytes);
      const sharedEntry = entries.find(e => e.name === 'xl/sharedStrings.xml');
      const shared: string[] = sharedEntry ? xlsxSharedStrings(await unzipEntry(bytes, sharedEntry)) : [];
      const sheets = entries.filter(e => /^xl\/worksheets\/[^/]+\.xml$/.test(e.name))
        .sort((a, b) => Number(/sheet(\d+)/.exec(a.name)?.[1] || 0) - Number(/sheet(\d+)/.exec(b.name)?.[1] || 0)).slice(0, 32);
      const courses: Course[] = [];
      for (const sheet of sheets) {
        courses.push(...parseRows(xlsxRows(await unzipEntry(bytes, sheet), shared), state.semester.totalWeeks, 'Excel').courses);
      }
      if (!courses.length) throw new Error('Excel 中未找到包含课程名称、星期和节次的课程表');
      for (const course of courses) {
        let hash = 0; for (let i = 0; i < course.courseName.length; i++) hash = Math.imul(hash, 31) + course.courseName.charCodeAt(i) | 0;
        course.colorIndex = (hash % 15 + 15) % 15 + 1;
      }
      return { courses, sourceLabel: 'Excel' };
  }
  const extension = name.split('.').pop() || 'txt';
  const text = decode(bytes);
  if ((extension === 'html' || extension === 'htm' || /^\s*<(?:!doctype|html|table)/i.test(text)) && bytes.byteLength > 2000000) throw new Error('HTML 文件过大，请只导出学期课表页面（不超过 2 MB）');
  return parseTextImport(text, state, extension);
}

export async function saveBackup(context: common.UIAbilityContext, text: string): Promise<boolean> {
  const options = new picker.DocumentSaveOptions();
  options.newFileNames = ['晴课表备份.json'];
  const uris = await new picker.DocumentViewPicker(context).save(options);
  if (uris.length === 0) return false;
  const file = fileIo.openSync(uris[0], fileIo.OpenMode.WRITE_ONLY | fileIo.OpenMode.TRUNC);
  try {
    fileIo.writeSync(file.fd, text);
  } finally {
    fileIo.closeSync(file);
  }
  return true;
}
