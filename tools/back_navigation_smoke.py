"""Verify a configured build: nested pages -> recorder -> launcher, including toolbar Back."""
import argparse
import os
import re
import subprocess
import time
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser()
parser.add_argument('--package', default='com.gujiu502.lectureframe')
parser.add_argument('--serial')
parser.add_argument('--dialogs-only', action='store_true')
args = parser.parse_args() if __name__ == "__main__" else parser.parse_args([])
adb = [os.environ.get('ADB', 'adb')] + (['-s', args.serial] if args.serial else [])
xml_path = '/sdcard/keyframe-back-test.xml'


def run(*command):
    return subprocess.run(adb + list(command), check=True, capture_output=True,
                          text=True, encoding='utf-8', errors='replace').stdout


def nodes():
    try:
        run('shell', 'uiautomator', 'dump', xml_path)
        return list(ET.fromstring(run('shell', 'cat', xml_path)).iter('node'))
    except (subprocess.CalledProcessError, ET.ParseError):
        return []  # Activity/window transitions can temporarily prevent an idle dump.


def find(label, timeout=20):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        current = nodes()
        for node in current:
            if label in (node.get('text'), node.get('content-desc')) and node.get('enabled') == 'true':
                bounds = list(map(int, re.findall(r'\d+', node.get('bounds', ''))))
                if len(bounds) == 4 and bounds[2] > bounds[0] and bounds[3] > bounds[1]:
                    return node
        time.sleep(.3)
    visible = [n.get('text') or n.get('content-desc') for n in current]
    visible = [re.sub(r'[^\s@]+@[^\s@]+', '[account]', text) for text in visible if text]
    raise AssertionError(f'Missing {label}; visible {visible}')


def tap(label):
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', find(label).get('bounds')))
    run('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))


def back():
    run('shell', 'input', 'keyevent', 'KEYCODE_BACK')
    time.sleep(.4)


def home():
    find('開始錄音')
    assert args.package + '/com.eva.recorderapp.MainActivity' in run('shell', 'dumpsys', 'activity', 'activities')


def launch():
    run('shell', 'am', 'start', '-n', args.package + '/com.eva.recorderapp.MainActivity',
        '-a', 'android.intent.action.VIEW', '-d', 'app://com.gujiu502.lectureframe')
    home()


def exit_from_home():
    home()
    back()
    active = [line for line in run('shell', 'dumpsys', 'activity', 'activities').splitlines()
              if 'mResumedActivity' in line or 'topResumedActivity' in line]
    assert active and all(args.package not in line for line in active), active
    launch()


def open_test_recording(long_press=False):
    tap('錄音列表')
    current = nodes()
    names = [n.get('text') for n in current if n.get('text', '').startswith('ZipCheck_')]
    assert names, 'Use a device with a ZIP smoke-test recording; do not select user recordings'
    node = find(names[0])
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds')))
    x, y = str((x1+x2)//2), str((y1+y2)//2)
    if long_press:
        run('shell', 'input', 'swipe', x, y, x, y, '800')
    else:
        run('shell', 'input', 'tap', x, y)


def save_document():
    current = nodes()
    name = next(n.get('text') for n in current if n.get('class') == 'android.widget.EditText')
    label = next(n.get('text') for n in current if n.get('class') == 'android.widget.Button' and n.get('text') in ('儲存', '保存', 'Save', 'SAVE'))
    tap(label)
    time.sleep(1)
    # Android may ask before replacing an older task-owned export.
    if any(n.get('text') in ('替換', '取代', 'Replace', 'REPLACE') for n in nodes()):
        tap(next(n.get('text') for n in nodes() if n.get('text') in ('替換', '取代', 'Replace', 'REPLACE')))
    stem, extension = name.rsplit('.', 1)
    # DocumentsUI adds a numeric suffix when the same smoke export already exists.
    pattern = re.compile(re.escape(stem) + r'(?: \(\d+\))?\.' + re.escape(extension))
    candidates = [n for n in run('shell', 'ls', '-t', '/sdcard/Download').splitlines() if pattern.fullmatch(n)]
    assert candidates, 'Export was not saved to Downloads'
    return candidates[0]


def check_dialogs():
    open_test_recording()
    tap('查看關鍵幀照片')
    find('關閉')
    back()
    exit_from_home()
    print('PASS: fullscreen photo -> recorder -> launcher')
    open_test_recording(long_press=True)
    tap('更多選項')
    tap('重新命名')
    # No filename is changed; cancelling must return home while preserving the original.
    time.sleep(.6)
    back()
    exit_from_home()
    print('PASS: rename dialog -> recorder -> launcher')


if __name__ == "__main__":
    try:
        launch()
        if args.dialogs_only:
            check_dialogs()
            raise SystemExit(0)
        tap('雲端備份與更新')
        find('我的雲端硬碟 / 課程錄音\n備份完成後，本地原始資料仍會保留。')
        back()
        exit_from_home()
        print('PASS: cloud page -> recorder -> launcher')

        tap('更多選項')
        tap('設定')
        find('錄音品質')
        back()
        exit_from_home()
        print('PASS: settings -> recorder -> launcher')

        tap('更多選項')
        tap('設定')
        tap('其他資訊')
        find('原始碼')
        back()
        exit_from_home()
        print('PASS: about dialog -> recorder -> launcher')

        tap('錄音列表')
        tap('更多選項')
        tap('搜尋')
        back()
        # IME can consume Back while it is visible; close it separately before testing page Back.
        try:
            find('開始錄音', timeout=5)
        except AssertionError:
            back()
        exit_from_home()
        print('PASS: nested search -> recorder -> launcher')

        tap('錄音列表')
        tap('回收桶')
        back()
        exit_from_home()
        print('PASS: nested recycle bin -> recorder -> launcher')

        tap('錄音列表')
        tap('更多選項')
        tap('分類')
        find('管理分類')
        back()
        exit_from_home()
        print('PASS: nested categories -> recorder -> launcher')

        tap('雲端備份與更新')
        tap('重新授權 Google Drive')
        find('連結 Google 帳號與雲端備份')
        back()
        exit_from_home()
        print('PASS: account Activity -> recorder -> launcher')

        # Toolbar Back must also clear nested navigation.
        tap('錄音列表')
        tap('更多選項')
        tap('分類')
        find('管理分類')
        tap('返回')
        exit_from_home()
        print('PASS: nested toolbar Back -> recorder -> launcher')

        open_test_recording()
        find('上一幀')
        back()
        exit_from_home()
        print('PASS: player -> recorder -> launcher')

        open_test_recording()
        tap('編輯')
        find('編輯音訊')
        back()
        exit_from_home()
        print('PASS: editor without unsaved edits -> recorder -> launcher')

        open_test_recording(long_press=True)
        find('取消選取')
        back()
        exit_from_home()
        print('PASS: selected recording -> recorder -> launcher')
        check_dialogs()
        open_test_recording()
        tap('本地識別')
        find('Whisper 本地語音識別')
        back()
        exit_from_home()
        print('PASS: Whisper dialog -> recorder -> launcher')
    finally:
        run('shell', 'input', 'keyevent', 'KEYCODE_HOME')
        run('shell', 'rm', '-f', xml_path)
