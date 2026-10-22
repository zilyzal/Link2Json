package com.zilyzal.link2json

import java.net.URLDecoder
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

data class ParseResult(val valid: List<String>, val invalid: Int)

data class Probe(val size: Long?, val mime: String?)

data class Found(val url: String, val file: String)

data class PartInfo(val url: String, val file: String, val size: Long? = null, val mime: String? = null)

/** One batch of files that belong together (e.g. all parts of one release on one host). */
data class LinkGroup(val id: String, val title: String, val host: String, val parts: List<PartInfo>)

data class Analysis(
    val title: String?,
    val image: String?,
    val source: String?,
    val groups: List<LinkGroup>,
    val notes: List<String>,
)

data class JsonDoc(val text: String, val fileName: String)

object LinkParser {
    private const val ARCH = """(?:rar|zip|7z|tar|gz|bz2|xz|iso|r\d{2}|z\d{2})"""

    private val splitter = Regex("""[\s,;]+""")
    private val schemeRegex = Regex("""^[a-zA-Z][a-zA-Z0-9+.-]*://\S+$""")
    private val bareRegex = Regex("""^(?:[a-zA-Z0-9-]+\.)+[a-zA-Z]{2,}(?:[:/?#]\S*)?$""")

    private val archiveToken = Regex("""(?i)[^\s"'<>|\\/]+\.$ARCH(?:\.\d{3})?(?:\.html?)?(?![A-Za-z0-9])""")
    private val wholeArchiveName = Regex("""(?i)^[^<>|\\/]{1,150}\.$ARCH(?:\.\d{3})?(?:\.html?)?$""")

    private val htmlSuffix = Regex("""(?i)\.html?$""")
    private val numericSuffix = Regex("""\.\d{3}$""")
    private val archiveExt = Regex("""(?i)\.$ARCH$""")
    private val partSuffix = Regex("""(?i)[._\s-]?(part|pt)[._\s-]?\d+(\.[a-z0-9]{1,5})?$""")
    private val extSuffix = Regex("""\.[A-Za-z][A-Za-z0-9]{0,4}$""")

    private val directExts = setOf(
        "rar", "zip", "7z", "tar", "gz", "bz2", "xz", "iso", "exe", "msi", "apk", "bin", "dmg", "pkg",
        "cab", "img", "deb", "mp4", "mkv", "avi", "mp3", "flac", "pdf", "epub", "torrent",
    )

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

    fun isArchiveName(s: String): Boolean = archiveToken.containsMatchIn(s)

    /** Pulls a file name like "Game.part1.rar" out of link text, or null when there is none. */
    fun fileFromText(text: String): String? {
        val t = text.trim()
        if (t.isEmpty()) return null
        if (wholeArchiveName.matches(t)) return t
        return archiveToken.find(t)?.value
    }

    /** True when the link itself points at a file; false when it looks like a web page to scan. */
    fun isDirect(url: String): Boolean {
        val name = fileNameOf(url)
        if (name.isEmpty()) return false
        if (isArchiveName(name)) return true
        return name.substringAfterLast('.', "").lowercase() in directExts
    }

    private fun afterScheme(url: String): String {
        val noFragment = url.substringBefore('#')
        return noFragment.substringAfter("://", noFragment)
    }

    fun hostOf(url: String): String = afterScheme(url).substringBefore('/').substringBefore('?')

