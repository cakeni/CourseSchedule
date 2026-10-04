import calendar
import datetime as dt
import json
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'semester-week-20261001'))
sys.dont_write_bytecode = True
import check_week_ui as device

device.ROOT = Path(__file__).resolve().parents[2] / 'entry/build/validation/calendar-picker-20261001'
device.ROOT.mkdir(parents=True, exist_ok=True)
screenshots = Path(__file__).resolve().parent

def text_click(nodes, text):
    target = next(node for node in nodes if node.get('text') == text)
    device.click([{**target, 'id': 'target'}], 'target')

def field_date(nodes):
    return next(node['text'] for node in nodes if re.fullmatch(r'\d{4}-\d{2}-\d{2}', node.get('text', '')))

def label(nodes, node_id):
    return next(node['text'] for node in nodes if node.get('id') == node_id)

def capture(name):
    device.run('shell', 'uitest', 'screenCap', '-p', '/data/local/tmp/' + name + '.png')
    device.run('file', 'recv', '/data/local/tmp/' + name + '.png', screenshots / (name + '.png'))

def new_semester():
    nodes = device.snapshot('settings')
    device.click(nodes, 'nav-settings')
    nodes = device.snapshot('settings')
    summary = next(node['text'] for node in nodes if re.fullmatch(r'第\d+周 · 共\d+周', node.get('text', '')))
    text_click(nodes, summary)
    text_click(device.snapshot('manager'), '创建新学期')
    return device.snapshot('editor')

nodes = new_semester()
initial = field_date(nodes)
device.click(nodes, 'semester-date')
nodes = device.snapshot('calendar-light')
capture('calendar-light')
assert label(nodes, 'calendar-selected-year') == initial[:4] + '年'
year, month = map(int, re.findall(r'\d+', label(nodes, 'calendar-month')))
days = {node['id'] for node in nodes if node.get('id', '').startswith('calendar-day-')}
assert len(days) == calendar.monthrange(year, month)[1]
device.click(nodes, 'calendar-day-1')
device.click(device.snapshot('changed'), 'calendar-cancel')
assert field_date(device.snapshot('cancelled')) == initial

device.click(device.snapshot('editor'), 'semester-date')
nodes = device.snapshot('reopened')
year, month = map(int, re.findall(r'\d+', label(nodes, 'calendar-month')))
device.click(nodes, 'calendar-next-month')
nodes = device.snapshot('next-month')
next_month = dt.date(year + (month == 12), month % 12 + 1, 1)
assert label(nodes, 'calendar-month') == f'{next_month.year}年{next_month.month}月'
device.click(nodes, 'calendar-day-1')
device.click(device.snapshot('selected'), 'calendar-confirm')
expected = next_month - dt.timedelta(days=next_month.weekday())
assert field_date(device.snapshot('confirmed')) == expected.isoformat()

device.click(device.snapshot('editor'), 'semester-date')
nodes = device.snapshot('leap-start')
year, month = map(int, re.findall(r'\d+', label(nodes, 'calendar-month')))
target_year = year if calendar.isleap(year) else year + (4 - year % 4)
if (target_year, 2) <= (year, month):
    target_year += 4
while not calendar.isleap(target_year):
    target_year += 4
for _ in range((target_year - year) * 12 + 2 - month):
    device.click(nodes, 'calendar-next-month')
    nodes = device.snapshot('leap-navigation')
assert label(nodes, 'calendar-month') == f'{target_year}年2月'
assert any(node.get('id') == 'calendar-day-29' for node in nodes)
assert not any(node.get('id') == 'calendar-day-30' for node in nodes)
device.click(nodes, 'calendar-day-29')
nodes = device.snapshot('leap-day')
assert label(nodes, 'calendar-selected-year') == f'{target_year}年'
assert label(nodes, 'calendar-selected-date').startswith('2月29日')
device.click(nodes, 'calendar-confirm')
leap_date = dt.date(target_year, 2, 29)
assert field_date(device.snapshot('leap-confirmed')) == (leap_date - dt.timedelta(days=leap_date.weekday())).isoformat()
device.run('shell', 'uitest', 'uiInput', 'keyEvent', 'Back')

nodes = device.snapshot('before-dark')
original_dark = next(node.get('checked') == 'true' for node in nodes if node.get('id') == 'dark-mode')
if not original_dark:
    device.click(nodes, 'dark-mode')
try:
    nodes = new_semester()
    dark_initial = field_date(nodes)
    device.click(nodes, 'semester-date')
    nodes = device.snapshot('calendar-dark')
    capture('calendar-dark')
    device.run('shell', 'uitest', 'uiInput', 'keyEvent', 'Back')
    assert field_date(device.snapshot('back-dismissed')) == dark_initial
    device.click(device.snapshot('editor'), 'semester-date')
    bounds = next(node['bounds'] for node in device.snapshot('mask') if node.get('id') == 'semester-calendar')
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', bounds))
    device.run('shell', 'uitest', 'uiInput', 'click', x1 // 2, (y1 + y2) // 2)
    assert field_date(device.snapshot('mask-dismissed')) == dark_initial
    device.run('shell', 'uitest', 'uiInput', 'keyEvent', 'Back')
finally:
    if not original_dark:
        device.click(device.snapshot('restore-theme'), 'dark-mode')

result = {'passed': True, 'checks': ['month grid', 'cancel unchanged', 'next month', 'Monday confirmation',
    'leap day and year boundary', 'dark theme', 'system Back', 'outside dismissal'], 'saved_semesters': 0}
(device.ROOT / 'ui-result.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps(result, ensure_ascii=False))
