from pathlib import Path
import xml.etree.ElementTree as ET
import json
import re
import shutil
import textwrap

root = Path(__file__).resolve().parents[3]
resources = root / 'harmonyos/entry/src/main/resources/rawfile'
ns = '{http://schemas.android.com/apk/res/android}'
for name in ['edit', 'today', 'time', 'person', 'room', 'text']:
    vector = ET.parse(root / f'app/src/main/res/drawable/ic_{name}.xml').getroot()
    svg = ET.Element('svg', xmlns='http://www.w3.org/2000/svg', viewBox=f'0 0 {vector.get(ns+"viewportWidth")} {vector.get(ns+"viewportHeight")}')
    for path in vector.findall('path'):
        ET.SubElement(svg, 'path', fill='white', d=path.get(ns+'pathData'))
    ET.ElementTree(svg).write(resources / f'ic_{name}.svg', encoding='utf-8')

source = root / 'app/src/main/java/com/courseschedule/ui/importdata'
for name in ['academic_school_directory.json', 'academic_school_directory_NOTICE.txt']:
    shutil.copyfile(root / 'app/src/main/assets' / name, resources / name)
profiles = re.findall(r'GenericAcademicProfile\("([^"]+)", "([^"]+)", AcademicSystem\.(\w+),\s*"([^"]+)"\)', (source / 'GenericAcademicImport.kt').read_text(encoding='utf-8'))
registry = (source / 'AcademicAdapterRegistry.kt').read_text(encoding='utf-8')
constants = dict(re.findall(r'const val (\w+) = "([^"]+)"', registry))
specs = []
for block in re.findall(r'AcademicAdapterSpec\(\s*(\w+), AcademicSystem\.(\w+),([\s\S]*?)captureMode = AcademicCaptureMode\.(\w+)\s*\)', registry):
    key, system, body, mode = block
    lists = {}
    for name, values in re.findall(r'(selectors|globals|requestPathPrefixes) = listOf\(([\s\S]*?)\)', body):
        lists[name] = re.findall(r'"([^"]*)"', values)
    specs.append(dict(id=constants[key], system=system, captureMode=mode, **lists))
maps = {}
for name in ['profileAdapters', 'sourceTypeAdapters']:
    block = re.search(r'private val ' + name + r' = mapOf\(([\s\S]*?)\n    \)', registry)
    maps[name] = {key: constants[value] for key, value in re.findall(r'"([^"]+)" to (\w+)', block[1])}
(resources / 'academic_profiles.json').write_text(json.dumps(dict(profiles=[dict(id=p[0], label=p[1], system=p[2], instructions=p[3]) for p in profiles], specs=specs, **maps), ensure_ascii=False), encoding='utf-8')

for filename in ['AcademicCaptureScript', 'QiangzhiCaptureScript', 'AcademicAiCaptureScript']:
    content = (source / (filename + '.kt')).read_text(encoding='utf-8')
    script = re.search(r'(?:private )?(?:val|const val) SCRIPT = """([\s\S]*?)"""\.trimIndent\(\)', content)
    if script:
        js = textwrap.dedent(script[1]).strip().replace("${'$'}", '$')
        (resources / (filename + '.js')).write_text(js, encoding='utf-8')
content = (source / 'AcademicWebImportActivity.kt').read_text(encoding='utf-8')
for name in ['SNAPSHOT_BRIDGE', 'TERM_PROBE_SCRIPT', 'NUAA_GRADUATE_TIMETABLE_SCRIPT', 'ZHENGFANG_FETCH_SCRIPT', 'FETCH_SCRIPT']:
    match = re.search(r'(?:private )?(?:val|const val) ' + name + r' = """([\s\S]*?)"""\.trimIndent\(\)', content)
    if match:
        (resources / (name + '.js')).write_text(textwrap.dedent(match[1]).strip().replace("${'$'}", '$'), encoding='utf-8')
