package com.littlebit.chat.plugins

/**
 * Contract for LittleBit Chat plugins / connectors.
 *
 * Step 2 ships the registry + UI only; real tools are wired later without
 * refactoring by replacing [PluginRegistry.plugins] entries with live
 * implementations of this interface.
 */
interface AppPlugin {
    val id: String
    val name: String
    val description: String
    val isEnabled: Boolean

    /** Run the tool. Called off the main thread. */
    suspend fun execute(input: String): String
}

/**
 * Descriptor for connectors that are announced but not wired yet. [execute]
 * returns a friendly message instead of crashing so the UI stays safe if a
 * stub is ever invoked.
 */
data class PluginDescriptor(
    override val id: String,
    override val name: String,
    override val description: String,
    override val isEnabled: Boolean = false,
) : AppPlugin {
    override suspend fun execute(input: String): String =
        "$name is not available yet — the connector is Coming Soon."
}
