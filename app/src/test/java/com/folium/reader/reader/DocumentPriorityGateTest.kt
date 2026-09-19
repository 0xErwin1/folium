package com.folium.reader.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * How long a wait for something that must happen is allowed to take on a loaded machine.
 */
private const val SETTLE_SECONDS = 30L

/**
 * How long a wait for something that must NOT happen is given to disprove itself.
 */
private const val NOT_HAPPENING_MILLIS = 200L

private const val QUIET_MILLIS = 1_500L
private const val FIRST_FOREGROUND_BOUND_MILLIS = 10_000L

class DocumentPriorityGateTest {
    private class FakeClock(startMillis: Long = 0L) : () -> Long {
        private val millis = AtomicLong(startMillis)
        override fun invoke(): Long = millis.get()
        fun advanceBy(deltaMillis: Long) { millis.addAndGet(deltaMillis) }
    }

    @Test fun idlePermitIsGrantedImmediatelyWhenNothingHasRendered() {
        val gate = DocumentPriorityGate(nowMillis = FakeClock())

        assertTrue(gate.awaitIdlePermit(QUIET_MILLIS) { false })
    }

    @Test fun idlePermitIsWithheldWhileAForegroundBlockIsOpen() {
        val clock = FakeClock()
        val gate = DocumentPriorityGate(nowMillis = clock)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val granted = AtomicBoolean(true)
        val finished = CountDownLatch(1)

        val renderer = Thread {
            gate.foreground {
                entered.countDown()
                release.awaitIgnoringInterrupts()
            }
        }
        renderer.start()
        assertTrue(entered.await(SETTLE_SECONDS, TimeUnit.SECONDS))

        val waiter = Thread {
            granted.set(gate.awaitIdlePermit(QUIET_MILLIS) { false })
            finished.countDown()
        }
        waiter.start()

        assertFalse(finished.await(NOT_HAPPENING_MILLIS, TimeUnit.MILLISECONDS))

        release.countDown()
        renderer.join(TimeUnit.SECONDS.toMillis(SETTLE_SECONDS))
        clock.advanceBy(QUIET_MILLIS)
        assertTrue(finished.await(SETTLE_SECONDS, TimeUnit.SECONDS))
        assertTrue(granted.get())
    }

    @Test fun idlePermitIsWithheldUntilTheQuietPeriodElapsesAfterTheLastForegroundBlockEnds() {
        val clock = FakeClock()
        val gate = DocumentPriorityGate(nowMillis = clock)

        gate.foreground {}

        val finished = CountDownLatch(1)
        val granted = AtomicBoolean(false)
        val waiter = Thread {
            granted.set(gate.awaitIdlePermit(QUIET_MILLIS) { false })
            finished.countDown()
        }
        waiter.start()

        assertFalse(finished.await(NOT_HAPPENING_MILLIS, TimeUnit.MILLISECONDS))

        clock.advanceBy(QUIET_MILLIS - 1)
        assertFalse(finished.await(NOT_HAPPENING_MILLIS, TimeUnit.MILLISECONDS))

        clock.advanceBy(1)
        assertTrue(finished.await(SETTLE_SECONDS, TimeUnit.SECONDS))
        assertTrue(granted.get())
    }

    @Test fun aNewForegroundBlockRestartsTheQuietPeriod() {
        val clock = FakeClock()
        val gate = DocumentPriorityGate(nowMillis = clock)

        gate.foreground {}
        clock.advanceBy(QUIET_MILLIS - 1)
        gate.foreground {}

        val finished = CountDownLatch(1)
        val granted = AtomicBoolean(false)
        val waiter = Thread {
            granted.set(gate.awaitIdlePermit(QUIET_MILLIS) { false })
            finished.countDown()
        }
        waiter.start()

        clock.advanceBy(QUIET_MILLIS - 1)
        assertFalse(finished.await(NOT_HAPPENING_MILLIS, TimeUnit.MILLISECONDS))

        clock.advanceBy(1)
        assertTrue(finished.await(SETTLE_SECONDS, TimeUnit.SECONDS))
        assertTrue(granted.get())
    }

    @Test fun cancellationReturnsPromptlyWithoutWaitingForTheQuietPeriod() {
        val clock = FakeClock()
        val gate = DocumentPriorityGate(nowMillis = clock)
        gate.foreground {}

        val finished = CountDownLatch(1)
        val granted = AtomicBoolean(true)
        val waiter = Thread {
            granted.set(gate.awaitIdlePermit(QUIET_MILLIS) { true })
            finished.countDown()
        }
        waiter.start()

        assertTrue(finished.await(SETTLE_SECONDS, TimeUnit.SECONDS))
        assertFalse(granted.get())
    }

