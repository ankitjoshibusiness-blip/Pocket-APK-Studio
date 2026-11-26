package com.pocketapkstudio

import java.io.File

/**
 * Locates source Android drawable/mipmap resources for the approximate Compose image preview.
 * It deliberately returns candidate files only; Android drawable inflation is performed on-device.
 */
internal object PreviewImageResourceFinder {
    private val supportedExtensions = setOf("xml", "png", "jpg", "jpeg", "webp")
    private val ignoredPathSegments = setOf(
        ".gradle", ".git", "build", "node_modules", ".idea", "generated", ".dart_tool", ".kotlin"
    )
    private val resourceNamePattern = Regex("[A-Za-z_][A-Za-z0-9_]*")

    fun findResources(projectRoot: File, resourceName: String): List<File> {
        if (!resourceNamePattern.matches(resourceName)) return emptyList()
        val root = runCatching { projectRoot.canonicalFile }.getOrNull() ?: return emptyList()
        if (!root.isDirectory) return emptyList()
        val rootPrefix = root.path.trimEnd(File.separatorChar) + File.separator

        return root.walkTopDown().maxDepth(16)
            .filter { candidate ->
                if (!candidate.isFile ||
                    !candidate.nameWithoutExtension.equals(resourceName, ignoreCase = false) ||
                    candidate.extension.lowercase() !in supportedExtensions
                ) {
                    false
                } else {
                    val canonical = runCatching { candidate.canonicalFile }.getOrNull()
                        ?: return@filter false
                    if (!canonical.path.startsWith(rootPrefix)) return@filter false

                    val relativeParts = canonical.relativeTo(root).invariantSeparatorsPath.split('/')
                    if (relativeParts.any { it in ignoredPathSegments }) return@filter false

                    val resourceFolder = canonical.parentFile?.name.orEmpty()
                    if (!resourceFolder.startsWith("drawable") &&
                        !resourceFolder.startsWith("mipmap")
                    ) {
                        return@filter false
                    }

                    var ancestor = canonical.parentFile
                    var insideRes = false
                    while (ancestor != null && ancestor.path.startsWith(rootPrefix)) {
                        if (ancestor.name == "res") {
                            insideRes = true
                            break
                        }
                        ancestor = ancestor.parentFile
                    }
                    insideRes
                }
            }
            .sortedWith(
                compareBy<File>(
                    { if (it.extension.equals("xml", ignoreCase = true)) 0 else 1 },
                    { if (it.parentFile?.name in setOf("drawable", "mipmap")) 0 else 1 },
                    { it.relativeTo(root).invariantSeparatorsPath }
                )
            )
            .toList()
    }
}
