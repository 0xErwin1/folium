package com.folium.reader.reader

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private const val ENGINE_ID = "test-engine"
private const val CONTENT_ID = "test-content"
private const val SETTLE_SECONDS = 30L

/** A whole-page 4x4 RGBA raster, cheap enough to downscale in every test without touching a real engine. */
private fun rgba(width: Int = 4, height: Int = 4, value: Byte = 10): ByteArray = ByteArray(width * height * 4) { value }

class PagePreviewsTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun offerThenAwaitIdleStoresAPreviewForThePage() {
        val root = temporaryFolder.newFolder()
        val previews = PagePreviews.open(root, ENGINE_ID, CONTENT_ID, layoutVersion = null, pageCount = 5)

        previews.offer(2, rgba(), 4, 4)
        assertTrue(previews.awaitIdleForTest(TimeUnit.SECONDS.toMillis(SETTLE_SECONDS)))

        val stored = previews.previewFor(2)
        assertNotNull(stored)
        assertNull(previews.previewFor(0))
        previews.close()
    }

    @Test fun versionOnlyAdvancesWhenAWriteActuallyLands() {
        val root = temporaryFolder.newFolder()
        val previews = PagePreviews.open(root, ENGINE_ID, CONTENT_ID, layoutVersion = null, pageCount = 5)
        assertEquals(0, previews.version)

        previews.offer(0, rgba(), 4, 4)
        assertTrue(previews.awaitIdleForTest(TimeUnit.SECONDS.toMillis(SETTLE_SECONDS)))
        assertEquals(1, previews.version)

        previews.close()
    }

    @Test fun offerIsANoOpWhenThePageAlreadyHasAPreview() {
        val root = temporaryFolder.newFolder()
        val previews = PagePreviews.open(root, ENGINE_ID, CONTENT_ID, layoutVersion = null, pageCount = 5)

        previews.offer(0, rgba(value = 1), 4, 4)
        assertTrue(previews.awaitIdleForTest(TimeUnit.SECONDS.toMillis(SETTLE_SECONDS)))
        val firstVersion = previews.version
        val firstPreview = previews.previewFor(0)

        previews.offer(0, rgba(value = 2), 4, 4)
        assertTrue(previews.awaitIdleForTest(TimeUnit.SECONDS.toMillis(SETTLE_SECONDS)))

        assertEquals(firstVersion, previews.version)
        assertEquals(firstPreview, previews.previewFor(0))
        previews.close()
    }

    @Test fun aFullQueueDropsAnOfferInsteadOfBlockingTheCaller() {
        val root = temporaryFolder.newFolder()
        val previews = PagePreviews.open(root, ENGINE_ID, CONTENT_ID, layoutVersion = null, pageCount = 20)

        val taskStarted = CountDownLatch(1)
        val blockFirstTask = CountDownLatch(1)
        assertTrue(previews.offerRawTaskForTest {
            taskStarted.countDown()
            blockFirstTask.awaitIgnoringInterrupts()
        })
        assertTrue(taskStarted.await(SETTLE_SECONDS, TimeUnit.SECONDS))
        // The first task is now running on the writer thread, so the queue is empty; fill it entirely.
        repeat(PAGE_PREVIEW_WRITE_QUEUE_CAPACITY) {
            assertTrue(previews.offerRawTaskForTest {})
        }

        val callerReturnedPromptly = CountDownLatch(1)
        Thread {
            previews.offer(0, rgba(), 4, 4)
            callerReturnedPromptly.countDown()
        }.start()
        assertTrue(callerReturnedPromptly.await(SETTLE_SECONDS, TimeUnit.SECONDS))

        blockFirstTask.countDown()
        assertTrue(previews.awaitIdleForTest(TimeUnit.SECONDS.toMillis(SETTLE_SECONDS)))
        assertNull("the dropped offer must never have reached the file", previews.previewFor(0))

        previews.close()
    }

    @Test fun closeIsPromptAndSafeToCallTwice() {
        val root = temporaryFolder.newFolder()
        val previews = PagePreviews.open(root, ENGINE_ID, CONTENT_ID, layoutVersion = null, pageCount = 5)

        val closed = CountDownLatch(1)
        Thread {
            previews.close()
            closed.countDown()
        }.start()
        assertTrue(closed.await(SETTLE_SECONDS, TimeUnit.SECONDS))

        previews.close()
    }

    @Test fun aSecondHolderOnTheSameDirectoryAndIdentitySeesPreviewsWrittenAfterTheFirstClosed() {
        val root = temporaryFolder.newFolder()
        val first = PagePreviews.open(root, ENGINE_ID, CONTENT_ID, layoutVersion = null, pageCount = 5)
        first.offer(1, rgba(), 4, 4)
        assertTrue(first.awaitIdleForTest(TimeUnit.SECONDS.toMillis(SETTLE_SECONDS)))
        first.close()

        val second = PagePreviews.open(root, ENGINE_ID, CONTENT_ID, layoutVersion = null, pageCount = 5)
        assertEquals(first.previewFor(1), second.previewFor(1))
        second.close()
    }

    @Test fun aDifferentLayoutVersionSeesNoPreviews() {
        val root = temporaryFolder.newFolder()
        val original = PagePreviews.open(root, ENGINE_ID, CONTENT_ID, layoutVersion = "layout-a", pageCount = 5)
        original.offer(1, rgba(), 4, 4)
        assertTrue(original.awaitIdleForTest(TimeUnit.SECONDS.toMillis(SETTLE_SECONDS)))
        original.close()

        val differentLayout = PagePreviews.open(root, ENGINE_ID, CONTENT_ID, layoutVersion = "layout-b", pageCount = 5)
        assertNull(differentLayout.previewFor(1))
        differentLayout.close()
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
