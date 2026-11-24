package com.littlebit.chat.data

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * Copies a model picked via the Storage Access Framework into app-internal
 * storage so the native engine can mmap it without holding a content-resolver
 * FD open for the whole session.
 */
object ModelFiles {

    fun copyModelToAppStorage(context: Context, uri: Uri): File {
        val dir = File(context.filesDir, "models").apply { mkdirs() }
        val raw = uri.lastPathSegment ?: "model.gguf"
        var name = raw.substringAfterLast('/').substringAfterLast(':')
        if (!name.endsWith(".gguf", ignoreCase = true)) name = "$name.gguf"
        val dest = File(dir, name)
        context.contentResolver.openInputStream(uri)!!.use { input ->
            dest.outputStream().use { input.copyTo(it) }
        }
        return dest
    }
}
