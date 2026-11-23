"""Check the exported H.264/AAC tracks against the duration of the original recording."""
import json
import subprocess


def streams(path):
    return json.loads(subprocess.check_output(
        ["ffprobe", "-v", "error", "-show_streams", "-of", "json", str(path)]
    ))["streams"]


def verify_mp4(video, original_audio):
    original = next(track for track in streams(original_audio) if track["codec_type"] == "audio")
    tracks = {track["codec_type"]: track for track in streams(video)}
    assert tracks["video"]["codec_name"] == "h264" and tracks["audio"]["codec_name"] == "aac", tracks
    for track in tracks.values():
        assert abs(float(track["duration"]) - float(original["duration"])) < 0.3, tracks
    subprocess.run(["ffmpeg", "-v", "error", "-xerror", "-i", str(video), "-f", "null", "-"], check=True)
