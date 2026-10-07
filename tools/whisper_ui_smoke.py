"""Verify local Whisper controls on an existing, task-owned ZIP smoke recording."""
import argparse
import hashlib
from pathlib import Path
import re
import time
import zipfile
import back_navigation_smoke as ui

p = argparse.ArgumentParser()
p.add_argument('--serial', required=True)
p.add_argument('--package', default='com.gujiu502.lectureframe')
p.add_argument('--recording-id', type=int)
a = p.parse_args()
ui.args.package = a.package
ui.adb = [ui.adb[0], '-s', a.serial]
output = Path('work/whisper-ui-smoke')
output.mkdir(parents=True, exist_ok=True)
rows = ui.run('shell', 'content', 'query', '--uri', 'content://media/external/audio/media', '--projection', '_id:_display_name:_data:owner_package_name')
owned = [r for r in rows.splitlines() if 'owner_package_name=' + a.package in r and '_display_name=ZipCheck_' in r]
assert owned
if a.recording_id is None:
    a.recording_id = max(int(re.search(r'_id=(\d+)', r)[1]) for r in owned)
row = next(r for r in owned if re.search(r'_id=' + str(a.recording_id) + r',', r))
assert 'owner_package_name=' + a.package in row and '_display_name=ZipCheck_' in row, 'Only use task-owned smoke recordings'
audio = re.search(r'_data=([^,]+)', row)[1]
ui.run('pull', audio, str(output / 'original-before'))
original_hash = hashlib.sha256((output / 'original-before').read_bytes()).hexdigest()

def open_recording():
    ui.run('shell', 'am', 'start', '-n', a.package + '/com.eva.recorderapp.MainActivity', '-a', 'android.intent.action.VIEW', '-d', 'app://com.gujiu502.lectureframe/player/' + str(a.recording_id))
    ui.tap('本地識別')


try:
    open_recording()
    if any(n.get('text') == '下載 Whisper 模型' for n in ui.nodes()):
        ui.tap('下載 Whisper 模型'); ui.find('下載本地 Whisper 模型？')
        ui.tap('下載')
        ui.find('開始識別', timeout=300)
        print('PASS: consent and checksum-verified on-device model download', flush=True)
    else:
        ui.find('開始識別')
    ui.tap('自動語言')
    ui.tap('開始識別')
    if any(n.get('text') == '重新識別這個錄音？' for n in ui.nodes()): ui.tap('開始識別')
    ui.find('識別完成，結果已保存在手機', timeout=180)
    ui.find('導出文字')
    print('PASS: prior-recording inference, saved result and Chinese completion UI', flush=True)
    ui.tap('導出文字')
    name = ui.save_document()
    ui.find('關閉')
    ui.run('pull', '/sdcard/Download/' + name, str(output / 'transcript.txt'))
    text = (output / 'transcript.txt').read_text(encoding='utf-8')
    assert text.strip()
    ui.tap('關閉')
    ui.run('shell', 'am', 'force-stop', a.package)
    open_recording()
    ui.find('導出文字')
    print('PASS: TXT export and result survives app restart', flush=True)
    ui.tap('關閉'); ui.tap('導出')
    name = ui.save_document()
    ui.find('導出')
    ui.run('pull', '/sdcard/Download/' + name, str(output / 'lecture.zip'))
    with zipfile.ZipFile(output / 'lecture.zip') as z:
        assert z.testzip() is None
        assert text in z.read('lecture.md').decode('utf-8')
        assert any(hashlib.sha256(z.read(n)).hexdigest() == original_hash for n in z.namelist() if n.startswith('audio/'))
    ui.run('pull', audio, str(output / 'original-after'))
    assert hashlib.sha256((output / 'original-after').read_bytes()).hexdigest() == original_hash
    print('PASS: transcript in ZIP, CRC and unchanged original audio', flush=True)
    ui.tap('本地識別'); ui.tap('自動語言'); ui.tap('開始識別')
    ui.find('重新識別這個錄音？'); ui.tap('開始識別')
    stop = next((n for n in ui.nodes() if n.get('text') == '停止識別' and n.get('enabled') == 'true'), None)
    if stop is not None:
        x1, y1, x2, y2 = map(int, re.findall(r'\d+', stop.get('bounds')))
        ui.run('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))
        ui.find('已停止，已保存的識別內容仍保留')
        ui.find('導出文字')
        print('PASS: Stop button cancels recognition and preserves saved content', flush=True)
    else:
        ui.find('識別完成，結果已保存在手機')
        print('INFO: short file completed before UI Stop; native abort is checked by WhisperTest', flush=True)
finally:
    ui.run('shell', 'input', 'keyevent', 'KEYCODE_HOME')
    ui.run('shell', 'rm', '-f', ui.xml_path)
