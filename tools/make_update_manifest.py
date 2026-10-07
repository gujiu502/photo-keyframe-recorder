"""Create the direct build update asset from the actual signed APK and AGP metadata."""
import argparse
import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument("apk", type=Path)
parser.add_argument("metadata", type=Path)
parser.add_argument("tag")
parser.add_argument("--min-supported", type=int, default=1)
args = parser.parse_args()
metadata = json.loads(args.metadata.read_text(encoding="utf-8"))
element, = metadata["elements"]
assert metadata["applicationId"] == "com.gujiu502.lectureframe"
assert element["versionName"] == args.tag.removeprefix("v")
assert 1 <= args.min_supported <= element["versionCode"]
assert args.apk.is_file() and args.apk.suffix == ".apk"
with args.apk.open("rb") as apk_stream:
    checksum = hashlib.file_digest(apk_stream, "sha256").hexdigest()
manifest = {
    "schemaVersion": 1,
    "versionCode": element["versionCode"],
    "versionName": element["versionName"],
    "minSupportedVersionCode": args.min_supported,
    "mandatory": args.min_supported > 1,
    "apkUrl": f"https://github.com/gujiu502/photo-keyframe-recorder/releases/download/{args.tag}/{args.apk.name}",
    "sha256": checksum,
    "releaseNotes": "改善長時間後臺錄音與拍照效能；加入可開始、停止及處理舊錄音的手機本地 Whisper 語音識別。",
    "publishedAt": datetime.now(timezone.utc).isoformat(),
}
(args.apk.parent / "update.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(f"Created update.json for {manifest['versionName']} ({manifest['versionCode']})")
