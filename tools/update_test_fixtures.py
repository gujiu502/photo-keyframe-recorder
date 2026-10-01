"""Build same-package APK fixtures for native signing identity/downgrade checks."""
import argparse
from pathlib import Path
import re
import struct
import subprocess
import zipfile

parser = argparse.ArgumentParser()
parser.add_argument("apk", type=Path)
parser.add_argument("sdk", type=Path)
parser.add_argument("debug_keystore", type=Path)
parser.add_argument("output", type=Path)
args = parser.parse_args()
args.output.mkdir(parents=True, exist_ok=True)
with zipfile.ZipFile(args.apk) as archive:
    manifest = bytearray(archive.read("AndroidManifest.xml"))
    resources = []
    position = 8
    patched = 0
    while position < len(manifest):
        kind, header_size, size = struct.unpack_from("<HHI", manifest, position)
        assert size >= header_size >= 8
        if kind == 0x180:
            resources = list(struct.unpack_from("<" + "I" * ((size - header_size) // 4), manifest, position + header_size))
        elif kind == 0x102:
            attr_start, attr_size, count = struct.unpack_from("<HHH", manifest, position + header_size + 8)
            for i in range(count):
                offset = position + header_size + attr_start + i * attr_size
                name = struct.unpack_from("<I", manifest, offset + 4)[0]
                if name < len(resources) and resources[name] == 0x0101021B:
                    current = struct.unpack_from("<I", manifest, offset + 16)[0]
                    struct.pack_into("<I", manifest, offset + 8, 0xFFFFFFFF)
                    struct.pack_into("<I", manifest, offset + 16, current + 1)
                    patched += 1
        position += size
    assert patched == 1, "Expected exactly one android:versionCode attribute"
    unsigned = args.output / "unsigned.apk"
    with zipfile.ZipFile(unsigned, "w", compression=zipfile.ZIP_DEFLATED) as out:
        for entry in archive.infolist():
            if re.fullmatch(r"META-INF/.*\.(SF|RSA|DSA|EC)|META-INF/MANIFEST.MF", entry.filename, re.I):
                continue
            out.writestr(entry, manifest if entry.filename == "AndroidManifest.xml" else archive.read(entry))
extension = ".bat" if __import__("os").name == "nt" else ""
signer = args.sdk / "build-tools" / "36.0.0" / ("apksigner" + extension)
subprocess.run([str(signer), "sign", "--ks", str(args.debug_keystore), "--ks-key-alias", "androiddebugkey", "--ks-pass", "pass:android", "--key-pass", "pass:android", "--out", str(args.output / "same-key.apk"), str(unsigned)], check=True)
wrong_key = args.output / "wrong-key.jks"
if not wrong_key.exists():
    subprocess.run(["keytool", "-genkeypair", "-keystore", str(wrong_key), "-storepass", "android", "-keypass", "android", "-alias", "wrong", "-dname", "CN=Update test only", "-keyalg", "RSA", "-validity", "2"], check=True, capture_output=True)
subprocess.run([str(signer), "sign", "--ks", str(wrong_key), "--ks-key-alias", "wrong", "--ks-pass", "pass:android", "--out", str(args.output / "wrong-key.apk"), str(unsigned)], check=True)
print(f"Created version {current + 1} update identity fixtures")
