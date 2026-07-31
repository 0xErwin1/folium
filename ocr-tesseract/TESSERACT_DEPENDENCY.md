# Tesseract dependency, bundled-native provenance, and offline data

## Resolved artifact

`cz.adaptech.tesseract4android:tesseract4android:4.9.0` is resolved from JitPack. The release tag resolves to wrapper source commit `15c534717b1cb58261b58d4e4c1200c7f81f668c` (released 2025-06-11); wrapper license: Apache-2.0, packaged as `src/main/assets/licenses/Tesseract4Android-Apache-2.0.txt`. The resolved AAR SHA-256 is `bce5d6413a1a5ae3d7240033fbbc851ba3217d0a08d9769400e17a077f42cb2a`, pinned in Gradle verification metadata.

The wrapper README at that exact commit (retrieved SHA-256 `6c88b5458deb45c9f994e7bcc115b07c2f884b5086d7bb0bcd0a00591e55aa7e`) declares the following upstream components. The version/source claims are **wrapper-declared transitive facts**; the installed AAR was independently inspected only for the named native libraries on every ABI, not rebuilt from those source archives.

| Installed AAR native library | Wrapper-declared upstream | Verified source/license notice packaged | Source checksum / notice checksum |
| --- | --- | --- | --- |
| `libtesseract.so` | Tesseract 5.5.1 | Apache-2.0: `assets/licenses/native/Tesseract-5.5.1-Apache-2.0.txt` | `https://github.com/tesseract-ocr/tesseract/tree/5.5.1`; source tarball `a7a3f2a7420cb6a6a94d80c24163e183cf1d2f1bed2df3bbc397c81808a57237`; notice `cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30` |
| `libleptonica.so` | Leptonica 1.85.0 | BSD-2-Clause: `assets/licenses/native/Leptonica-1.85.0-BSD-2-Clause.txt` | `https://github.com/DanBloomberg/leptonica/tree/1.85.0`; source tarball `c01376bce0379d4ea4bc2ec5d5cbddaa49e2f06f88242619ab8c059e21adf233`; notice `87829abb5bbb00b55a107365da89e9a33f86c4250169e5a1e5588505be7d5806` |
| `libjpeg.so` | IJG libjpeg 9f | IJG license: `assets/licenses/native/libjpeg-9f-IJG.txt` | `https://ijg.org/files/jpegsrc.v9f.tar.gz`; source tarball `04705c110cb2469caa79fb71fba3d7bf834914706e9641a4589485c1f832565b`; notice `7c25493a9f64fed34d01445467341bda77bc1cdbeccbe33558659ef173fb9ff2` |
| `libpngx.so` | libpng 1.6.48 | libpng-2.0: `assets/licenses/native/libpng-1.6.48-libpng-2.0.txt` | `https://github.com/pnggroup/libpng/tree/v1.6.48`; source tarball `b17e99026055727e8cba99160c3a9a7f9af788e9f786daeadded5a42243f1dd0`; notice `16d9daaafbf63a31a5bdc91d4600972548fef5aaa1244202393288dbd079c49a` |

The AAR contains `libjpeg.so`, `libleptonica.so`, `libpngx.so`, and `libtesseract.so` for `arm64-v8a`, `armeabi-v7a`, `x86`, and `x86_64`. The verified source notices are distributed with the app. The native component names/versions are not inferred from library names.

## Offline language data

The app packages official `tesseract-ocr/tessdata_fast` commit `87416418657359cb625c412a48b6e1d6d41c29bd` (2024-08-01), Apache-2.0. `fast` limits APK and recognition-memory cost; device quality measurement remains required before release.

- `eng.traineddata`: `7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2`
- `spa.traineddata`: `6f2e04d02774a18f01bed44b1111f2cd7f3ba7ac9dc4373cd3f898a40ea6b464`
- Source: `https://github.com/tesseract-ocr/tessdata_fast/raw/87416418657359cb625c412a48b6e1d6d41c29bd/{eng,spa}.traineddata`

The adapter copies only those APK assets into `filesDir/folium-ocr/tessdata`, verifies hashes before initialization, and has no network operation or storage permission. A single engine is thread-confined; every changed `OcrRequest.languages` set recycles and reinitializes the native API. Recognized words are NFC-normalized. Their language is deterministic request configuration: the sole requested language, or lexical language-code order when multiple engines are requested (`eng` before `spa`); no glyph/accent heuristic is used.
