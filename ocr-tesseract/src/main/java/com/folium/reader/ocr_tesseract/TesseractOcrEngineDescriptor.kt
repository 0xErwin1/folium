package com.folium.reader.ocr_tesseract

import com.folium.reader.core.ocr.OcrEngine
import com.folium.reader.core.ocr.OcrEngineDescriptor
import com.folium.reader.core.ocr.OcrEngineEnvironment
import com.folium.reader.core.ocr.OcrRequest
import com.folium.reader.core.text.TextEngineVersion

class TesseractOcrEngineDescriptor : OcrEngineDescriptor {
    override fun textEngineVersion(request: OcrRequest): TextEngineVersion =
        tesseractTextEngineVersion(request)

    override fun create(environment: OcrEngineEnvironment): OcrEngine = TesseractOcrEngine(environment)
}
