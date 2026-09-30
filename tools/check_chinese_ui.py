"""Check default UI resources: python tools/check_chinese_ui.py."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET

technical = {
    "audio_metadata_bitrate", "audio_metadata_sample_rate", "player_playback_speed",
    "audio_quality_sample_rate_bit_rate", "recording_settings_encoder_acc",
    "recording_settings_encoder_three_gpp", "recording_settings_encoder_amr_wb",
    "recording_settings_encoder_optus", "recording_settings_name_format_date_time",
    "recording_settings_name_format_count", "widget_recordings_preview_duration",
}
count = 0
for path in Path(".").glob("**/src/main/res/values/*.xml"):
    for entry in ET.parse(path).getroot():
        if entry.tag != "string":
            continue
        name = entry.get("name")
        if name in technical:
            continue
        assert re.search("[\u3400-\u9fff]", "".join(entry.itertext())), f"Untranslated UI string: {path}: {name}"
        count += 1
print(f"PASS: {count} Chinese UI strings")
