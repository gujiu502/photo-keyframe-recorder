"""Run on an installed test build: python tools/ui_smoke.py --package ... [--serial ...]."""
import argparse
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
    run("shell", "uiautomator", "dump", "/sdcard/keyframe-smoke.xml")
    return list(ET.fromstring(run("shell", "cat", "/sdcard/keyframe-smoke.xml")).iter("node"))


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
    tap("拍照关键帧")
    tap("拍照")
    find(f"{count} 张照片")


for permission in ("RECORD_AUDIO", "CAMERA", "POST_NOTIFICATIONS", "READ_MEDIA_AUDIO", "READ_EXTERNAL_STORAGE"):
    run("shell", "pm", "grant", args.package, "android.permission." + permission, check=False)
run("shell", "am", "start", "-n", args.package + "/com.eva.recorderapp.MainActivity")
tap("Record")
time.sleep(3)
photo(1)
tap("Pause")
photo(2)
photo(3)
tap("Resume")
run("shell", "input", "keyevent", "3")
time.sleep(3)
run("shell", "am", "start", "-n", args.package + "/com.eva.recorderapp.MainActivity")
find("3 张照片")
tap("Stop")
tap("Done")
find("Record")
print("PASS: CameraX capture, paused captures, background reconnect and stop")

tap("Record")
photo(1)
run("shell", "am", "force-stop", args.package)
run("shell", "am", "start", "-n", args.package + "/com.eva.recorderapp.MainActivity")
find("发现未完成的录音")
print("PASS: force-stop recovery preserves the interrupted session")
