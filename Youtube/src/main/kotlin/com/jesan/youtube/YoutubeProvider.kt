package com.jesan.youtube

import android.util.Base64
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

// Phase 3 Universal Deep-Extraction Provider (Custom HTML) for: youtube.com
// Preset: Archive.org (Public Domain) | Cloudflare Shield: OFF
// Card: .item | Title: h3 | Poster: src
class YoutubeProvider : MainAPI() {
    override var mainUrl = "youtube.com"
    override var name = "Youtube"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val primaryCardSelector = ".item"
    private val fallbackCardSelectors = listOf(
        "article.item",
        "article.post",
        ".movies-list .ml-item",
        ".items article",
        ".movie-card",
        ".movie-item",
        ".post-item",
        ".grid-item",
        "article"
    )
    private val titleSelector = "h3"
    private val posterAttr = "src"
    private val searchPattern = "/search?q={query}"
    private val paginationFormat = "/page/{page}/"
    private val customUserAgent = ""
    private val customReferer = ""
    private val customCookie = ""

    private val nonContentPathHints = listOf(
        "/category/", "/tag/", "/tags/", "/genre/", "/page/", "/author/",
        "/login", "/signin", "/register", "/wp-login", "/wp-admin",
        "/dmca", "/privacy", "/contact", "/about", "/terms", "/disclaimer",
        "javascript:", "mailto:", "t.me/", "telegram"
    )

    private val knownExtractorHosts = listOf(
        "hubcloud", "gdflix", "vcloud", "filepress", "streamtape", "dood",
        "ds2play", "filemoon", "streamwish", "wishfast", "vidhide", "mixdrop",
        "pixeldrain", "gofile", "vidsrc", "uqload", "mp4upload", "lulustream",
        "voe", "upstream", "sendcm", "drive.google", "workers.dev"
    )

    private val actionKeywordRegex = Regex(
        "(?i)(480p|720p|1080p|2160p|4k|watch|stream|download|server|fast|direct|g-direct|instant|play|link)"
    )
    private val directStreamRegex = Regex(
        "https?://[^\"'\\s<>\\\\]+\\.(?:m3u8|mp4|mkv|webm)(?:\\?[^\"'\\s<>\\\\]*)?",
        RegexOption.IGNORE_CASE
    )
    private val jsonFilePropRegex = Regex(
        "(?i)(?:\"|')?(?:file|source|src|stream|url)(?:\"|')?\\s*[:=]\\s*(?:\"|')(https?://[^\"'\\s]+)(?:\"|')"
    )
    private val atobRegex = Regex("atob\\(\\s*[\"']([A-Za-z0-9+/=]{16,})[\"']\\s*\\)")
    private val windowLocationRegex = Regex(
        "(?i)(?:window\\.location(?:\\.href)?|location\\.replace|location\\.href)\\s*[=(]\\s*[\"'](https?://[^\"'\\s]+)[\"']"
    )
    private val seasonEpRegex = Regex("(?i)s0*(\\d+)\\s*e0*(\\d+)")
    private val epOnlyRegex = Regex("(?i)(?:ep|episode|e)\\s*0*(\\d+)")
    private val yearRegex = Regex("\\b((?:19|20)\\d{2})\\b")

    override val mainPage = mainPageOf(
        "/watch?v=iLVe0XC_DA8&feature=mweb_c3_open_app_51888860&itc_campaign=mweb_c3_open_app_51888860&redirect_app_store_ios=1&app=desktop" to "Open App"
    )

    private fun buildRequestHeaders(referer: String?): Map<String, String> {
        val headers = linkedMapOf<String, String>()
        if (customUserAgent.isNotBlank()) headers["User-Agent"] = customUserAgent
        val effRef = referer?.takeIf { it.isNotBlank() } ?: customReferer.takeIf { it.isNotBlank() }
        if (effRef != null) headers["Referer"] = effRef
        if (customCookie.isNotBlank()) headers["Cookie"] = customCookie
        return headers
    }

