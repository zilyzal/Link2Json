package com.zilyzal.link2json

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

object Net {
    const val UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"

    /** Opens a connection and follows redirects manually (also across http/https). */
    fun open(startUrl: String, method: String, range: Boolean = false, timeoutMs: Int = 10000): HttpURLConnection? {
        var current = startUrl
        repeat(6) {
            val c = (URL(current).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
                setRequestProperty("Accept-Language", "en-US,en;q=0.9")
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
}

data class PageResult(
    val finalUrl: String,
    val title: String?,
    val image: String?,
    val found: List<Found>,
    val isFile: Boolean = false,
    val error: String? = null,
)

object Scraper {
    private val urlRegex = Regex("""https?://[^\s"'<>)\]]+""")
    private val charsetRegex = Regex("""(?i)charset=([\w-]+)""")
    private val junkImage = Regex("""(?i)(logo|avatar|icon|emoji|sprite|gravatar|pixel|blank|spacer|\.svg|\.gif|data:)""")
    private val titleSplit = Regex("""\s[|–—-]\s""")

    fun fetch(url: String): PageResult = try {
        fetchInner(url)
    } catch (e: SocketTimeoutException) {
        failed(url, "timed out")
    } catch (e: UnknownHostException) {
        failed(url, "no connection")
    } catch (e: Exception) {
        failed(url, e.message?.take(60) ?: "failed")
    }

    private fun failed(url: String, msg: String) = PageResult(url, null, null, emptyList(), error = msg)

    private fun fetchInner(url: String): PageResult {
        val c = Net.open(url, "GET") ?: return failed(url, "too many redirects")
        try {
            val finalUrl = c.url.toString()
            val code = c.responseCode
            if (code !in 200..299) return failed(url, "HTTP $code")

            val type = c.contentType?.lowercase().orEmpty()
            val disposition = c.getHeaderField("Content-Disposition")?.lowercase().orEmpty()
            val looksLikePage = type.isEmpty() || type.contains("html") || type.contains("xml") || type.startsWith("text/")
            if (disposition.contains("attachment") || !looksLikePage) {
                return PageResult(finalUrl, null, null, emptyList(), isFile = true)
            }

            val bytes = readLimited(c, 4_000_000)
            val charset = charsetRegex.find(type)?.groupValues?.get(1)
            val doc = Jsoup.parse(ByteArrayInputStream(bytes), charset, finalUrl)
            return PageResult(finalUrl, pageTitle(doc), pageImage(doc), pageLinks(doc))
        } finally {
            c.disconnect()
        }
    }

    private fun readLimited(c: HttpURLConnection, limit: Int): ByteArray {
        var input: InputStream = c.inputStream
        if (c.contentEncoding?.contains("gzip", ignoreCase = true) == true) input = GZIPInputStream(input)
        input.use { s ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            while (out.size() < limit) {
                val n = s.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        }
    }

    private fun pageTitle(doc: Document): String? {
        val raw = doc.select("meta[property=og:title]").firstOrNull()?.attr("content")?.takeIf { it.isNotBlank() }
            ?: doc.title()
        val first = raw.split(titleSplit).firstOrNull()?.trim().orEmpty()
        val cleaned = if (first.length >= 3) first else raw.trim()
        return cleaned.takeIf { it.isNotBlank() }
    }

    private fun pageImage(doc: Document): String? {
        val metas = doc.select(
            "meta[property=og:image], meta[property=og:image:url], meta[name=twitter:image], meta[name=twitter:image:src]"
        )
        for (m in metas) {
            val u = m.absUrl("content")
            if (u.startsWith("http")) return u
        }
        doc.select("link[rel=image_src]").firstOrNull()?.absUrl("href")?.takeIf { it.startsWith("http") }?.let { return it }
        for (img in doc.select("article img, .entry-content img, .post-content img, .post img, .content img, img")) {
            val u = img.absUrl("data-src").ifEmpty { img.absUrl("data-lazy-src") }.ifEmpty { img.absUrl("src") }
            if (u.startsWith("http") && !junkImage.containsMatchIn(u)) return u
        }
        return null
    }

    private fun pageLinks(doc: Document): List<Found> {
        val found = LinkedHashMap<String, String>()

        for (a in doc.select("a[href]")) {
            val href = a.absUrl("href")
            if (!href.startsWith("http")) continue
            val urlName = LinkParser.fileNameOf(href)
            val file = when {
                LinkParser.isArchiveName(urlName) -> urlName
                else -> LinkParser.fileFromText(a.text())
            } ?: continue
            found.putIfAbsent(href, file)
        }

        val body = doc.body()
        if (body != null) {
            for (m in urlRegex.findAll(body.wholeText())) {
                val href = m.value.trimEnd('.', ',', ';', ':')
                val name = LinkParser.fileNameOf(href)
                if (LinkParser.isArchiveName(name)) found.putIfAbsent(href, name)
            }
        }
        return found.map { Found(it.key, it.value) }
    }
}

object Analyzer {
    suspend fun run(urls: List<String>): Analysis = coroutineScope {
        val gate = Semaphore(3)
        val pages = urls.filter { !LinkParser.isDirect(it) }
        val fetched: Map<String, PageResult> = pages
            .map { u -> async(Dispatchers.IO) { gate.withPermit { u to Scraper.fetch(u) } } }
            .awaitAll()
            .toMap()

        val items = ArrayList<Found>()
        val seen = HashSet<String>()
        val notes = ArrayList<String>()
        var title: String? = null
        var image: String? = null
        var source: String? = null

        fun add(url: String, file: String) {
            if (seen.add(url)) items.add(Found(url, file))
        }

        for (u in urls) {
            val host = LinkParser.hostOf(u)
            if (LinkParser.isDirect(u)) {
                add(u, LinkParser.fileNameOf(u).ifEmpty { host })
                continue
            }
            val r = fetched[u] ?: continue
            when {
                r.error != null -> {
                    notes.add("Couldn't open $host (${r.error}); kept the link as it is.")
                    add(u, LinkParser.fileNameOf(u).ifEmpty { host })
                }
                r.isFile -> add(u, LinkParser.fileNameOf(u).ifEmpty { host })
                r.found.isEmpty() -> {
                    notes.add("No rar/zip links found on $host; kept the link as it is.")
                    add(u, LinkParser.fileNameOf(u).ifEmpty { host })
                    if (title == null) title = r.title
                    if (image == null) image = r.image
                    if (source == null) source = r.finalUrl
                }
                else -> {
                    r.found.forEach { add(it.url, it.file) }
                    if (title == null) title = r.title
                    if (image == null) image = r.image
                    if (source == null) source = r.finalUrl
                }
            }
        }
        Analysis(title, image, source, LinkParser.group(items), notes)
    }
}

object LinkChecker {
    suspend fun check(urls: List<String>): List<Probe> = coroutineScope {
        val gate = Semaphore(4)
        urls.map { u -> async(Dispatchers.IO) { gate.withPermit { probe(u) } } }.awaitAll()
    }

    private fun mimeOf(c: HttpURLConnection): String? =
        c.contentType?.substringBefore(';')?.trim()?.takeIf { it.isNotEmpty() }

    private fun probe(url: String): Probe {
        var size: Long? = null
        var mime: String? = null
        try {
            val head = Net.open(url, "HEAD", timeoutMs = 8000)
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
                val get = Net.open(url, "GET", range = true, timeoutMs = 8000)
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
        // A web page is not the file itself, so its size/type would be misleading.
        if (mime?.startsWith("text/") == true) {
            size = null
            mime = null
        }
        return Probe(size, mime)
    }
}
