#!/usr/bin/env python3
"""Regenerate Folium's self-authored, deterministic fixture corpus."""
from __future__ import annotations

import hashlib
import io
import json
import zipfile
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
FIXTURES = ROOT / "test-fixtures"
PDF_DIRECTORY = FIXTURES / "pdf"
PROVENANCE = "Self-authored in scripts/generate-fixtures.py; no external document content."
LICENSE = "CC0-1.0"

# A deliberately small, source-authored 5x7 raster alphabet. Scan PDFs contain
# these pixels only: they have no PDF text operators or embedded fonts.
GLYPHS = {
    " ": ("000", "000", "000", "000", "000", "000", "000"),
    "A": ("01110", "10001", "10001", "11111", "10001", "10001", "10001"),
    "B": ("11110", "10001", "10001", "11110", "10001", "10001", "11110"),
    "C": ("01111", "10000", "10000", "10000", "10000", "10000", "01111"),
    "D": ("11110", "10001", "10001", "10001", "10001", "10001", "11110"),
    "E": ("11111", "10000", "10000", "11110", "10000", "10000", "11111"),
    "G": ("01111", "10000", "10000", "10111", "10001", "10001", "01110"),
    "H": ("10001", "10001", "10001", "11111", "10001", "10001", "10001"),
    "I": ("11111", "00100", "00100", "00100", "00100", "00100", "11111"),
    "L": ("10000", "10000", "10000", "10000", "10000", "10000", "11111"),
    "N": ("10001", "11001", "10101", "10011", "10001", "10001", "10001"),
    "O": ("01110", "10001", "10001", "10001", "10001", "10001", "01110"),
    "P": ("11110", "10001", "10001", "11110", "10000", "10000", "10000"),
    "R": ("11110", "10001", "10001", "11110", "10100", "10010", "10001"),
    "S": ("01111", "10000", "10000", "01110", "00001", "00001", "11110"),
    "T": ("11111", "00100", "00100", "00100", "00100", "00100", "00100"),
    "U": ("10001", "10001", "10001", "10001", "10001", "10001", "01110"),
    "V": ("10001", "10001", "10001", "10001", "10001", "01010", "00100"),
    "Y": ("10001", "10001", "01010", "00100", "00100", "00100", "00100"),
    "Z": ("11111", "00001", "00010", "00100", "01000", "10000", "11111"),
}


def assemble_pdf(objects: list[bytes]) -> bytes:
    body = bytearray(b"%PDF-1.4\n%\xe2\xe3\xcf\xd3\n")
    offsets = [0]
    for number, value in enumerate(objects, 1):
        offsets.append(len(body))
        body += f"{number} 0 obj\n".encode() + value + b"\nendobj\n"
    start = len(body)
    body += f"xref\n0 {len(objects) + 1}\n0000000000 65535 f \n".encode()
    body += b"".join(f"{offset:010d} 00000 n \n".encode() for offset in offsets[1:])
    body += f"trailer\n<< /Size {len(objects) + 1} /Root 1 0 R >>\nstartxref\n{start}\n%%EOF\n".encode()
    return bytes(body)


def stream_object(data: bytes) -> bytes:
    return f"<< /Length {len(data)} >>\nstream\n".encode() + data + b"\nendstream"


def raster(lines: list[str], width: int = 900, height: int = 1200) -> bytes:
    pixels = bytearray(b"\xff") * (width * height)
    scale, left, top, line_gap = 7, 60, 150, 40
    for line_number, line in enumerate(lines):
        x = left
        y = top + line_number * (7 * scale + line_gap)
        for character in line:
            glyph = GLYPHS[character]
            for row, bits in enumerate(glyph):
                for column, bit in enumerate(bits):
                    if bit == "1":
                        for yy in range(y + row * scale, y + (row + 1) * scale):
                            for xx in range(x + column * scale, x + (column + 1) * scale):
                                pixels[yy * width + xx] = 0
            x += (len(glyph[0]) + 1) * scale
    return zlib.compress(bytes(pixels), level=9)