    @Test fun aBoundedPermitIsWithheldFromAGateThatHasNeverRendered() {
        val clock = FakeClock()
        val gate = DocumentPriorityGate(nowMillis = clock)

        val finished = CountDownLatch(1)
        val granted = AtomicBoolean(false)
        val waiter = Thread {
            granted.set(gate.awaitIdlePermit(QUIET_MILLIS, firstForegroundBoundMillis = FIRST_FOREGROUND_BOUND_MILLIS) { false })
            finished.countDown()
        }
        waiter.start()

        assertFalse(finished.await(NOT_HAPPENING_MILLIS, TimeUnit.MILLISECONDS))

        clock.advanceBy(FIRST_FOREGROUND_BOUND_MILLIS - 1)
        assertFalse(finished.await(NOT_HAPPENING_MILLIS, TimeUnit.MILLISECONDS))

        clock.advanceBy(1)
        assertTrue(finished.await(SETTLE_SECONDS, TimeUnit.SECONDS))
        assertTrue(granted.get())
    }

    @Test fun aBoundedPermitIsGrantedAssoonAsTheFirstForegroundBlockEndsAndTheQuietPeriodElapses() {
        val clock = FakeClock()
        val gate = DocumentPriorityGate(nowMillis = clock)

        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val renderer = Thread {
            gate.foreground {
                entered.countDown()
                release.awaitIgnoringInterrupts()
            }
        }
        renderer.start()
        assertTrue(entered.await(SETTLE_SECONDS, TimeUnit.SECONDS))

        val finished = CountDownLatch(1)
        val granted = AtomicBoolean(false)
        val waiter = Thread {
            granted.set(gate.awaitIdlePermit(QUIET_MILLIS, firstForegroundBoundMillis = FIRST_FOREGROUND_BOUND_MILLIS) { false })
            finished.countDown()
        }
        waiter.start()

        release.countDown()
        renderer.join(TimeUnit.SECONDS.toMillis(SETTLE_SECONDS))
        assertFalse(finished.await(NOT_HAPPENING_MILLIS, TimeUnit.MILLISECONDS))

        clock.advanceBy(QUIET_MILLIS)
        assertTrue(finished.await(SETTLE_SECONDS, TimeUnit.SECONDS))
        assertTrue(granted.get())
    }

    @Test fun aBoundedPermitStillHonorsCancellationBeforeTheBoundElapses() {
        val clock = FakeClock()
        val gate = DocumentPriorityGate(nowMillis = clock)

        val finished = CountDownLatch(1)
        val granted = AtomicBoolean(true)
        val waiter = Thread {
            granted.set(gate.awaitIdlePermit(QUIET_MILLIS, firstForegroundBoundMillis = FIRST_FOREGROUND_BOUND_MILLIS) { true })
            finished.countDown()
        }
        waiter.start()

        assertTrue(finished.await(SETTLE_SECONDS, TimeUnit.SECONDS))
        assertFalse(granted.get())
    }

    @Test fun ocrPermitSemanticsAreUnchanged() {
        val gate = DocumentPriorityGate(nowMillis = FakeClock())

        val immediatePermit = gate.awaitOcrPermit { false }
        assertTrue(immediatePermit != null)
        assertFalse(gate.isPreempted(requireNotNull(immediatePermit)))

        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val renderer = Thread {
            gate.foreground {
                entered.countDown()
                release.awaitIgnoringInterrupts()
            }
        }
        renderer.start()
        assertTrue(entered.await(SETTLE_SECONDS, TimeUnit.SECONDS))
        assertTrue(gate.isPreempted(requireNotNull(immediatePermit)))

        val cancelled = AtomicBoolean(false)
        val cancelledLatch = CountDownLatch(1)
        val waiter = Thread {
            val permit = gate.awaitOcrPermit { cancelled.get() }
            cancelledLatch.countDown()
            assertTrue(permit == null)
        }
        waiter.start()
        Thread.sleep(NOT_HAPPENING_MILLIS)
        cancelled.set(true)
        assertTrue(cancelledLatch.await(SETTLE_SECONDS, TimeUnit.SECONDS))
        release.countDown()
        renderer.join(TimeUnit.SECONDS.toMillis(SETTLE_SECONDS))
    }
}

private fun CountDownLatch.awaitIgnoringInterrupts() {
    while (count > 0) {
        try {
            await()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
