package com.folium.reader.core.diskcache

import com.folium.reader.core.pdf.PageSpaceRect
import com.folium.reader.core.pdf.RenderSpec
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest

/** Bumped whenever the on-disk entry layout changes in a way old files cannot be read back under. */
const val PAGE_RASTER_CACHE_FORMAT_VERSION: Int = 1

/** A page whose [RenderSpec.pageSpace] covers the whole page, independent of any pan. */
private val WHOLE_PAGE_SPACE = PageSpaceRect(0f, 0f, 1f, 1f)

/**
 * Whether [RenderSpec] is safe to persist: it must describe the entire page rather than a viewport
 * crop, so the same file is correct at every pan and every zoom level a reader ever reaches it from.
 * A base-tier spec satisfies this by construction, and so does a fitted, off-screen window page or
 * an unzoomed visible page whenever its layout happens to reveal the page in full — the geometry
 * decides this, not the priority the request was made under.
 */
fun RenderSpec.isWholePage(): Boolean = pageSpace == WHOLE_PAGE_SPACE

/**
 * Identifies one persisted raster: a page at a specific size within a specific document, engine and
 * layout. Two keys that differ in any field never share a file, and [fileName] is derived from every
 * one of them, so a stale file left over from an older engine, layout or format version is simply
 * never looked up again rather than being read back incorrectly.
 *
 * [contentId] is the document's content-derived identity, not a file path or a session-local book
 * id, so the same file is reused across sessions and across a book moved or renamed on disk.
 * [layoutVersion] is null for a fixed-layout document, and for a reflowable one laid out under its
 * default box and stylesheet — see [com.folium.reader.core.pdf.ReflowLayoutBox] callers — and is the
 * stable, content-derived layout fingerprint otherwise.
 */
data class DiskPageCacheKey(
    val formatVersion: Int,
    val engineId: String,
    val contentId: String,
    val layoutVersion: String?,
    val pageIndex: Int,
    val width: Int,
    val height: Int,
    val pageSpace: PageSpaceRect
) {
    init {
        require(engineId.isNotBlank() && contentId.isNotBlank() && pageIndex >= 0 && width > 0 && height > 0) {
            "engineId and contentId must be non-blank, pageIndex/width/height must be positive, " +
                "got engineId=\"$engineId\" contentId=\"$contentId\" pageIndex=$pageIndex width=$width height=$height"
        }
    }

    companion object {
        fun forWholePageSpec(
            engineId: String,
            contentId: String,
            layoutVersion: String?,
            pageIndex: Int,
            spec: RenderSpec
        ): DiskPageCacheKey? {
            if (!spec.isWholePage()) return null
            return DiskPageCacheKey(
                PAGE_RASTER_CACHE_FORMAT_VERSION, engineId, contentId, layoutVersion, pageIndex, spec.width, spec.height, spec.pageSpace
            )
        }
    }
}

/**
 * A stable, order-sensitive byte encoding of every field in [DiskPageCacheKey], used both to name
 * the file a key maps to and to serialize the header that file carries for round-trip validation.
 * Kept in one place so the two never drift apart from each other.
 */
internal fun DiskPageCacheKey.canonicalBytes(): ByteArray {
    val buffer = ByteArrayOutputStream()
    DataOutputStream(buffer).use { out ->
        out.writeInt(formatVersion)
        out.writeUTF(engineId)
        out.writeUTF(contentId)
        out.writeBoolean(layoutVersion != null)
        if (layoutVersion != null) out.writeUTF(layoutVersion)
        out.writeInt(pageIndex)
        out.writeInt(width)
        out.writeInt(height)
        out.writeFloat(pageSpace.left)
        out.writeFloat(pageSpace.top)
        out.writeFloat(pageSpace.right)
        out.writeFloat(pageSpace.bottom)
    }
    return buffer.toByteArray()
}

/** The file name [key] is stored under: a fixed-length hex digest, so no field length can collide with another. */
fun diskPageCacheFileName(key: DiskPageCacheKey): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(key.canonicalBytes())
    return digest.joinToString("") { "%02x".format(it) } + ".pgc"
}
