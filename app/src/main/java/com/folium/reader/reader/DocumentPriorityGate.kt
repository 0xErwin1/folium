package com.folium.reader.reader

internal class DocumentPriorityGate {
    internal data class OcrPermit(val generation: Long)

    private val lock = Object()
    private var foregroundCount = 0
    private var generation = 0L

    fun <T> foreground(block: () -> T): T {
        synchronized(lock) {
            foregroundCount++
            generation++
            lock.notifyAll()
        }

        return try {
            block()
        } finally {
            synchronized(lock) {
                foregroundCount--
                lock.notifyAll()
            }
        }
    }

    fun awaitOcrPermit(cancelled: () -> Boolean): OcrPermit? = synchronized(lock) {
        while (foregroundCount > 0 && !cancelled()) {
            try {
                lock.wait(OCR_WAIT_POLL_MILLIS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
        }

        if (cancelled()) null else OcrPermit(generation)
    }

    fun isPreempted(permit: OcrPermit): Boolean = synchronized(lock) {
        foregroundCount > 0 || permit.generation != generation
    }
}

private const val OCR_WAIT_POLL_MILLIS = 25L
