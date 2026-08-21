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
            ReadingPosition(chapterIndex = 0, characterOffset = 0),
            ReadingPosition(chapterIndex = 7, characterOffset = 512),
            ReadingPosition(chapterIndex = Int.MAX_VALUE, characterOffset = Int.MAX_VALUE)
        )

        positions.forEach { position ->
            val token = ReadingPositionTokens.mintPosition(position)
            assertEquals(position, ReadingPositionTokens.parsePosition(token))
        }
    }

    /**
     * The catalog and the progress file join their fields with a unit separator and escape nothing,
     * resting on no value being able to contain one. A token that could would corrupt the whole file.
     */
    @Test fun tokenValueCarriesNoUnitSeparatorOrControlCharacters() {
        val extremes = listOf(0 to 0, 1 to 1, 0 to Int.MAX_VALUE, Int.MAX_VALUE to 0, 147 to 1_200_000)

        extremes.forEach { (chapterIndex, characterOffset) ->
            val token = ReadingPositionTokens.mintPosition(ReadingPosition(chapterIndex, characterOffset))
            assertFalse(token.value.contains(''))
            assertFalse(token.value.any(Char::isISOControl))
        }
    }

    /** A long book's offset runs into the millions of characters, well past any small-int shortcut. */
    @Test fun aLargeChapterOffsetRoundTrips() {
        val position = ReadingPosition(chapterIndex = 146, characterOffset = Int.MAX_VALUE)
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
