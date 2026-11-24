package com.littlebit.chat.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.IOException

/**
 * Imports SAF-selected GGUF files into app-private storage. Keeping the file
 * in filesDir/models makes it persistent across app launches without asking
 * the user to pick or copy a multi-gigabyte model again.
 */
object ModelFiles {

    fun copyModelToAppStorage(
        context: Context,
        uri: Uri,
        onProgress: (copiedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): File {
        val metadata = queryDocumentMetadata(context, uri)
        val fallbackName = uri.lastPathSegment
            ?.substringAfterLast('/')
            ?.substringAfterLast(':')
            ?.takeIf { it.isNotBlank() }
            ?: "model.gguf"
        val displayName = metadata.first?.takeIf { it.isNotBlank() } ?: fallbackName
        val fileName = displayName
            .replace('/', '_')
            .replace('\\', '_')
            .replace(Regex("[\\p{Cntrl}]"), "_")
            .trim()
            .ifBlank { "model.gguf" }

        require(fileName.endsWith(".gguf", ignoreCase = true)) {
            "Selected file is not a .gguf model: $fileName"
        }

        val modelsDir = File(context.filesDir, "models")
        if (!modelsDir.exists() && !modelsDir.mkdirs()) {
            throw IOException("Could not create the app models directory.")
        }

        val destination = File(modelsDir, fileName)
        val temporary = File(modelsDir, "$fileName.importing")
        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: throw IOException("The selected document could not be opened.")
            input.use { source ->
                temporary.outputStream().buffered().use { output ->
                    val totalBytes = metadata.second
                    var copiedBytes = 0L
                    var lastReportedBytes = 0L
                    val buffer = ByteArray(1024 * 1024)
                    onProgress(0L, totalBytes)
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        copiedBytes += count
                        if (
                            copiedBytes - lastReportedBytes >= 8L * 1024L * 1024L ||
                            (totalBytes > 0L && copiedBytes >= totalBytes)
                        ) {
                            onProgress(copiedBytes, totalBytes)
                            lastReportedBytes = copiedBytes
                        }
                    }
                    output.flush()
                    onProgress(copiedBytes, totalBytes)
                }
            }

            if (destination.exists() && !destination.delete()) {
                throw IOException("Could not replace the existing " + destination.name + " file.")
            }
            if (!temporary.renameTo(destination)) {
                temporary.copyTo(destination, overwrite = true)
                temporary.delete()
            }
            return destination
        } catch (t: Throwable) {
            temporary.delete()
            throw t
        }
    }

    fun listLocalModels(context: Context): List<File> {
        val modelsDir = File(context.filesDir, "models")
        return modelsDir.listFiles()
            ?.filter { it.isFile && it.extension.equals("gguf", ignoreCase = true) }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()
    }

    fun deleteLocalModel(context: Context, file: File): Boolean {
        val modelsDir = File(context.filesDir, "models").canonicalFile
        val target = file.canonicalFile
        if (target.parentFile != modelsDir) return false
        return !target.exists() || target.delete()
    }

    /**
     * Returns (display name, size). DISPLAY_NAME is the provider's real name;
     * URI path segments often contain opaque document IDs such as "28946".
     */
    private fun queryDocumentMetadata(context: Context, uri: Uri): Pair<String?, Long> {
        return runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) {
                    null to -1L
                } else {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    val name = if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
                        cursor.getString(nameIndex)
                    } else {
                        null
                    }
                    val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                        cursor.getLong(sizeIndex)
                    } else {
                        -1L
                    }
                    name to size
                }
            } ?: (null to -1L)
        }.getOrDefault(null to -1L)
    }
}
