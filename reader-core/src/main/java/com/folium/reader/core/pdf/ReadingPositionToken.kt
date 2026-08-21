package com.folium.reader.core.pdf

import com.folium.reader.core.library.requireOpaque
import java.util.Base64
import java.util.zip.CRC32

/**
 * Opaque, transportable identity for a stored reading position. [requireOpaque] guarantees the
 * value carries no control characters, and because every token is minted by [ReadingPositionTokens]
 * out of Base64url segments joined by literal dots, it also never carries the unit separator
 * [com.folium.reader.core.library.LibraryRecords] joins fields with — so a token drops into that
 * encoding unescaped, exactly as [com.folium.reader.core.library.BookId] already does.
 */
@JvmInline
value class ReadingPositionToken(val value: String) {
    init { requireOpaque(value, "ReadingPositionToken") }
}

/**
 * A place in a reflowable book, expressed so that laying the book out differently cannot move it.
 *
 * A chapter is a fact about the file rather than about any layout, and a character offset into that
 * chapter is a fact about the text. Neither moves when the type size or the stylesheet changes, so
 * the two together name the same words under any pagination.
 *
 * The engine's own bookmark is deliberately not part of this. It resolves only to a chapter and a
 * page within it, so it cannot place a reader inside a chapter that has grown; and a device spike
 * measured that applying a stylesheet destroys every bookmark the session had already minted, while
 * [chapterIndex] and [characterOffset] survive it untouched.
 */
data class ReadingPosition(val chapterIndex: Int, val characterOffset: Int) {
    init {
        require(chapterIndex >= 0) { "chapterIndex must be non-negative, was $chapterIndex" }
        require(characterOffset >= 0) { "characterOffset must be non-negative, was $characterOffset" }
    }
}

/**
 * Mints and parses [ReadingPositionToken]s under the grammar `fpt1.<scope>.<payload>.<crc>`, where
 * `scope` and `payload` are Base64url without padding of the caller's strings and `crc` is eight
 * lowercase hex digits of `CRC32` over the literal `<scope>.<payload>` bytes as written. Base64url
 * never contains a dot, so the four segments split unambiguously with no escaping.
 *
 * A token's scope is checked on parse, not merely carried: it lets [rescope] wrap a whole token as
 * another token's payload, so a position minted under one namespace — a document's own identity,
 * for instance — cannot be mistaken for a position minted under a different one when unwrapped.
 */
object ReadingPositionTokens {
    private const val FORMAT_MARKER = "fpt1"
    private const val POSITION_SCOPE = "position"
    private val base64Encoder = Base64.getUrlEncoder().withoutPadding()
    private val base64Decoder = Base64.getUrlDecoder()

    fun mint(scope: String, payload: String): ReadingPositionToken {
        val body = "${encode(scope)}.${encode(payload)}"
        return ReadingPositionToken("$FORMAT_MARKER.$body.${crc32Hex(body)}")
    }

    /** Returns the payload when [token] parses under [expectedScope]; null on any mismatch or corruption. */
    fun parse(token: ReadingPositionToken, expectedScope: String): String? {
        val segments = token.value.split('.')
        if (segments.size != 4) return null

        val (marker, encodedScope, encodedPayload, crc) = segments
        if (marker != FORMAT_MARKER) return null

        val body = "$encodedScope.$encodedPayload"
        if (crc32Hex(body) != crc) return null

        val scope = decode(encodedScope) ?: return null
        if (scope != expectedScope) return null

        return decode(encodedPayload)
    }

    fun mintPosition(position: ReadingPosition, scope: String = POSITION_SCOPE): ReadingPositionToken {
        val payload = "${position.chapterIndex}:${position.characterOffset}"
        return mint(scope, payload)
    }

    fun parsePosition(token: ReadingPositionToken, scope: String = POSITION_SCOPE): ReadingPosition? {
        val fields = parse(token, scope)?.split(':') ?: return null
        if (fields.size != 2) return null

        val chapterIndex = fields[0].toIntOrNull() ?: return null
        val characterOffset = fields[1].toIntOrNull() ?: return null

        return runCatching { ReadingPosition(chapterIndex, characterOffset) }.getOrNull()
    }

    /** Wraps [inner]'s whole value as the payload of a new token scoped to [outerScope]. */
    fun rescope(inner: ReadingPositionToken, outerScope: String): ReadingPositionToken =
        mint(outerScope, inner.value)

    /** Exact inverse of [rescope]; null when [outer] was not scoped to [outerScope] or is corrupt. */
    fun unscope(outer: ReadingPositionToken, outerScope: String): ReadingPositionToken? =
        parse(outer, outerScope)?.let { runCatching { ReadingPositionToken(it) }.getOrNull() }

    private fun encode(value: String): String = base64Encoder.encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun decode(value: String): String? =
        runCatching { String(base64Decoder.decode(value), Charsets.UTF_8) }.getOrNull()

    private fun crc32Hex(body: String): String {
        val crc32 = CRC32()
        crc32.update(body.toByteArray(Charsets.UTF_8))
        return String.format("%08x", crc32.value)
    }
}
