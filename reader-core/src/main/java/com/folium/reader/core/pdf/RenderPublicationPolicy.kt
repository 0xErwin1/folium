package com.folium.reader.core.pdf

import java.util.concurrent.atomic.AtomicBoolean

/** Coordinates session-scoped render publication without depending on a rendering engine or UI toolkit. */
class RenderPublicationPolicy {
    private val lock = Any()
    private var sessionGeneration = 0L
    private var viewportEpoch = 0L
    private var nextWorkId = 0L

    fun openSession(source: String): RenderWork = synchronized(lock) {
        require(source.isNotBlank())
        sessionGeneration++
        viewportEpoch++
        RenderWork(++nextWorkId, source, sessionGeneration, viewportEpoch)
    }

    fun begin(source: String): RenderWork = openSession(source)

    fun invalidateViewportDemand() = synchronized(lock) { viewportEpoch++ }

    fun isCurrent(work: RenderWork): Boolean = synchronized(lock) {
        !work.isCancelled && work.generation == sessionGeneration && work.viewportEpoch == viewportEpoch
    }

    fun <T> publish(
        work: RenderWork,
        candidate: RenderCandidate<T>,
        publishAccepted: (T) -> Unit
    ): RenderPublicationDecision = synchronized(lock) {
        val decision = when {
            !work.tryComplete() -> RenderPublicationDecision.AlreadyTerminal
            work.isCancelled -> RenderPublicationDecision.RejectedCancelled
            work.generation != sessionGeneration || work.viewportEpoch != viewportEpoch -> RenderPublicationDecision.RejectedStale
            else -> RenderPublicationDecision.Published
        }
        if (decision == RenderPublicationDecision.Published) publishAccepted(candidate.value) else candidate.release()
        decision
    }
}

/** A neutral result whose ownership is transferred to publication or released when rejected. */
class RenderCandidate<T>(val value: T, private val releaseValue: (T) -> Unit) {
    private val released = AtomicBoolean(false)

    fun release() {
        if (released.compareAndSet(false, true)) releaseValue(value)
    }
}

class RenderWork internal constructor(
    internal val id: Long,
    internal val sourceIdentity: String,
    internal val generation: Long,
    internal val viewportEpoch: Long
) {
    private val cancelled = AtomicBoolean(false)
    private val completed = AtomicBoolean(false)

    val cancellationSignal = CancellationSignal { isCancelled }
    val isCancelled: Boolean get() = cancelled.get()

    fun cancel() { cancelled.set(true) }

    internal fun tryComplete(): Boolean = completed.compareAndSet(false, true)
}

enum class RenderPublicationDecision { Published, RejectedCancelled, RejectedStale, AlreadyTerminal }
