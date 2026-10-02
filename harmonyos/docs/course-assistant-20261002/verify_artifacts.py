"""Check deliverable routes and optional local-secret exclusion without printing secrets."""
from pathlib import Path
import json
import re
import subprocess
import sys
import zipfile

repo = Path(__file__).resolve().parents[3]
credential = Path(sys.argv[1]).read_text(encoding='utf-8-sig') if len(sys.argv) > 1 else ''
match = re.search(r'sk-[A-Za-z0-9_-]+', credential)
key = match.group(0).encode() if match else b''
outputs = Path(r'D:\dev-cache\harmony-course-assistant-20261002\output')
for artifact in outputs.glob('CourseSchedule-*.hap'):
    with zipfile.ZipFile(artifact) as archive:
        profiles = [name for name in archive.namelist() if name.endswith('main_pages.json')]
        assert profiles, 'HAP 未包含页面列表'
        assert json.loads(archive.read(profiles[0]))['src'] == ['pages/Index'], '交付包含测试页面'
        for name in archive.namelist():
            contents = archive.read(name)
            assert b'NativeAssistantChecks' not in contents, '交付包包含测试入口'
            if key: assert key not in contents, '交付包包含本机凭证'
    print(artifact.name + ': production route / test-entry exclusion / credential exclusion passed')
files = []
for args in [['diff', '--name-only', 'HEAD', '-z'], ['ls-files', '--others', '--exclude-standard', '-z']]:
    files += subprocess.check_output(['git', *args], cwd=repo).decode('utf-8').split('\0')
if key:
    for name in set(files):
        file = repo / name
        if name and file.is_file(): assert key not in file.read_bytes(), '待提交文件包含本机凭证'
    print('Changed and untracked files: credential exclusion passed')
