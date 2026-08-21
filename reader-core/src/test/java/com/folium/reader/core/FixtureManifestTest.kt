package com.folium.reader.core

import com.folium.reader.core.pdf.ReflowLayoutBox
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FixtureManifestTest {
    @Test fun manifestListsHashesAndDescribesTheCuratedCorpus() {
        val root = requireNotNull(
            generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
                .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ) { "repository root is missing" }
        val manifest = File(root, "test-fixtures/manifest.json")
        assertTrue("fixture manifest is missing", manifest.isFile)
        val text = manifest.readText()
        assertTrue("fixture schema is missing", text.contains("\"schemaVersion\": 2"))
        val files = Regex("\\\"file\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").findAll(text).map { it.groupValues[1] }.toList()
        val expectedFiles = setOf(
            "native-spanish.pdf", "native-english.pdf", "native-mixed.pdf", "scan-spanish.pdf", "scan-english.pdf",
            "mixed-native-scanned.pdf", "rotated-cropped-large.pdf", "corrupt.pdf", "password-protected.pdf",
            "reflowable.epub", "reflowable-long.epub", "corrupt.epub", "unsupported.txt"
        )
        assertEquals(expectedFiles, files.toSet())
        assertEquals(files.size, files.toSet().size)
        val fixtureDirectory = File(root, "test-fixtures/pdf")
        assertEquals(expectedFiles, fixtureDirectory.listFiles()?.map { it.name }?.toSet())
        listOf("case", "provenance", "license", "sha256", "pageTraits", "languages", "expectedTokens", "expectedGeometry", "expectedFailureMode").forEach {
            assertEquals("manifest field count: $it", files.size, Regex("(?m)^      \\\"$it\\\"").findAll(text).count())
        }
        files.forEach { name ->
            val file = File(fixtureDirectory, name)
            assertTrue("listed fixture is missing: $name", file.isFile)
            val expectedHash = Regex("\\\"file\\\"\\s*:\\s*\\\"${Regex.escape(name)}\\\"(?s:.*?)\\\"sha256\\\"\\s*:\\s*\\\"([0-9a-f]{64})\\\"")
                .find(text)?.groupValues?.get(1)
            assertTrue("fixture hash is missing: $name", expectedHash != null)
            val actualHash = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
            assertEquals("fixture hash mismatch: $name", expectedHash, actualHash)
        }

        assertTrue("Spanish scan tokens must be declared", Regex("""(?s:"file"\s*:\s*"scan-spanish\.pdf".*?"expectedTokens"\s*:\s*\[\s*"BIBLIOTECA")""").containsMatchIn(text))
        assertTrue("English scan tokens must be declared", Regex("""(?s:"file"\s*:\s*"scan-english\.pdf".*?"expectedTokens"\s*:\s*\[\s*"ENGLISH")""").containsMatchIn(text))
        val spanishScan = File(fixtureDirectory, "scan-spanish.pdf").readBytes()
        val englishScan = File(fixtureDirectory, "scan-english.pdf").readBytes()
        assertNotEquals("language scans must differ", spanishScan.toList(), englishScan.toList())
        listOf(spanishScan, englishScan).forEach { scan ->
            assertTrue("scan must contain a raster image", scan.containsBytes("/Subtype /Image".toByteArray()))
            assertFalse("scan must not contain a font", scan.containsBytes("/Font".toByteArray()))
            assertFalse("scan must not contain native text operators", scan.containsBytes(" BT ".toByteArray()))
        }
        val mixed = File(fixtureDirectory, "mixed-native-scanned.pdf").readBytes()
        assertEquals("mixed fixture page count", 2, Regex("/Type /Page /Parent").findAll(mixed.toString(Charsets.ISO_8859_1)).count())
        assertTrue("mixed fixture needs native text", mixed.containsBytes("/Font".toByteArray()))
        assertTrue("mixed fixture needs image-only content", mixed.containsBytes("/Subtype /Image".toByteArray()))
        val geometry = File(fixtureDirectory, "rotated-cropped-large.pdf").readText(Charsets.ISO_8859_1)
        listOf("/Rotate 90", "/MediaBox [0 0 1440 2160]", "/CropBox [100 100 1300 2000]").forEach { value -> assertTrue("missing geometry: $value", geometry.contains(value)) }
        val geometryContract = Regex("\"file\"\\s*:\\s*\"rotated-cropped-large\\.pdf\"(?s:.*?)\"expectedFailureMode\"\\s*:\\s*null")
            .find(text)?.value.orEmpty()
        assertTrue("cropped fixture must only expect visible tokens", Regex("\"expectedTokens\"\\s*:\\s*\\[\\s*\"cropped\"\\s*,\\s*\"large\"\\s*,\\s*\"page\"\\s*]").containsMatchIn(geometryContract))
        assertTrue("cropped fixture must retain the authored excluded token", Regex("\"excludedTokens\"\\s*:\\s*\\[\\s*\"Rotated\"\\s*]").containsMatchIn(geometryContract))
        assertFalse("corrupt fixture must lack xref", File(fixtureDirectory, "corrupt.pdf").readText().contains("xref"))
        assertTrue("password fixture must be encrypted", File(fixtureDirectory, "password-protected.pdf").readBytes().containsBytes("/Encrypt".toByteArray()))

        val reflowable = File(fixtureDirectory, "reflowable.epub")
        assertTrue("reflowable fixture must be an EPUB archive", reflowable.readBytes().startsWithBytes(byteArrayOf('P'.code.toByte(), 'K'.code.toByte())))
        ZipFile(reflowable).use { archive ->
            assertEquals("application/epub+zip", archive.getInputStream(requireNotNull(archive.getEntry("mimetype"))).bufferedReader().readText())
            assertTrue("EPUB container is missing", archive.getEntry("META-INF/container.xml") != null)
            assertTrue("EPUB package is missing", archive.getEntry("OEBPS/content.opf") != null)
        }

        val reflowableLong = File(fixtureDirectory, "reflowable-long.epub")
        ZipFile(reflowableLong).use { archive ->
            assertTrue("EPUB package is missing", archive.getEntry("OEBPS/content.opf") != null)
            val packageText = archive.getInputStream(requireNotNull(archive.getEntry("OEBPS/content.opf"))).bufferedReader().readText()
            assertTrue(
                "reflowable-long fixture must have at least three spine entries",
                Regex("<itemref\\s").findAll(packageText).count() >= 3
            )
            assertTrue("reflowable-long fixture must declare a nav document", packageText.contains("properties=\"nav\""))
            assertTrue("reflowable-long fixture's nav document is missing", archive.getEntry("OEBPS/nav.xhtml") != null)
        }

        val corruptEpub = File(fixtureDirectory, "corrupt.epub")
        assertTrue("corrupt EPUB fixture must be a zip archive", corruptEpub.readBytes().startsWithBytes(byteArrayOf('P'.code.toByte(), 'K'.code.toByte())))
        ZipFile(corruptEpub).use { archive ->
            assertTrue("corrupt EPUB fixture's container is missing", archive.getEntry("META-INF/container.xml") != null)
            assertNull("corrupt EPUB fixture must omit the package the container names", archive.getEntry("OEBPS/content.opf"))
        }

        val unsupportedText = File(fixtureDirectory, "unsupported.txt")
        val unsupportedTextBytes = unsupportedText.readBytes()
        assertFalse("unsupported text fixture must not start with a zip signature", unsupportedTextBytes.startsWithBytes(byteArrayOf('P'.code.toByte(), 'K'.code.toByte())))
        assertFalse("unsupported text fixture must not start with a PDF signature", unsupportedTextBytes.startsWithBytes("%PDF".toByteArray()))

        val layoutBoxContract = Regex("\"file\"\\s*:\\s*\"reflowable\\.epub\"(?s:.*?)\"layoutBox\"\\s*:\\s*\\{[^}]*}").find(text)?.value.orEmpty()
        assertTrue(
            "manifest's declared layout box must match ReflowLayoutBox.BOX_1",
            Regex(
                "\"widthPoints\"\\s*:\\s*${ReflowLayoutBox.BOX_1.widthPoints.toInt()},?\\s*" +
                    "\"heightPoints\"\\s*:\\s*${ReflowLayoutBox.BOX_1.heightPoints.toInt()},?\\s*" +
                    "\"emPoints\"\\s*:\\s*${ReflowLayoutBox.BOX_1.emPoints.toInt()}"
            ).containsMatchIn(layoutBoxContract)
        )
    }

    private fun ByteArray.containsBytes(needle: ByteArray): Boolean =
        needle.isNotEmpty() && indices.any { start -> start + needle.size <= size && needle.indices.all { offset -> this[start + offset] == needle[offset] } }

    private fun ByteArray.startsWithBytes(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
}
