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

RASTER_SOURCE = FIXTURES / "raster-source"
RASTER_IMAGES = {
    "spanish": RASTER_SOURCE / "scan-spanish.gray.zlib",
    "english": RASTER_SOURCE / "scan-english.gray.zlib",
}
RASTER_WIDTH = 900
RASTER_HEIGHT = 1200

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


def raster(language: str) -> bytes:
    compressed = RASTER_IMAGES[language].read_bytes()
    pixels = zlib.decompress(compressed)
    if len(pixels) != RASTER_WIDTH * RASTER_HEIGHT:
        raise ValueError(f"invalid raster source for {language}")
    return compressed


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
            compressed = raster(page["raster_language"])
            content = b"q 612 0 0 792 0 0 cm /Im0 Do Q"
            content_id = len(objects) + 1
            objects.append(stream_object(content))
            image_id = len(objects) + 1
            objects.append(b"<< /Type /XObject /Subtype /Image /Width " + str(RASTER_WIDTH).encode() + b" /Height " + str(RASTER_HEIGHT).encode() + b" /ColorSpace /DeviceGray /BitsPerComponent 8 /Filter /FlateDecode /Length " + str(len(compressed)).encode() + b" >>\nstream\n" + compressed + b"\nendstream")
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


LONG_CHAPTER_PARAGRAPH = (
    "The reader carries this page forward one word at a time, and the words themselves were "
    "written for this fixture and nothing else. Folium never borrows text it does not own, so "
    "every sentence here exists only to be long enough to reflow across more than a single screen. "
) * 6


def epub_document(chapters: list[str], include_package: bool = True, nav: bool = True) -> bytes:
    """Assembles a minimal EPUB 3 archive from `chapters`, one XHTML document per entry.

    `include_package` controls whether `OEBPS/content.opf` is actually written: the container
    always names it, so omitting it is what makes the corrupt fixture corrupt. `nav` controls
    whether an EPUB 3 navigation document is written and referenced from the package manifest,
    which is what gives a document a non-empty outline.
    """
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w") as archive:
        def add(name: str, content: bytes, compression: int = zipfile.ZIP_DEFLATED) -> None:
            info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
            info.compress_type = compression
            info.external_attr = 0o100644 << 16
            archive.writestr(info, content)

        add("mimetype", b"application/epub+zip", zipfile.ZIP_STORED)
        add(
            "META-INF/container.xml",
            b'<?xml version="1.0" encoding="UTF-8"?><container version="1.0" '
            b'xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles>'
            b'<rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>'
            b"</rootfiles></container>",
        )

        manifest_items = []
        spine_items = []
        for index, chapter_text in enumerate(chapters, start=1):
            name = f"chapter{index}.xhtml"
            add(
                f"OEBPS/{name}",
                f'<?xml version="1.0" encoding="UTF-8"?><html xmlns="http://www.w3.org/1999/xhtml">'
                f"<head><title>Chapter {index}</title></head><body><p>{chapter_text}</p></body></html>".encode(),
            )
            manifest_items.append(f'<item id="chapter{index}" href="{name}" media-type="application/xhtml+xml"/>')
            spine_items.append(f'<itemref idref="chapter{index}"/>')

        if nav:
            nav_links = "".join(
                f'<li><a href="chapter{index}.xhtml">Chapter {index}</a></li>' for index in range(1, len(chapters) + 1)
            )
            add(
                "OEBPS/nav.xhtml",
                b'<?xml version="1.0" encoding="UTF-8"?><html xmlns="http://www.w3.org/1999/xhtml" '
                b'xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Contents</title></head>'
                b'<body><nav epub:type="toc"><ol>' + nav_links.encode() + b"</ol></nav></body></html>",
            )
            manifest_items.append('<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>')

        if include_package:
            package = (
                '<?xml version="1.0" encoding="UTF-8"?><package version="3.0" '
                'xmlns="http://www.idpf.org/2007/opf" unique-identifier="book">'
                '<metadata xmlns:dc="http://purl.org/dc/elements/1.1/">'
                '<dc:identifier id="book">folium-fixture</dc:identifier>'
                "<dc:title>Folium Fixture</dc:title><dc:language>en</dc:language></metadata>"
                f"<manifest>{''.join(manifest_items)}</manifest>"
                f"<spine>{''.join(spine_items)}</spine></package>"
            ).encode()
            add("OEBPS/content.opf", package)

    return output.getvalue()


