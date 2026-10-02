"""Build-only validation entry. Always restore before producing a deliverable HAP."""
from pathlib import Path
import sys

root = Path(__file__).resolve().parents[2]
ability = root / 'entry/src/main/ets/entryability/EntryAbility.ets'
pages = root / 'entry/src/main/resources/base/profile/main_pages.json'
fixture = root / 'entry/src/main/ets/pages/NativeAssistantChecks.ets'
cache = root / 'entry/build/validation/assistant-source'
cache.mkdir(parents=True, exist_ok=True)
if sys.argv[1] == 'prepare':
    if fixture.exists():
        raise RuntimeError('Restore the current validation entry first.')
    for source in (ability, pages):
        (cache / source.name).write_bytes(source.read_bytes())
    ability.write_text(ability.read_text(encoding='utf-8').replace("'pages/Index'", "'pages/NativeAssistantChecks'"), encoding='utf-8')
    pages.write_text('{"src":["pages/Index","pages/NativeAssistantChecks"]}\n', encoding='utf-8')
    fixture.write_bytes((root / 'tests/NativeAssistantChecks.ets').read_bytes())
elif sys.argv[1] == 'restore':
    for source in (ability, pages):
        source.write_bytes((cache / source.name).read_bytes())
    fixture.unlink(missing_ok=True)
