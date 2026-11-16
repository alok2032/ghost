package com.ayra.ai.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ayra.ai.app.ui.theme.LocalAyraUiConfig

private sealed class MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock()
    data class Quote(val text: String) : MdBlock()
    data class Bullet(val prefix: String, val text: String) : MdBlock()
    data class CodeBlock(val language: String, val code: String) : MdBlock()
    data class Table(val headers: List<String>, val rows: List<List<String>>) : MdBlock()
    object Divider : MdBlock()
    data class Paragraph(val text: String) : MdBlock()
}

private fun parseMarkdownBlocks(raw: String): List<MdBlock> {
    val result = mutableListOf<MdBlock>()
    val lines = raw.replace("\r\n", "\n").split("\n")
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trim()
        when {
            trimmed.startsWith("```") -> {
                val lang = trimmed.removePrefix("```").trim().ifBlank { "code" }
                val codeLines = mutableListOf<String>()
                i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) {
                    codeLines.add(lines[i])
                    i++
                }
                result.add(MdBlock.CodeBlock(lang, codeLines.joinToString("\n")))
            }
            trimmed == "---" || trimmed == "***" -> result.add(MdBlock.Divider)
            trimmed.startsWith("### ") -> result.add(MdBlock.Heading(3, trimmed.removePrefix("### ").trim()))
            trimmed.startsWith("## ") -> result.add(MdBlock.Heading(2, trimmed.removePrefix("## ").trim()))
            trimmed.startsWith("# ") -> result.add(MdBlock.Heading(1, trimmed.removePrefix("# ").trim()))
            trimmed.startsWith("> ") -> result.add(MdBlock.Quote(trimmed.removePrefix("> ").trim()))
            trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("• ") -> {
                result.add(MdBlock.Bullet("•", trimmed.drop(2).trim()))
            }
            Regex("^\\d+\\.\\s+.*").matches(trimmed) -> {
                val numPrefix = trimmed.substringBefore(".") + "."
                val body = trimmed.substringAfter(".").trim()
                result.add(MdBlock.Bullet(numPrefix, body))
            }
            trimmed.startsWith("|") && trimmed.endsWith("|") && trimmed.count { it == '|' } >= 2 -> {
                val tableRows = mutableListOf<List<String>>()
                while (i < lines.size && lines[i].trim().startsWith("|")) {
                    val rowLine = lines[i].trim()
                    if (!Regex("^\\|[\\s\\-:|]+\\|$").matches(rowLine)) {
                        val cells = rowLine.trim('|').split("|").map { it.trim() }
                        tableRows.add(cells)
                    }
                    i++
                }
                if (tableRows.isNotEmpty()) {
                    result.add(MdBlock.Table(tableRows.first(), tableRows.drop(1)))
                }
                continue
            }
            trimmed.isNotBlank() -> {
                val paraLines = mutableListOf(line)
                while (i + 1 < lines.size) {
                    val next = lines[i + 1].trim()
                    if (next.isBlank() || next.startsWith("```") || next.startsWith("#") ||
                        next.startsWith("> ") || next.startsWith("- ") || next.startsWith("* ") ||
                        next.startsWith("|") || next == "---"
                    ) break
                    i++
                    paraLines.add(lines[i])
                }
                result.add(MdBlock.Paragraph(paraLines.joinToString("\n")))
            }
        }
        i++
    }
    return result
}

