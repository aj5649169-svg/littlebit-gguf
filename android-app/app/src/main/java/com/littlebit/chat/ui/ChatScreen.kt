package com.littlebit.chat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.littlebit.chat.data.ChatMessage
import com.littlebit.chat.engine.ThinkBlocks

private val Suggestions = listOf(
    "Explain how LLM quantization works, simply",
    "Write a haiku about the ocean",
    "Give me a 3-day Tokyo itinerary",
    "Draft a polite deadline-extension email",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    vm: ChatViewModel,
    onMenu: () -> Unit,
    onOpenModelHub: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val listState = rememberLazyListState()
    var autoScrollEnabled by remember { mutableStateOf(true) }
    var touchingList by remember { mutableStateOf(false) }
    val manualScroll = remember(listState) {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                // Track the position after actual drag/fling movement. Content
                // growth alone can move the sentinel without changing intent.
                if (consumed.y != 0f) autoScrollEnabled = !listState.canScrollForward
                return Offset.Zero
            }
        }
    }
    val totalItemCount =
        (if (vm.chatError != null) 1 else 0) +
            vm.currentMessages.size +
            (if (vm.streamActive) 1 else 0) +
            (if (vm.currentMessages.isEmpty() && !vm.streamActive) 1 else 0) + 1

    val scrolling = listState.isScrollInProgress
    LaunchedEffect(totalItemCount, vm.rawStream.length, autoScrollEnabled, touchingList, scrolling) {
        if (autoScrollEnabled && !touchingList && !listState.isScrollInProgress) {
            listState.scrollToItem(totalItemCount - 1)
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    ModelSelectorPill(
                        modelName = vm.modelName,
                        loaded = vm.isLoaded,
                        onClick = onOpenModelHub,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onMenu) {
                        Icon(Icons.Default.Menu, contentDescription = "Open menu")
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        bottomBar = { InputBar(vm) },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp)
                .nestedScroll(manualScroll)
                .pointerInput(listState) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        touchingList = true
                        try {
                            do {
                                val event = awaitPointerEvent(PointerEventPass.Final)
                            } while (event.changes.any { it.pressed })
                        } finally {
                            touchingList = false
                        }
                    }
                },
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (vm.chatError != null) {
                item(key = "chat-error") {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                vm.chatError.orEmpty(),
                                modifier = Modifier.weight(1f),
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            TextButton(onClick = vm::dismissChatError) { Text("Dismiss") }
                        }
                    }
                }
            }
            items(
                count = vm.currentMessages.size,
                key = { index -> index },
            ) { index ->
                val message = vm.currentMessages[index]
                MessageRow(
                    message = message,
                    isLast = index == vm.currentMessages.size - 1,
                    showStats = index == vm.currentMessages.size - 1 && !vm.streamActive,
                    vm = vm,
                )
            }
            if (vm.streamActive) {
                item(key = "streaming") { StreamingRow(vm) }
            }
            if (vm.currentMessages.isEmpty() && !vm.streamActive) {
                item(key = "empty") {
                    Box(Modifier.fillParentMaxSize()) {
                        EmptyState(
                            modelLoaded = vm.isLoaded,
                            onOpenModelHub = onOpenModelHub,
                            onSuggestion = vm::sendText,
                            lastModelName = vm.lastModelName,
                            onReloadLastModel = vm::reloadLastModel,
                        )
                    }
                }
            }
            item(key = "bottom_spacer") { Spacer(Modifier.height(1.dp)) }
        }
    }
}

@Composable
private fun MessageRow(
    message: ChatMessage,
    isLast: Boolean,
    showStats: Boolean,
    vm: ChatViewModel,
) {
    val isUser = message.role == "user"
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        if (isUser) {
            Text(
                text = message.content,
                modifier = Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.9f))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                color = Color.White,
                fontSize = 15.sp,
            )
        } else {
            val split = ThinkBlocks.split(message.content)
            Column(
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                SelectionContainer {
                    Column {
                        if (split.think != null) {
                            ThinkAccordion(split.think)
                        }
                        MarkdownText(split.answer.ifBlank { "…" })
                    }
                }
                if (showStats && isLast) {
                    AssistantMetaRow(vm)
                }
            }
        }
    }
}

@Composable
private fun StreamingRow(vm: ChatViewModel) {
    val split = ThinkBlocks.split(vm.rawStream)
    Column(
        modifier = Modifier
            .fillMaxWidth(0.92f)
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        if (vm.isPrefilling && vm.rawStream.isEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    if (vm.fastMode) "Reading prompt…" else "Thinking…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            if (split.think != null) ThinkAccordion(split.think)
            MarkdownText(split.answer + " ▍")
        }
    }
}

