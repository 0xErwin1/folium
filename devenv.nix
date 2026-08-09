{ pkgs }:

{
  packages = [
    pkgs.git
    pkgs.jdk17_headless
    pkgs.jq
    pkgs.python3
    pkgs.shellcheck
    # Must precede the Android SDK on PATH: platform-tools/sqlite3 resolves the
    # stale libc++ that build-tools puts on LD_LIBRARY_PATH and dies on startup.
    pkgs.sqlite
  ];

  languages.java.enable = false;
  env.JAVA_HOME = "${pkgs.jdk17_headless}";

  android = {
    enable = true;
    platforms.version = [ "35" ];
    buildTools.version = [ "35.0.0" ];
    emulator.enable = false;
    systemImages.enable = false;
    ndk.enable = false;
    sources.enable = false;
    googleAPIs.enable = false;
    googleTVAddOns.enable = false;
    extras = [ ];
    extraLicenses = [ ];
  };

  enterShell = ''
    printf 'Folium dev shell (Java 17, Android SDK 35)\n'
  '';

  scripts.check.exec = ''
    set -euo pipefail
    cd "$DEVENV_ROOT"
    ./gradlew \
      :reader-core:testDebugUnitTest \
      :engine-mupdf:testDebugUnitTest \
      :ocr-tesseract:testDebugUnitTest \
      :app:testDebugUnitTest \
      :app:assembleDebug
  '';
}
