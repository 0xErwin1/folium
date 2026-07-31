# Folium PDF fixtures

This ten-case corpus is self-authored and redistributable under CC0-1.0. It contains no third-party document content or personal data. The two scan PDFs contain only image pixels; their independent Spanish and English expected tokens and intended normalized regions are declared in `manifest.json`.

## Deterministic scan source

The generator embeds the committed `raster-source/scan-{spanish,english}.gray.zlib` byte streams directly into image-only PDFs. It does not load host fonts or invoke a host rasterizer. Their SHA-256 values are pinned in `manifest.json`, and `verify-fixtures.sh` verifies that the committed outputs equal two consecutive regenerations and every Android module copy equals its canonical fixture.

The scan `expectedRegions` are intended source regions, not OCR-derived tolerances. They are the normalized `(minX, minY, maxXExclusive, maxYExclusive)` bounds of all grayscale pixels below 128 for each authored line, divided by 900×1200. Spanish includes detached accent pixels in the line bound. Connected quality tests load these regions and opaque `corpusId` values directly from the copied manifest and require the expected token's OCR box to overlap its authored source region.

Those CC0 raster sources were authored with the pinned `raster-source/NotoSans-Regular.ttf` input (Noto Sans, font revision `132055`, SHA-256 `511cc04b442ffcc3b24d65c2cc780bdffaf482b20ba0aebdc8f27b37017ff0f5`) using ImageMagick `7.1.2-27` and explicit 900×1200 grayscale geometry. Noto Sans is under SIL Open Font License 1.1; the complete notice is `raster-source/OFL-1.1.txt` (SHA-256 `8eea8287e5876b539670cadb82e99f9a7afddec6f6730811be1daf25d2e9bcfd`). The font is retained as a provenance input, not used during normal fixture regeneration.

## Regenerate

Run `PYTHONPATH=/path/to/pypdf-5.1.0 python3 scripts/generate-fixtures.py`, copy the two scan PDFs to `app/src/androidTest/assets/pdf/`, then run `bash scripts/verify-fixtures.sh`. The generator uses pypdf 5.1.0 only for the fixed encrypted fixture; all generated PDF bytes have no timestamps or randomness.
