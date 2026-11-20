package com.example

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
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class HdmoviesProvider : MainAPI() {
    override var mainUrl = "https://hdmovieszone.in"
    override var name = "Hdmovies"
    override val hasMainPage = true
    override var lang = "hi"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "" to "Latest Movies"
    )

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    )

    private fun resolveUrl(raw: String?): String {
        val value = raw?.trim().orEmpty()
        return when {
            value.isBlank() -> ""
            value.startsWith("http://") || value.startsWith("https://") -> value
            value.startsWith("//") -> "https:$value"
            value.startsWith("/") -> "$mainUrl$value"
            else -> "$mainUrl/$value"
        }
    }

    private fun throwBlackboxReport(
        stage: String,
        targetUrl: String,
        httpStatus: Int? = null,
        info: String
    ): Nothing {
        throw ErrorLoadingException(
            """
            [CLOUDSTREAM_BLACKBOX_REPORT]
            Provider: ${this.name}
            Stage: $stage
            Target_URL: $targetUrl
            HTTP_Status: ${httpStatus ?: "N/A"}
            Info: $info
            """.trimIndent()
        )
    }

    private fun extractCards(doc: Document): List<SearchResponse> {
        val selectorCandidates = listOf(
            "article.post",
            "article.item",
            "div.items article",
            "div.ml-item",
            "div.bw_thumb",
            "div.post-item",
            "article"
        )
        var elements: List<Element> = emptyList()
        for (selector in selectorCandidates) {
            val found = doc.select(selector)
            if (found.isNotEmpty()) {
                elements = found
                break
            }
        }
        return elements.mapNotNull { card ->
            val linkEl = card.selectFirst("h2 a, h3 a, .title a, a[title], a[href]") ?: return@mapNotNull null
            val href = resolveUrl(linkEl.attr("href"))
            if (href.isBlank() || href == mainUrl || href == "$mainUrl/") return@mapNotNull null
            val imgEl = card.selectFirst("img")
            val title = linkEl.text().trim()
                .ifBlank { linkEl.attr("title").trim() }
                .ifBlank { imgEl?.attr("alt")?.trim().orEmpty() }
            if (title.isBlank()) return@mapNotNull null
            val poster = resolveUrl(
                imgEl?.attr("data-src")?.ifBlank { null }
                    ?: imgEl?.attr("data-lazy-src")?.ifBlank { null }
                    ?: imgEl?.attr("data-original")?.ifBlank { null }
                    ?: imgEl?.attr("src")
            )
            newMovieSearchResponse(title, href, TvType.Movie) {
                if (poster.isNotBlank()) this.posterUrl = poster
            }
        }.distinctBy { it.url }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = if (request.data.isBlank()) mainUrl else "$mainUrl/${request.data.trim('/')}"
        val url = if (page <= 1) "$base/" else "$base/page/$page/"
        try {
            val resp = app.get(url, headers = headers)
            if (resp.code !in 200..299) {
                if (page > 1) return newHomePageResponse(HomePageList(request.name, emptyList()), false)
                throwBlackboxReport("getMainPage", url, resp.code, "HTTP error loading page")
            }
            val items = extractCards(resp.document)
            if (items.isEmpty() && page == 1) {
                val sampleClasses = resp.document.select("article, div[class*=post], div[class*=movie], div[class*=item]")
                    .take(5).joinToString(", ") { "${it.tagName()}.${it.className()}" }
                throwBlackboxReport("getMainPage", url, resp.code, "0 cards matched. Sample tags: $sampleClasses")
            }
            return newHomePageResponse(HomePageList(request.name, items), items.size >= 8)
        } catch (e: ErrorLoadingException) {
            throw e
        } catch (e: Exception) {
            throwBlackboxReport("getMainPage", url, null, e.message ?: e::class.java.simpleName)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val url = "$mainUrl/?s=$encoded"
        val resp = app.get(url, headers = headers)
        if (resp.code !in 200..299) return emptyList()
        return extractCards(resp.document)
    }

    override suspend fun load(url: String): LoadResponse {
        val resp = app.get(url, headers = headers)
        if (resp.code !in 200..299) {
            throwBlackboxReport("load", url, resp.code, "Failed to fetch movie page")
        }
        val doc = resp.document
        val title = doc.selectFirst("h1.entry-title, h1.post-title, h1")?.text()?.trim()
            ?.ifBlank { null }
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: "Untitled"
        val poster = resolveUrl(
            doc.selectFirst("meta[property=og:image]")?.attr("content")?.ifBlank { null }
                ?: doc.selectFirst(".poster img, .entry-content img, article img")?.let {
                    it.attr("data-src").ifBlank { it.attr("src") }
                }
        )
        val plot = doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
            ?: doc.selectFirst(".entry-content p")?.text()?.trim()
        val year = Regex("(19|20)\\d{2}").find(title)?.value?.toIntOrNull()

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            if (poster.isNotBlank()) this.posterUrl = poster
            this.plot = plot
            this.year = year
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val resp = app.get(data, headers = headers)
        if (resp.code !in 200..299) {
            throwBlackboxReport("loadLinks", data, resp.code, "Failed to fetch stream page")
        }
        val doc = resp.document
        var linksFound = 0

        val iframeUrls = doc.select("iframe[src], iframe[data-src]").mapNotNull { el ->
            resolveUrl(el.attr("data-src").ifBlank { el.attr("src") }).takeIf { it.isNotBlank() }
        }.distinct()

        for (iframe in iframeUrls) {
            if (loadExtractor(iframe, data, subtitleCallback, callback)) {
                linksFound++
            }
        }

        val directVideos = doc.select("video source[src], a[href$=.mp4], a[href$=.mkv], a[href$=.m3u8]").mapNotNull { el ->
            resolveUrl(el.attr("src").ifBlank { el.attr("href") }).takeIf { it.isNotBlank() }
        }.distinct()

        for (videoUrl in directVideos) {
            val isHls = videoUrl.contains(".m3u8", true)
            val type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            callback.invoke(
                newExtractorLink(this.name, "$name Direct", videoUrl, type) {
                    this.referer = data
                    this.quality = Qualities.Unknown.value
                }
            )
            linksFound++
        }

        val hostKeywords = listOf(
            "hubcloud", "gdflix", "filepress", "streamtape", "dood", "mixdrop",
            "vidsrc", "filemoon", "streamwish", "vcloud", "pixeldrain", "gofile", "drive.google"
        )
        val candidateLinks = doc.select(".entry-content a[href], article a[href]").mapNotNull { el ->
            val href = resolveUrl(el.attr("href"))
            if (href.isNotBlank() && hostKeywords.any { href.contains(it, true) }) href else null
        }.distinct()

        for (hostUrl in candidateLinks) {
            if (loadExtractor(hostUrl, data, subtitleCallback, callback)) {
                linksFound++
            }
        }

        if (linksFound == 0) {
            val sampleLinks = doc.select(".entry-content a[href], iframe[src]").take(8)
                .joinToString(" | ") { it.attr("href").ifBlank { it.attr("src") } }
            throwBlackboxReport("loadLinks", data, resp.code, "0 streams extracted. Found links: $sampleLinks")
        }
        return true
    }
}
