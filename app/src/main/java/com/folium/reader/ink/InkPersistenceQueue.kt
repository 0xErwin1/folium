package com.folium.reader.ink

import com.folium.reader.core.ink.OpenSheet
import com.folium.reader.core.ink.SheetEdit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
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
 * whether and how to recover: retrying, migrating to a new sheet, or giving up. This queue itself
 * never crashes and never drops an edit without reporting it.
 */
class InkPersistenceQueue(
    private val sink: InkEditSink,
    private val onFailure: (Throwable) -> Unit,
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "folium-ink-writer") }
) {
    @Volatile private var failed: Boolean = false

    val hasFailed: Boolean get() = failed

    /** Enqueues [edit] to be applied in order; a no-op once this queue has already failed. */
    fun enqueue(edit: SheetEdit) {
        if (failed) return

        executor.execute {
            if (failed) return@execute

            try {
                sink.apply(edit)
            } catch (throwable: Throwable) {
                failed = true
                onFailure(throwable)
            }
        }
    }

    /** Blocks until every edit enqueued so far has been applied (or the queue has failed), or [timeoutMillis] elapses. Returns whether it drained in time. */
    fun flushAndWait(timeoutMillis: Long): Boolean {
        val drained = CountDownLatch(1)
        executor.execute { drained.countDown() }
        return drained.await(timeoutMillis, TimeUnit.MILLISECONDS)
    }

    /** Stops accepting further work; already-queued edits already applied by the time [flushAndWait] returned are unaffected. */
    fun shutdown() {
        executor.shutdown()
    }
}
