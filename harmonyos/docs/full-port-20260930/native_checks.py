"""Install a temporary validation entry, then restore the production entry after testing."""
from pathlib import Path
import zipfile
import sys
import shutil

root = Path(__file__).resolve().parents[3] / 'harmonyos'
ability = root / 'entry/src/main/ets/entryability/EntryAbility.ets'
pages = root / 'entry/src/main/resources/base/profile/main_pages.json'
cache = root / 'entry/build/validation/native-check-source'
cache.mkdir(parents=True, exist_ok=True)
if sys.argv[1] == 'prepare':
    web = root / 'entry/build/validation/web-fixture'
    web.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(root / 'tests/WebFixture.html', web / 'index.html')
    if "pages/NativeChecks" in ability.read_text(encoding='utf-8'):
        raise RuntimeError('Validation entry is already installed; restore it before preparing again.')
    for source in (ability, pages):
        (cache / source.name).write_bytes(source.read_bytes())
    ability.write_text(ability.read_text(encoding='utf-8').replace("'pages/Index'", "'pages/NativeChecks'"), encoding='utf-8')
    pages.write_text('{"src":["pages/Index","pages/NativeChecks"]}\n', encoding='utf-8')
    (root / 'entry/src/main/ets/pages/NativeChecks.ets').write_bytes((root / 'tests/NativeChecks.ets').read_bytes())
    resource = root / 'entry/src/main/resources/rawfile'
    shared = '<sst><si><t>课程名称</t></si><si><t>星期</t></si><si><t>节次</t></si><si><t>周次</t></si><si><t>本机Excel验证</t></si><si><t>星期一</t></si><si><t>1-2</t></si><si><t>1-8</t></si></sst>'
    sheet = '<worksheet><sheetData><row r="1">' + ''.join(f'<c r="{col}1" t="s"><v>{i}</v></c>' for i, col in enumerate('ABCD')) + '</row><row r="2">' + ''.join(f'<c r="{col}2" t="s"><v>{i+4}</v></c>' for i, col in enumerate('ABCD')) + '</row></sheetData></worksheet>'
    for name, method in [('native-deflate.xlsx', zipfile.ZIP_DEFLATED), ('native-stored.xlsx', zipfile.ZIP_STORED)]:
        with zipfile.ZipFile(resource / name, 'w', compression=method) as z:
            z.writestr('xl/sharedStrings.xml', shared)
            z.writestr('xl/worksheets/sheet1.xml', sheet)
elif sys.argv[1] == 'restore':
    for source in (ability, pages):
        source.write_bytes((cache / source.name).read_bytes())
    for name in ['native-deflate.xlsx', 'native-stored.xlsx']:
        (root / 'entry/src/main/resources/rawfile' / name).unlink(missing_ok=True)
    (root / 'entry/src/main/ets/pages/NativeChecks.ets').unlink(missing_ok=True)
