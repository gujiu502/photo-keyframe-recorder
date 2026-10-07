"""Actual >2h microphone/background/camera soak; never substitutes a simulated clock."""
import argparse, os, re, subprocess, time, xml.etree.ElementTree as ET

p = argparse.ArgumentParser()
p.add_argument('--serial', required=True)
p.add_argument('--package', default='com.gujiu502.lectureframe.debug')
p.add_argument('--seconds', type=int, default=7500)
args = p.parse_args()
assert args.seconds > 7200
adb = [os.environ.get('ADB', 'adb'), '-s', args.serial]
xml = '/sdcard/keyframe-soak.xml'

def run(*parts):
    return subprocess.run(adb + list(parts), check=True, capture_output=True, text=True, encoding='utf-8', errors='replace').stdout

def find(text):
    deadline = time.monotonic() + 45
    while time.monotonic() < deadline:
        try:
            run('shell', 'uiautomator', 'dump', xml)
            for n in ET.fromstring(run('shell', 'cat', xml)).iter('node'):
                if text in (n.get('text'), n.get('content-desc')) and n.get('enabled') == 'true': return n
        except (subprocess.CalledProcessError, ET.ParseError): pass
        time.sleep(.5)
    raise AssertionError('Missing ' + text)

def tap(text):
    n = find(text)
    a,b,c,d = map(int,re.findall(r'\d+', n.get('bounds')))
    run('shell','input','tap',str((a+c)//2),str((b+d)//2))

def photo(count):
    start=time.monotonic()
    tap('拍照關鍵幀'); tap('拍照'); find(f'{count} 張照片')
    print(f'Photo {count}: {time.monotonic()-start:.1f}s including UI automation',flush=True)

try:
    run('shell','wm','size','720x1280')
    run('shell','am','start','-n',args.package+'/com.eva.recorderapp.MainActivity')
    tap('開始錄音')
    started=time.monotonic()
    for count in range(1,6): photo(count)
    pid=run('shell','pidof',args.package).strip()
    assert pid
    run('shell','input','keyevent','KEYCODE_HOME')
    # Power-off the emulated display; retain the original setting for handoff.
    run('shell','input','keyevent','KEYCODE_SLEEP')
    until=time.monotonic()+args.seconds
    baseline=None
    while time.monotonic()<until:
        assert run('shell','pidof',args.package).strip()==pid,'Recording process restarted or crashed'
        services=run('shell','dumpsys','activity','services',args.package)
        assert 'VoiceRecorderService' in services and 'isForeground=true' in services
        if baseline is None or int(time.monotonic()-started)//600 != baseline:
            baseline=int(time.monotonic()-started)//600
            memory=run('shell','dumpsys','meminfo',args.package)
            summary=next((s.strip() for s in memory.splitlines() if s.strip().startswith('TOTAL PSS:')), 'memory sampled')
            print(f'{int(time.monotonic()-started)}s: foreground service alive, {summary}',flush=True)
        time.sleep(min(30,max(.1,until-time.monotonic())))
    run('shell','input','keyevent','KEYCODE_WAKEUP')
    run('shell','am','start','-n',args.package+'/com.eva.recorderapp.MainActivity')
    for count in range(6,11): photo(count)
    tap('停止')
    fields=find('檔名（不含副檔名）')
    a,b,c,d=map(int,re.findall(r'\d+',fields.get('bounds')))
    run('shell','input','tap',str((a+c)//2),str((b+d)//2))
    run('shell','input','text','LongCheck')
    tap('完成'); find('開始錄音')
    rows=run('shell','content','query','--uri','content://media/external/audio/media','--projection','_id:_display_name:duration:owner_package_name')
    owned=[r for r in rows.splitlines() if '_display_name=LongCheck_' in r and 'owner_package_name='+args.package in r]
    assert owned,'Long recording was not saved'
    row=max(owned,key=lambda r:int(re.search(r'_id=(\d+)',r)[1]))
    assert int(re.search(r'duration=(\d+)',row)[1])>7_200_000,row
    print('PASS: actual >2h background recording, process continuity, ten CameraX photos and finalized playable-duration metadata',flush=True)
finally:
    run('shell','input','keyevent','KEYCODE_WAKEUP')
    run('shell','wm','size','reset')
    run('shell','input','keyevent','KEYCODE_HOME')
    run('shell','rm','-f',xml)
