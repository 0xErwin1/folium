#!/usr/bin/env bash
set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
manifest="$root/test-fixtures/manifest.json"
fixtures="$root/test-fixtures/pdf"

if ! python3 -c 'import pypdf' >/dev/null 2>&1 && [ -d /tmp/folium-pypdf ]; then
  export PYTHONPATH="/tmp/folium-pypdf${PYTHONPATH:+:$PYTHONPATH}"
fi

python3 - "$manifest" "$fixtures" <<'PY'
import hashlib
import io
import json
import sys
import zipfile
from pathlib import Path

from pypdf import PdfReader

manifest_path, fixture_directory = map(Path, sys.argv[1:])
if not manifest_path.is_file():
    raise SystemExit(f"missing manifest: {manifest_path}")
data = json.loads(manifest_path.read_text(encoding="utf-8"))
if data.get("schemaVersion") != 2:
    raise SystemExit("manifest schemaVersion must be 2")
entries = data.get("fixtures")
if not isinstance(entries, list) or len(entries) != 10:
    raise SystemExit("manifest must list exactly 10 fixtures")
required = {"case", "file", "provenance", "license", "sha256", "pageTraits", "languages", "expectedTokens", "expectedGeometry", "expectedFailureMode"}
expected = {
    "native-spanish.pdf": (["native-text"], ["Spanish"], None),
    "native-english.pdf": (["native-text"], ["English"], None),
    "native-mixed.pdf": (["native-text"], ["Spanish", "English"], None),
    "scan-spanish.pdf": (["raster-image-only"], ["Spanish"], None),
    "scan-english.pdf": (["raster-image-only"], ["English"], None),
    "mixed-native-scanned.pdf": (["native-text", "raster-image-only"], ["English"], None),
    "rotated-cropped-large.pdf": (["native-text", "rotated", "cropped", "large-page"], ["English"], None),
    "corrupt.pdf": (["corrupt"], [], "corrupt-pdf"),
    "unsupported.epub": (["unsupported-format"], [], "unsupported-format"),
    "password-protected.pdf": (["native-text", "password-protected"], ["English"], "password-required"),
}
listed, by_name = set(), {}
for entry in entries:
    if not isinstance(entry, dict) or required - entry.keys():
        raise SystemExit("manifest entry is missing a required field")
    name = entry["file"]
    if not isinstance(name, str) or Path(name).name != name or name in listed or name not in expected:
        raise SystemExit(f"invalid, duplicate, or unexpected fixture name: {name!r}")
    if entry["case"] != name.rsplit(".", 1)[0] or entry["provenance"] != "Self-authored in scripts/generate-fixtures.py; no external document content." or entry["license"] != "CC0-1.0":
        raise SystemExit(f"invalid identity/provenance fields: {name}")
    if not isinstance(entry["sha256"], str) or len(entry["sha256"]) != 64 or not isinstance(entry["pageTraits"], list) or not isinstance(entry["languages"], list) or not isinstance(entry["expectedTokens"], list) or not isinstance(entry["expectedGeometry"], dict):
        raise SystemExit(f"invalid field type: {name}")
    if (entry["pageTraits"], entry["languages"], entry["expectedFailureMode"]) != expected[name]:
        raise SystemExit(f"incorrect semantic category: {name}")
    path = fixture_directory / name
    if not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest() != entry["sha256"]:
        raise SystemExit(f"missing or tampered fixture: {name}")
    listed.add(name)
    by_name[name] = entry
if {path.name for path in fixture_directory.iterdir() if path.is_file()} != listed:
    raise SystemExit("manifest and fixture directory differ")

def page_semantics(path):
    reader = PdfReader(path, strict=True)
    result = []
    for page in reader.pages:
        resources = page.get("/Resources", {})
        xobjects = resources.get("/XObject", {})
        images = [item.get_object() for item in xobjects.values() if item.get_object().get("/Subtype") == "/Image"]
        result.append((bool(resources.get("/Font")), bool(images), page.extract_text().strip()))
    return result

for name in ("scan-spanish.pdf", "scan-english.pdf"):
    entry = by_name[name]
    pages = page_semantics(fixture_directory / name)
    if not entry["expectedTokens"] or entry["expectedGeometry"].get("source") != "raster-image-pixels" or pages != [(False, True, "")]:
        raise SystemExit(f"scan is not an image-only OCR fixture: {name}")