/** Collapsible "Thought process" card (ChatGPT / Gemini style). */
@Composable
private fun ThinkAccordion(think: String) {
    var expanded by remember { mutableStateOf(false) }
    Surface(
        onClick = { expanded = !expanded },
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Thought process",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    if (expanded) "Hide" else "Show",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Icon(
                    if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (expanded) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = think,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Live tokens/sec badge + copy button under a completed assistant reply. */
@Composable
private fun AssistantMetaRow(vm: ChatViewModel) {
    val clipboard = LocalClipboardManager.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 8.dp),
    ) {
        if (vm.isLoaded) {
            BadgeChip(
                text = vm.activeBackendLabel,
                color = if (vm.isUsingVulkan) MaterialTheme.colorScheme.tertiary
                    else MaterialTheme.colorScheme.secondary,
            )
        }
        BadgeChip(
            text = "%.1f t/s · %d tokens".format(vm.lastTps, vm.lastTokens),
            color = MaterialTheme.colorScheme.primary,
        )
        val last = vm.currentMessages.lastOrNull { it.role == "assistant" }
        if (last != null) {
            IconButton(
                onClick = { clipboard.setText(AnnotatedString(ThinkBlocks.strip(last.content))) },
                modifier = Modifier.size(28.dp),
            ) {
                Icon(
                    Icons.Default.ContentCopy,
                    contentDescription = "Copy reply",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmptyState(
    modelLoaded: Boolean,
    onOpenModelHub: () -> Unit,
    onSuggestion: (String) -> Unit,
    lastModelName: String?,
    onReloadLastModel: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("LittleBit Chat", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (modelLoaded) {
                "On-device AI — your messages never leave this phone."
            } else {
                "Load a model to start chatting. Everything runs offline."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        if (!modelLoaded) {
            Button(onClick = onOpenModelHub) { Text("Open Model Hub") }
            if (lastModelName != null) {
                Spacer(Modifier.height(8.dp))
                Button(onClick = onReloadLastModel) {
                    Text("↻ Reload $lastModelName", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.height(20.dp))
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (s in Suggestions) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .padding(vertical = 4.dp)
                        .clickable(enabled = modelLoaded) { onSuggestion(s) },
                ) {
                    Text(
                        text = s,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (modelLoaded) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        },
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InputBar(vm: ChatViewModel) {
    var showSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .navigationBarsPadding()
            .imePadding(),
    ) {
        if (vm.preWarming) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "Pre-warming…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            Surface(
                onClick = { vm.updateFastMode(!vm.fastMode) },
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Text(
                    if (vm.fastMode) "⚡ Fast" else "🧠 Think",
                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 5.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            IconButton(onClick = { showSheet = true }) {
                Icon(Icons.Default.Add, contentDescription = "Attachments and plugins")
            }
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.weight(1f),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextField(
                        value = vm.input,
                        onValueChange = vm::onInputChange,
                        modifier = Modifier.weight(1f),
                        placeholder = {
                            Text(
                                if (vm.isLoaded) "Message" else "Load a model to chat…",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                        maxLines = 5,
                    )
                }
            }
            Spacer(Modifier.width(6.dp))
            val canSend = vm.isLoaded && vm.input.isNotBlank() && !vm.isGenerating
            Surface(
                onClick = {
                    keyboardController?.hide()
                    focusManager.clearFocus()
                    if (vm.isGenerating) vm.stopGeneration() else if (canSend) vm.send()
                },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.alpha(if (vm.isGenerating || canSend) 1f else 0.4f),
            ) {
                Icon(
                    if (vm.isGenerating) Icons.Default.Stop else Icons.AutoMirrored.Filled.Send,
                    contentDescription = if (vm.isGenerating) "Stop generation" else "Send",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
    }

    if (showSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSheet = false },
            sheetState = sheetState,
        ) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text("Attachments & Plugins", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "These will plug into the chat bar in a future update.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                SheetRow("Attach files", "Summarize PDFs, docs and images")
                SheetRow("Web search", "Ground answers with live sources")
                SheetRow("Reminders", "Create on-device reminders from chat")
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun SheetRow(title: String, subtitle: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        BadgeChip(text = "Coming Soon", color = MaterialTheme.colorScheme.tertiary)
    }
}
