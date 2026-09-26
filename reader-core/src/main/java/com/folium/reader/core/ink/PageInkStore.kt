package com.folium.reader.core.ink

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

private const val PAGE_INK_META_FILE_NAME = "page-ink.meta"
private const val PAGE_INK_META_MAGIC: Int = 0x464F_4C50 // "FOLP"
private const val PAGE_INK_META_VERSION: Int = 1
private val PAGE_LOG_FILE_NAME = Regex("""p(0|[1-9][0-9]*)\.log""")

/** [pageIndex] is already open through this [PageInkStore]; only one writer per page is allowed at a time. */
class PageInkAlreadyOpenException(val pageIndex: Int) : Exception("page $pageIndex ink is already open")

/**
 * [file] exists but does not parse as a page-ink binding. The file is left exactly as it was, the
 * same way [SheetMetaCorruptException] leaves a sheet's metadata.
 */
class PageInkMetaCorruptException(val file: File, reason: String) :
    Exception("corrupt page ink metadata at $file: $reason")

/**
 * Durable, crash-safe storage for the ink handwritten directly on the pages of ONE book, kept apart
 * from the book file itself: `<root>/p<pageIndex>.log` holds each inked page's [SheetStrokeLog], in
 * [PageInkExtent] units, and `<root>/page-ink.meta` holds the identity of the document the ink was
 * drawn on (see [bind]).
 *
 * At most one [OpenPageInk] may be open for a given page through a given store instance at a time:
 * [open] registers the page in an in-process set and throws [PageInkAlreadyOpenException] for a second
 * attempt. Different pages may be open at the same time, as a two-page spread needs.
 *
 * Every method is blocking, synchronous I/O: callers run them off the main thread.
 */
class PageInkStore(
    private val root: File,
    private val durability: SheetStrokeLogDurability = SheetStrokeLogDurability.EVERY_RECORD
) {
    private val openPages = ConcurrentHashMap.newKeySet<Int>()

    /**
     * Opens [pageIndex]'s ink for writing, replaying its log when one exists and creating an empty one
     * when not; [OpenPageInk.close] removes that file again if the page ends up with no live item.
     */
    fun open(pageIndex: Int): OpenPageInk {
        require(pageIndex >= 0) { "pageIndex must be non-negative, was $pageIndex" }
        if (!openPages.add(pageIndex)) throw PageInkAlreadyOpenException(pageIndex)

        try {
            val file = pageFile(pageIndex)
            val log = SheetStrokeLog.open(file, durability)
            return OpenPageInk(this, pageIndex, log, file)
        } catch (e: Exception) {
            openPages.remove(pageIndex)
            throw e
        }
    }

    /**
     * [pageIndex]'s ink, replayed read-only through [SheetStrokeLog.read]: safe to call while the page
     * is open for writing elsewhere, and never repairs or otherwise changes the file. A page with no
     * log reads as [SheetStrokeLogSnapshot.EMPTY].
     */
    fun read(pageIndex: Int): SheetStrokeLogSnapshot {
        require(pageIndex >= 0) { "pageIndex must be non-negative, was $pageIndex" }

        return SheetStrokeLog.read(pageFile(pageIndex))
    }

    /**
     * Every page that has a log file, from the directory listing alone, without replaying any log.
     * Names that are not exactly `p<index>.log` are ignored. A page open right now is listed even with
     * no item yet, and a page whose last item was erased while a crash kept its writer from closing
     * stays listed until it is next opened and closed.
     */
    fun pagesWithInk(): Set<Int> {
        val files = root.listFiles { candidate -> candidate.isFile } ?: return emptySet()

        return files.mapNotNullTo(HashSet()) { file ->
            PAGE_LOG_FILE_NAME.matchEntire(file.name)?.groupValues?.get(1)?.toIntOrNull()
        }
    }

    /**
     * Records [identity] as the document this book's ink was drawn on, replacing any earlier one. The
     * file is replaced atomically, the same way a sheet's metadata is, so a crash leaves either the old
     * or the new identity, never a mix. The caller compares [boundIdentity] to the current document's
     * identity to decide whether the stored ink still belongs to it.
     */
    fun bind(identity: String) {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { out ->
            out.writeInt(PAGE_INK_META_MAGIC)
            out.writeByte(PAGE_INK_META_VERSION)
            out.writeUTF(identity)
        }

        writeFileAtomically(metaFile(), buffer.toByteArray())
    }

    /** The identity last passed to [bind], or `null` when none was; throws [PageInkMetaCorruptException] for a file it cannot read. */
    fun boundIdentity(): String? {
        val file = metaFile()
        if (!file.isFile) return null

        try {
            DataInputStream(file.inputStream().buffered()).use { input ->
                if (input.readInt() != PAGE_INK_META_MAGIC) throw PageInkMetaCorruptException(file, "bad magic")

                val version = input.readByte().toInt() and 0xFF
                if (version != PAGE_INK_META_VERSION) throw PageInkMetaCorruptException(file, "unsupported version $version")

                return input.readUTF()
            }
        } catch (e: IOException) {
            throw PageInkMetaCorruptException(file, e.message ?: "I/O error")
        }
    }

    /** Deletes [root] with every page's ink and the binding. Refused while any page is open. */
    fun deleteAll() {
        check(openPages.isEmpty()) { "cannot delete page ink while pages $openPages are open" }

        root.deleteRecursively()
    }

    internal fun release(pageIndex: Int) {
        openPages.remove(pageIndex)
    }

    private fun pageFile(pageIndex: Int): File = File(root, "p$pageIndex.log")

    private fun metaFile(): File = File(root, PAGE_INK_META_FILE_NAME)
}

/**
 * One book page's ink, open for reading and writing through a [PageInkStore]. Blocking, synchronous
 * I/O with no internal locking beyond the store's open registry: the caller owns whatever single
 * thread drives it, never the main thread.
 *
 * [close] removes the page's log file when no item is live, so a page whose ink was all erased, or
 * that was opened and never drawn on, leaves nothing behind for [PageInkStore.pagesWithInk] to list.
 */
class OpenPageInk internal constructor(
    private val store: PageInkStore,
    val pageIndex: Int,
    private val log: SheetStrokeLog,
    private val file: File
) : InkLayerWriter {

    private var nextSequenceCounter: Long = log.maxSequenceSeen + 1
    private var closed = false

    val replayReport: SheetStrokeLogReplayReport get() = log.replayReport

    override fun items(): List<SheetItem> = log.liveItems()

    override fun strokes(): List<InkStroke> = log.liveStrokes()

    override fun textBoxes(): List<SheetTextBox> = log.liveTexts()

    override fun nextSequence(): Long {
        val sequence = nextSequenceCounter
        nextSequenceCounter += 1
        return sequence
    }

    override fun apply(edit: SheetEdit) {
        checkOpen()
        log.append(edit)
    }

    override fun flush() = log.flush()

    override fun compact() = log.compact()

    override fun close() {
        if (closed) return

        val empty = log.liveItems().isEmpty()
        try {
            log.close()
            if (empty) file.delete()
        } finally {
            store.release(pageIndex)
            closed = true
        }
    }

    private fun checkOpen() = check(!closed) { "page $pageIndex ink is closed" }
}
