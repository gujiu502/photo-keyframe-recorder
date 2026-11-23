"""Run on an installed test build: python tools/ui_smoke.py --package ... [--serial ...]."""
import argparse
from pathlib import Path
import os
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from verify_mp4 import verify_mp4

parser = argparse.ArgumentParser()
parser.add_argument("--package", default="com.gujiu502.lectureframe.debug")
parser.add_argument("--serial")
args = parser.parse_args()
adb = [os.environ.get("ADB", "adb")] + (["-s", args.serial] if args.serial else [])
output = Path("work/ui-smoke-" + args.package)
output.mkdir(parents=True, exist_ok=True)


def run(*command, check=True):
    return subprocess.run(adb + list(command), check=check, capture_output=True, text=True, encoding="utf-8", errors="replace").stdout


def nodes():
    dumped = subprocess.run(adb + ["shell", "uiautomator", "dump", "/sdcard/keyframe-smoke.xml"], capture_output=True, text=True, encoding="utf-8", errors="replace")
    if dumped.returncode or "dumped to" not in (dumped.stdout + dumped.stderr).lower():
        return []
    root = ET.fromstring(run("shell", "cat", "/sdcard/keyframe-smoke.xml"))
    def propagate_disabled(node, enabled=True):
        enabled = enabled and node.get("enabled", "true") == "true"
        if not enabled:
            node.set("enabled", "false")
        for child in node:
            propagate_disabled(child, enabled)
    propagate_disabled(root)
    return list(root.iter("node"))


def find(label, timeout=35):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        for node in nodes():
            if label in (node.get("text"), node.get("content-desc")) and node.get("enabled") == "true":
                return node
        time.sleep(0.5)
    current = nodes()
    (output / "failure.xml").write_text(run("shell", "cat", "/sdcard/keyframe-smoke.xml"), encoding="utf-8")
    with (output / "logcat.txt").open("wb") as log:
        subprocess.run(adb + ["logcat", "-d"], stdout=log, check=False)
    with (output / "failure.png").open("wb") as screenshot:
        subprocess.run(adb + ["exec-out", "screencap", "-p"], stdout=screenshot, check=False)
    visible = [(n.get("text"), n.get("content-desc"), n.get("enabled")) for n in current if n.get("text") or n.get("content-desc")]
    raise AssertionError(f"Missing enabled control: {label}; visible: {visible}")


