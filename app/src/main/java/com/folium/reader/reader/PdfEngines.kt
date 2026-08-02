package com.folium.reader.reader

import com.folium.reader.core.pdf.PdfEngine
import java.util.ServiceLoader

/**
 * Resolves the rendering engine the app was packaged with.
 *
 * The app is not allowed to name a concrete engine: the architecture guard rejects any reference
 * from `:app` to an adapter implementation, precisely so the application layer cannot grow a
 * dependency on one. The adapter module registers itself as a [PdfEngine] service instead, and this
 * is where that registration is read back. A missing registration is a packaging defect, not a
 * runtime condition a reader could ever recover from, so it fails loudly rather than degrading.
 */
object PdfEngines {
    fun load(): PdfEngine {
        val engines = ServiceLoader.load(PdfEngine::class.java, PdfEngines::class.java.classLoader).toList()
        check(engines.size == 1) { "expected exactly one registered PdfEngine, found ${engines.size}" }
        return engines.single()
    }
}