@Composable
private fun buildInlineAnnotated(
    text: String,
    accent: Color,
    codeBg: Color
): AnnotatedString {
    val uriHandler = LocalUriHandler.current
    return remember(text, accent, codeBg) {
        buildAnnotatedString {
            val tokenRegex = Regex("(\\*\\*[^*]+\\*\\*|\\*[^*\n]+\\*|`[^`]+`|https?://[^\\s)\\]>]+)")
            var cursor = 0
            tokenRegex.findAll(text).forEach { match ->
                if (match.range.first > cursor) {
                    append(text.substring(cursor, match.range.first))
                }
                val token = match.value
                when {
                    token.startsWith("**") && token.endsWith("**") && token.length > 4 -> {
                        pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                        append(token.substring(2, token.length - 2))
                        pop()
                    }
                    token.startsWith("*") && token.endsWith("*") && token.length > 2 -> {
                        pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                        append(token.substring(1, token.length - 1))
                        pop()
                    }
                    token.startsWith("`") && token.endsWith("`") && token.length > 2 -> {
                        pushStyle(
                            SpanStyle(
                                fontFamily = FontFamily.Monospace,
                                background = codeBg,
                                color = accent,
                                fontSize = 12.5.sp
                            )
                        )
                        append(" ${token.substring(1, token.length - 1)} ")
                        pop()
                    }
                    token.startsWith("http") -> {
                        val link = LinkAnnotation.Url(
                            url = token,
                            styles = TextLinkStyles(
                                style = SpanStyle(
                                    color = accent,
                                    textDecoration = TextDecoration.Underline,
                                    fontWeight = FontWeight.Medium
                                )
                            ),
                            linkInteractionListener = {
                                runCatching { uriHandler.openUri(token) }
                            }
                        )
                        pushLink(link)
                        append(token)
                        pop()
                    }
                    else -> append(token)
                }
                cursor = match.range.last + 1
            }
            if (cursor < text.length) {
                append(text.substring(cursor))
            }
        }
    }
}

@Composable
fun RichMarkdownView(
    content: String,
    onCopyCode: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val uiConfig = LocalAyraUiConfig.current
    val accent = uiConfig.accentColor
    val onSurface = MaterialTheme.colorScheme.onSurface
    val inlineCodeBg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
    val blocks = remember(content) { parseMarkdownBlocks(content) }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Heading -> {
                    val size = when (block.level) {
                        1 -> 18.sp
                        2 -> 16.sp
                        else -> 14.5.sp
                    }
                    Text(
                        text = buildInlineAnnotated(block.text, accent, inlineCodeBg),
                        color = if (block.level <= 2) accent else onSurface,
                        fontSize = size,
                        fontWeight = FontWeight.Bold,
                        lineHeight = (size.value + 5).sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                is MdBlock.Quote -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                            .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 7.dp)
                    ) {
                        Text(
                            text = buildInlineAnnotated(block.text, accent, inlineCodeBg),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.5.sp,
                            fontStyle = FontStyle.Italic
                        )
                    }
                }
                is MdBlock.Bullet -> {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(
                            text = block.prefix,
                            color = accent,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        Text(
                            text = buildInlineAnnotated(block.text, accent, inlineCodeBg),
                            color = onSurface,
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                is MdBlock.Divider -> {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
                is MdBlock.Table -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .clip(RoundedCornerShape(10.dp))
                            .border(1.dp, accent.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            block.headers.forEach { h ->
                                Text(h, color = accent, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                        }
                        HorizontalDivider(color = accent.copy(alpha = 0.25f))
                        block.rows.forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                row.forEach { cell ->
                                    Text(cell, color = onSurface, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
                is MdBlock.CodeBlock -> {
                    val codeBg = if (uiConfig.isDark) Color(0xFF090D18) else Color(0xFF0F172A)
                    val headerBg = if (uiConfig.isDark) Color(0xFF111827) else Color(0xFF1E293B)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(codeBg)
                            .border(1.dp, accent.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                    ) {
                        Column {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(headerBg)
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(Icons.Default.Code, contentDescription = null, tint = accent, modifier = Modifier.size(15.dp))
                                    Text(block.language.uppercase(), color = accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                                Row(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(Color(0xFF1E293B))
                                        .clickable { onCopyCode(block.code) }
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy Code", tint = Color.White, modifier = Modifier.size(13.dp))
                                    Text("Copy Code", color = Color.White, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp)
                                    .horizontalScroll(rememberScrollState())
                            ) {
                                Text(
                                    text = block.code,
                                    color = Color(0xFFE2E8F0),
                                    fontSize = 12.5.sp,
                                    fontFamily = FontFamily.Monospace,
                                    lineHeight = 18.sp
                                )
                            }
                        }
                    }
                }
                is MdBlock.Paragraph -> {
                    Text(
                        text = buildInlineAnnotated(block.text, accent, inlineCodeBg),
                        color = onSurface,
                        fontSize = 14.5.sp,
                        lineHeight = 22.sp
                    )
                }
            }
        }
    }
}
