package com.pocketapkstudio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ApkFileValidatorTest {
    private fun createArchive(file: File, entries: Map<String, ByteArray>) {
        ZipOutputStream(file.outputStream()).use { output ->
            entries.forEach { (name, bytes) ->
                output.putNextEntry(ZipEntry(name))
                output.write(bytes)
                output.closeEntry()
            }
        }
    }

    @Test
    fun acceptsArchiveWithNonEmptyRootAndroidManifest() {
        val directory = Files.createTempDirectory("apk-validator").toFile()
        try {
            val apk = File(directory, "valid.apk")
            createArchive(
                apk,
                mapOf(
                    "AndroidManifest.xml" to byteArrayOf(0x03, 0x00, 0x08, 0x00),
                    "classes.dex" to byteArrayOf(0x64, 0x65, 0x78)
                )
            )
            assertTrue(ApkFileValidator.isValidApk(apk))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun rejectsZipWithoutRootAndroidManifest() {
        val directory = Files.createTempDirectory("apk-validator").toFile()
        try {
            val apk = File(directory, "missing-manifest.apk")
            createArchive(apk, mapOf("classes.dex" to byteArrayOf(0x64, 0x65, 0x78)))
            assertFalse(ApkFileValidator.isValidApk(apk))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun rejectsEmptyAndroidManifest() {
        val directory = Files.createTempDirectory("apk-validator").toFile()
        try {
            val apk = File(directory, "empty-manifest.apk")
            createArchive(apk, mapOf("AndroidManifest.xml" to byteArrayOf()))
            assertFalse(ApkFileValidator.isValidApk(apk))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun rejectsNonZipAndEmptyFiles() {
        val directory = Files.createTempDirectory("apk-validator").toFile()
        try {
            val text = File(directory, "text.apk").apply { writeText("not an APK") }
            val empty = File(directory, "empty.apk").apply { createNewFile() }
            assertFalse(ApkFileValidator.isValidApk(text))
            assertFalse(ApkFileValidator.isValidApk(empty))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun rejectsMissingFile() {
        val directory = Files.createTempDirectory("apk-validator").toFile()
        try {
            assertFalse(ApkFileValidator.isValidApk(File(directory, "missing.apk")))
        } finally {
            directory.deleteRecursively()
        }
    }
}
