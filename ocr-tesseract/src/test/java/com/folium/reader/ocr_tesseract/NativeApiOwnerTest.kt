package com.folium.reader.ocr_tesseract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class NativeApiOwnerTest {
    @Test fun initializationExceptionReleasesPartiallyCreatedApiExactlyOnce() {
        val api = Any()
        var releases = 0
        val owner = NativeApiOwner<Any> { releases++ }
        assertThrows(IllegalStateException::class.java) {
            owner.acquire(setOf("eng"), { api }) { throw IllegalStateException("init") }
        }
        assertEquals(1, releases)
        owner.close()
        assertEquals(1, releases)
    }

    @Test fun fatalInitializationErrorIsRethrownAfterCleanup() {
        val api = Any()
        var releases = 0
        val owner = NativeApiOwner<Any> { releases++ }
        val error = assertThrows(AssertionError::class.java) {
            owner.acquire(setOf("eng"), { api }) { throw AssertionError("fatal") }
        }
        assertEquals("fatal", error.message)
        assertEquals(1, releases)
    }

    @Test fun languageSetChangeClosesOldApiAndReinitializes() {
        val first = Any()
        val second = Any()
        val released = mutableListOf<Any>()
        val owner = NativeApiOwner<Any>(released::add)
        assertSame(first, owner.acquire(setOf("eng"), { first }) { true })
        assertSame(first, owner.acquire(setOf("eng"), { error("must reuse") }) { true })
        assertSame(second, owner.acquire(setOf("spa"), { second }) { true })
        owner.close()
        assertEquals(listOf(first, second), released)
    }

    @Test fun cleanupFailureIsSuppressedWithoutReplacingFatalFailure() {
        val owner = NativeApiOwner<Any> { throw IllegalStateException("cleanup") }
        val error = assertThrows(AssertionError::class.java) {
            owner.acquire(setOf("eng"), { Any() }) { throw AssertionError("fatal") }
        }
        assertEquals("fatal", error.message)
        assertEquals(1, error.suppressed.size)
    }
}
