package com.pocketapkstudio

import java.io.File
import java.util.zip.ZipFile

/**
 * Performs a structural check before the build callback reports an APK as ready.
 *
 * This verifies that the file is a readable ZIP archive containing a non-empty
 * root AndroidManifest.xml entry. It does not replace Android PackageManager's
 * full package/signature validation during installation.
 */
internal object ApkFileValidator {
    fun isValidApk(file: File): Boolean {
        if (!file.isFile || file.length() <= 0L) return false
        return try {
            ZipFile(file).use { archive ->
                val manifest = archive.getEntry("AndroidManifest.xml")
                manifest != null &&
                    !manifest.isDirectory &&
                    manifest.size > 0L &&
                    archive.getInputStream(manifest).use { it.read() >= 0 }
            }
        } catch (_: Exception) {
            false
        }
    }
}
