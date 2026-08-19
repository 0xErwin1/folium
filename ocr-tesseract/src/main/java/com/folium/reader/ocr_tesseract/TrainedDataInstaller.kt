package com.folium.reader.ocr_tesseract

import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/**
 * Materializes the bundled trained data under a private directory and proves it is intact.
 *
 * Verification is content-addressed and therefore expensive: hashing every language costs a full
 * read of several megabytes. A successful install is remembered for the lifetime of this instance,
 * because the destination is private to the app and nothing else writes it while an engine holds
 * it open. A failed install is not remembered, so a transient filesystem error is retried on the
 * next attempt rather than being latched into a permanently broken engine.
 */
internal class TrainedDataInstaller(
    private val dataRoot: File,
    private val expectedHashes: Map<String, String>,
    private val openData: (String) -> InputStream
) {
    @Volatile private var verified = false

    fun install() {
        if (verified) return

        val directory = File(dataRoot, "tessdata")
        if (directory.exists() && !directory.isDirectory) throw LanguageDataIntegrityException()
        if (!directory.exists() && !directory.mkdirs()) {
            if (dataRoot.exists() && !dataRoot.isDirectory) throw LanguageDataIntegrityException()
            throw IOException("Cannot create OCR data directory")
        }

        expectedHashes.forEach { (name, expectedHash) ->
            val destination = File(directory, "$name.traineddata")
            if (destination.sha256() != expectedHash) {
                reinstall(directory, name, destination, expectedHash)
            }
        }

        verified = true
    }

    private fun reinstall(directory: File, name: String, destination: File, expectedHash: String) {
        val temporary = File(directory, ".$name.traineddata.installing")
        try {
            openBundledData(name).use { input -> temporary.outputStream().use(input::copyTo) }
            if (temporary.sha256() != expectedHash) throw LanguageDataIntegrityException()
            if (destination.exists() && !destination.delete()) throw IOException("Cannot replace OCR data")
            if (!temporary.renameTo(destination)) throw IOException("Cannot install OCR data")
        } finally {
            if (temporary.exists()) temporary.delete()
        }
        if (destination.sha256() != expectedHash) throw LanguageDataIntegrityException()
    }

    private fun openBundledData(name: String): InputStream = try {
        openData(name)
    } catch (failure: FileNotFoundException) {
        throw MissingBundledLanguageDataException(failure)
    }
}

/** Streams the file so verifying multi-megabyte trained data never materializes it on the heap. */
private fun File.sha256(): String {
    if (!isFile) return ""
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    inputStream().use { input ->
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