    /** Host without port and subdomains, so dl1.host.com and dl2.host.com count as one host. */
    fun hostKey(host: String): String {
        val labels = host.substringBefore(':').lowercase().split('.').filter { it.isNotEmpty() }
        if (labels.size <= 2) return labels.joinToString(".")
        val last = labels[labels.size - 1]
        val prev = labels[labels.size - 2]
        val take = if (last.length == 2 && prev.length <= 3) 3 else 2
        return labels.takeLast(take).joinToString(".")
    }

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
        var n = file.trim()
        n = n.replace(htmlSuffix, "")
        n = n.replace(numericSuffix, "")
        n = n.replace(archiveExt, "")
        n = n.replace(partSuffix, "")
        n = n.replace(extSuffix, "")
        n = n.trim()
        return n.ifEmpty { "untitled" }
    }

    /** Groups files by host and release name, keeping page order for groups and natural order inside. */
    fun group(items: List<Found>): List<LinkGroup> {
        val parts = LinkedHashMap<String, MutableList<PartInfo>>()
        val titles = HashMap<String, String>()
        val hosts = HashMap<String, String>()
        for (f in items) {
            val base = deriveName(f.file)
            val host = hostKey(hostOf(f.url))
            val key = host + "|" + base.lowercase()
            parts.getOrPut(key) { mutableListOf() }.add(PartInfo(f.url, f.file))
            titles.putIfAbsent(key, base)
            hosts.putIfAbsent(key, host)
        }
        return parts.map { (key, list) ->
            val sorted = list.sortedWith { a, b ->
                val c = compareNatural(a.file, b.file)
                if (c != 0) c else compareNatural(a.url, b.url)
            }
            LinkGroup(key, titles[key] ?: "untitled", hosts[key] ?: "", sorted)
        }
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
    fun build(nameInput: String, meta: Analysis?, groups: List<LinkGroup>): JsonDoc {
        val name = nameInput.trim().ifEmpty {
            meta?.title?.takeIf { it.isNotBlank() } ?: groups.first().title
        }
        val all = groups.flatMap { it.parts }
        val sizes = all.mapNotNull { it.size }
        val total = if (sizes.size == all.size) sizes.sum() else null
        val created = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(
            OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS)
        )

        val sb = StringBuilder(512 + all.size * 200)
        sb.append("{\n")
        sb.append("  \"name\": ").append(quote(name)).append(",\n")
        meta?.image?.let { sb.append("  \"image\": ").append(quote(it)).append(",\n") }
        meta?.source?.let { sb.append("  \"source\": ").append(quote(it)).append(",\n") }
        sb.append("  \"created\": ").append(quote(created)).append(",\n")
        sb.append("  \"totalParts\": ").append(all.size).append(",\n")
        if (total != null) sb.append("  \"totalSize\": ").append(total).append(",\n")
        sb.append("  \"packages\": [\n")
        groups.forEachIndexed { gi, g ->
            val gSizes = g.parts.mapNotNull { it.size }
            sb.append("    {\n")
            sb.append("      \"name\": ").append(quote(g.title)).append(",\n")
            sb.append("      \"host\": ").append(quote(g.host)).append(",\n")
            sb.append("      \"type\": ").append(quote(if (g.parts.size > 1) "multipart" else "single")).append(",\n")
            sb.append("      \"parts\": ").append(g.parts.size).append(",\n")
            if (gSizes.size == g.parts.size) {
                sb.append("      \"totalSize\": ").append(gSizes.sum()).append(",\n")
            }
            sb.append("      \"links\": [\n")
            g.parts.forEachIndexed { i, p ->
                sb.append("        {\n")
                sb.append("          \"part\": ").append(i + 1).append(",\n")
                sb.append("          \"file\": ").append(quote(p.file)).append(",\n")
                sb.append("          \"url\": ").append(quote(p.url))
                if (p.size != null) sb.append(",\n          \"size\": ").append(p.size)
                if (p.mime != null) sb.append(",\n          \"mime\": ").append(quote(p.mime))
                sb.append("\n        }")
                if (i < g.parts.lastIndex) sb.append(',')
                sb.append('\n')
            }
            sb.append("      ]\n")
            sb.append("    }")
            if (gi < groups.lastIndex) sb.append(',')
            sb.append('\n')
        }
        sb.append("  ]\n}\n")

        val safe = name.replace(Regex("""[\\/:*?"<>|]"""), "")
            .trim()
            .replace(Regex("""\s+"""), "_")
            .take(60)
            .ifEmpty { "package" }
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
