package com.littlebit.chat.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.littlebit.chat.data.ChatSession
import com.littlebit.chat.ui.theme.LocalHapticsEnabled
import java.io.File
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

data class Attachment(val name: String, val text: String, val truncated: Boolean) {
    val prompt get() = "Attached file: $name\n\n$text" + if (truncated) "\n[File truncated at 12 KiB]" else ""
}

fun readAttachment(context: Context, uri: Uri): Attachment {
    val resolver = context.contentResolver
    val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0) else null
    } ?: error("Could not read the file name.")
    require(name.substringAfterLast('.').lowercase() in setOf("txt","md","json","csv","kt","py","cpp","log")) {
        "Choose a .txt, .md, .json, .csv, .kt, .py, .cpp or .log file."
    }
    val limit = 12 * 1024
    val bytes = ByteArray(limit + 1)
    var count = 0
    requireNotNull(resolver.openInputStream(uri)).use { stream ->
        while (count < bytes.size) {
            val n = stream.read(bytes, count, bytes.size - count)
            if (n <= 0) break
            count += n
        }
    }
    val chars = CharBuffer.allocate(limit)
    val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
    decoder.decode(ByteBuffer.wrap(bytes, 0, minOf(limit, count)), chars, count <= limit).throwIfError()
    chars.flip()
    return Attachment(name, chars.toString(), count > limit)
}

private fun java.nio.charset.CoderResult.throwIfError() { if (isError) throwException() }

@Composable
fun hapticAction(): () -> Unit {
    val feedback = LocalHapticFeedback.current
    val enabled = LocalHapticsEnabled.current
    return { if (enabled) feedback.performHapticFeedback(HapticFeedbackType.LongPress) }
}

private fun shareMarkdown(context: Context, session: ChatSession) {
    val dir = File(context.cacheDir, "exports/${session.id}").apply { mkdirs() }
    val title = session.title.replace(Regex("[^\\p{L}\\p{N} _-]"), "").take(64).ifBlank { "Chat" }
    val file = File(dir, "$title.md")
    file.writeText("# ${session.title}\n\n" + session.messages.joinToString("\n\n") {
        "## ${it.role.replaceFirstChar(Char::uppercase)}\n\n${it.content}"
    })
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.exports", file)
    val intent = Intent(Intent.ACTION_SEND).setType("text/markdown")
        .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    intent.clipData = ClipData.newRawUri(file.name, uri)
    context.startActivity(Intent.createChooser(intent, "Export chat"))
}

@Composable
fun HistoryMenu(vm: ChatViewModel, session: ChatSession) {
    var open by remember(session.id) { mutableStateOf(false) }
    var action by remember(session.id) { mutableStateOf("") }
    var name by remember(session.id, open) { mutableStateOf(session.title) }
    val context = LocalContext.current
    Box {
        IconButton(onClick = { open = true }) { Text("⋮", style = MaterialTheme.typography.titleLarge) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (item in listOf(if (session.pinned) "Unpin" else "Pin", "Rename", "Export Markdown", "Delete")) {
                DropdownMenuItem(text = { Text(item) }, enabled = !vm.busy, onClick = {
                    open = false
                    when (item) {
                        "Pin", "Unpin" -> vm.pin(session.id)
                        "Export Markdown" -> runCatching { shareMarkdown(context, session) }.onFailure {
                            android.widget.Toast.makeText(context, it.message, android.widget.Toast.LENGTH_LONG).show()
                        }
                        else -> action = item
                    }
                })
            }
        }
    }
    if (action.isNotEmpty()) AlertDialog(onDismissRequest = { action = "" },
        title = { Text(if (action == "Rename") "Rename chat" else "Delete this chat?") },
        text = { if (action == "Rename") OutlinedTextField(name, { name = it }, singleLine = true) else Text(session.title) },
        confirmButton = { TextButton(onClick = {
            if (action == "Rename") vm.rename(session.id, name) else vm.deleteChat(session.id)
            action = ""
        }) { Text(action) } }, dismissButton = { TextButton(onClick = { action = "" }) { Text("Cancel") } })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageActions(vm: ChatViewModel, index: Int, close: () -> Unit) {
    val message = vm.currentMessages.getOrNull(index) ?: return
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val haptic = hapticAction()
    var editing by remember { mutableStateOf(false) }
    var content by remember { mutableStateOf(message.content) }
    if (editing) {
        AlertDialog(onDismissRequest = close, title = { Text("Edit & resend") },
            text = { OutlinedTextField(content, { content = it }, maxLines = 8) },
            confirmButton = { TextButton(enabled = content.isNotBlank(), onClick = { close(); vm.resend(index, content) }) { Text("Resend") } },
            dismissButton = { TextButton(onClick = close) { Text("Cancel") } })
        return
    }
    ModalBottomSheet(onDismissRequest = close) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            TextButton(onClick = { haptic(); clipboard.setText(AnnotatedString(message.content)); close() }) { Text("Copy full message") }
            if (message.role == "user") {
                TextButton(enabled = vm.isLoaded && !vm.busy, onClick = { editing = true }) { Text("Edit & resend") }
            } else {
                if (index == vm.currentMessages.lastIndex) {
                    TextButton(enabled = vm.isLoaded && !vm.busy, onClick = { close(); vm.regenerate(index) }) { Text("Regenerate") }
                    if (message.cutOff) TextButton(enabled = vm.isLoaded && !vm.busy,
                        onClick = { close(); vm.continueReply(index) }) { Text("Continue") }
                }
                TextButton(onClick = {
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, message.content), "Share reply")); close()
                }) { Text("Share reply") }
            }
            TextButton(enabled = !vm.busy, onClick = { close(); vm.removeMessage(index) }) { Text("Delete message") }
        }
    }
}
