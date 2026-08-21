package com.folium.reader.core.pdf

import java.util.Base64
import java.util.zip.CRC32
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ReadingPositionTokenTest {

    @Test fun mintThenParseRoundTripsScopeAndPayload() {
        val token = ReadingPositionTokens.mint("chapter-list", "chapter=3;offset=128")
        assertEquals("chapter=3;offset=128", ReadingPositionTokens.parse(token, "chapter-list"))
    }

    @Test fun typedPositionRoundTripsAllThreeFields() {
        val positions = listOf(
            ReadingPosition(bookmark = 0L, chapterIndex = 0, characterOffset = 0),
            ReadingPosition(bookmark = 4096L, chapterIndex = 7, characterOffset = 512),
            ReadingPosition(bookmark = Long.MAX_VALUE, chapterIndex = Int.MAX_VALUE, characterOffset = Int.MAX_VALUE)
        )

        positions.forEach { position ->
            val token = ReadingPositionTokens.mintPosition(position)
            assertEquals(position, ReadingPositionTokens.parsePosition(token))
        }
    }

    @Test fun tokenValueCarriesNoUnitSeparatorOrControlCharacters() {
        val bookmarksWithHighBitSet = listOf(0L, 1L, -1L, Long.MIN_VALUE, Long.MAX_VALUE, (1L shl 63) or 42L)

        bookmarksWithHighBitSet.forEach { bookmark ->
            val token = ReadingPositionTokens.mintPosition(ReadingPosition(bookmark, chapterIndex = 1, characterOffset = 1))
            assertFalse(token.value.contains(''))
            assertFalse(token.value.any(Char::isISOControl))
        }
    }

    @Test fun aBookmarkWithTheHighBitSetRoundTrips() {
        val position = ReadingPosition(bookmark = Long.MIN_VALUE, chapterIndex = 2, characterOffset = 9)
        val token = ReadingPositionTokens.mintPosition(position)
        assertEquals(position, ReadingPositionTokens.parsePosition(token))
    }

    @Test fun wrongFormatMarkerParsesToNull() {
        val token = ReadingPositionToken(rewriteSegment(mintRaw("scope", "payload"), index = 0, replacement = "fpt2"))
        assertNull(ReadingPositionTokens.parse(token, "scope"))
    }

    @Test fun wrongArityParsesToNull() {
        val raw = mintRaw("scope", "payload")
        val tooFewSegments = ReadingPositionToken(raw.split('.').dropLast(1).joinToString("."))
        val tooManySegments = ReadingPositionToken("$raw.extra")
        assertNull(ReadingPositionTokens.parse(tooFewSegments, "scope"))
        assertNull(ReadingPositionTokens.parse(tooManySegments, "scope"))
    }

    @Test fun nonBase64UrlPayloadParsesToNullRatherThanThrowing() {
        val body = "AAAA.!!!not-base64!!!"
        val token = ReadingPositionToken("fpt1.$body.${crc32HexOf(body)}")
        assertNull(ReadingPositionTokens.parse(token, "scope"))
    }

    @Test fun crcMismatchParsesToNull() {
        val token = ReadingPositionToken(rewriteSegment(mintRaw("scope", "payload"), index = 3, replacement = "deadbeef"))
        assertNull(ReadingPositionTokens.parse(token, "scope"))
    }

    @Test fun scopeMismatchParsesToNull() {
        val token = ReadingPositionTokens.mint("actual-scope", "payload")
        assertNull(ReadingPositionTokens.parse(token, "expected-scope"))
    }

    @Test fun rescopeAndUnscopeAreExactInverses() {
        val inner = ReadingPositionTokens.mint("position", "chapter=3;offset=128")
        val outer = ReadingPositionTokens.rescope(inner, "document-42")
        assertEquals(inner, ReadingPositionTokens.unscope(outer, "document-42"))
    }

    @Test fun unscopingWithTheWrongOuterScopeReturnsNull() {
        val inner = ReadingPositionTokens.mint("position", "chapter=3;offset=128")
        val outer = ReadingPositionTokens.rescope(inner, "document-42")
        assertNull(ReadingPositionTokens.unscope(outer, "document-99"))
    }

    @Test fun requireOpaqueRejectsBlankValue() {
        assertThrows(IllegalArgumentException::class.java) { ReadingPositionToken("") }
        assertThrows(IllegalArgumentException::class.java) { ReadingPositionToken("   ") }
    }

    @Test fun requireOpaqueRejectsAControlCharacter() {
        assertThrows(IllegalArgumentException::class.java) { ReadingPositionToken("fpt1.a.b.c\u0007") }
    }

    /** Same wire shape [ReadingPositionTokens] produces, built independently so corruption tests do not rely on it. */
    private fun mintRaw(scope: String, payload: String): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val body = "${encoder.encodeToString(scope.toByteArray(Charsets.UTF_8))}." +
            encoder.encodeToString(payload.toByteArray(Charsets.UTF_8))
        return "fpt1.$body.${crc32HexOf(body)}"
    }

    private fun rewriteSegment(raw: String, index: Int, replacement: String): String =
        raw.split('.').toMutableList().also { it[index] = replacement }.joinToString(".")

    private fun crc32HexOf(body: String): String {
        val crc32 = CRC32()
        crc32.update(body.toByteArray(Charsets.UTF_8))
        return String.format("%08x", crc32.value)
    }
}
