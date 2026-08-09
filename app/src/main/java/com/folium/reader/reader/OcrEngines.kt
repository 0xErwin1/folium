package com.folium.reader.reader

import com.folium.reader.core.ocr.OcrEngineDescriptor
import java.util.ServiceLoader

/** Loads engine-owned OCR identity without constructing or running an OCR engine. */
internal object OcrEngines {
    fun loadDescriptor(): OcrEngineDescriptor {
        val descriptors = ServiceLoader.load(
            OcrEngineDescriptor::class.java,
            OcrEngines::class.java.classLoader
        ).toList()
        check(descriptors.size == 1) {
            "expected exactly one registered OcrEngineDescriptor, found ${descriptors.size}"
        }
        return descriptors.single()
    }
}
