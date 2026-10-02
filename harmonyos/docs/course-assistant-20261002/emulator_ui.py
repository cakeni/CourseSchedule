"""Observed-layout actions on the dedicated CourseScheduleAssistant emulator."""
from pathlib import Path
import subprocess
import json
import re
import sys
import time
sys.stdout.reconfigure(encoding='utf-8')
sys.stderr.reconfigure(encoding='utf-8')
hdc = r'D:\devco\DevEco Studio\sdk\default\openharmony\toolchains\hdc.exe'
folder = Path(__file__).parent

def command(*args):
    result = subprocess.run([hdc, '-t', '127.0.0.1:5555', *map(str, args)], capture_output=True, text=True, encoding='utf-8', errors='replace')
    if result.returncode or ('Error' in result.stdout and 'No Error' not in result.stdout):
        raise RuntimeError(result.stdout + result.stderr)
    return result.stdout

def snapshot(system=False):
    args = ['shell', 'uitest', 'dumpLayout', '-p', '/data/local/tmp/assistant-layout.json']
    if not system: args += ['-b', 'com.courseschedule.qingke']
    command(*args)
    local = folder / 'layout.json'
    command('file', 'recv', '/data/local/tmp/assistant-layout.json', local)
    tree = json.loads(local.read_text(encoding='utf-8'))
    local.unlink()
    command('shell', 'rm', '/data/local/tmp/assistant-layout.json')
    result = []
    def visit(node):
        attributes = node.get('attributes', {})
        if attributes.get('id') == 'assistant-api-key': attributes['text'] = '[密码字段]'
        result.append(attributes)
        for child in node.get('children', []): visit(child)
    visit(tree)
    return result

def find(key, value, system=False):
    found = [a for a in snapshot(system) if a.get(key) == value and a.get('visible') == 'true']
    if not found: raise RuntimeError(f'未找到 {key}={value}')
    return found[0]

def rect(a): return list(map(int, re.findall(r'-?\d+', a['bounds'])))
def center(a):
    x1,y1,x2,y2 = rect(a)
    return (x1+x2)//2, (y1+y2)//2

action = sys.argv[1]
if action in ['snapshot', 'system']:
    for a in snapshot(action == 'system'):
        if a.get('visible') == 'true' and (a.get('text') or a.get('id')):
            print(a.get('type'), a.get('id'), repr('[密码字段]' if a.get('id') == 'assistant-api-key' else a.get('text')), a.get('bounds'))
elif action in ['id', 'text', 'system-id', 'system-text']:
    command('shell', 'uitest', 'uiInput', 'click', *center(find(action.split('-')[-1], sys.argv[2], action.startswith('system-'))))
    time.sleep(0.6)
elif action in ['input', 'replace', 'key-from-file']:
    target = 'assistant-api-key' if action == 'key-from-file' else sys.argv[2]
    x,y = center(find('id',target)); command('shell','uitest','uiInput','click',x,y); time.sleep(0.6)
    x,y = center(find('id',target))
    if action == 'replace':
        command('shell','uitest','uiInput','keyEvent','2072','2017'); command('shell','uitest','uiInput','keyEvent','2055')
    if action == 'key-from-file':
        raw = Path(sys.argv[2]).read_text(encoding='utf-8-sig')
        match = re.search(r'sk-[a-zA-Z0-9_-]+',raw)
        if not match: raise RuntimeError('未找到有效 Key')
        value=match.group(0)
    else: value=sys.argv[3]
    quoted="'"+value.replace("'","'\\''")+"'"
    try: command('shell','uitest','uiInput','inputText',x,y,quoted)
    except RuntimeError as error: raise RuntimeError(str(error).replace(value,'[redacted]')) from None
    if action == 'key-from-file': print('Key 已填入密码字段，内容未输出。')
    if len(sys.argv) < 5 or sys.argv[4] != 'keyboard':
        command('shell','uitest','uiInput','keyEvent','Back')
elif action in ['swipe', 'long', 'bottom']:
    a=find('id',sys.argv[2]);x1,y1,x2,y2=rect(a);x=(x1+x2)//2
    if action=='long': command('shell','uitest','uiInput','longClick',*center(a))
    else:
        for _ in range(24 if action == 'bottom' else 1):
            command('shell','uitest','uiInput','swipe',x,y2-22,x,y1+22,450)
elif action == 'form-bottom':
    forms = [a for a in snapshot() if a.get('type') == 'Scroll' and a.get('visible') == 'true']
    if not forms: raise RuntimeError('未找到可见表单滚动区')
    x1,y1,x2,y2 = rect(forms[-1]); x=(x1+x2)//2
    command('shell','uitest','uiInput','swipe',x,y2-22,x,y1+22,450)
    time.sleep(0.6)
elif action == 'capture':
    time.sleep(0.4)
    name=sys.argv[2];command('shell','uitest','screenCap','-p',f'/data/local/tmp/{name}.png')
    command('file','recv',f'/data/local/tmp/{name}.png',folder/(name+'.png'))
