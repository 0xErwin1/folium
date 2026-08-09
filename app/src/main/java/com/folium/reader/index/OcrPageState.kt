package com.folium.reader.index

import com.folium.reader.core.library.BookId
import com.folium.reader.core.text.TextEngineVersion

internal typealias OcrPageState = com.folium.reader.core.ocr.OcrPageState
internal typealias OcrPageStatus = com.folium.reader.core.ocr.OcrPageStatus
internal typealias OcrTransitionOutcome = com.folium.reader.core.ocr.OcrTransitionOutcome

internal data class OcrPageKey(
    val bookId: BookId,
    val documentVersion: DocumentContentVersion,
    val pageIndex: Int,
    val textSchemaVersion: Int,
    val nativeEngineVersion: TextEngineVersion,
    val usabilityPolicyVersion: String,
    val ocrEngineVersion: TextEngineVersion
) {
    init {
        require(pageIndex >= 0)
        require(textSchemaVersion > 0)
        require(usabilityPolicyVersion.isNotBlank())
    }

    fun textKey() = TextPageIndexKey(
        bookId, documentVersion, pageIndex, com.folium.reader.core.text.TextSource.OCR,
        textSchemaVersion, ocrEngineVersion
    )
}

internal data class OcrAttempt(val key: OcrPageKey, val generation: Long)

internal data class OcrTransition(
    val outcome: OcrTransitionOutcome,
    val status: OcrPageStatus? = null,
    val attempt: OcrAttempt? = null
)

internal fun TextPageIndexKey.isCompatibleNativeOwner(ocrKey: OcrPageKey): Boolean =
    source == com.folium.reader.core.text.TextSource.NATIVE_PDF &&
        bookId == ocrKey.bookId &&
        documentVersion == ocrKey.documentVersion &&
        pageIndex == ocrKey.pageIndex &&
        textSchemaVersion == ocrKey.textSchemaVersion &&
        engineVersion == ocrKey.nativeEngineVersion