def tap(label):
    node = find(label)
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
    run("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))


def photo(count):
    tap("拍照關鍵幀")
    tap("拍照")
    find(f"{count} 張照片")


for permission in ("RECORD_AUDIO", "CAMERA", "POST_NOTIFICATIONS", "READ_MEDIA_AUDIO", "READ_EXTERNAL_STORAGE"):
    run("shell", "pm", "grant", args.package, "android.permission." + permission, check=False)
run("shell", "am", "start", "-n", args.package + "/com.eva.recorderapp.MainActivity")
tap("開始錄音")
time.sleep(3)
photo(1)
tap("暫停")
photo(2)
photo(3)
tap("繼續錄音")
run("shell", "input", "keyevent", "3")
time.sleep(3)
run("shell", "am", "start", "-n", args.package + "/com.eva.recorderapp.MainActivity")
find("3 張照片")
with (output / "recording.png").open("wb") as screenshot:
    subprocess.run(adb + ["exec-out", "screencap", "-p"], stdout=screenshot, check=True)
tap("結束並儲存")
find("檔名（不含副檔名）")
assert not any(n.get("text") == "完成" and n.get("enabled") == "true" for n in nodes()), "Empty filename must not save"
fields = [n for n in nodes() if n.get("class") == "android.widget.EditText"]
field = next(n for n in fields if any("檔名" in child.get("text", "") for child in n.iter()))
x1, y1, x2, y2 = map(int, re.findall(r"\d+", field.get("bounds")))
run("shell", "input", "tap", str((x1+x2)//2), str((y1+y2)//2))
run("shell", "input", "text", "Mp4Check")
find("檔名格式")
if re.search(r"mInputShown=true|mImeWindowVis=[37]", run("shell", "dumpsys", "input_method")):
    run("shell", "input", "keyevent", "KEYCODE_BACK")
    find("檔名（不含副檔名）")
with (output / "save-dialog.png").open("wb") as screenshot:
    subprocess.run(adb + ["exec-out", "screencap", "-p"], stdout=screenshot, check=True)
tap("完成")
def save_video(suffix=""):
    deadline = time.monotonic() + 35
    while time.monotonic() < deadline:
        picker = nodes()
        filename = next((n.get("text") for n in picker if n.get("class") == "android.widget.EditText"
                         and (n.get("text") or "").endswith(".mp4")), None)
        save_label = next((n.get("text") for n in picker if n.get("class") == "android.widget.Button"
                           and n.get("text") in ("儲存", "保存", "Save", "SAVE")), None)
        if filename and save_label:
            if suffix:
                field = next(n for n in picker if n.get("class") == "android.widget.EditText" and n.get("text") == filename)
                x1, y1, x2, y2 = map(int, re.findall(r"\d+", field.get("bounds")))
                run("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
                run("shell", "input", "keyevent", "KEYCODE_MOVE_END")
                for _ in range(len(filename)):
                    run("shell", "input", "keyevent", "KEYCODE_DEL")
                filename = filename.removesuffix(".mp4") + suffix + ".mp4"
                run("shell", "input", "text", filename)
            tap(save_label)
            return filename
    with (output / "picker-timeout.png").open("wb") as screenshot:
        subprocess.run(adb + ["exec-out", "screencap", "-p"], stdout=screenshot, check=False)
    (output / "picker-timeout.xml").write_text(run("shell", "cat", "/sdcard/keyframe-smoke.xml"), encoding="utf-8")
    (output / "picker-timeout-logcat.txt").write_text(run("logcat", "-d"), encoding="utf-8")
    raise AssertionError("MP4 document picker did not open")


def pull_video(name, path):
    deadline = time.monotonic() + 120
    while time.monotonic() < deadline:
        pulled = subprocess.run(adb + ["pull", "/sdcard/Download/" + name, str(path)], capture_output=True)
        if pulled.returncode == 0 and path.stat().st_size > 0:
            return
        time.sleep(1)
    raise AssertionError("MP4 was not saved to Downloads")


save_name = save_video()
assert re.fullmatch(r"Mp4Check_\d{4}-\d{2}-\d{2}_\d{2}-\d{2}-\d{2}\.mp4", save_name), save_name
saved_video = output / "saved.mp4"
pull_video(save_name, saved_video)
find("開始錄音")
print("PASS: CameraX capture, paused captures, background reconnect and stop")

rows = run("shell", "content", "query", "--uri", "content://media/external/audio/media",
           "--projection", "_id:_display_name:_data:owner_package_name")
recordings = [row for row in rows.splitlines() if "owner_package_name=" + args.package in row and "_display_name=Mp4Check_" in row]
assert recordings, "Named recording missing from MediaStore"
row = max(recordings, key=lambda row: int(re.search(r"_id=(\d+)", row)[1]))
filename = re.search(r"_display_name=([^,]+)", row)[1]
audio_path = re.search(r"_data=([^,]+)", row)[1]
assert re.fullmatch(r"Mp4Check_\d{4}-\d{2}-\d{2}_\d{2}-\d{2}-\d{2}\.[a-z0-9]+", filename), filename
with (output / "recorder.png").open("wb") as screenshot:
    subprocess.run(adb + ["exec-out", "screencap", "-p"], stdout=screenshot, check=True)
tap("錄音列表")
find(filename.rsplit(".", 1)[0])
with (output / "recordings.png").open("wb") as screenshot:
    subprocess.run(adb + ["exec-out", "screencap", "-p"], stdout=screenshot, check=True)
tap(filename.rsplit(".", 1)[0])
find("匯出 MP4")
with (output / "player.png").open("wb") as screenshot:
    subprocess.run(adb + ["exec-out", "screencap", "-p"], stdout=screenshot, check=True)
tap("匯出 MP4")
export_name = save_video("-reexport")
exported_video = output / "exported.mp4"
audio_copy = output / "original.m4a"
pull_video(export_name, exported_video)
find("匯出 MP4", timeout=120)
run("pull", audio_path, str(audio_copy))

for video in (saved_video, exported_video):
    verify_mp4(video, audio_copy)
print("PASS: timestamped save and player export decode as single MP4s with full audio and video tracks")
run("shell", "am", "start", "-n", args.package + "/com.eva.recorderapp.MainActivity", "-a", "android.intent.action.VIEW", "-d", "app://com.gujiu502.lectureframe")

tap("開始錄音")
photo(1)
run("shell", "am", "force-stop", args.package)
run("shell", "am", "start", "-n", args.package + "/com.eva.recorderapp.MainActivity")
find("發現未完成的錄音")
print("PASS: force-stop recovery preserves the interrupted session")
