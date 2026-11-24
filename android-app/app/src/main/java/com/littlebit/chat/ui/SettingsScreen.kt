package com.littlebit.chat.ui

import android.app.ActivityManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.style.TextOverflow
import java.io.File
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.littlebit.chat.data.SettingsStore
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

private data class RamInfo(val total: Long, val avail: Long)

private fun queryRam(context: Context): RamInfo {
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val info = ActivityManager.MemoryInfo()
    am.getMemoryInfo(info)
    return RamInfo(info.totalMem, info.availMem)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: ChatViewModel, onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val settings = vm.settings
    val crashFile = remember { File(context.filesDir, "last_crash.txt") }
    var crashLog by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        crashLog = runCatching { crashFile.takeIf { it.isFile }?.readText() }.getOrNull()
    }

    var totalRam by remember { mutableLongStateOf(0L) }
    var availRam by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            val ram = queryRam(context)
            totalRam = ram.total
            availRam = ram.avail
            delay(2000)
        }
    }

    var threads by remember { mutableFloatStateOf(settings.threads.toFloat()) }
    var ctxSize by remember { mutableFloatStateOf(settings.contextSize.toFloat()) }
    var temperature by remember { mutableFloatStateOf(settings.temperature.toFloat()) }
    var repeatPenalty by remember { mutableFloatStateOf(settings.repeatPenalty.toFloat()) }
    var dry by remember { mutableFloatStateOf(settings.dryMultiplier.toFloat()) }
    var systemPrompt by remember { mutableStateOf(settings.systemPrompt) }
    var vulkanEnabled by remember { mutableStateOf(settings.vulkanOffload) }
    var gpuLayers by remember { mutableFloatStateOf(settings.vulkanGpuLayers.toFloat()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "ram") { RamBanner(totalRam, availRam) }
            if (crashLog != null) {
                item(key = "last-crash-log") {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = androidx.compose.material3.CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                        ),
                    ) {
                        Column(Modifier.fillMaxWidth().padding(14.dp)) {
                            Text("Last crash log", style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer)
                            Text(crashLog.orEmpty(), modifier = Modifier.padding(top = 8.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                maxLines = 14, overflow = TextOverflow.Ellipsis)
                            TextButton(
                                onClick = { crashFile.delete(); crashLog = null },
                                modifier = Modifier.align(Alignment.End),
                            ) { Text("Dismiss and delete") }
                        }
                    }
                }
            }
            item(key = "inference-header") {
                Text("Inference", style = MaterialTheme.typography.titleMedium)
            }
            item(key = "threads") {
                SliderRow(
                    label = "CPU Threads",
                    value = threads,
                    range = 2f..8f,
                    steps = 5,
                    display = threads.roundToInt().toString(),
                    onChange = { threads = it },
                    onChangeFinished = { vm.onThreadsChanged(threads.roundToInt()) },
                )
            }
            item(key = "ctx") {
                SliderRow(
                    label = "Context Size",
                    value = ctxSize,
                    range = 1024f..8192f,
                    steps = 27,
                    display = ctxSize.roundToInt().toString(),
                    onChange = { ctxSize = it },
                    onChangeFinished = {
                        settings.contextSize = (ctxSize.roundToInt() / 256) * 256
                        ctxSize = settings.contextSize.toFloat()
                    },
                )
            }
            item(key = "temp") {
                SliderRow(
                    label = "Temperature",
                    value = temperature,
                    range = 0f..2f,
                    steps = 39,
                    display = "%.2f".format(temperature),
                    onChange = { temperature = it },
                    onChangeFinished = { settings.temperature = temperature.toDouble() },
                )
            }
            item(key = "rep") {
                SliderRow(
                    label = "Repeat Penalty",
                    value = repeatPenalty,
                    range = 1f..2f,
                    steps = 49,
                    display = "%.2f".format(repeatPenalty),
                    onChange = { repeatPenalty = it },
                    onChangeFinished = { settings.repeatPenalty = repeatPenalty.toDouble() },
                )
            }
            item(key = "dry") {
                SliderRow(
                    label = "DRY Multiplier",
                    value = dry,
                    range = 0f..2f,
                    steps = 39,
                    display = "%.2f".format(dry),
                    onChange = { dry = it },
                    onChangeFinished = { settings.dryMultiplier = dry.toDouble() },
                )
            }
            item(key = "sysprompt") {
                Column {
                    Text("System Prompt", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = systemPrompt,
                        onValueChange = {
                            systemPrompt = it
                            settings.systemPrompt = it
                        },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                    )
                }
            }
            item(key = "features-header") {
                Text("Features", style = MaterialTheme.typography.titleMedium)
            }
            item(key = "instant") {
                SwitchRow(
                    title = "Instant Reply (Background Pre-Warm)",
                    subtitle = "Drafts your reply while you type — shows a “Pre-warming…” dot in the chat bar.",
                    checked = vm.instantReply,
                    onCheckedChange = vm::updateInstantReply,
                )
            }
            item(key = "fastmode") {
                SwitchRow(
                    title = "Fast Mode by default",
                    subtitle = "Skip the reasoning chain (/no_think) — faster answers, less heat.",
                    checked = vm.fastMode,
                    onCheckedChange = vm::updateFastMode,
                )
            }
            item(key = "vulkan") {
                SwitchRow(
                    title = "Vulkan GPU Offload",
                    subtitle = "Experimental GPU inference. Off by default; CPU uses KleidiAI.",
                    checked = vulkanEnabled,
                    onCheckedChange = {
                        vulkanEnabled = it
                        settings.vulkanOffload = it
                    },
                )
            }
            if (vulkanEnabled) {
                item(key = "vulkan-layers") {
                    Column {
                        SliderRow(
                            label = "GPU Layers (n_gpu_layers)",
                            value = gpuLayers,
                            range = 0f..99f,
                            steps = 98,
                            display = gpuLayers.roundToInt().toString(),
                            onChange = { gpuLayers = it },
                            onChangeFinished = {
                                settings.vulkanGpuLayers = gpuLayers.roundToInt()
                                gpuLayers = settings.vulkanGpuLayers.toFloat()
                            },
                        )
                        Text(
                            "Reload model after changing GPU offload. On Mali GPUs, compare t/s against CPU KleidiAI.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }
            item(key = "footer") { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun RamBanner(total: Long, avail: Long) {
    Card(shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text("Device RAM", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(4.dp))
            if (total > 0) {
                Text(
                    "%.1f GB free of %.1f GB".format(avail / 1e9, total / 1e9),
                    style = MaterialTheme.typography.titleMedium,
                )
                LinearProgressIndicator(
                    progress = { (avail.toFloat() / total).coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                )
                Text(
                    when {
                        total >= 12L * 1024 * 1024 * 1024 ->
                            "All tiers are safe on this device, including Heavyweight 12B–14B models."
                        total >= 8L * 1024 * 1024 * 1024 ->
                            "Ultra-Light, Balanced and High Quality (8B) tiers are safe. Avoid Heavyweight models."
                        else ->
                            "Stick to Ultra-Light (0.6B–1.7B) models — and close background apps before loading."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text("Reading memory info…", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    display: String,
    onChange: (Float) -> Unit,
    onChangeFinished: () -> Unit,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(display, style = MaterialTheme.typography.labelLarge)
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
            onValueChangeFinished = onChangeFinished,
        )
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    Card(shape = RoundedCornerShape(16.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 6.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
        }
    }
}
