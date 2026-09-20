package com.folium.reader.core.ink

import java.io.Closeable
import java.io.File
import java.util.concurrent.ConcurrentHashMap

private const val SHEET_META_FILE_NAME = "sheet.meta"
private const val SHEET_STROKES_FILE_NAME = "strokes.log"

/** No sheet is stored under [id]. */
class SheetNotFoundException(val id: SheetId) : Exception("no sheet stored for $id")

/** [SheetStore.create] was called for an [id] that already has a stored sheet. */
class SheetAlreadyExistsException(val id: SheetId) : Exception("a sheet already exists for $id")

/** [id] is already open through this [SheetStore]; only one writer per sheet is allowed at a time. */
class SheetAlreadyOpenException(val id: SheetId) : Exception("sheet $id is already open")

/** A [Sheet]'s catalog-level fields, read from its metadata alone, without touching its strokes. */
data class SheetSummary(
    val id: SheetId,
    val title: String,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val template: SheetTemplate,
    val anchor: SheetAnchor?
)

/**
 * The result of [SheetStore.list]: every sheet whose metadata parsed, plus, separately, the ids of
 * any sheet directories whose metadata did not — reported rather than skipped, since a metadata file
 * this store cannot read is never deleted or treated as absent.
 */
data class SheetListing(val sheets: List<SheetSummary>, val unreadable: List<SheetId>)

/**
 * Durable, crash-safe storage for handwritten [Sheet]s, laid out as one directory per sheet under
 * [root]: `<root>/<sheetId>/sheet.meta` for its catalog fields and `<root>/<sheetId>/strokes.log` for
 * its append-only stroke history. See [SheetMetaFile] and [SheetStrokeLog] for each file's own
 * crash-safety guarantees.
 *
 * At most one [OpenSheet] may be open for a given [SheetId] through a given [SheetStore] instance at
 * a time: [create] and [open] register the id in an in-process set and [SheetAlreadyOpenException] is
 * thrown immediately for a second attempt, rather than letting two writers race over the same log.
 *
 * Blocking, synchronous I/O; the caller owns whatever thread it runs on. [durability] and [nowMillis]
 * apply to every [OpenSheet] this store opens or creates.
 */
class SheetStore(
    private val root: File,
    private val durability: SheetStrokeLogDurability = SheetStrokeLogDurability.EVERY_RECORD,
    private val nowMillis: () -> Long = System::currentTimeMillis
) {
    private val openIds = ConcurrentHashMap.newKeySet<SheetId>()

    fun create(sheet: Sheet): OpenSheet {
        val dir = sheetDir(sheet.id)
        if (dir.exists()) throw SheetAlreadyExistsException(sheet.id)
        if (!openIds.add(sheet.id)) throw SheetAlreadyOpenException(sheet.id)

        try {
            dir.mkdirs()
            val metaFile = File(dir, SHEET_META_FILE_NAME)
            SheetMetaFile.write(metaFile, sheet)
            val log = SheetStrokeLog.open(File(dir, SHEET_STROKES_FILE_NAME), durability)
            return OpenSheet(this, sheet, log, metaFile, nowMillis)
        } catch (e: Exception) {
            openIds.remove(sheet.id)
            throw e
        }
    }

    fun open(id: SheetId): OpenSheet {
        val metaFile = File(sheetDir(id), SHEET_META_FILE_NAME)
        if (!metaFile.isFile) throw SheetNotFoundException(id)
        if (!openIds.add(id)) throw SheetAlreadyOpenException(id)

        try {
            val sheet = SheetMetaFile.read(metaFile)
            val log = SheetStrokeLog.open(File(sheetDir(id), SHEET_STROKES_FILE_NAME), durability)
            return OpenSheet(this, sheet, log, metaFile, nowMillis)
        } catch (e: Exception) {
            openIds.remove(id)
            throw e
        }
    }

    /** Reads only metadata, never strokes: cheap enough to call for an entire library's worth of sheets. */
    fun list(): SheetListing {
        val sheets = mutableListOf<SheetSummary>()
        val unreadable = mutableListOf<SheetId>()

        val dirs = root.listFiles { candidate -> candidate.isDirectory } ?: emptyArray()
        for (dir in dirs) {
            val id = try {
                SheetId(dir.name)
            } catch (_: IllegalArgumentException) {
                continue
            }

            val metaFile = File(dir, SHEET_META_FILE_NAME)
            if (!metaFile.isFile) continue

            try {
                val sheet = SheetMetaFile.read(metaFile)
                sheets += SheetSummary(sheet.id, sheet.title, sheet.createdAtEpochMillis, sheet.updatedAtEpochMillis, sheet.template, sheet.anchor)
            } catch (_: SheetMetaCorruptException) {
                unreadable += id
            }
        }

        return SheetListing(sheets, unreadable)
    }

    fun exists(id: SheetId): Boolean = File(sheetDir(id), SHEET_META_FILE_NAME).isFile

    /** Deletes [id]'s whole directory: strokes, metadata, everything. The only destructive call on this store. */
    fun delete(id: SheetId) {
        check(id !in openIds) { "cannot delete sheet $id while it is open" }
        sheetDir(id).deleteRecursively()
    }

    internal fun release(id: SheetId) {
        openIds.remove(id)
    }

    private fun sheetDir(id: SheetId): File = File(root, id.value)
}

