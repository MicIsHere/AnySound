package io.anysound.text

import io.anysound.config.Settings
import java.util.regex.Pattern

object TextProcessor {
    // Protect structural punctuation before removing sentence punctuation.
    private val protectedText = Regex("https?://[^\\s，。！？]+|www\\.[^\\s，。！？]+|[\\w.+-]+@[\\w.-]+\\.[A-Za-z]{2,}|[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)+|\\d+(?:[.,]\\d+)+")
    private val grapheme = Pattern.compile("\\X")

    fun cleanVoice(raw: String, settings: Settings): String {
        var text = raw.trim()
        if (settings.filterFillers && settings.fillers.isNotEmpty()) {
            val words = settings.fillers.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }
            // Only standalone runs: never delete characters inside ordinary words.
            val pauses = Regex("(?<![\\p{L}\\p{N}])(?:(?:$words))+(?![\\p{L}\\p{N}])[\\s，,。 .]*")
            text = pauses.replace(text, " ")
        }
        if (settings.cleanPunctuation) {
            val protected = mutableListOf<String>()
            text = protectedText.replace(text) { match ->
                // A trailing ASCII period can be sentence punctuation after a URL.
                val value = match.value.trimEnd('.', ',')
                val suffix = match.value.substring(value.length)
                protected += value
                "\uE000${protected.lastIndex}\uE001$suffix"
            }
            text = text.replace(Regex("[，,。.]+"), " ")
            text = Regex("\uE000(\\d+)\uE001").replace(text) { protected[it.groupValues[1].toInt()] }
        }
        return text.replace(Regex("\\s+"), " ").trim()
    }

    fun split(text: String, limit: Int = 144): List<String> {
        require(limit > 0)
        require('\u0000' !in text) { "文本不能包含空字符" }
        // ponytail: bounded batches keep the quadratic boundary scan inexpensive.
        require(text.length <= 14_400) { "单次内容过长，请分次发送（最多 14400 字符）" }
        if (text.isBlank()) return emptyList()
        val matcher = grapheme.matcher(text)
        val boundaries = mutableListOf<Int>()
        while (matcher.find()) {
            require(matcher.end() - matcher.start() <= limit) { "一个组合字符超过 ChatBox 限制，请缩短后发送" }
            boundaries += matcher.end()
        }
        val chunks = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            val ninthNewline = text.indices.asSequence().drop(start).filter { text[it] == '\n' }.take(9).toList().takeIf { it.size == 9 }?.last()
            val maxEnd = boundaries.lastOrNull { it > start && it - start <= limit && (ninthNewline == null || it <= ninthNewline) }
                ?: error("无法分割文本")
            var end = maxEnd
            if (end < text.length) {
                // Prefer a boundary in the latter half, avoiding many tiny fragments.
                val candidates = boundaries.filter { it > start + limit / 2 && it <= maxEnd }
                end = candidates.lastOrNull { text[it - 1] in "。！？.!?\n" }
                    ?: candidates.lastOrNull { text[it - 1].isWhitespace() || text[it - 1] in "，,;；" }
                    ?: maxEnd
            }
            chunks += text.substring(start, end)
            start = end
        }
        return chunks
    }
}
