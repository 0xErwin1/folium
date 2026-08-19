# MuPDF's native layer resolves these classes, their fields and their constructors by name through
# JNI, so nothing in this package may be renamed or removed however unreachable it looks from Java.
-keep class com.artifex.mupdf.fitz.** { *; }

# tesseract4android and the leptonica bindings it ships do the same: the native side caches field
# and method ids by name, and the progress callback is invoked from native code.
-keep class com.googlecode.tesseract.android.** { *; }
-keep class com.googlecode.leptonica.android.** { *; }

# Room resolves the generated implementation of each @Database by name at runtime.
-keep class com.folium.reader.index.*_Impl { *; }
