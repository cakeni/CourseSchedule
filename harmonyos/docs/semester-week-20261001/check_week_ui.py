import argparse
import json
import re
import subprocess
import sys
import time
from pathlib import Path

sys.stdout.reconfigure(encoding='utf-8')
ROOT = Path(__file__).resolve().parents[2] / 'entry/build/validation/semester-week-20261001'
ROOT.mkdir(parents=True, exist_ok=True)
HDC = r'D:\devco\DevEco Studio\sdk\default\openharmony\toolchains\hdc.exe'

def run(*args):
    r = subprocess.run([HDC, '-t', '127.0.0.1:5555', *map(str, args)], capture_output=True, text=True, encoding='utf-8', errors='replace')
    if r.returncode or '[Fail]' in r.stdout or '[Error]' in r.stdout:
        raise RuntimeError('Device command failed')
    return r.stdout

def snapshot(label):
    run('shell', 'uitest', 'dumpLayout', '-p', '/data/local/tmp/semester-week-check.json', '-b', 'com.courseschedule.harmonyos')
    path = ROOT / (label + '.json')
    run('file', 'recv', '/data/local/tmp/semester-week-check.json', path)
    tree = json.loads(path.read_text(encoding='utf-8'))
    nodes = []
    def visit(n):
        a = n.get('attributes', {})
        if a.get('visible') == 'true': nodes.append(a)
        for c in n.get('children', []): visit(c)
    visit(tree)
    return nodes

def week(nodes):
    return int(next(re.fullmatch(r'第(\d+)周', a.get('text', '')).group(1)
                    for a in nodes if re.fullmatch(r'第(\d+)周', a.get('text', ''))))

def click(nodes, node_id):
    a = next(a for a in nodes if a.get('id') == node_id)
    x1,y1,x2,y2 = map(int, re.findall(r'-?\d+', a['bounds']))
    run('shell', 'uitest', 'uiInput', 'click', (x1+x2)//2, (y1+y2)//2)
    time.sleep(.4)

def swipe(nodes, forward):
    # The empty weekend column starts the gesture inside the timetable viewport.
    # Use the observed table and navigation bounds rather than desktop positions.
    card = next(a for a in nodes if a.get('id','').startswith('course-card-'))
    nav = next(a for a in nodes if a.get('id') == 'nav-schedule')
    _, top, _, bottom = map(int, re.findall(r'-?\d+', card['bounds']))
    nx1, ny1, nx2, ny2 = map(int, re.findall(r'-?\d+', nav['bounds']))
    y = min(top+100, ny1-100)
    # Display is 1280 px wide; navigation spans thirds of the same screen.
    width = nx2 * 3
    left, right = round(width*.18), round(width*.86)
    run('shell', 'uitest', 'uiInput', 'swipe', right if forward else left, y, left if forward else right, y, 2400)
    time.sleep(.4)

def check(nodes, course_id, start, end, week_type):
    current = week(nodes)
    target = next((a for a in nodes if a.get('id') == 'course-name-' + str(course_id)), None)
    active = start <= current <= end and (week_type == 0 or current % 2 == (1 if week_type == 1 else 0))
    next_active = any(week_type == 0 or w % 2 == (1 if week_type == 1 else 0) for w in range(max(current, start), end+1))
    assert bool(target) == next_active, f'Course presence incorrect at week {current}'
    if target:
        inactive = target.get('text','').startswith('[非本周]')
        assert inactive == (not active), f'Stale week status at week {current}'
    return {'week': current, 'present': bool(target), 'active': active}

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('label')
    parser.add_argument('course_id', type=int)
    parser.add_argument('start', type=int)
    parser.add_argument('end', type=int)
    parser.add_argument('--week-type', type=int, choices=[0, 1, 2], default=0)
    parser.add_argument('--total', type=int, default=20)
    args = parser.parse_args()
    if not re.fullmatch(r'[A-Za-z0-9_-]+', args.label):
        parser.error('label must contain only letters, numbers, underscores or hyphens')
    nodes = snapshot(args.label + '-initial')
    while week(nodes) > 1:
        click(nodes, 'previous-week')
        nodes = snapshot(args.label + '-position')
    records = []
    for pass_index, weeks in enumerate([range(1,args.total+1), range(args.total-1,0,-1)]):
        for target_week in weeks:
            if week(nodes) != target_week:
                # Late weeks can have no course at all. The next/previous buttons
                # remain available and exercise the same selectWeek path.
                if any(a.get('id','').startswith('course-card-') for a in nodes):
                    swipe(nodes, pass_index == 0)
                else:
                    click(nodes, 'next-week' if pass_index == 0 else 'previous-week')
                nodes = snapshot(f'{args.label}-pass{pass_index+1}-week{target_week}')
            assert week(nodes) == target_week, 'Week gesture failed'
            records.append({'pass':pass_index+1, **check(nodes,args.course_id,args.start,args.end,args.week_type)})
        print(json.dumps({'pass':pass_index+1,'checked':len(weeks),'passed':True}),flush=True)
    result = {'checks':records,'all_passed':True}
    (ROOT/(args.label+'-result.json')).write_text(json.dumps(result,indent=2),encoding='utf-8')
    print(json.dumps({'total_checks':len(records),'all_passed':True}),flush=True)
