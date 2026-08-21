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
 * A position inside a reflowable chapter, addressed two ways at once. The engine's own bookmark
 * resolves [chapterIndex]'s start cheaply, but a device spike measured that a bookmark alone only
 * survives a re-pagination when it names a chapter start: any mid-chapter position drifts as the
 * chapter's page count changes. [characterOffset] is what actually places the reader inside the
 * chapter once [bookmark] has found it.
 */
data class ReadingPosition(val bookmark: Long, val chapterIndex: Int, val characterOffset: Int) {
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
        val payload = "${java.lang.Long.toHexString(position.bookmark)}:${position.chapterIndex}:${position.characterOffset}"
        return mint(scope, payload)
    }

    fun parsePosition(token: ReadingPositionToken, scope: String = POSITION_SCOPE): ReadingPosition? {
        val fields = parse(token, scope)?.split(':') ?: return null
        if (fields.size != 3) return null

        val bookmark = runCatching { java.lang.Long.parseUnsignedLong(fields[0], 16) }.getOrNull() ?: return null
        val chapterIndex = fields[1].toIntOrNull() ?: return null
        val characterOffset = fields[2].toIntOrNull() ?: return null

        return runCatching { ReadingPosition(bookmark, chapterIndex, characterOffset) }.getOrNull()
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
