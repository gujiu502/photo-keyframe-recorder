"""Verify an exported lecture ZIP: python tools/verify_zip.py file.zip [original_audio]."""
import hashlib
import json
from pathlib import Path, PurePosixPath
import sys
import zipfile

with zipfile.ZipFile(sys.argv[1]) as archive:
    assert archive.testzip() is None, "ZIP checksum failed"
    names = archive.namelist()
    assert len(names) == len(set(names)), "Duplicate ZIP entries"
    assert all(not PurePosixPath(name).is_absolute() and ".." not in PurePosixPath(name).parts for name in names)
    manifest = json.loads(archive.read("manifest.json"))
    timeline = json.loads(archive.read("timeline.json"))
    assert manifest["schemaVersion"] == timeline["schemaVersion"] == 1
    assert manifest["title"], "Missing recording title"
    audio = archive.read(manifest["audioPath"])
    assert len(audio) > 32, "Missing audio content"
    if manifest["audioPath"].endswith((".m4a", ".mp4")):
        assert audio[4:8] == b"ftyp", "Invalid MP4 audio header"
    if len(sys.argv) > 2:
        assert hashlib.sha256(audio).digest() == hashlib.sha256(Path(sys.argv[2]).read_bytes()).digest(), "Audio differs from recording"
    items = timeline["items"]
    assert len({item["id"] for item in items}) == len(items)
    positions = [item["positionMs"] for item in items]
    assert all(isinstance(pos, int) and pos >= 0 for pos in positions)
    assert positions == sorted(positions), "Timeline is not sorted"
    markdown = archive.read("lecture.md").decode("utf-8")
    photos = [item for item in items if item["type"] == "PHOTO"]
    for photo in photos:
        data = archive.read(photo["mediaPath"])
        assert data[:2] == b"\xff\xd8" and data[-2:] == b"\xff\xd9", "Invalid JPEG"
        assert photo["mediaPath"] in markdown, "Missing Markdown photo reference"
    assert len(photos) == sum(name.startswith("keyframes/") for name in names)
    print(f"PASS: {len(audio)} audio bytes, {len(photos)} JPEG photos, {len(items)} ordered timeline items, UTF-8 Markdown, ZIP checksums")