if (fixture_directory / "scan-spanish.pdf").read_bytes() == (fixture_directory / "scan-english.pdf").read_bytes():
    raise SystemExit("scanned language fixtures must differ")
mixed = page_semantics(fixture_directory / "mixed-native-scanned.pdf")
if len(mixed) != 2 or not mixed[0][0] or not mixed[0][2] or mixed[1] != (False, True, "") or by_name["mixed-native-scanned.pdf"]["expectedGeometry"].get("pageTraits") != ["native-text", "raster-image-only"]:
    raise SystemExit("mixed fixture must have native then image-only scanned pages")
geometry_page = PdfReader(fixture_directory / "rotated-cropped-large.pdf", strict=True).pages[0]
if list(geometry_page.mediabox) != [0, 0, 1440, 2160] or list(geometry_page.cropbox) != [100, 100, 1300, 2000] or geometry_page.rotation != 90:
    raise SystemExit("geometry fixture lacks its declared page geometry")
geometry_entry = by_name["rotated-cropped-large.pdf"]
if geometry_entry["expectedTokens"] != ["cropped", "large", "page"] or geometry_entry["expectedGeometry"].get("excludedTokens") != ["Rotated"]:
    raise SystemExit("geometry fixture text expectations contradict its CropBox")
try:
    PdfReader(fixture_directory / "corrupt.pdf", strict=True)
except Exception:
    pass
else:
    raise SystemExit("corrupt fixture unexpectedly parsed")
with zipfile.ZipFile(fixture_directory / "unsupported.epub") as archive:
    if archive.namelist()[0] != "mimetype" or archive.read("mimetype") != b"application/epub+zip" or "META-INF/container.xml" not in archive.namelist() or "OEBPS/content.opf" not in archive.namelist():
        raise SystemExit("unsupported fixture is not a valid EPUB structure")
password = fixture_directory / "password-protected.pdf"
reader = PdfReader(password, strict=True)
if not reader.is_encrypted or reader.decrypt("wrong-password") != 0:
    raise SystemExit("password fixture accepts an invalid password")
reader = PdfReader(password, strict=True)
if reader.decrypt("folium") == 0 or not reader.pages[0].extract_text().strip():
    raise SystemExit("password fixture rejects its expected password")
print("verified 10 deterministic, semantic fixtures")
PY

for copied in \
    "app/src/androidTest/assets/ocr-manifest.json:test-fixtures/manifest.json" \
    "app/src/androidTest/assets/pdf/scan-spanish.pdf:test-fixtures/pdf/scan-spanish.pdf" \
    "app/src/androidTest/assets/pdf/scan-english.pdf:test-fixtures/pdf/scan-english.pdf" \
    "engine-mupdf/src/androidTest/assets/pdf/native-english.pdf:test-fixtures/pdf/native-english.pdf" \
    "engine-mupdf/src/androidTest/assets/pdf/scan-english.pdf:test-fixtures/pdf/scan-english.pdf" \
    "engine-mupdf/src/androidTest/assets/pdf/rotated-cropped-large.pdf:test-fixtures/pdf/rotated-cropped-large.pdf" \
    "engine-mupdf/src/androidTest/assets/pdf/corrupt.pdf:test-fixtures/pdf/corrupt.pdf" \
    "engine-mupdf/src/androidTest/assets/pdf/password-protected.pdf:test-fixtures/pdf/password-protected.pdf"; do
    target=${copied%%:*}
    source=${copied#*:}
    cmp "$root/$source" "$root/$target" || {
        echo "mapped test asset differs from canonical fixture" >&2
        exit 1
    }
done

current=$( (sha256sum "$manifest" "$fixtures"/* "$root/test-fixtures/raster-source"/*.zlib) | sha256sum )
PYTHONPATH="${PYTHONPATH:+$PYTHONPATH:}/tmp/folium-pypdf" python3 "$root/scripts/generate-fixtures.py"
generation_one=$( (sha256sum "$manifest" "$fixtures"/* "$root/test-fixtures/raster-source"/*.zlib) | sha256sum )
PYTHONPATH="${PYTHONPATH:+$PYTHONPATH:}/tmp/folium-pypdf" python3 "$root/scripts/generate-fixtures.py"
generation_two=$( (sha256sum "$manifest" "$fixtures"/* "$root/test-fixtures/raster-source"/*.zlib) | sha256sum )
[ "$current" = "$generation_one" ] && [ "$generation_one" = "$generation_two" ] || {
    echo "fixture generation is not reproducible" >&2
    exit 1
}
