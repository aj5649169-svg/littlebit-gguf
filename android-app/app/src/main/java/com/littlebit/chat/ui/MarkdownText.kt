package com.littlebit.chat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Lightweight Markdown renderer for assistant replies: bold, italics, bullet
 * lists, inline code and styled fenced code blocks with a 1-tap copy button.
 * Dependency-free on purpose (a markdown library would pull a large
 * transitive tree into a CPU-bound chat app).
 */

private sealed interface MdBlock {
    data class Paragraph(val text: String) : MdBlock
    data class Bullets(val items: List<String>) : MdBlock
    data class Code(val lang: String, val code: String) : MdBlock
    data class Math(val expression: String) : MdBlock
}

private fun parseMarkdown(text: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val lines = text.replace("\r\n", "\n").split("\n")
    var paragraph = StringBuilder()
    var bullets = mutableListOf<String>()
    var inCode = false
    var codeLang = ""
    var code = StringBuilder()
    var inMath = false
    var math = StringBuilder()

    fun flushParagraph() {
        if (paragraph.isNotBlank()) blocks.add(MdBlock.Paragraph(paragraph.toString().trim()))
        paragraph = StringBuilder()
    }
    fun flushBullets() {
        if (bullets.isNotEmpty()) blocks.add(MdBlock.Bullets(bullets.toList()))
        bullets = mutableListOf()
    }

    for (line in lines) {
        if (line.trim().startsWith("```")) {
            if (inCode) {
                blocks.add(MdBlock.Code(codeLang, code.toString().trimEnd()))
                code = StringBuilder()
                codeLang = ""
                inCode = false
            } else {
                flushParagraph()
                flushBullets()
                codeLang = line.trim().removePrefix("```").trim()
                inCode = true
            }
            continue
        }
        if (inCode) {
            code.append(line).append('\n')
            continue
        }
        if (inMath) {
            val mathDelimiter = 36.toChar().toString().repeat(2)
            val close = line.indexOf(mathDelimiter)
            if (close >= 0) {
                if (close > 0) math.append(line.substring(0, close))
                blocks.add(MdBlock.Math(math.toString().trim()))
                math = StringBuilder()
                inMath = false
                val trailing = line.substring(close + 2).trim()
                if (trailing.isNotEmpty()) paragraph.append(trailing)
            } else {
                if (math.isNotEmpty()) math.append('\n')
                math.append(line)
            }
            continue
        }
        val trimmed = line.trim()
        val mathDelimiter = 36.toChar().toString().repeat(2)
        if (trimmed.startsWith(mathDelimiter)) {
            flushParagraph()
            flushBullets()
            val expression = trimmed.removePrefix(mathDelimiter)
            val close = expression.indexOf(mathDelimiter)
            if (close >= 0) {
                blocks.add(MdBlock.Math(expression.substring(0, close).trim()))
                val trailing = expression.substring(close + 2).trim()
                if (trailing.isNotEmpty()) paragraph.append(trailing)
            } else {
                inMath = true
                math.append(expression.trim())
            }
            continue
        }
        val bulletMatch = Regex("^\\s*[-*•]\\s+(.*)$").find(line)
        if (bulletMatch != null) {
            flushParagraph()
            bullets.add(bulletMatch.groupValues[1])
            continue
        }
        if (line.isBlank()) {
            flushParagraph()
            flushBullets()
            continue
        }
        flushBullets()
        if (paragraph.isNotEmpty()) paragraph.append('\n')
        paragraph.append(line.trim())
    }
    if (inCode) blocks.add(MdBlock.Code(codeLang, code.toString().trimEnd()))
    if (inMath) blocks.add(MdBlock.Math(math.toString().trim()))
    flushParagraph()
    flushBullets()
    return blocks
}

