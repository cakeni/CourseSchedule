from pathlib import Path
import json
import re
import textwrap

root = Path(__file__).resolve().parents[3]
source = (root / 'app/src/test/java/com/courseschedule/ui/importdata/ReportScheduleParserTest.kt').read_text(encoding='utf-8')
fixtures = {}
for match in re.finditer(r'@Test fun (\w+)\([^)]*\)\s*\{([\s\S]*?)(?=@Test|\Z)', source):
    html = re.search(r'val (?:html|valid) = """([\s\S]*?)"""', match[2])
    if html:
        fixtures[match[1]] = textwrap.dedent(html[1]).strip()
(root / 'harmonyos/tests/ReportFixtures.json').write_text(json.dumps(fixtures, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
