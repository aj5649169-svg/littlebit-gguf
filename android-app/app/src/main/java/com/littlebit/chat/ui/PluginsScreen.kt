package com.littlebit.chat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.littlebit.chat.plugins.PluginRegistry

private fun pluginIcon(id: String): ImageVector = when (id) {
    "web-search" -> Icons.Default.Search
    "calculator" -> Icons.Default.Build
    "file-reader" -> Icons.Default.Description
    else -> Icons.Default.Notifications
}

/**
 * Plugins & Connectors screen. Step 2 announces the connector surface only:
 * the descriptors come from [PluginRegistry] and each card carries a
 * "Coming Soon" badge so real tools can be wired later without refactoring.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginsScreen(vm: ChatViewModel, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Plugins & Connectors") },
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
            for (plugin in PluginRegistry.plugins) {
                item(key = plugin.id) {
                    Card(shape = RoundedCornerShape(16.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                        ) {
                            Icon(
                                pluginIcon(plugin.id),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Column(
                                Modifier
                                    .weight(1f)
                                    .padding(horizontal = 12.dp),
                            ) {
                                Text(plugin.name, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    plugin.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (plugin.isEnabled) {
                                val calc = plugin.id == "calculator"
                                androidx.compose.material3.Switch(
                                    checked = if (calc) vm.settings.calculator else vm.settings.clock,
                                    onCheckedChange = {
                                        if (calc) vm.settings.calculator = it else vm.settings.clock = it
                                        vm.invalidateDraft()
                                    })
                            } else BadgeChip(text = "Coming Soon", color = MaterialTheme.colorScheme.tertiary)
                        }
                    }
                }
            }
        }
    }
}