private fun inlineMarkdown(text: String, baseColor: Color, codeBg: Color): AnnotatedString =
    buildAnnotatedString {
        val mathMarker = 36.toChar().toString()
        val codeMarker = 96.toChar().toString()
        var i = 0
        while (i < text.length) {
            when {
                text.startsWith(mathMarker, i) -> {
                    val end = text.indexOf(mathMarker, i + 1)
                    if (end > i + 1) {
                        withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) {
                            append(formatLatex(text.substring(i + 1, end)))
                        }
                        i = end + 1
                    } else {
                        append(text[i]); i++
                    }
                }
                text.startsWith("**", i) -> {
                    val end = text.indexOf("**", i + 2)
                    if (end > i + 2) {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            append(text.substring(i + 2, end))
                        }
                        i = end + 2
                    } else {
                        append(text[i]); i++
                    }
                }
                text.startsWith("__", i) -> {
                    val end = text.indexOf("__", i + 2)
                    if (end > i + 2) {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            append(text.substring(i + 2, end))
                        }
                        i = end + 2
                    } else {
                        append(text[i]); i++
                    }
                }
                text.startsWith(codeMarker, i) -> {
                    val end = text.indexOf(codeMarker, i + 1)
                    if (end > i) {
                        withStyle(
                            SpanStyle(
                                fontFamily = FontFamily.Monospace,
                                background = codeBg,
                                color = baseColor,
                            ),
                        ) {
                            append(text.substring(i + 1, end))
                        }
                        i = end + 1
                    } else {
                        append(text[i]); i++
                    }
                }
                text.startsWith("*", i) || text.startsWith("_", i) -> {
                    val marker = text[i]
                    val end = text.indexOf(marker, i + 1)
                    if (end > i + 1) {
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                            append(text.substring(i + 1, end))
                        }
                        i = end + 1
                    } else {
                        append(text[i]); i++
                    }
                }
                else -> {
                    val next = listOfNotNull(
                        text.indexOf(mathMarker, i).takeIf { it >= 0 },
                        text.indexOf("**", i).takeIf { it >= 0 },
                        text.indexOf("__", i).takeIf { it >= 0 },
                        text.indexOf(codeMarker, i).takeIf { it >= 0 },
                        text.indexOf('*', i).takeIf { it >= 0 },
                        text.indexOf('_', i).takeIf { it >= 0 },
                    ).minOrNull() ?: text.length
                    append(text.substring(i, next))
                    i = next
                }
            }
        }
    }

@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = LocalContentColor.current,
) {
    val blocks = remember(text) { parseMarkdown(text) }
    val codeBg = MaterialTheme.colorScheme.surfaceVariant
    Column(modifier = modifier) {
        for (block in blocks) {
            when (block) {
                is MdBlock.Paragraph -> Text(
                    text = inlineMarkdown(block.text, color, codeBg),
                    style = MaterialTheme.typography.bodyLarge,
                    color = color,
                )
                is MdBlock.Bullets -> Column {
                    for (item in block.items) {
                        Row(Modifier.padding(vertical = 1.dp)) {
                            Text(
                                "•  ",
                                style = MaterialTheme.typography.bodyLarge,
                                color = color,
                            )
                            Text(
                                text = inlineMarkdown(item, color, codeBg),
                                style = MaterialTheme.typography.bodyLarge,
                                color = color,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
                is MdBlock.Code -> CodeBlock(block.lang, block.code)
                is MdBlock.Math -> MathBlock(block.expression)
            }
        }
    }
}


private fun formatLatex(source: String): String {
    var value = Regex("""\\text\{([^{}]*)\}""").replace(source) { it.groupValues[1] }
    val replacements = listOf(
        "\\qquad" to "    ",
        "\\quad" to "  ",
        "\\times" to "×",
        "\\cdot" to "·",
        "\\leq" to "≤",
        "\\le" to "≤",
        "\\geq" to "≥",
        "\\ge" to "≥",
        "\\neq" to "≠",
        "\\ne" to "≠",
        "\\approx" to "≈",
        "\\pm" to "±",
        "\\infty" to "∞",
        "\\rightarrow" to "→",
        "\\to" to "→",
        "\\left" to "",
        "\\right" to "",
    )
    replacements.forEach { (latex, unicode) -> value = value.replace(latex, unicode) }
    return value.replace(36.toChar().toString(), "").trim()
}

@Composable
private fun MathBlock(expression: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    ) {
        Text(
            text = formatLatex(expression),
            fontFamily = FontFamily.Monospace,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
        )
    }
}

@Composable
private fun CodeBlock(lang: String, code: String) {
    val clipboard = LocalClipboardManager.current
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                    .padding(start = 12.dp),
            ) {
                Text(
                    text = lang.uppercase().ifBlank { "CODE" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { clipboard.setText(AnnotatedString(code)) }) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = "Copy code",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = code,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }
    }
}
