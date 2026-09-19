package com.folium.reader.reader

internal class DocumentPriorityGate(private val nowMillis: () -> Long = { System.nanoTime() / NANOS_PER_MILLI }) {
    internal data class OcrPermit(val generation: Long)

    private val lock = Object()
    private var foregroundCount = 0
    private var generation = 0L

    /** Null until the first foreground block ends, so a document that has never rendered starts idle. */
    private var lastForegroundEndMillis: Long? = null

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
                if (foregroundCount == 0) lastForegroundEndMillis = nowMillis()
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

    /**
     * Waits until no foreground render is in progress and [quietMillis] have passed since the last
     * one ended. A reader flipping pages leaves well under a second between turns, while a background
     * index slice can hold the engine for seconds on a slow device, so starting one the instant a
     * render finishes only delays the next page turn instead of avoiding it.
     *
     * Returns false the moment [cancelled] reports true, without ever waiting out the quiet period —
     * this is how a caller with real, user-driven work to serve avoids being held here.
     */
    fun awaitIdlePermit(quietMillis: Long, cancelled: () -> Boolean): Boolean = synchronized(lock) {
        while (!cancelled()) {
            val idleSinceMillis = lastForegroundEndMillis
            val idle = foregroundCount == 0 &&
                (idleSinceMillis == null || nowMillis() - idleSinceMillis >= quietMillis)
            if (idle) return true
            try {
                lock.wait(IDLE_WAIT_POLL_MILLIS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        false
    }
}

private const val OCR_WAIT_POLL_MILLIS = 25L
private const val IDLE_WAIT_POLL_MILLIS = 25L
private const val NANOS_PER_MILLI = 1_000_000L
