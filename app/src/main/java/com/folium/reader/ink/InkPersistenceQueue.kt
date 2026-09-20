package com.folium.reader.ink

import com.folium.reader.core.ink.OpenSheet
import com.folium.reader.core.ink.SheetEdit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/** Where a queued [SheetEdit] is ultimately written. Extracted so [InkPersistenceQueue] is testable without a real [OpenSheet]. */
fun interface InkEditSink {
    fun apply(edit: SheetEdit)
}

/** Applies every queued [SheetEdit] to [openSheet], in order, on whatever thread calls it. */
class OpenSheetEditSink(private val openSheet: OpenSheet) : InkEditSink {
    override fun apply(edit: SheetEdit) = openSheet.apply(edit)
}

/**
 * Persists [SheetEdit]s to a [InkEditSink] in the exact order they were enqueued, on a single
 * dedicated writer thread, so a surface's undo/redo and stroke commits never race each other or the
 * order they happened on screen.
 *
 * Once [sink] throws, this queue reports it through [onFailure] exactly once and stops applying any
 * edit enqueued afterward: the failure may mean the underlying store is no longer trustworthy, so
 * silently continuing could interleave new writes with a corrupt or incomplete one. The caller is
 * expected to keep the strokes behind the failed edit visible on screen and to decide, out of band,
 * whether and how to recover: retrying, migrating to a new sheet, or giving up. That single report
 * also covers every edit that was accepted earlier and was still waiting behind the failed one: none
 * of them is written, and no second report follows, so after a failure the caller must treat every
 * edit since its last successful [flushAndWait] as unsaved. This queue itself never throws.
 */
class InkPersistenceQueue(
    private val sink: InkEditSink,
    private val onFailure: (Throwable) -> Unit,
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "folium-ink-writer") }
) {
    @Volatile private var failed: Boolean = false
    @Volatile private var closed: Boolean = false

    val hasFailed: Boolean get() = failed

    /** Whether [shutdown] has run; a closed queue accepts nothing more and never throws for it. */
    val isClosed: Boolean get() = closed

    /**
     * Enqueues [edit] to be applied in order and returns whether it was accepted. An edit is refused,
     * and nothing is thrown, once this queue has failed or has been shut down: the caller must not
     * show an edit as done when this returns `false`.
     */
    fun enqueue(edit: SheetEdit): Boolean {
        if (failed || closed) return false

        return submit {
            if (failed) return@submit

            try {
                sink.apply(edit)
            } catch (throwable: Throwable) {
                failed = true
                onFailure(throwable)
            }
        }
    }

    /**
     * Blocks until every edit accepted so far has been applied (or the queue has failed), or
     * [timeoutMillis] elapses. Returns whether it drained in time. After [shutdown] this waits for
     * the writer thread to finish the edits it had already accepted.
     */
    fun flushAndWait(timeoutMillis: Long): Boolean {
        val drained = CountDownLatch(1)

        if (!submit { drained.countDown() }) {
            return executor.awaitTermination(timeoutMillis, TimeUnit.MILLISECONDS)
        }

        return drained.await(timeoutMillis, TimeUnit.MILLISECONDS)
    }

    /** Stops accepting further work; edits accepted before this call are still applied, in order. */
    fun shutdown() {
        closed = true
        executor.shutdown()
    }

    /**
     * Hands [work] to the writer thread, reporting rather than throwing when the executor has already
     * been shut down: [shutdown] can race a caller that read [closed] just before it was set.
     */
    private fun submit(work: () -> Unit): Boolean =
        try {
            executor.execute(work)
            true
        } catch (_: RejectedExecutionException) {
            false
        }
}
