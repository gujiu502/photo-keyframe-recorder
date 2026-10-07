"""Export the task-owned >2h LongCheck through Android's document picker."""
import argparse, re, subprocess, sys, json, zipfile
from pathlib import Path
import back_navigation_smoke as ui
p = argparse.ArgumentParser()
p.add_argument('--serial', required=True)
p.add_argument('--package', default='com.gujiu502.lectureframe.debug')
a = p.parse_args()
ui.adb = [ui.adb[0], '-s', a.serial]
ui.args.package = a.package
output = Path('work/long-recording-export')
output.mkdir(parents=True, exist_ok=True)
rows = ui.run('shell', 'content', 'query', '--uri', 'content://media/external/audio/media', '--projection', '_id:_display_name:_data:duration:owner_package_name')
owned = [r for r in rows.splitlines() if 'owner_package_name=' + a.package in r and '_display_name=LongCheck_' in r]
assert owned, 'Run long_recording_smoke first; never choose user recordings'
row = max(owned, key=lambda r: int(re.search(r'_id=(\d+)', r)[1]))
recording_id = re.search(r'_id=(\d+)', row)[1]
duration = int(re.search(r'duration=(\d+)', row)[1])
path = re.search(r'_data=([^,]+)', row)[1]
audio = output / ('original' + Path(path).suffix)
try:
    ui.run('shell','am','force-stop',a.package)
    ui.run('shell','am','start','-n',a.package+'/com.eva.recorderapp.MainActivity','-a','android.intent.action.VIEW','-d','app://com.gujiu502.lectureframe/player/'+recording_id)
    ui.tap('導出')
    name = ui.save_document()
    ui.find('導出', timeout=120)
    ui.run('pull','/sdcard/Download/'+name,str(output/'lecture.zip'))
    ui.run('pull',path,str(audio))
    subprocess.run([sys.executable,'tools/verify_zip.py',str(output/'lecture.zip'),str(audio)],check=True)
    with zipfile.ZipFile(output/'lecture.zip') as z:
        items=json.loads(z.read('timeline.json'))['items']
        photos=[item for item in items if item['type']=='PHOTO']
        assert len(photos)==10
        last_photo = max(item['positionMs'] for item in photos)
        assert last_photo>7200000
    print(f'PASS: ten-photo ZIP, original audio bytes and post-two-hour timestamps; audio={duration}ms, last_photo={last_photo}ms',flush=True)
    assert duration>7200000, 'Audio duration is shorter than the background test; preserve export for diagnosis'
    print('PASS: >2h original audio, ten photos, post-two-hour timestamps and document-picker ZIP export',flush=True)
finally:
    ui.run('shell','input','keyevent','KEYCODE_HOME')
    ui.run('shell','rm','-f',ui.xml_path)
