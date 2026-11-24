package com.littlebit.chat

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.littlebit.chat.engine.LlamaEngine
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Step 1 engine test screen — proves the native JNI engine end-to-end
 * (load GGUF via SAF, template chat, stream tokens, stop, t/s).
 * The full chat UI, model hub, and plugin screens land in Step 2.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LittleBitTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    EngineScreen(engine = remember { LlamaEngine() })
                }
            }
        }
    }
}

private val Accent = Color(0xFF2DD4A7)
private val InkDark = Color(0xFF101418)

private val DarkColors = darkColorScheme(
    primary = Accent,
    secondary = Color(0xFF6EE7C8),
    surface = Color(0xFF14181D),
    background = InkDark,
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF0E9F80),
    secondary = Color(0xFF0B7C66),
    surface = Color(0xFFF4F7F5),
    background = Color(0xFFFCFDFC),
)

@Composable
fun LittleBitTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}

private data class ChatMessage(val role: String, val content: String)

@Composable
fun EngineScreen(engine: LlamaEngine) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var modelInfo by remember { mutableStateOf<LlamaEngine.ModelInfo?>(null) }
    var status by remember { mutableStateOf("Load a .gguf model to begin") }
    var loadProgress by remember { mutableFloatStateOf(-1f) }
    val messages = remember { mutableStateListOf<ChatMessage>() }
    var streaming by remember { mutableStateOf("") }
    var generating by remember { mutableStateOf(false) }
    var lastTps by remember { mutableDoubleStateOf(0.0) }
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    val ram = remember { deviceRam(context) }

    LaunchedEffect(messages.size, streaming.length) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    val pickModel = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            status = "Copying model into app storage…"
            loadProgress = 0f
            try {
                val dest = copyModelToAppStorage(context, uri)
                status = "Loading ${dest.name}…"
                val info = engine.loadModel(
                    path = dest.absolutePath,
                    contextSize = 4096,
                    threads = 4,
                    onProgress = { p -> loadProgress = p },
                )
                modelInfo = info
                status = "Ready — ${info.description} · " +
                    "${"%.1f".format(info.sizeBytes / 1e9)} GB · ${info.fileType}"
            } catch (t: Throwable) {
                status = "Load failed: ${t.message ?: t.javaClass.simpleName}"
            }
            loadProgress = -1f
        }
    }

    fun send() {
        val text = input.trim()
        if (text.isEmpty() || generating || !engine.isLoaded) return
        messages.add(ChatMessage("user", text))
        input = ""
        streaming = ""
        val history = messages.map { it.role to it.content }
        scope.launch(Dispatchers.Default) {
            generating = true
            try {
                val prompt = engine.formatChat(
                    "You are LittleBit Chat, a helpful on-device assistant. Be concise.",
                    history,
                )
                val result = engine.generate(prompt) { piece -> streaming += piece }
                lastTps = result.tokensPerSec
                messages.add(ChatMessage("assistant", streaming.ifBlank { result.output }))
            } catch (t: Throwable) {
                messages.add(ChatMessage("assistant", "Error: ${t.message ?: t.javaClass.simpleName}"))
            }
            streaming = ""
            generating = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        // Header
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text("LittleBit Chat", style = MaterialTheme.typography.titleLarge)
            Text(
                "RAM ${"%.1f".format(ram.avail / 1e9)} GB free of " +
                    "${"%.1f".format(ram.total / 1e9)} GB",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (loadProgress in 0f..1f) {
                LinearProgressIndicator(
                    progress = { loadProgress.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                )
            }
        }

        // Messages
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(messages.size) { i ->
                MessageBubble(messages[i])
            }
            if (streaming.isNotEmpty()) {
                item {
                    MessageBubble(ChatMessage("assistant", streaming), streaming = true)
                }
            }
            if (generating && streaming.isEmpty()) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(4.dp),
                            strokeWidth = 2.dp,
                        )
                        Text("Thinking…", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        // Input row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { pickModel.launch(arrayOf("*/*")) }) {
                Icon(Icons.Default.FolderOpen, contentDescription = "Load .gguf model")
            }
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Message") },
                enabled = engine.isLoaded && !generating,
                maxLines = 4,
            )
            if (generating) {
                IconButton(onClick = { engine.stop() }) {
                    Icon(Icons.Default.Stop, contentDescription = "Stop generation")
                }
            } else {
                IconButton(
                    onClick = { send() },
                    enabled = engine.isLoaded && input.isNotBlank(),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage, streaming: Boolean = false) {
    val isUser = message.role == "user"
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        SelectionContainer {
            Text(
                text = message.content + if (streaming) " ▍" else "",
                modifier = Modifier
                    .background(
                        color = if (isUser) {
                            scheme.primary.copy(alpha = 0.85f)
                        } else {
                            scheme.surfaceVariant
                        },
                        shape = RoundedCornerShape(16.dp),
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp)
                    .fillMaxWidth(0.85f),
                color = if (isUser) Color.White else scheme.onSurface,
                fontSize = 15.sp,
            )
        }
    }
}

private data class RamInfo(val total: Long, val avail: Long)

private fun deviceRam(context: Context): RamInfo {
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val info = ActivityManager.MemoryInfo()
    am.getMemoryInfo(info)
    return RamInfo(info.totalMem, info.availMem)
}

private fun copyModelToAppStorage(context: Context, uri: Uri): File {
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
