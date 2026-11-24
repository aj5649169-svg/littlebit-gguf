package com.littlebit.chat.plugins

import java.math.MathContext
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

object PluginRegistry {
    val calculator = object : AppPlugin {
        override val id = "calculator"
        override val name = "Calculator"
        override val description = "Type /calc followed by arithmetic, including parentheses."
        override val isEnabled = true
        override suspend fun execute(input: String): String = runCatching {
            Arithmetic(input.removePrefix("/calc")).evaluate()
        }.getOrElse { "Calculator: ${it.message ?: "Invalid expression"}" }
    }
    val clock = object : AppPlugin {
        override val id = "clock"
        override val name = "Local date & time"
        override val description = "Include the phone’s local date, time and zone in each prompt."
        override val isEnabled = true
        override suspend fun execute(input: String) = timeContext()
    }
    fun timeContext(): String = "Local date and time: " +
        ZonedDateTime.now().format(DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm z"))

    val plugins: List<AppPlugin> = listOf(calculator, clock,
        PluginDescriptor("web-search", "Web Search", "Look up online sources."),
        PluginDescriptor("reminders", "Reminders", "Schedule on-device reminders."))
}

private class Arithmetic(source: String) {
    private val text = source.trim()
    private var pos = 0
    private fun take(c: Char): Boolean {
        while (text.getOrNull(pos)?.isWhitespace() == true) pos++
        if (text.getOrNull(pos) != c) return false
        pos++
        return true
    }
    fun evaluate(): String {
        require(text.length in 1..256) { "Enter up to 256 characters." }
        val n = sum()
        require(pos == text.length) { "Unexpected character." }
        return n.stripTrailingZeros().toPlainString()
    }
    private fun sum(): java.math.BigDecimal {
        var n = product()
        while (true) n = when {
            take('+') -> n + product()
            take('-') -> n - product()
            else -> return n
        }
    }
    private fun product(): java.math.BigDecimal {
        var n = atom()
        while (true) n = when {
            take('*') -> n * atom()
            take('/') -> n.divide(atom(), MathContext.DECIMAL128)
            else -> return n
        }
    }
    private fun atom(): java.math.BigDecimal {
        if (take('-')) return -atom()
        if (take('+')) return atom()
        if (take('(')) {
            val n = sum()
            require(take(')')) { "Missing closing parenthesis." }
            return n
        }
        val start = pos
        while (text.getOrNull(pos)?.let { it.isDigit() || it == '.' } == true) pos++
        return text.substring(start, pos).toBigDecimalOrNull() ?: error("Expected a number.")
    }
}
