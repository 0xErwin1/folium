# Tesseract dependency and offline data

- Integration: `cz.adaptech.tesseract4android:tesseract4android:4.9.0`, resolved from JitPack. Source: https://github.com/adaptech-cz/Tesseract4Android, release `4.9.0` (2025-06-11), Apache-2.0. The project is active and publishes source; its README identifies Tesseract 5.5.1, Leptonica 1.85.0, libjpeg 9f, and libpng 1.6.48.
- AAR SHA-256: `bce5d6413a1a5ae3d7240033fbbc851ba3217d0a08d9769400e17a077f42cb2a`; Gradle verification metadata pins the AAR and module metadata.
- Language data: official `tesseract-ocr/tessdata_fast` commit `87416418657359cb625c412a48b6e1d6d41c29bd` (2024-08-01), Apache-2.0. `fast` was selected to limit installed APK and recognition-memory cost; device quality measurement remains required before release.
  - `eng.traineddata`: `7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2`
  - `spa.traineddata`: `6f2e04d02774a18f01bed44b1111f2cd7f3ba7ac9dc4373cd3f898a40ea6b464`
  - Source URLs: `https://github.com/tesseract-ocr/tessdata_fast/raw/87416418657359cb625c412a48b6e1d6d41c29bd/{eng,spa}.traineddata`

The adapter copies these two APK assets only into the app-private `filesDir/folium-ocr/tessdata` directory and verifies their hashes before initialization. It has no network operation or storage permission. Data is intentionally bundled once in the APK and once in app-private storage because the selected native API requires a readable filesystem path.

`TesseractOcrEngine` owns one API instance on its constructing thread. Calls from another thread receive a neutral retryable resource failure; `close` is idempotent and releases native state. Cancellation is checked before initialization/image assignment, after assignment, and after extraction. The underlying native recognition call cannot safely be interrupted from a second thread, so in-flight cancellation completes at its next supported checkpoint.
