package com.folium.reader.ocr_tesseract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

class TrainedDataInstallerTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun firstInstallWritesEveryLanguageAndLaterInstallsReadNothing() {
        val payloads = mapOf("eng" to "english".toByteArray(), "spa" to "spanish".toByteArray())
        var opens = 0
        val installer = installer(payloads) { name -> opens++; ByteArrayInputStream(payloads.getValue(name)) }

        installer.install()

        assertEquals(payloads.size, opens)
        payloads.forEach { (name, payload) ->
            assertTrue(File(temporaryFolder.root, "tessdata/$name.traineddata").readBytes().contentEquals(payload))
        }

        repeat(3) { installer.install() }

        assertEquals(payloads.size, opens)
    }

    @Test fun repeatedInstallsDoNotRereadInstalledFiles() {
        val payloads = mapOf("eng" to "english".toByteArray())
        val installer = installer(payloads) { name -> ByteArrayInputStream(payloads.getValue(name)) }
        installer.install()

        val destination = File(temporaryFolder.root, "tessdata/eng.traineddata")
        val readsBefore = destination.lastModified()
        destination.delete()

        installer.install()

        assertTrue("a verified installer must not touch the filesystem again", !destination.exists())
        assertTrue(readsBefore > 0L)
    }

    @Test fun corruptExistingFileIsReplacedFromTheBundle() {
        val payloads = mapOf("eng" to "english".toByteArray())
        val destination = File(temporaryFolder.root, "tessdata/eng.traineddata")
        destination.parentFile.mkdirs()
        destination.writeText("corrupt")

        installer(payloads) { name -> ByteArrayInputStream(payloads.getValue(name)) }.install()

        assertTrue(destination.readBytes().contentEquals(payloads.getValue("eng")))
    }

    @Test fun bundleContentThatFailsVerificationIsRejectedAndLeavesNoPartialFile() {
        val payloads = mapOf("eng" to "english".toByteArray())
        val installer = installer(payloads) { ByteArrayInputStream("tampered".toByteArray()) }

        assertThrows(LanguageDataIntegrityException::class.java) { installer.install() }

        val directory = File(temporaryFolder.root, "tessdata")
        assertEquals(emptyList<String>(), directory.list()?.toList().orEmpty())
    }

    @Test fun missingBundleIsReportedAsMissingRatherThanAsAnIoFailure() {
        val payloads = mapOf("eng" to "english".toByteArray())
        val installer = installer(payloads) { throw FileNotFoundException("absent") }

        assertThrows(MissingBundledLanguageDataException::class.java) { installer.install() }
    }

    @Test fun aDataRootOccupiedByAFileIsReportedAsAnIntegrityFailure() {
        val root = File(temporaryFolder.root, "occupied")
        root.writeText("not a directory")
        val payloads = mapOf("eng" to "english".toByteArray())
        val installer = TrainedDataInstaller(root, hashesOf(payloads)) {
            ByteArrayInputStream(payloads.getValue(it))
        }

        assertThrows(LanguageDataIntegrityException::class.java) { installer.install() }
    }

    @Test fun aFailedInstallIsRetriedRatherThanRememberedAsDone() {
        val payloads = mapOf("eng" to "english".toByteArray())
        var fail = true
        val installer = installer(payloads) { name ->
            if (fail) throw IOException("transient") else ByteArrayInputStream(payloads.getValue(name))
        }

        assertThrows(IOException::class.java) { installer.install() }
        fail = false
        installer.install()

        assertTrue(File(temporaryFolder.root, "tessdata/eng.traineddata").exists())
    }

    private fun installer(payloads: Map<String, ByteArray>, openData: (String) -> InputStream) =
        TrainedDataInstaller(temporaryFolder.root, hashesOf(payloads), openData)

    private fun hashesOf(payloads: Map<String, ByteArray>): Map<String, String> = payloads.mapValues { (_, bytes) ->
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
