from pathlib import Path
import subprocess
import json
import re
import sys
import time
sys.stdout.reconfigure(encoding='utf-8')

hdc = r'D:\devco\DevEco Studio\sdk\default\openharmony\toolchains\hdc.exe'
folder = Path(__file__).parent
def command(*args):
    result = subprocess.run([hdc, '-t', '127.0.0.1:5555', *map(str, args)], capture_output=True, text=True, encoding='utf-8', errors='replace')
    if result.returncode or ('Error' in result.stdout and 'No Error' not in result.stdout): raise RuntimeError(result.stdout + result.stderr)
    return result.stdout
def snapshot():
    args = ['shell', 'uitest', 'dumpLayout', '-p', '/data/local/tmp/full-port.json']
    if not sys.argv[1].startswith('sys'): args += ['-b', 'com.courseschedule.qingke']
    command(*args)
    local = folder / 'layout.json'
    command('file', 'recv', '/data/local/tmp/full-port.json', local)
    tree = json.loads(local.read_text(encoding='utf-8'))
    result = []
    def visit(node):
        result.append(node.get('attributes', {}))
        for child in node.get('children', []): visit(child)
    visit(tree)
    return result
def find(key, value):
    found = [a for a in snapshot() if a.get(key) == value and a.get('visible') == 'true']
    if not found: raise RuntimeError(f'未找到 {key}={value}')
    return found[-1] if sys.argv[1].startswith('sys') else found[0]
def center(a):
    x1,y1,x2,y2 = map(int, re.findall(r'-?\d+', a['bounds']))
    return (x1+x2)//2, (y1+y2)//2
def click(key, value):
    command('shell', 'uitest', 'uiInput', 'click', *center(find(key,value)))
    time.sleep(0.7)
action = sys.argv[1]
if action in ['snapshot', 'sys-snapshot']:
    for a in snapshot():
        if a.get('visible') == 'true' and (a.get('text') or a.get('id')):
            print(a.get('type'), a.get('id'), repr(a.get('text')), a.get('bounds'))
elif action in ['id', 'text', 'sys-id', 'sys-text']: click('id' if action.endswith('id') else 'text', sys.argv[2])
elif action in ['input', 'replace']:
    a = find('id', sys.argv[2]); x,y = center(a)
    command('shell', 'uitest', 'uiInput', 'click', x, y)
    time.sleep(0.7)
    x,y = center(find('id', sys.argv[2]))
    if action == 'replace':
        command('shell', 'uitest', 'uiInput', 'keyEvent', '2072', '2017')
        command('shell', 'uitest', 'uiInput', 'keyEvent', '2055')
    quoted = "'" + sys.argv[3].replace("'", "'\\''") + "'"
    command('shell', 'uitest', 'uiInput', 'inputText', x, y, quoted)
    time.sleep(0.7)
    command('shell', 'uitest', 'uiInput', 'keyEvent', 'Back')
    time.sleep(0.7)
elif action == 'capture':
    name = sys.argv[2]
    command('shell', 'uitest', 'screenCap', '-p', f'/data/local/tmp/{name}.png')
    command('file', 'recv', f'/data/local/tmp/{name}.png', folder / (name+'.png'))
