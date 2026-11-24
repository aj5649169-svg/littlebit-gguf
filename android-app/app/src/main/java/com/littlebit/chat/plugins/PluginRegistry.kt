package com.littlebit.chat.plugins

/**
 * Static registry of app plugins / connectors.
 *
 * Swap any entry for a live [AppPlugin] implementation (real web search,
 * calculator, file tools) — the Plugins screen and future tool-calling loop
 * only depend on [AppPlugin] + this list.
 */
object PluginRegistry {
    val plugins: List<PluginDescriptor> = listOf(
        PluginDescriptor(
            id = "web-search",
            name = "Web Search",
            description = "Search the web for current events, docs and facts.",
        ),
        PluginDescriptor(
            id = "calculator",
            name = "Calculator",
            description = "Evaluate arithmetic and unit conversions precisely.",
        ),
        PluginDescriptor(
            id = "file-reader",
            name = "File Reader",
            description = "Read and summarize local documents picked via SAF.",
        ),
        PluginDescriptor(
            id = "reminders",
            name = "Reminders",
            description = "Create on-device reminders from chat messages.",
        ),
    )
}
