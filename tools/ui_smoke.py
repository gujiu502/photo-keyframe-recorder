"""Run on an installed test build: python tools/ui_smoke.py --package ... [--serial ...]."""
import argparse
from pathlib import Path
import os
import re
import subprocess
import time
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser()
parser.add_argument("--package", default="com.gujiu502.lectureframe.debug")
parser.add_argument("--serial")
args = parser.parse_args()
adb = [os.environ.get("ADB", "adb")] + (["-s", args.serial] if args.serial else [])


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
    raise AssertionError(f"Missing enabled control: {label}")


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
tap("停止")
find("檔名（不含副檔名）")
assert not any(n.get("text") == "完成" and n.get("enabled") == "true" for n in nodes()), "Empty filename must not save"
fields = [n for n in nodes() if n.get("class") == "android.widget.EditText"]
field = next(n for n in fields if any("檔名" in child.get("text", "") for child in n.iter()))
x1, y1, x2, y2 = map(int, re.findall(r"\d+", field.get("bounds")))
run("shell", "input", "tap", str((x1+x2)//2), str((y1+y2)//2))
run("shell", "input", "text", "ZipCheck")
tap("完成")
find("開始錄音")
print("PASS: CameraX capture, paused captures, background reconnect and stop")

rows = run("shell", "content", "query", "--uri", "content://media/external/audio/media",
           "--projection", "_id:_display_name:_data:owner_package_name")
recordings = [row for row in rows.splitlines() if "owner_package_name=" + args.package in row and "_display_name=ZipCheck_" in row]
assert recordings, "Named recording missing from MediaStore"
row = max(recordings, key=lambda row: int(re.search(r"_id=(\d+)", row)[1]))
recording_id = re.search(r"_id=(\d+)", row)[1]
filename = re.search(r"_display_name=([^,]+)", row)[1]
audio_path = re.search(r"_data=([^,]+)", row)[1]
assert re.fullmatch(r"ZipCheck_\d{4}-\d{2}-\d{2}_\d{2}-\d{2}-\d{2}\.[a-z0-9]+", filename), filename
run("shell", "am", "start", "-n", args.package + "/com.eva.recorderapp.MainActivity", "-a", "android.intent.action.VIEW",
    "-d", "app://com.gujiu502.lectureframe/player/" + recording_id)
tap("導出")
picker = nodes()
zip_name = next(node.get("text") for node in picker if node.get("class") == "android.widget.EditText")
save_label = next(node.get("text") for node in picker if node.get("class") == "android.widget.Button" and node.get("text") in ("儲存", "保存", "Save", "SAVE"))
tap(save_label)
find("導出")
output = Path("work/ui-smoke-" + args.package)
output.mkdir(parents=True, exist_ok=True)
zip_path = output / "lecture.zip"
audio_copy = output / "original.m4a"
deadline = time.monotonic() + 30
while time.monotonic() < deadline:
    pulled = subprocess.run(adb + ["pull", "/sdcard/Download/" + zip_name, str(zip_path)], capture_output=True)
    if pulled.returncode == 0:
        break
    time.sleep(0.5)
assert pulled.returncode == 0, "ZIP was not saved to Downloads"
run("pull", audio_path, str(audio_copy))
subprocess.run([os.sys.executable, "tools/verify_zip.py", str(zip_path), str(audio_copy)], check=True)
print("PASS: filename timestamp and player ZIP export through Android document picker")
run("shell", "am", "start", "-n", args.package + "/com.eva.recorderapp.MainActivity", "-a", "android.intent.action.VIEW", "-d", "app://com.gujiu502.lectureframe")

tap("開始錄音")
photo(1)
run("shell", "am", "force-stop", args.package)
run("shell", "am", "start", "-n", args.package + "/com.eva.recorderapp.MainActivity")
find("發現未完成的錄音")
print("PASS: force-stop recovery preserves the interrupted session")