/**
 * A [Sheet] currently open for reading and editing through a [SheetStore]. Blocking, synchronous I/O
 * with no internal locking beyond the [SheetStore]-level open registry: the caller owns whatever
 * single thread drives a given open sheet at a time, the same contract [SheetEditHistory] documents
 * for the edits [apply] records.
 *
 * [rename] persists immediately; [apply] only bumps [sheet]'s timestamp in memory; [close] always
 * persists the final in-memory metadata before releasing this sheet's slot in [SheetStore]'s open
 * registry, so a caller never has to call both.
 */
class OpenSheet internal constructor(
    private val store: SheetStore,
    initialSheet: Sheet,
    private val log: SheetStrokeLog,
    private val metaFile: File,
    private val nowMillis: () -> Long
) : Closeable {

    private var currentSheet: Sheet = initialSheet
    private var nextSequenceCounter: Long = log.maxSequenceSeen + 1
    private var closed = false

    val sheet: Sheet get() = currentSheet
    val replayReport: SheetStrokeLogReplayReport get() = log.replayReport
    val totalBytes: Long get() = log.totalBytes
    val liveBytes: Long get() = log.liveBytes
    val deadRecordCount: Int get() = log.deadRecordCount

    /** Every live stroke, ordered by [InkStroke.sequence]. */
    fun strokes(): List<InkStroke> = log.liveStrokes()

    /** The sequence number to give the next newly drawn stroke; never collides with one ever recorded, live or removed. */
    fun nextSequence(): Long {
        val sequence = nextSequenceCounter
        nextSequenceCounter += 1
        return sequence
    }

    /** Appends [edit] to the stroke log and bumps [sheet]'s updated time in memory; call [close] or [rename] to persist it. */
    fun apply(edit: SheetEdit) {
        checkOpen()
        log.append(edit)
        currentSheet = currentSheet.copy(updatedAtEpochMillis = maxOf(currentSheet.updatedAtEpochMillis, nowMillis()))
    }

    /** Renames [sheet] and persists the new metadata immediately. */
    fun rename(title: String) {
        checkOpen()
        currentSheet = currentSheet.copy(title = title, updatedAtEpochMillis = maxOf(currentSheet.updatedAtEpochMillis, nowMillis()))
        SheetMetaFile.write(metaFile, currentSheet)
    }

    fun flush() = log.flush()

    fun compact() = log.compact()

    override fun close() {
        if (closed) return
        SheetMetaFile.write(metaFile, currentSheet)
        log.close()
        store.release(currentSheet.id)
        closed = true
    }

    private fun checkOpen() = check(!closed) { "sheet ${currentSheet.id} is closed" }
}
