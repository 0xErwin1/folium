package com.folium.reader.ocr_tesseract

internal class NativeApiOwner<T>(private val release: (T) -> Unit) {
    private var value: T? = null
    private var languages: Set<String>? = null

    fun acquire(requestedLanguages: Set<String>, create: () -> T, initialize: (T) -> Boolean): T {
        if (languages != requestedLanguages) close()
        value?.let { return it }
        val created = create()
        try {
            check(initialize(created))
            value = created
            languages = requestedLanguages
            return created
        } catch (failure: Throwable) {
            releaseAfterFailure(created, failure)
            throw failure
        }
    }

    fun close() {
        val current = value ?: return
        value = null
        languages = null
        release(current)
    }

    private fun releaseAfterFailure(created: T, failure: Throwable) {
        try {
            release(created)
        } catch (cleanup: Throwable) {
            failure.addSuppressed(cleanup)
        }
    }
}