def fixture_pdf(pages: list[dict[str, object]]) -> bytes:
    objects: list[bytes] = [b"<< /Type /Catalog /Pages 2 0 R >>", b""]
    page_ids: list[int] = []
    for page in pages:
        page_id = len(objects) + 1
        page_ids.append(page_id)
        objects.append(b"")
        content = page.get("text")
        if content is not None:
            encoded = str(content).replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)").encode("latin-1")
            command = b"BT /F1 18 Tf 72 720 Td (" + encoded + b") Tj ET"
            content_id = len(objects) + 1
            objects.append(stream_object(command))
            resources = "/Resources << /Font << /F1 0 0 R >> >>"
            extra = f" /Contents {content_id} 0 R"
            objects[page_id - 1] = ("<< /Type /Page /Parent 2 0 R /MediaBox [" + str(page.get("media_box", "0 0 612 792")) + "] " + resources + extra + (f" /Rotate {page['rotation']}" if "rotation" in page else "") + (f" /CropBox [{page['crop_box']}]" if "crop_box" in page else "") + " >>").encode()
        else:
            compressed = raster(page["raster_lines"])
            content = b"q 612 0 0 792 0 0 cm /Im0 Do Q"
            content_id = len(objects) + 1
            objects.append(stream_object(content))
            image_id = len(objects) + 1
            objects.append(b"<< /Type /XObject /Subtype /Image /Width 900 /Height 1200 /ColorSpace /DeviceGray /BitsPerComponent 8 /Filter /FlateDecode /Length " + str(len(compressed)).encode() + b" >>\nstream\n" + compressed + b"\nendstream")
            objects[page_id - 1] = f"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /XObject << /Im0 {image_id} 0 R >> >> /Contents {content_id} 0 R >>".encode()
    if any("text" in page for page in pages):
        font_id = len(objects) + 1
        for page_id in page_ids:
            objects[page_id - 1] = objects[page_id - 1].replace(b"/F1 0 0 R", f"/F1 {font_id} 0 R".encode())
        objects.append(b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")
    objects[1] = f"<< /Type /Pages /Kids [{' '.join(f'{page_id} 0 R' for page_id in page_ids)}] /Count {len(page_ids)} >>".encode()
    return assemble_pdf(objects)


def encrypted_pdf() -> bytes:
    # pypdf 5.1.0 emits deterministic output for this fixed input and password.
    from pypdf import PdfReader, PdfWriter

    writer = PdfWriter()
    writer.append_pages_from_reader(PdfReader(io.BytesIO(fixture_pdf([{"text": "Protected Folium fixture"}]))) )
    writer.encrypt("folium")
    output = io.BytesIO()
    writer.write(output)
    return output.getvalue()


def write(name: str, content: bytes) -> None:
    (PDF_DIRECTORY / name).write_bytes(content)


def epub_document() -> bytes:
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w") as archive:
        for name, content, compression in (
            ("mimetype", b"application/epub+zip", zipfile.ZIP_STORED),
            ("META-INF/container.xml", b'<?xml version="1.0" encoding="UTF-8"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>', zipfile.ZIP_DEFLATED),
            ("OEBPS/content.opf", b'<?xml version="1.0" encoding="UTF-8"?><package version="3.0" xmlns="http://www.idpf.org/2007/opf" unique-identifier="book"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="book">folium-fixture</dc:identifier><dc:title>Folium Unsupported Fixture</dc:title><dc:language>en</dc:language></metadata><manifest><item id="chapter" href="chapter.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="chapter"/></spine></package>', zipfile.ZIP_DEFLATED),
            ("OEBPS/chapter.xhtml", b'<?xml version="1.0" encoding="UTF-8"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>Folium Fixture</title></head><body><p>This is a deterministic EPUB fixture.</p></body></html>', zipfile.ZIP_DEFLATED),
        ):
            info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
            info.compress_type = compression
            info.external_attr = 0o100644 << 16
            archive.writestr(info, content)
    return output.getvalue()


def main() -> None:
    PDF_DIRECTORY.mkdir(parents=True, exist_ok=True)
    for path in PDF_DIRECTORY.iterdir():
        if path.is_file():
            path.unlink()
    write("native-spanish.pdf", fixture_pdf([{"text": "Biblioteca espanola: corazon lectura"}]))
    write("native-english.pdf", fixture_pdf([{"text": "English library: reader search"}]))
    write("native-mixed.pdf", fixture_pdf([{"text": "Biblioteca library: espanol English"}]))
    write("scan-spanish.pdf", fixture_pdf([{"raster_lines": ["BIBLIOTECA ESPANOLA", "LECTURA CORAZON"]}]))
    write("scan-english.pdf", fixture_pdf([{"raster_lines": ["ENGLISH LIBRARY", "READER SEARCH"]}]))
    write("mixed-native-scanned.pdf", fixture_pdf([{"text": "Native evidence: reader"}, {"raster_lines": ["SCANNED EVIDENCE", "ENGLISH READER"]}]))
    write("rotated-cropped-large.pdf", fixture_pdf([{"text": "Rotated cropped large page", "rotation": 90, "media_box": "0 0 1440 2160", "crop_box": "100 100 1300 2000"}]))
    write("corrupt.pdf", b"%PDF-1.4\nThis self-authored fixture deliberately has no cross-reference table.\n")
    write("unsupported.epub", epub_document())
    write("password-protected.pdf", encrypted_pdf())

    descriptions = [
        ("native-spanish.pdf", ["native-text"], ["Spanish"], ["Biblioteca", "lectura"], {"source": "native-pdf-text", "pageCount": 1}, None),
        ("native-english.pdf", ["native-text"], ["English"], ["English", "reader"], {"source": "native-pdf-text", "pageCount": 1}, None),
        ("native-mixed.pdf", ["native-text"], ["Spanish", "English"], ["Biblioteca", "English"], {"source": "native-pdf-text", "pageCount": 1}, None),
        ("scan-spanish.pdf", ["raster-image-only"], ["Spanish"], ["BIBLIOTECA", "LECTURA"], {"source": "raster-image-pixels", "pageCount": 1, "imagePixels": [900, 1200]}, None),
        ("scan-english.pdf", ["raster-image-only"], ["English"], ["ENGLISH", "READER"], {"source": "raster-image-pixels", "pageCount": 1, "imagePixels": [900, 1200]}, None),
        ("mixed-native-scanned.pdf", ["native-text", "raster-image-only"], ["English"], ["Native", "READER"], {"source": "per-page", "pageCount": 2, "pageTraits": ["native-text", "raster-image-only"]}, None),
        ("rotated-cropped-large.pdf", ["native-text", "rotated", "cropped", "large-page"], ["English"], ["Rotated", "large"], {"source": "pdf-page-boxes", "pageCount": 1, "mediaBox": [0, 0, 1440, 2160], "cropBox": [100, 100, 1300, 2000], "rotationDegrees": 90}, None),
        ("corrupt.pdf", ["corrupt"], [], [], {"source": "not-applicable", "pageCount": 0}, "corrupt-pdf"),
        ("unsupported.epub", ["unsupported-format"], [], [], {"source": "not-applicable", "pageCount": 0}, "unsupported-format"),
        ("password-protected.pdf", ["native-text", "password-protected"], ["English"], [], {"source": "encrypted-pdf", "pageCount": 1}, "password-required"),
    ]
    fixtures = [{"case": name.rsplit(".", 1)[0], "file": name, "provenance": PROVENANCE, "license": LICENSE, "sha256": hashlib.sha256((PDF_DIRECTORY / name).read_bytes()).hexdigest(), "pageTraits": traits, "languages": languages, "expectedTokens": tokens, "expectedGeometry": geometry, "expectedFailureMode": failure} for name, traits, languages, tokens, geometry, failure in descriptions]
    (FIXTURES / "manifest.json").write_text(json.dumps({"schemaVersion": 2, "generation": "Deterministic source-authored PDF bytes; no timestamps, randomness, external content, or personal data.", "fixtures": fixtures}, indent=2) + "\n")


if __name__ == "__main__":
    main()
