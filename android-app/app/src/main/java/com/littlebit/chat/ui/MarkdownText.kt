package com.littlebit.chat.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.style.TextDecoration
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
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Quote(val text: String) : MdBlock
    data object Rule : MdBlock
    data class Table(val rows: List<List<String>>) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class Bullets(val items: List<String>) : MdBlock
    data class Code(val lang: String, val code: String) : MdBlock
    data class Math(val expression: String) : MdBlock
}

private fun parseMarkdown(text: String, checkpoint: (Int, Int) -> Unit = { _, _ -> }): List<MdBlock> {
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

    var scannedRow = 0
    var scannedOffset = 0
    fun complete(row: Int) {
        while (scannedRow < row) scannedOffset += lines[scannedRow++].length + 1
        checkpoint(scannedOffset, blocks.size)
    }
    var row = 0
    fun cells(line: String) = line.trim().trim('|').split('|').map { it.trim() }
    while (row < lines.size) {
        val line = lines[row++]
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
        val heading = Regex("""^(#{1,3})\s+(.+)""").matchEntire(trimmed)
        if (heading != null || trimmed.startsWith(">") || trimmed == "---") {
            flushParagraph()
            flushBullets()
            blocks.add(when {
                heading != null -> MdBlock.Heading(heading.groupValues[1].length, heading.groupValues[2])
                trimmed == "---" -> MdBlock.Rule
                else -> MdBlock.Quote(trimmed.drop(1).trimStart())
            })
            continue
        }
        if ('|' in trimmed && row < lines.size && '|' in lines[row] &&
            cells(lines[row]).all { it.matches(Regex(":?-{3,}:?")) }) {
            flushParagraph()
            flushBullets()
            val rows = mutableListOf(cells(trimmed))
            row++
            while (row < lines.size && '|' in lines[row]) rows.add(cells(lines[row++]))
            blocks.add(MdBlock.Table(rows))
            continue
        }
        if (Regex("""^\d+[.)]\s+.+""").matches(trimmed)) {
            flushParagraph()
            flushBullets()
            blocks.add(MdBlock.Paragraph(trimmed))
            if (row < lines.size && '|' !in trimmed) complete(row)
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
            if (row < lines.size) complete(row)
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
private fun linked(text: String, color: Color, background: Color): AnnotatedString {
    val formatted = inlineMarkdown(text, color, background)
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    val style = TextLinkStyles(SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline))
    return buildAnnotatedString {
        append(formatted)
        Regex("""https?://[^\s<>]+""").findAll(formatted.text).forEach { match ->
            val url = match.value.trimEnd('.', ',', ')', ']')
            addLink(LinkAnnotation.Url(url, style) { runCatching { uri.openUri(url) } },
                match.range.first, match.range.first + url.length)
        }
    }
}

private class StreamingMarkdown {
    private var previous = ""
    private var offset = 0
    private val finished = mutableListOf<MdBlock>()

    fun update(text: String): List<MdBlock> {
        if (!text.startsWith(previous)) {
            finished.clear()
            offset = 0
        }
        previous = text
        var committed = 0
        var length = 0
        val tail = parseMarkdown(text.substring(offset)) { end, count ->
            length = end
            committed = count
        }
        val result = finished + tail
        finished.addAll(tail.take(committed))
        offset += length
        return result
    }
}

@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = LocalContentColor.current,
) {
    val parser = remember { StreamingMarkdown() }
    val blocks = remember(text) { parser.update(text.replace("\r\n", "\n")) }
    val codeBg = MaterialTheme.colorScheme.surfaceVariant
    Column(modifier = modifier, verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
        for (block in blocks) {
            RenderBlock(block, color, codeBg)
        }
    }
}

@Composable
private fun RenderBlock(block: MdBlock, color: Color, codeBg: Color) {
            when (block) {
                is MdBlock.Paragraph -> Text(
                    text = linked(block.text, color, codeBg),
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
                                text = linked(item, color, codeBg),
                                style = MaterialTheme.typography.bodyLarge,
                                color = color,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
                is MdBlock.Heading -> Text(linked(block.text, color, codeBg), style = when (block.level) {
                    1 -> MaterialTheme.typography.headlineSmall
                    2 -> MaterialTheme.typography.titleLarge
                    else -> MaterialTheme.typography.titleMedium
                })
                is MdBlock.Quote -> Row(Modifier.height(IntrinsicSize.Min).padding(vertical = 4.dp)) {
                    Box(Modifier.width(3.dp).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
                    Text(linked(block.text, color, codeBg), Modifier.padding(start = 10.dp))
                }
                MdBlock.Rule -> androidx.compose.material3.HorizontalDivider(Modifier.padding(vertical = 8.dp))
                is MdBlock.Table -> Column(Modifier.horizontalScroll(rememberScrollState())) {
                    for ((index, row) in block.rows.withIndex()) Row {
                        for (cell in row) Text(linked(cell, color, codeBg),
                            Modifier.width(144.dp).background(if (index == 0) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer).border(1.dp, MaterialTheme.colorScheme.outlineVariant).padding(9.dp),
                            fontWeight = if (index == 0) FontWeight.Bold else FontWeight.Normal)
                    }
                }
                is MdBlock.Code -> CodeBlock(block.lang, block.code)
                is MdBlock.Math -> MathBlock(block.expression)
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
        "\\mathbb{N}" to "ℕ", "\\mathbb{R}" to "ℝ",
        "\\Rightarrow" to "⇒", "\\forall" to "∀", "\\exists" to "∃",
        "\\rightarrow" to "→",
        "\\to" to "→",
        "\\left" to "",
        "\\right" to "",
    )
    replacements.forEach { (latex, unicode) -> value = value.replace(latex, unicode) }
    return Regex("""\\in(?![A-Za-z])""").replace(value, "∈").replace(36.toChar().toString(), "").trim()
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
    val buzz = hapticAction()
    Surface(
        color = Color(0xFF171A21),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
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
                    .background(Color(0xFF20242D))
                    .padding(start = 12.dp),
            ) {
                Text(
                    text = lang.uppercase().ifBlank { "CODE" },
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFC7CBD4),
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { buzz(); clipboard.setText(AnnotatedString(code)) }) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = "Copy code",
                        tint = Color(0xFFC7CBD4),
                    )
                }
            }
            Text(
                text = code,
                softWrap = false,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                color = Color(0xFFF1F2F4),
                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }
    }
}
