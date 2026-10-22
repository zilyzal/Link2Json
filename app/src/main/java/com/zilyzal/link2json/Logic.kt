package com.zilyzal.link2json

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

data class ParseResult(val valid: List<String>, val invalid: Int)

data class Probe(val size: Long?, val mime: String?)

data class PartInfo(val url: String, val file: String, val size: Long? = null, val mime: String? = null)

data class JsonDoc(val text: String, val fileName: String)

object LinkParser {
    private val splitter = Regex("""[\s,;]+""")
    private val schemeRegex = Regex("""^[a-zA-Z][a-zA-Z0-9+.-]*://\S+$""")
    private val bareRegex = Regex("""^(?:[a-zA-Z0-9-]+\.)+[a-zA-Z]{2,}(?:[:/?#]\S*)?$""")
    private val partSuffix = Regex("""(?i)[._-]?(part|pt)[._-]?\d+(\.[a-z0-9]{1,5})?$""")
    private val numericSuffix = Regex("""\.\d{3}$""")
    private val extSuffix = Regex("""\.[A-Za-z][A-Za-z0-9]{0,4}$""")

    fun parse(text: String): ParseResult {
        val seen = LinkedHashSet<String>()
        var invalid = 0
        for (raw in text.split(splitter)) {
            val token = raw.trim().trim('"', '\'', '<', '>')
            if (token.isEmpty()) continue
            val url = when {
                schemeRegex.matches(token) -> token
                bareRegex.matches(token) -> "https://$token"
                else -> null
            }
            if (url == null) invalid++ else seen.add(url)
        }
        return ParseResult(seen.toList(), invalid)
    }

    fun arrange(valid: List<String>, multi: Boolean, sort: Boolean): List<String> {
        if (!multi) return valid.take(1)
        if (!sort) return valid
        val cmp = Comparator<String> { a, b ->
            val byFile = compareNatural(fileNameOf(a).ifEmpty { a }, fileNameOf(b).ifEmpty { b })
            if (byFile != 0) byFile else compareNatural(a, b)
        }
        return valid.sortedWith(cmp)
    }

    private fun afterScheme(url: String): String {
        val noFragment = url.substringBefore('#')
        return noFragment.substringAfter("://", noFragment)
    }

    fun hostOf(url: String): String = afterScheme(url).substringBefore('/').substringBefore('?')

    fun fileNameOf(url: String): String {
        val path = afterScheme(url).substringBefore('?').substringAfter('/', "")
        val last = path.trimEnd('/').substringAfterLast('/')
        return try {
            URLDecoder.decode(last.replace("+", "%2B"), "UTF-8")
        } catch (e: Exception) {
            last
        }
    }

    fun deriveName(file: String): String {
        var n = file
        n = n.replace(partSuffix, "")
        n = n.replace(numericSuffix, "")
        n = n.replace(extSuffix, "")
        n = n.trim()
        return n.ifEmpty { "untitled" }
    }

    fun compareNatural(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                var ei = i
                while (ei < a.length && a[ei].isDigit()) ei++
                var ej = j
                while (ej < b.length && b[ej].isDigit()) ej++
                val na = a.substring(i, ei).trimStart('0')
                val nb = b.substring(j, ej).trimStart('0')
                if (na.length != nb.length) return na.length - nb.length
                val c = na.compareTo(nb)
                if (c != 0) return c
                i = ei
                j = ej
            } else {
                val c = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (c != 0) return c
                i++
                j++
            }
        }
        return (a.length - i) - (b.length - j)
    }
}

object JsonBuilder {
    fun build(nameInput: String, parts: List<PartInfo>): JsonDoc {
        val name = nameInput.trim().ifEmpty { LinkParser.deriveName(parts.first().file) }
        val sizes = parts.mapNotNull { it.size }
        val total = if (sizes.size == parts.size) sizes.sum() else null
        val created = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(
            OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS)
        )

        val sb = StringBuilder(256 + parts.size * 180)
        sb.append("{\n")
        sb.append("  \"name\": ").append(quote(name)).append(",\n")
        sb.append("  \"type\": ").append(quote(if (parts.size > 1) "multipart" else "single")).append(",\n")
        sb.append("  \"parts\": ").append(parts.size).append(",\n")
        if (total != null) sb.append("  \"totalSize\": ").append(total).append(",\n")
        sb.append("  \"created\": ").append(quote(created)).append(",\n")
        sb.append("  \"links\": [\n")
        parts.forEachIndexed { i, p ->
            sb.append("    {\n")
            sb.append("      \"part\": ").append(i + 1).append(",\n")
            sb.append("      \"file\": ").append(quote(p.file)).append(",\n")
            sb.append("      \"url\": ").append(quote(p.url))
            if (p.size != null) sb.append(",\n      \"size\": ").append(p.size)
            if (p.mime != null) sb.append(",\n      \"mime\": ").append(quote(p.mime))
            sb.append("\n    }")
            if (i < parts.lastIndex) sb.append(',')
            sb.append('\n')
        }
        sb.append("  ]\n}\n")

        val safe = name.replace(Regex("""[\\/:*?"<>|]"""), "")
            .trim()
            .replace(Regex("""\s+"""), "_")
            .take(60)
            .ifEmpty { "links" }
        return JsonDoc(sb.toString(), "$safe.json")
    }

    private fun quote(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }
}

object LinkChecker {
    suspend fun check(urls: List<String>): List<Probe> = coroutineScope {
        val gate = Semaphore(4)
        urls.map { u -> async(Dispatchers.IO) { gate.withPermit { probe(u) } } }.awaitAll()
    }

    private fun request(startUrl: String, method: String, range: Boolean): HttpURLConnection? {
        var current = startUrl
        repeat(6) {
            val c = (URL(current).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 8000
                readTimeout = 8000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "Mozilla/5.0 (Android) Link2JSON")
                setRequestProperty("Accept-Encoding", "identity")
                if (range) setRequestProperty("Range", "bytes=0-0")
            }
            val code = c.responseCode
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                val loc = c.getHeaderField("Location")
                c.disconnect()
                if (loc.isNullOrBlank()) return null
                current = URL(URL(current), loc).toString()
            } else {
                return c
            }
        }
        return null
    }

    private fun mimeOf(c: HttpURLConnection): String? =
        c.contentType?.substringBefore(';')?.trim()?.takeIf { it.isNotEmpty() }

    private fun probe(url: String): Probe {
        var size: Long? = null
        var mime: String? = null
        try {
            val head = request(url, "HEAD", false)
            if (head != null) {
                try {
                    if (head.responseCode in 200..299) {
                        size = head.contentLengthLong.takeIf { it >= 0 }
                        mime = mimeOf(head)
                    }
                } finally {
                    head.disconnect()
                }
            }
            if (size == null) {
                val get = request(url, "GET", true)
                if (get != null) {
                    try {
                        val code = get.responseCode
                        if (code == 206) {
                            size = get.getHeaderField("Content-Range")
                                ?.substringAfterLast('/')?.trim()?.toLongOrNull()
                        } else if (code in 200..299) {
                            size = get.contentLengthLong.takeIf { it >= 0 }
                        }
                        if (mime == null && code in 200..299) mime = mimeOf(get)
                    } finally {
                        get.disconnect()
                    }
                }
            }
        } catch (e: Exception) {
            // Unreachable or unsupported link: leave size/mime empty.
        }
        return Probe(size, mime)
    }
}