    private suspend fun safeGet(url: String, referer: String? = null) =
        app.get(
            url,
            headers = buildRequestHeaders(referer),
            referer = referer ?: customReferer.ifBlank { mainUrl }
        )

    private suspend fun safePost(
        url: String,
        data: Map<String, String>,
        referer: String? = null
    ) = app.post(
        url,
        data = data,
        headers = buildRequestHeaders(referer),
        referer = referer ?: customReferer.ifBlank { mainUrl }
    )

    private fun resolveUrl(raw: String?, baseOverride: String = mainUrl): String {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isBlank() || trimmed.startsWith("data:") || trimmed.startsWith("#")) return ""
        val base = baseOverride.trimEnd('/')
        return when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            trimmed.startsWith("//") -> "https:$trimmed"
            trimmed.startsWith("/") -> "$base$trimmed"
            else -> "$base/$trimmed"
        }
    }

    private fun isNonContentUrl(url: String): Boolean {
        val lower = url.lowercase().trimEnd('/')
        if (lower.isBlank() || lower == mainUrl.lowercase().trimEnd('/')) return true
        return nonContentPathHints.any { lower.contains(it) }
    }

    private fun extractPosterFromImg(img: Element?): String? {
        if (img == null) return null
        val srcsetFirst = img.attr("srcset")
            .split(",")
            .firstOrNull()
            ?.trim()
            ?.substringBefore(" ")
            ?.takeIf { it.isNotBlank() }
        val raw = img.attr(posterAttr).takeIf { it.isNotBlank() && !it.startsWith("data:") }
            ?: img.attr("data-src").takeIf { it.isNotBlank() && !it.startsWith("data:") }
            ?: img.attr("data-lazy-src").takeIf { it.isNotBlank() && !it.startsWith("data:") }
            ?: img.attr("data-original").takeIf { it.isNotBlank() && !it.startsWith("data:") }
            ?: srcsetFirst
            ?: img.attr("src").takeIf { it.isNotBlank() && !it.startsWith("data:") }
        return resolveUrl(raw).takeIf { it.isNotBlank() }
    }

    private fun buildPagedUrl(categoryPath: String, page: Int): String {
        val cleanPath = categoryPath.trim().trimEnd('/')
        val base = if (cleanPath.isBlank()) mainUrl else "$mainUrl/${cleanPath.trimStart('/')}"
        if (page <= 1) return base
        val suffix = paginationFormat.replace("{page}", page.toString())
        return if (suffix.startsWith("?")) {
            if (base.contains("?")) "$base&${suffix.removePrefix("?")}" else "$base$suffix"
        } else {
            "${base.trimEnd('/')}/${suffix.trimStart('/')}"
        }
    }

    private fun Element.toSearchItem(): SearchResponse? {
        val anchor = this.selectFirst("a[href]") ?: return null
        val href = resolveUrl(anchor.attr("href"))
        if (isNonContentUrl(href)) return null
        val img = this.selectFirst("img")
        val title = this.selectFirst(titleSelector)?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: anchor.attr("title").trim().takeIf { it.isNotBlank() }
            ?: img?.attr("alt")?.trim()?.takeIf { it.isNotBlank() }
            ?: anchor.text().trim()
        if (title.length < 2) return null
        val poster = extractPosterFromImg(img)
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = poster
        }
    }

    /**
     * Multi-Section Card Harvester:
     * Combines primary selector + smart fallbacks without breaking early on small sliders,
     * filters non-content links, and deduplicates by URL.
     */
    private fun harvestCards(doc: Document): List<SearchResponse> {
        val seenUrls = linkedSetOf<String>()
        val harvested = mutableListOf<SearchResponse>()
        val selectors = buildList {
            if (primaryCardSelector.isNotBlank()) add(primaryCardSelector)
            addAll(fallbackCardSelectors)
        }.distinct()

        for (selector in selectors) {
            val matchedElements = runCatching { doc.select(selector) }.getOrDefault(emptyList())
            for (el in matchedElements) {
                val item = el.toSearchItem() ?: continue
                if (seenUrls.add(item.url)) {
                    harvested.add(item)
                }
            }
        }
        return harvested
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = buildPagedUrl(request.data, page)
        try {
            val resp = safeGet(url, mainUrl)
            if (resp.code == 404 && page > 1) {
                return newHomePageResponse(HomePageList(request.name, emptyList()), false)
            }
            if (resp.code !in 200..299) {
                throw ErrorLoadingException(
                    "[CLOUDSTREAM_BLACKBOX_REPORT]\nProvider: $name\nStage: getMainPage\nTarget_URL: $url\nHTTP_Status: ${resp.code}\nError: Non-2xx HTTP status"
                )
            }
            val items = harvestCards(resp.document)
            return newHomePageResponse(HomePageList(request.name, items), items.isNotEmpty())
        } catch (e: ErrorLoadingException) {
            throw e
        } catch (e: Exception) {
            throw ErrorLoadingException(
                "[CLOUDSTREAM_BLACKBOX_REPORT]\nProvider: $name\nStage: getMainPage\nTarget_URL: $url\nError: ${e.message}"
            )
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val path = searchPattern.replace("{query}", encoded)
        val url = "$mainUrl/${path.trimStart('/')}"
        try {
            val resp = safeGet(url, mainUrl)
            if (resp.code == 404) return emptyList()
            if (resp.code !in 200..299) {
                throw ErrorLoadingException(
                    "[CLOUDSTREAM_BLACKBOX_REPORT]\nProvider: $name\nStage: search\nTarget_URL: $url\nHTTP_Status: ${resp.code}\nError: Non-2xx HTTP status"
                )
            }
            return harvestCards(resp.document)
        } catch (e: ErrorLoadingException) {
            throw e
        } catch (e: Exception) {
            throw ErrorLoadingException(
                "[CLOUDSTREAM_BLACKBOX_REPORT]\nProvider: $name\nStage: search\nTarget_URL: $url\nError: ${e.message}"
            )
        }
    }

    override suspend fun load(url: String): LoadResponse {
        try {
            val resp = safeGet(url, mainUrl)
            val doc = resp.document
            val title = doc.selectFirst("h1, .entry-title, .data h1, title")?.text()?.trim().orEmpty().ifBlank { name }
            val poster = resolveUrl(doc.selectFirst("meta[property=og:image]")?.attr("content"))
                .takeIf { it.isNotBlank() }
                ?: extractPosterFromImg(doc.selectFirst(".poster img, .entry-content img, article img"))
            val plot = doc.selectFirst("meta[name=description]")?.attr("content")?.trim()?.takeIf { it.isNotBlank() }
                ?: doc.selectFirst(".wp-content p, .entry-content p, .synopsis p")?.text()?.trim()
            val yearText = doc.selectFirst(".date, .year, .release, h1")?.text().orEmpty() + " " + title
            val parsedYear = yearRegex.find(yearText)?.groupValues?.getOrNull(1)?.toIntOrNull()
            val genres = doc.select("a[href*=/genre/], a[href*=/category/], .sgeneros a")
                .mapNotNull { it.text().trim().takeIf { t -> t.length in 2..28 } }
                .distinct()
                .take(8)
            val related = doc.select(".related-posts article, .srelacionados article, .related a:has(img)")
                .mapNotNull { it.toSearchItem() }
                .filter { it.url != url }
                .distinctBy { it.url }
                .take(12)

            // Auto Movie vs TV Series / Episode Detection
            val episodeEntries = linkedMapOf<String, Episode>()
            val structuredNodes = doc.select(".episodios li a[href], .season-list a[href], .episodes-list a[href], .seasons a[href]")
            val fallbackEpisodeNodes = doc.select(".entry-content a[href], .download-links a[href], a[href]")
                .filter { a ->
                    val txt = a.text() + " " + a.attr("href")
                    seasonEpRegex.containsMatchIn(txt) || epOnlyRegex.containsMatchIn(txt)
                }
            val combinedEpisodeNodes = (structuredNodes + fallbackEpisodeNodes)
            var autoEpNum = 1
            for (anchor in combinedEpisodeNodes) {
                val epUrl = resolveUrl(anchor.attr("href"), mainUrl)
                if (epUrl.isBlank() || epUrl == url || isNonContentUrl(epUrl)) continue
                if (episodeEntries.containsKey(epUrl)) continue
                val label = anchor.text().trim().ifBlank { "Episode $autoEpNum" }
                val seMatch = seasonEpRegex.find(label + " " + epUrl)
                val epMatch = epOnlyRegex.find(label + " " + epUrl)
                val parsedSeason = seMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
                val parsedEp = seMatch?.groupValues?.getOrNull(2)?.toIntOrNull()
                    ?: epMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: autoEpNum
                episodeEntries[epUrl] = newEpisode(epUrl) {
                    this.name = label
                    this.season = parsedSeason
                    this.episode = parsedEp
                }
                autoEpNum++
            }

            if (episodeEntries.size >= 2) {
                val sortedEpisodes = episodeEntries.values.sortedWith(
                    compareBy<Episode>({ it.season ?: 1 }, { it.episode ?: 0 })
                )
                return newTvSeriesLoadResponse(title, url, TvType.TvSeries, sortedEpisodes) {
                    this.posterUrl = poster
                    this.plot = plot
                    this.year = parsedYear
                    this.tags = genres
                    this.recommendations = related
                }
            }

            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
                this.year = parsedYear
                this.tags = genres
                this.recommendations = related
            }
        } catch (e: ErrorLoadingException) {
            throw e
        } catch (e: Exception) {
            throw ErrorLoadingException(
                "[CLOUDSTREAM_BLACKBOX_REPORT]\nProvider: $name\nStage: load\nTarget_URL: $url\nError: ${e.message}"
            )
        }
    }

    /**
     * Self-contained Dean Edwards eval(function(p,a,c,k,e,d)...) Packed JS Unpacker.
     */
    private fun unpackDeanEdwards(script: String): String {
        val regex = Regex(
            "eval\\(function\\(p,a,c,k,e,[dr]\\)\\{.*?\\}\\('((?:\\\\.|[^'\\\\])*)'\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*'([^']*)'\\.split\\('\\|'\\)",
            setOf(RegexOption.DOT_MATCHES_ALL)
        )
        val match = regex.find(script) ?: return ""
        val payload = match.groupValues[1].replace("\\'", "'").replace("\\\\", "\\")
        val radix = match.groupValues[2].toIntOrNull() ?: return ""
        val count = match.groupValues[3].toIntOrNull() ?: return ""
        val symtab = match.groupValues[4].split("|")
        fun encodeBase(n: Int): String {
            val alphabet = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
            if (n == 0) return "0"
            var value = n
            val sb = StringBuilder()
            while (value > 0) {
                sb.append(alphabet[value % radix.coerceIn(2, 62)])
                value /= radix.coerceIn(2, 62)
            }
            return sb.reverse().toString()
        }
        val lookup = HashMap<String, String>()
        for (i in 0 until minOf(count, symtab.size)) {
            val word = symtab[i]
            if (word.isNotEmpty()) lookup[encodeBase(i)] = word
        }
        return Regex("\\b\\w+\\b").replace(payload) { m -> lookup[m.value] ?: m.value }
    }

    private fun inferQuality(text: String): Int = when {
        text.contains("2160", true) || text.contains("4k", true) -> Qualities.P2160.value
        text.contains("1080", true) -> Qualities.P1080.value
        text.contains("720", true) -> Qualities.P720.value
        text.contains("480", true) -> Qualities.P480.value
        text.contains("360", true) -> Qualities.P360.value
        else -> Qualities.Unknown.value
    }

    private fun isKnownExtractorHost(url: String): Boolean {
        val lower = url.lowercase()
        return knownExtractorHosts.any { lower.contains(it) }
    }

    private suspend fun emitDirectStream(
        streamUrl: String,
        refererUrl: String,
        labelHint: String,
        emittedStreams: MutableSet<String>,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val clean = streamUrl.trim()
        if (!clean.startsWith("http") || !emittedStreams.add(clean)) return false
        val isHls = clean.lowercase().contains(".m3u8")
        val linkType = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
        callback.invoke(
            newExtractorLink(
                this.name,
                "$name $labelHint",
                clean,
                linkType
            ) {
                this.referer = refererUrl
                this.quality = inferQuality(labelHint + " " + clean)
            }
        )
        return true
    }

    private suspend fun inspectDocumentStreamsAndEmbeds(
        doc: Document,
        pageUrl: String,
        emittedStreams: MutableSet<String>,
        triedEmbeds: MutableSet<String>,
        iframesFound: MutableList<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Int {
        var count = 0

        // Layer 1A: Direct HTML video / source / anchor media links
        doc.select("video[src], source[src], a[href*=.m3u8], a[href*=.mp4], a[href*=.mkv], a[href*=.webm]")
            .forEach { el ->
                val raw = el.attr("src").ifBlank { el.attr("href") }
                val resolved = resolveUrl(raw, pageUrl)
                if (resolved.isNotBlank() && emitDirectStream(resolved, pageUrl, "Direct", emittedStreams, callback)) {
                    count++
                }
            }

        // Layer 1B: Inline <script> regex, JSON file/source, atob Base64, and Dean Edwards packed JS
        doc.select("script").forEach { scriptEl ->
            val rawScript = scriptEl.data()
            if (rawScript.isBlank()) return@forEach
            val unpacked = if (rawScript.contains("eval(function(p,a,c,k,e")) unpackDeanEdwards(rawScript) else ""
            val combined = if (unpacked.isNotBlank()) "$rawScript\n$unpacked" else rawScript

            directStreamRegex.findAll(combined).forEach { m ->
                if (emitDirectStream(m.value, pageUrl, "Script", emittedStreams, callback)) count++
            }
            jsonFilePropRegex.findAll(combined).forEach { m ->
                val candidate = m.groupValues[1]
                if (directStreamRegex.containsMatchIn(candidate)) {
                    if (emitDirectStream(candidate, pageUrl, "JSON", emittedStreams, callback)) count++
                }
            }
            atobRegex.findAll(combined).forEach { m ->
                val decoded = runCatching {
                    String(Base64.decode(m.groupValues[1], Base64.DEFAULT), Charsets.UTF_8)
                }.getOrDefault("")
                directStreamRegex.findAll(decoded).forEach { dm ->
                    if (emitDirectStream(dm.value, pageUrl, "Base64", emittedStreams, callback)) count++
                }
            }
        }

        // Layer 2A: Embedded player iframes (src, data-src, data-litespeed-src)
        doc.select("iframe[src], iframe[data-src], iframe[data-litespeed-src]").forEach { iframe ->
            val rawSrc = iframe.attr("src")
                .ifBlank { iframe.attr("data-src") }
                .ifBlank { iframe.attr("data-litespeed-src") }
            val resolved = resolveUrl(rawSrc, pageUrl)
            if (resolved.startsWith("http") && !resolved.contains("about:blank")) {
                iframesFound.add(resolved)
                if (triedEmbeds.add(resolved)) {
                    if (directStreamRegex.containsMatchIn(resolved)) {
                        if (emitDirectStream(resolved, pageUrl, "Iframe", emittedStreams, callback)) count++
                    } else if (loadExtractor(resolved, pageUrl, subtitleCallback, callback)) {
                        count++
                    }
                }
            }
        }

        return count
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var linksFound = 0
        var lastStatus: Int? = null
        val emittedStreams = linkedSetOf<String>()
        val triedEmbeds = linkedSetOf<String>()
        val iframesFound = mutableListOf<String>()
        val ajaxTriggers = mutableListOf<String>()
        val candidateSample = mutableListOf<String>()
        val hopResults = mutableListOf<String>()

        try {
            val resp = safeGet(data, mainUrl)
            lastStatus = resp.code
            val doc = resp.document

            // Layer 1 & Layer 2A on target page
            linksFound += inspectDocumentStreamsAndEmbeds(
                doc, data, emittedStreams, triedEmbeds, iframesFound, subtitleCallback, callback
            )

            // Layer 2B: WordPress DooPlay AJAX Player Tabs
            doc.select("li.dooplay_player_option[data-post][data-nume][data-type]").forEach { opt ->
                val postId = opt.attr("data-post").trim()
                val nume = opt.attr("data-nume").trim()
                val type = opt.attr("data-type").trim()
                if (postId.isBlank() || nume.isBlank()) return@forEach
                ajaxTriggers.add("post=$postId,nume=$nume,type=$type")
                runCatching {
                    val ajaxResp = safePost(
                        "$mainUrl/wp-admin/admin-ajax.php",
                        mapOf(
                            "action" to "doo_player_ajax",
                            "post" to postId,
                            "nume" to nume,
                            "type" to type
                        ),
                        data
                    )
                    val bodyText = ajaxResp.text
                    val ajaxUrls = linkedSetOf<String>()
                    Regex("https?://[^\"'\\s<>\\\\]+").findAll(bodyText).forEach { m ->
                        val unescaped = m.value.replace("\\/", "/")
                        if (!unescaped.endsWith(".js") && !unescaped.endsWith(".css")) {
                            ajaxUrls.add(unescaped)
                        }
                    }
                    Jsoup.parse(bodyText).select("iframe[src]").forEach { ifr ->
                        val s = resolveUrl(ifr.attr("src"), mainUrl)
                        if (s.startsWith("http")) ajaxUrls.add(s)
                    }
                    ajaxUrls.forEach { u ->
                        if (triedEmbeds.add(u)) {
                            if (directStreamRegex.containsMatchIn(u)) {
                                if (emitDirectStream(u, data, "DooPlay AJAX", emittedStreams, callback)) linksFound++
                            } else if (loadExtractor(u, data, subtitleCallback, callback)) {
                                linksFound++
                            }
                        }
                    }
                }
            }

            // Layer 3: 2-Hop / 3-Hop Intermediate Button & Known-Host Follower
            val candidatePairs = linkedMapOf<String, String>()
            doc.select(".entry-content a[href], .download-links a[href], button[data-url], a[href]")
                .forEach { el ->
                    val rawHref = el.attr("data-url").ifBlank { el.attr("href") }
                    val resolved = resolveUrl(rawHref, data)
                    if (resolved.isBlank() || resolved == data || isNonContentUrl(resolved)) return@forEach
                    val label = el.text().trim().ifBlank { el.attr("title").trim() }.ifBlank { "Link" }
                    if (isKnownExtractorHost(resolved) || actionKeywordRegex.containsMatchIn("$label $resolved")) {
                        if (!candidatePairs.containsKey(resolved)) {
                            candidatePairs[resolved] = label.take(40)
                        }
                    }
                }

            candidatePairs.entries.take(12).forEach { (u, lbl) ->
                candidateSample.add("$lbl -> $u")
            }

            // Pass known hosts straight to CloudStream loadExtractor first
            val intermediateQueue = mutableListOf<Pair<String, String>>()
            for ((candUrl, candLabel) in candidatePairs) {
                if (directStreamRegex.containsMatchIn(candUrl)) {
                    if (emitDirectStream(candUrl, data, candLabel, emittedStreams, callback)) linksFound++
                } else if (isKnownExtractorHost(candUrl)) {
                    if (triedEmbeds.add(candUrl) && loadExtractor(candUrl, data, subtitleCallback, callback)) {
                        linksFound++
                    }
                } else {
                    intermediateQueue.add(candUrl to candLabel)
                }
            }

            // Follow up to 2 hops across max 8 intermediate wrapper/redirect buttons
            val visitedHops = linkedSetOf<String>(data)
            for ((hop1Url, hop1Label) in intermediateQueue.take(8)) {
                if (!visitedHops.add(hop1Url)) continue
                val hopOutcome = runCatching {
                    var currentUrl = hop1Url
                    var prevRef = data
                    var foundInChain = 0
                    for (hopDepth in 1..2) {
                        val hopResp = safeGet(currentUrl, prevRef)
                        val hopDoc = hopResp.document
                        foundInChain += inspectDocumentStreamsAndEmbeds(
                            hopDoc, currentUrl, emittedStreams, triedEmbeds, iframesFound, subtitleCallback, callback
                        )
                        // Check known hosts on hopped page
                        var nextRedirect: String? = null
                        hopDoc.select("a[href], button[data-url]").forEach { el ->
                            val nextU = resolveUrl(el.attr("data-url").ifBlank { el.attr("href") }, currentUrl)
                            if (nextU.isBlank()) return@forEach
                            if (directStreamRegex.containsMatchIn(nextU)) {
                                if (emitDirectStream(nextU, currentUrl, hop1Label, emittedStreams, callback)) foundInChain++
                            } else if (isKnownExtractorHost(nextU)) {
                                if (triedEmbeds.add(nextU) && loadExtractor(nextU, currentUrl, subtitleCallback, callback)) {
                                    foundInChain++
                                }
                            } else if (nextRedirect == null && hopDepth == 1 && !isNonContentUrl(nextU) && actionKeywordRegex.containsMatchIn(el.text() + " " + nextU)) {
                                nextRedirect = nextU
                            }
                        }
                        // Inspect meta[http-equiv=refresh] or window.location redirect
                        if (nextRedirect == null && hopDepth == 1) {
                            val metaRefresh = hopDoc.selectFirst("meta[http-equiv=refresh]")?.attr("content").orEmpty()
                            val metaUrl = Regex("(?i)url=([^;\\s]+)").find(metaRefresh)?.groupValues?.getOrNull(1)
                            val jsRedirect = windowLocationRegex.find(hopResp.text)?.groupValues?.getOrNull(1)
                            val candidateNext = resolveUrl(metaUrl ?: jsRedirect, currentUrl)
                            if (candidateNext.startsWith("http") && visitedHops.add(candidateNext)) {
                                nextRedirect = candidateNext
                            }
                        }
                        if (foundInChain > 0 || nextRedirect == null) break
                        prevRef = currentUrl
                        currentUrl = nextRedirect!!
                    }
                    foundInChain
                }
                val chainHits = hopOutcome.getOrDefault(0)
                linksFound += chainHits
                hopResults.add("$hop1Label -> hits=$chainHits (${hopOutcome.exceptionOrNull()?.message ?: "ok"})")
            }

            // Layer 4: Rich Diagnostic Blackbox on 0 Streams
            if (linksFound == 0) {
                throw ErrorLoadingException(
                    """
                    [CLOUDSTREAM_BLACKBOX_REPORT]
                    Provider: ${this.name}
                    Stage: loadLinks
                    Target_URL: $data
                    HTTP_Status: ${lastStatus ?: "N/A"}
                    Iframes_Found: ${if (iframesFound.isEmpty()) "none" else iframesFound.distinct().take(6).joinToString(" | ")}
                    AJAX_Triggers_Found: ${if (ajaxTriggers.isEmpty()) "none" else ajaxTriggers.take(6).joinToString(" | ")}
                    Candidate_Buttons_Sample: ${if (candidateSample.isEmpty()) "none" else candidateSample.joinToString(" ; ")}
                    Hop_Results: ${if (hopResults.isEmpty()) "none" else hopResults.joinToString(" ; ")}
                    """.trimIndent()
                )
            }
            return true
        } catch (e: ErrorLoadingException) {
            throw e
        } catch (e: Exception) {
            throw ErrorLoadingException(
                "[CLOUDSTREAM_BLACKBOX_REPORT]\nProvider: $name\nStage: loadLinks\nTarget_URL: $data\nHTTP_Status: ${lastStatus ?: "N/A"}\nError: ${e.message}"
            )
        }
    }
}
