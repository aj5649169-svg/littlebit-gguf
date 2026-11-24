package com.littlebit.chat

import android.os.Bundle
import android.os.Process
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import kotlin.system.exitProcess
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.littlebit.chat.data.ChatSession
import com.littlebit.chat.ui.ChatScreen
import com.littlebit.chat.ui.ChatViewModel
import com.littlebit.chat.ui.ModelHubScreen
import com.littlebit.chat.ui.PluginsScreen
import com.littlebit.chat.ui.SettingsScreen
import com.littlebit.chat.ui.theme.LittleBitTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * LittleBit Chat — Step 2 shell: modal navigation drawer + chat / model hub /
 * plugins / settings screens, all sharing one [ChatViewModel].
 */
class MainActivity : ComponentActivity() {
    private fun installCrashLogHandler() {
        val appContext = applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val trace = StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }.toString()
                File(appContext.filesDir, "last_crash.txt").writeText(
                    "Thread: ${thread.name}\\nTime: ${System.currentTimeMillis()}\\n\\n$trace"
                )
            }
            if (previous != null) previous.uncaughtException(thread, throwable)
            else {
                Process.killProcess(Process.myPid())
                exitProcess(10)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installCrashLogHandler()
        setContent {
            LittleBitTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    RootScaffold()
                }
            }
        }
    }
}

private enum class AppScreen { CHAT, MODEL_HUB, PLUGINS, SETTINGS }

@Composable
private fun RootScaffold(vm: ChatViewModel = viewModel()) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val hideIme: () -> Unit = {
        keyboardController?.hide()
        focusManager.clearFocus()
    }
    var screen by remember { mutableStateOf(AppScreen.CHAT) }

    // A successful native model load always returns the user to the shared Chat screen.
    LaunchedEffect(vm.modelLoadSuccessVersion) {
        if (vm.modelLoadSuccessVersion > 0) {
            screen = AppScreen.CHAT
        }
    }

    val closeDrawer: () -> Unit = { scope.launch { drawerState.close() } }

    BackHandler(enabled = drawerState.isOpen) { closeDrawer() }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                DrawerContent(
                    vm = vm,
                    onNewChat = {
                        vm.createChat()
                        screen = AppScreen.CHAT
                        closeDrawer()
                    },
                    onOpenChat = { id ->
                        vm.switchChat(id)
                        screen = AppScreen.CHAT
                        closeDrawer()
                    },
                    onDeleteChat = vm::deleteChat,
                    onOpenModelHub = {
                        hideIme()
                        screen = AppScreen.MODEL_HUB
                        closeDrawer()
                    },
                    onOpenPlugins = {
                        hideIme()
                        screen = AppScreen.PLUGINS
                        closeDrawer()
                    },
                    onOpenSettings = {
                        hideIme()
                        screen = AppScreen.SETTINGS
                        closeDrawer()
                    },
                )
            }
        },
    ) {
        when (screen) {
            AppScreen.CHAT -> ChatScreen(
                vm = vm,
                onMenu = { hideIme(); scope.launch { drawerState.open() } },
                onOpenModelHub = { hideIme(); screen = AppScreen.MODEL_HUB },
                onOpenSettings = { hideIme(); screen = AppScreen.SETTINGS },
            )
            AppScreen.MODEL_HUB -> ModelHubScreen(vm = vm, onBack = { hideIme(); screen = AppScreen.CHAT })
            AppScreen.PLUGINS -> PluginsScreen(onBack = { hideIme(); screen = AppScreen.CHAT })
            AppScreen.SETTINGS -> SettingsScreen(vm = vm, onBack = { hideIme(); screen = AppScreen.CHAT })
        }
    }
}

@Composable
private fun DrawerContent(
    vm: ChatViewModel,
    onNewChat: () -> Unit,
    onOpenChat: (String) -> Unit,
    onDeleteChat: (String) -> Unit,
    onOpenModelHub: () -> Unit,
    onOpenPlugins: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
        Text("LittleBit Chat", style = MaterialTheme.typography.titleLarge)
        Text(
            vm.modelName ?: "No model loaded",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(10.dp))

        Surface(
            onClick = onNewChat,
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "New Chat",
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.titleSmall,
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        Text(
            "Chat History",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))

        LazyColumn(Modifier.weight(1f, fill = false)) {
            items(
                items = vm.sessions,
                key = { it.id },
            ) { session ->
                ChatHistoryRow(
                    session = session,
                    selected = session.id == vm.currentSession?.id,
                    onOpen = { onOpenChat(session.id) },
                    onDelete = { onDeleteChat(session.id) },
                )
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        NavigationDrawerItem(
            label = { Text("Download Models") },
            selected = false,
            onClick = onOpenModelHub,
            icon = { Icon(Icons.Default.FolderOpen, contentDescription = null) },
        )
        NavigationDrawerItem(
            label = { Text("Plugins & Connectors") },
            selected = false,
            onClick = onOpenPlugins,
            icon = { Icon(Icons.Default.Edit, contentDescription = null) },
        )
        NavigationDrawerItem(
            label = { Text("Settings") },
            selected = false,
            onClick = onOpenSettings,
            icon = { Icon(Icons.Default.Settings, contentDescription = null) },
        )
        Spacer(Modifier.height(8.dp))
    }
}

private val timeFormat = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())

@Composable
private fun ChatHistoryRow(
    session: ChatSession,
    selected: Boolean,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onOpen)
            .padding(start = 10.dp, top = 2.dp, bottom = 2.dp, end = 2.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                session.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                timeFormat.format(Date(session.updatedAt)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(34.dp)) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "Delete chat",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