def main() -> None:
    PDF_DIRECTORY.mkdir(parents=True, exist_ok=True)
    for path in PDF_DIRECTORY.iterdir():
        if path.is_file():
            path.unlink()
    write("native-spanish.pdf", fixture_pdf([{"text": "Biblioteca espanola: corazon lectura"}]))
    write("native-english.pdf", fixture_pdf([{"text": "English library: reader search"}]))
    write("native-mixed.pdf", fixture_pdf([{"text": "Biblioteca library: espanol English"}]))
    write("scan-spanish.pdf", fixture_pdf([{"raster_language": "spanish"}]))
    write("scan-english.pdf", fixture_pdf([{"raster_language": "english"}]))
    write("mixed-native-scanned.pdf", fixture_pdf([{"text": "Native evidence: reader"}, {"raster_language": "english"}]))
    write("rotated-cropped-large.pdf", fixture_pdf([{"text": "Rotated cropped large page", "rotation": 90, "media_box": "0 0 1440 2160", "crop_box": "100 100 1300 2000"}]))
    write("corrupt.pdf", b"%PDF-1.4\nThis self-authored fixture deliberately has no cross-reference table.\n")
    write("password-protected.pdf", encrypted_pdf())
    write("reflowable.epub", epub_document(["This is a deterministic EPUB fixture."]))
    write("reflowable-long.epub", epub_document([LONG_CHAPTER_PARAGRAPH] * 6))
    write("corrupt.epub", epub_document(["This chapter is unreachable."], include_package=False))
    write(
        "unsupported.txt",
        b"This self-authored fixture is a plain text file the app must reject at its accept-list.\n",
    )

    descriptions = [
        ("native-spanish.pdf", ["native-text"], ["Spanish"], ["Biblioteca", "lectura"], {"source": "native-pdf-text", "pageCount": 1}, None),
        ("native-english.pdf", ["native-text"], ["English"], ["English", "reader"], {"source": "native-pdf-text", "pageCount": 1}, None),
        ("native-mixed.pdf", ["native-text"], ["Spanish", "English"], ["Biblioteca", "English"], {"source": "native-pdf-text", "pageCount": 1}, None),
        ("scan-spanish.pdf", ["raster-image-only"], ["Spanish"], ["BIBLIOTECA", "LECTURA"], {"source": "raster-image-pixels", "pageCount": 1, "imagePixels": [RASTER_WIDTH, RASTER_HEIGHT], "rasterSource": "raster-source/scan-spanish.gray.zlib", "corpusId": "OCR-SPA-01", "expectedRegions": [[75 / RASTER_WIDTH, 168 / RASTER_HEIGHT, 680 / RASTER_WIDTH, 220 / RASTER_HEIGHT], [75 / RASTER_WIDTH, 276 / RASTER_HEIGHT, 630 / RASTER_WIDTH, 330 / RASTER_HEIGHT]]}, None),
        ("scan-english.pdf", ["raster-image-only"], ["English"], ["ENGLISH", "READER"], {"source": "raster-image-pixels", "pageCount": 1, "imagePixels": [RASTER_WIDTH, RASTER_HEIGHT], "rasterSource": "raster-source/scan-english.gray.zlib", "corpusId": "OCR-ENG-01", "expectedRegions": [[75 / RASTER_WIDTH, 179 / RASTER_HEIGHT, 541 / RASTER_WIDTH, 220 / RASTER_HEIGHT], [75 / RASTER_WIDTH, 289 / RASTER_HEIGHT, 497 / RASTER_WIDTH, 330 / RASTER_HEIGHT]]}, None),
        ("mixed-native-scanned.pdf", ["native-text", "raster-image-only"], ["English"], ["Native", "READER"], {"source": "per-page", "pageCount": 2, "pageTraits": ["native-text", "raster-image-only"]}, None),
        ("rotated-cropped-large.pdf", ["native-text", "rotated", "cropped", "large-page"], ["English"], ["cropped", "large", "page"], {"source": "pdf-page-boxes", "pageCount": 1, "mediaBox": [0, 0, 1440, 2160], "cropBox": [100, 100, 1300, 2000], "rotationDegrees": 90, "excludedTokens": ["Rotated"]}, None),
        ("corrupt.pdf", ["corrupt"], [], [], {"source": "not-applicable", "pageCount": 0}, "corrupt-pdf"),
        ("password-protected.pdf", ["native-text", "password-protected"], ["English"], [], {"source": "encrypted-pdf", "pageCount": 1}, "password-required"),
        (
            "reflowable.epub",
            ["reflowable"],
            ["English"],
            ["deterministic", "EPUB"],
            {
                "source": "epub-reflow",
                "pageCount": 1,
                "layoutBox": {"widthPoints": 450, "heightPoints": 675, "emPoints": 18},
                "outlineEntries": 1,
            },
            None,
        ),
        (
            "reflowable-long.epub",
            ["reflowable", "multi-chapter"],
            ["English"],
            ["Chapter", "reflow"],
            {
                "source": "epub-reflow",
                "pageCount": 12,
                "layoutBox": {"widthPoints": 450, "heightPoints": 675, "emPoints": 18},
                "outlineEntries": 6,
            },
            None,
        ),
        ("corrupt.epub", ["corrupt"], [], [], {"source": "not-applicable", "pageCount": 0}, "corrupt-epub"),
        ("unsupported.txt", ["unsupported-format"], [], [], {"source": "not-applicable", "pageCount": 0}, "unsupported-format"),
    ]
    fixtures = [{"case": name.rsplit(".", 1)[0], "file": name, "provenance": PROVENANCE, "license": LICENSE, "sha256": hashlib.sha256((PDF_DIRECTORY / name).read_bytes()).hexdigest(), "pageTraits": traits, "languages": languages, "expectedTokens": tokens, "expectedGeometry": geometry, "expectedFailureMode": failure} for name, traits, languages, tokens, geometry, failure in descriptions]
    raster_sources = {
        path.relative_to(FIXTURES).as_posix(): hashlib.sha256(path.read_bytes()).hexdigest()
        for path in (RASTER_IMAGES["spanish"], RASTER_IMAGES["english"])
    }
    (FIXTURES / "manifest.json").write_text(json.dumps({
        "schemaVersion": 2,
        "generation": "Deterministic source-authored PDF bytes; no timestamps, randomness, external content, or personal data.",
        "rasterSources": raster_sources,
        "fixtures": fixtures
    }, indent=2) + "\n")


if __name__ == "__main__":
    main()
