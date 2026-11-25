package com.littlebit.chat.data

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import java.io.File
import kotlin.math.roundToInt

class DeviceProfile(context: Context) {
    private val activity = requireNotNull(context.getSystemService(ActivityManager::class.java))
    private val power = requireNotNull(context.getSystemService(PowerManager::class.java))
    fun memory() = ActivityManager.MemoryInfo().also(activity::getMemoryInfo)
    val ramTier = (memory().totalMem / 1e9).roundToInt()
    val defaultContext = when { ramTier <= 6 -> 1024; ramTier >= 15 -> 4096; else -> 2048 }

    // Compare frequency clusters relative to the slowest cluster. A prime
    // core can clock substantially higher than the other performance cores.
    val fastCpus = File("/sys/devices/system/cpu").listFiles().orEmpty().mapNotNull { dir ->
        val id = dir.name.removePrefix("cpu").toIntOrNull() ?: return@mapNotNull null
        val khz = runCatching { File(dir, "cpufreq/cpuinfo_max_freq").readText().trim().toLong() }.getOrNull()
        khz?.takeIf { it > 0 }?.let { id to it }
    }.let { rows ->
        val low = rows.minOfOrNull { it.second } ?: 0
        val faster = rows.filter { it.second > low * 1.15 }
        faster.ifEmpty { rows }.sortedByDescending { it.second }.map { it.first }.toIntArray()
    }
    val performanceCount = fastCpus.size.takeIf { it > 0 } ?: Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
    fun warmAllowed() = memory().let { !it.lowMemory && it.availMem >= 2_500_000_000L } &&
        (Build.VERSION.SDK_INT < 29 || power.currentThermalStatus < PowerManager.THERMAL_STATUS_MODERATE)

    fun observeHeat(change: (Boolean) -> Unit): () -> Unit {
        if (Build.VERSION.SDK_INT < 29) return {}
        val listener = PowerManager.OnThermalStatusChangedListener { change(it >= PowerManager.THERMAL_STATUS_MODERATE) }
        power.addThermalStatusListener(listener)
        change(power.currentThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE)
        return { power.removeThermalStatusListener(listener) }
    }
}
