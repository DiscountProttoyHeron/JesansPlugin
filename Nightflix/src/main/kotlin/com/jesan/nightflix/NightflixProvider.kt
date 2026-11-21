package com.jesan.nightflix

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
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

class NightflixProvider : MainAPI() {
    override var mainUrl = "https://nightflix.st"
    override var name = "Nightflix"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val browserUa = "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    override val mainPage = mainPageOf(
        "" to "Latest Movies",
        "/movies" to "Nightflix Movies",
        "/tv" to "Nightflix TV Shows",
        "/top-rated" to "Nightflix Top Rated"
    )

    private val directStreamRegex = Regex(
        "https?://[^\"'\\s<>\\\\]+\\.(?:m3u8|mp4|mkv|webm)(?:\\?[^\"'\\s<>\\\\]*)?",
        RegexOption.IGNORE_CASE
    )
    private val jsonStreamRegex = Regex(
        "(?i)(?:\"|')?(?:file|source|src|stream|url|hls)(?:\"|')?\\s*[:=]\\s*(?:\"|')(https?://[^\"'\\s]+)(?:\"|')"
    )
    private val atobRegex = Regex("atob\\(\\s*[\"']([A-Za-z0-9+/=]{16,})[\"']\\s*\\)")
    private val yearRegex = Regex("\\b((?:19|20)\\d{2})\\b")

    private fun defaultHeaders(referer: String = "$mainUrl/"): Map<String, String> = mapOf(
        "User-Agent" to browserUa,
        "Referer" to referer,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
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

    private fun extractPoster(img: Element?): String? {
        if (img == null) return null
        val srcsetFirst = img.attr("srcset").split(",").firstOrNull()?.trim()?.substringBefore(" ")
        val raw = img.attr("src").takeIf { it.isNotBlank() && !it.startsWith("data:") }
            ?: img.attr("data-src").takeIf { it.isNotBlank() && !it.startsWith("data:") }
            ?: img.attr("data-lazy-src").takeIf { it.isNotBlank() && !it.startsWith("data:") }
            ?: srcsetFirst
        return resolveUrl(raw).takeIf { it.isNotBlank() }
    }

    private fun harvestCards(doc: Document): List<SearchResponse> {
        val seen = linkedSetOf<String>()
        val items = mutableListOf<SearchResponse>()
        val selectors = listOf(
            "article",
            ".movie-item",
            ".post",
            ".movie-card",
            ".grid-item",
            "a[href*=/watch/movie/]",
            "a[href*=/watch/tv/]"
        )
        for (sel in selectors) {
            val nodes = runCatching { doc.select(sel) }.getOrDefault(emptyList())
            for (node in nodes) {
                val anchor = if (node.tagName() == "a") node else node.selectFirst("a[href]") ?: continue
                val href = resolveUrl(anchor.attr("href"))
                if (href.isBlank() || href == mainUrl || href == "$mainUrl/") continue
                if (!href.contains("/watch/") && !href.contains("/movie/") && !href.contains("/tv/")) continue
                val img = node.selectFirst("img")
                val title = node.selectFirst("h2, h3, .title")?.text()?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: anchor.attr("title").trim().takeIf { it.isNotBlank() }
                    ?: img?.attr("alt")?.trim()?.takeIf { it.isNotBlank() }
                    ?: anchor.text().trim()
                if (title.length < 2) continue
                if (seen.add(href)) {
                    val isTv = href.contains("/tv/", true)
                    items.add(
                        newMovieSearchResponse(title, href, if (isTv) TvType.TvSeries else TvType.Movie) {
                            this.posterUrl = extractPoster(img)
                        }
                    )
                }
            }
        }
        return items
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = if (request.data.isBlank()) mainUrl else "$mainUrl/${request.data.trimStart('/')}"
        val url = if (page <= 1) base else "${base.trimEnd('/')}/page/$page/"
        val resp = app.get(url, headers = defaultHeaders())
        if (resp.code == 404 && page > 1) {
            return newHomePageResponse(HomePageList(request.name, emptyList()), false)
        }
        if (resp.code !in 200..299) {
            throw ErrorLoadingException("[CLOUDSTREAM_BLACKBOX_REPORT]\nProvider: $name\nStage: getMainPage\nTarget_URL: $url\nHTTP_Status: ${resp.code}")
        }
        val cards = harvestCards(resp.document)
        return newHomePageResponse(HomePageList(request.name, cards), cards.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val url = "$mainUrl/search?q=$encoded"
        val resp = app.get(url, headers = defaultHeaders())
        if (resp.code !in 200..299) return emptyList()
        return harvestCards(resp.document)
    }

    override suspend fun load(url: String): LoadResponse {
        val resp = app.get(url, headers = defaultHeaders())
        val doc = resp.document
        val rawTitle = doc.selectFirst("h1, .entry-title, .title")?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: name
        val title = rawTitle.substringBefore(" - Nightflix").trim().ifBlank { rawTitle }
        val poster = resolveUrl(doc.selectFirst("meta[property=og:image]")?.attr("content"))
            .takeIf { it.isNotBlank() }
            ?: extractPoster(doc.selectFirst("img"))
        val plot = doc.selectFirst("meta[name=description]")?.attr("content")?.trim()
        val year = yearRegex.find("$title ${doc.text().take(500)}")?.groupValues?.getOrNull(1)?.toIntOrNull()

        val episodes = mutableListOf<Episode>()
        val epRegex = Regex("(?i)/watch/tv/(\\d+)/(\\d+)/(\\d+)")
        doc.select("a[href*=/watch/tv/]").forEach { a ->
            val epHref = resolveUrl(a.attr("href"))
            val m = epRegex.find(epHref)
            if (m != null) {
                val sNum = m.groupValues[2].toIntOrNull() ?: 1
                val eNum = m.groupValues[3].toIntOrNull() ?: (episodes.size + 1)
                val epLabel = a.text().trim().ifBlank { "S${sNum}E${eNum}" }
                episodes.add(
                    newEpisode(epHref) {
                        this.name = epLabel
                        this.season = sNum
                        this.episode = eNum
                    }
                )
            }
        }
        val distinctEps = episodes.distinctBy { it.data }
        if (distinctEps.size >= 2) {
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, distinctEps) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
        }
    }

    private suspend fun emitStream(
        streamUrl: String,
        refererUrl: String,
        label: String,
        emitted: MutableSet<String>,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val clean = streamUrl.trim()
        if (!clean.startsWith("http") || !emitted.add(clean)) return false
        val lower = clean.lowercase()
        val isHls = lower.contains(".m3u8") || lower.contains("/hls/") || lower.contains("master")
        val type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
        val origin = runCatching {
            val u = URI(refererUrl)
            "${u.scheme}://${u.host}"
        }.getOrDefault(refererUrl.trimEnd('/'))
        val quality = when {
            lower.contains("2160") || lower.contains("4k") -> Qualities.P2160.value
            lower.contains("1080") -> Qualities.P1080.value
            lower.contains("720") -> Qualities.P720.value
            lower.contains("480") -> Qualities.P480.value
            else -> Qualities.Unknown.value
        }
        callback.invoke(
            newExtractorLink(this.name, "$name $label", clean, type) {
                this.referer = refererUrl
                this.quality = quality
                this.headers = mapOf(
                    "User-Agent" to browserUa,
                    "Referer" to refererUrl,
                    "Origin" to origin,
                    "Accept" to "*/*"
                )
            }
        )
        return true
    }

    private suspend fun extractVidcore(
        embedUrl: String,
        pageReferer: String,
        emitted: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Int {
        var hits = 0
        if (loadExtractor(embedUrl, pageReferer, subtitleCallback, callback)) hits++
        val resp = runCatching {
            app.get(
                embedUrl,
                headers = mapOf(
                    "User-Agent" to browserUa,
                    "Referer" to pageReferer
                ),
                allowRedirects = true
            )
        }.getOrNull() ?: return hits

        val finalEmbedUrl = resp.url.ifBlank { embedUrl }
        val activeOrigin = runCatching {
            val u = URI(finalEmbedUrl)
            "${u.scheme}://${u.host}"
        }.getOrDefault("https://vidcore.io")
        val activeReferer = "$activeOrigin/"

        val text = resp.text.replace("\\/", "/")
        directStreamRegex.findAll(text).forEach { m ->
            if (emitStream(m.value, activeReferer, "VidCore HLS", emitted, callback)) hits++
        }
        jsonStreamRegex.findAll(text).forEach { m ->
            val candidate = m.groupValues[1]
            if (directStreamRegex.containsMatchIn(candidate)) {
                if (emitStream(candidate, activeReferer, "VidCore", emitted, callback)) hits++
            }
        }
        atobRegex.findAll(text).forEach { m ->
            val decoded = runCatching {
                String(Base64.decode(m.groupValues[1], Base64.DEFAULT), Charsets.UTF_8)
            }.getOrDefault("")
            directStreamRegex.findAll(decoded).forEach { dm ->
                if (emitStream(dm.value, activeReferer, "VidCore Base64", emitted, callback)) hits++
            }
        }
        return hits
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var linksFound = 0
        val emitted = linkedSetOf<String>()
        val candidateEmbeds = linkedSetOf<String>()

        // Derive VidCore embed directly from /watch/movie/{id} or /watch/tv/{id}/{s}/{e}
        Regex("/watch/movie/(\\d+)").find(data)?.groupValues?.getOrNull(1)?.let { tmdbId ->
            candidateEmbeds.add("https://vidcore.net/movie/$tmdbId?autoPlay=true")
            candidateEmbeds.add("https://vidcore.io/movie/$tmdbId?autoPlay=true")
        }
        Regex("/watch/tv/(\\d+)/(\\d+)/(\\d+)").find(data)?.let { m ->
            val (tmdbId, s, e) = m.destructured
            candidateEmbeds.add("https://vidcore.net/tv/$tmdbId/$s/$e?autoPlay=true")
            candidateEmbeds.add("https://vidcore.io/tv/$tmdbId/$s/$e?autoPlay=true")
        }

        val pageResp = runCatching { app.get(data, headers = defaultHeaders()) }.getOrNull()
        if (pageResp != null && pageResp.code in 200..299) {
            val doc = pageResp.document
            doc.select("iframe[src], iframe[data-src]").forEach { ifr ->
                val src = resolveUrl(ifr.attr("src").ifBlank { ifr.attr("data-src") }, data)
                if (src.startsWith("http")) candidateEmbeds.add(src)
            }
            directStreamRegex.findAll(pageResp.text.replace("\\/", "/")).forEach { m ->
                if (emitStream(m.value, data, "Direct", emitted, callback)) linksFound++
            }
        }

        for (embed in candidateEmbeds) {
            if (embed.contains("vidcore", true)) {
                linksFound += extractVidcore(embed, data, emitted, subtitleCallback, callback)
            } else if (loadExtractor(embed, data, subtitleCallback, callback)) {
                linksFound++
            }
        }

        if (linksFound == 0) {
            throw ErrorLoadingException(
                "[CLOUDSTREAM_BLACKBOX_REPORT]\nProvider: $name\nStage: loadLinks\nTarget_URL: $data\nEmbeds_Tried: ${candidateEmbeds.joinToString(" | ")}"
            )
        }
        return true
    }
}
