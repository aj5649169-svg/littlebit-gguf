package com.littlebit.chat.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.littlebit.chat.data.ModelEntry
import com.littlebit.chat.data.ModelsCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val HeavyRamColor = Color(0xFFE2A336)
private val RecommendedColor = Color(0xFF35B37E)
private val ExperimentalColor = Color(0xFF7C8CF8)

/**
 * Model Hub: grouped catalog from assets/models.json. Downloads are handed to
 * Chrome via ACTION_VIEW (no in-app downloader, no INTERNET permission); local
 * .gguf files are loaded through the Storage Access Framework.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelHubScreen(vm: ChatViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    var models by remember { mutableStateOf<List<ModelEntry>?>(null) }
    var confirmHeavy by remember { mutableStateOf<ModelEntry?>(null) }

    LaunchedEffect(Unit) {
        models = withContext(Dispatchers.IO) { ModelsCatalog.load(context) }
    }

    val pickModel = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(vm::loadModelFromUri) }

    fun openInChrome(url: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            if (it is ActivityNotFoundException) {
                runCatching {
                    context.startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_VIEW, Uri.parse(url)),
                            "Open with",
                        ),
                    )
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Model Hub") },
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
            item(key = "local") {
                Column {
                    Button(
                        onClick = { pickModel.launch(arrayOf("*/*")) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = vm.loadProgress < 0f,
                    ) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null)
                        Spacer(Modifier.padding(4.dp))
                        Text("Load Local .gguf")
                    }
                    Text(
                        "Downloads open in Chrome — grab the file there, then load it " +
                            "from your Downloads folder with the button above.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    vm.loadStatus?.let { status ->
                        Text(
                            status,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                    if (vm.loadProgress in 0f..1f) {
                        LinearProgressIndicator(
                            progress = { vm.loadProgress.coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                        )
                    }
                }
            }

            if (vm.modelName != null) {
                item(key = "loaded") { LoadedModelCard(vm) }
            }

            val catalog = models
            if (catalog == null) {
                item(key = "loading") {
                    Text(
                        "Loading catalog…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                for (tier in ModelsCatalog.TIER_ORDER) {
                    val group = catalog.filter { it.tier == tier }
                    if (group.isEmpty()) continue
                    item(key = "tier-$tier") {
                        Column {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                group.first().tierLabel,
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                group.first().ramNote,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    for (entry in group) {
                        item(key = entry.id) {
                            ModelCard(
                                entry = entry,
                                onDownload = {
                                    if (entry.ramWarning == "heavy") {
                                        confirmHeavy = entry
                                    } else {
                                        openInChrome(entry.url)
                                    }
                                },
                            )
                        }
                    }
                }
                item(key = "footer") { Spacer(Modifier.height(16.dp)) }
            }
        }
    }

    confirmHeavy?.let { entry ->
        AlertDialog(
            onDismissRequest = { confirmHeavy = null },
            title = { Text("12 GB+ RAM required") },
            text = {
                Text(
                    "${entry.name} (${"%.1f".format(entry.sizeGB)} GB) is a heavyweight model. " +
                        "On phones with less RAM it may fail to load or force-close other apps. Continue?",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    openInChrome(entry.url)
                    confirmHeavy = null
                }) { Text("Download anyway") }
            },
            dismissButton = {
                TextButton(onClick = { confirmHeavy = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun LoadedModelCard(vm: ChatViewModel) {
    Card(shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Loaded model", style = MaterialTheme.typography.labelMedium)
                    Text(
                        vm.modelName ?: "",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    vm.modelInfo?.let { info ->
                        Text(
                            "${"%.1f".format(info.sizeBytes / 1e9)} GB · ${info.fileType}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                TextButton(onClick = vm::unloadModel) { Text("Unload") }
            }
        }
    }
}

@Composable
private fun ModelCard(entry: ModelEntry, onDownload: () -> Unit) {
    Card(shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(entry.name, style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${entry.quant} · %.2f GB · %s".format(entry.sizeGB, entry.ramNote),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onDownload) { Text("Download") }
            }
            if (entry.recommended) {
                BadgeChip(
                    text = "Fast & Cool (Recommended)",
                    color = RecommendedColor,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Text(
                    "~2.5 GB models run ~2× faster and delay thermal throttling on phones.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            if (entry.ramWarning == "heavy") {
                BadgeChip(
                    text = "12 GB+ RAM Required",
                    color = HeavyRamColor,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            if (entry.experimental) {
                BadgeChip(
                    text = "Experimental",
                    color = ExperimentalColor,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}
