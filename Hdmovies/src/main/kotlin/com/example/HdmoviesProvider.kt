package com.example

import com.fasterxml.jackson.annotation.JsonProperty
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
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLEncoder

// Zero-Failure Modern CloudStream 3 Provider Scaffold for: https://hdmovieszone.in
// Edit selectors below or use Apply AI Patch to customize for this site.
class HdmoviesProvider : MainAPI() {
    override var mainUrl = "https://hdmovieszone.in"
    override var name = "Hdmovies"
    override val hasMainPage = true
    override var lang = "hin"
    override val supportedTypes = setOf(TvType.Movie)

    override val mainPage = mainPageOf(
        "featured" to "Featured",
        "latest" to "Latest"
    )

    private fun Any?.toSafeString(): String? = when (this) {
        is String -> this
        is List<*> -> this.firstOrNull()?.toString()
        else -> this?.toString()
    }

    private fun encodeFilePath(fileName: String): String =
        fileName.trimStart('/').split("/").joinToString("/") { segment ->
            URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
        }

    private fun throwBlackboxReport(
        stage: String,
        targetUrl: String,
        httpStatus: Int? = null,
        filesScanned: Int = 0,
        availableExtensions: List<String> = emptyList(),
        errorMessage: String
    ): Nothing {
        throw ErrorLoadingException(
            """
            [CLOUDSTREAM_BLACKBOX_REPORT]
            Provider: ${this.name}
            Stage: $stage
            Target_URL: $targetUrl
            HTTP_Status: ${httpStatus ?: "N/A"}
            Files_Scanned: $filesScanned
            Available_Extensions: ${if (availableExtensions.isEmpty()) "none" else availableExtensions.joinToString(", ")}
            Error: $errorMessage
            """.trimIndent()
        )
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = "$mainUrl/api/catalog?category=${request.data}&page=$page"
        try {
            val resp = app.get(url)
            if (resp.code !in 200..299) {
                throwBlackboxReport("getMainPage", url, resp.code, errorMessage = "Non-2xx HTTP status")
            }
            val catalog = AppUtils.parseJson<GenericCatalogResponse>(resp.text)
            val items = catalog.items.orEmpty().mapNotNull { entry ->
                val title = entry.title.toSafeString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val id = entry.id.toSafeString() ?: return@mapNotNull null
                newMovieSearchResponse(title, "$mainUrl/item/$id", TvType.Movie) {
                    this.posterUrl = entry.posterUrl.toSafeString()
                }
            }
            return newHomePageResponse(HomePageList(request.name, items), hasNext = items.isNotEmpty())
        } catch (e: ErrorLoadingException) {
            throw e
        } catch (e: Exception) {
            throwBlackboxReport("getMainPage", url, errorMessage = e.message ?: "Parse failure")
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/api/search?q=${URLEncoder.encode(query.trim(), "UTF-8")}"
        try {
            val resp = app.get(url)
            if (resp.code !in 200..299) {
                throwBlackboxReport("search", url, resp.code, errorMessage = "Non-2xx HTTP status")
            }
            val catalog = AppUtils.parseJson<GenericCatalogResponse>(resp.text)
            return catalog.items.orEmpty().mapNotNull { entry ->
                val title = entry.title.toSafeString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val id = entry.id.toSafeString() ?: return@mapNotNull null
                newMovieSearchResponse(title, "$mainUrl/item/$id", TvType.Movie) {
                    this.posterUrl = entry.posterUrl.toSafeString()
                }
            }
        } catch (e: ErrorLoadingException) {
            throw e
        } catch (e: Exception) {
            throwBlackboxReport("search", url, errorMessage = e.message ?: "Search failure")
        }
    }

    override suspend fun load(url: String): LoadResponse {
        try {
            val doc = app.get(url).document
            val title = doc.selectFirst("h1, title")?.text()?.trim().orEmpty().ifBlank { name }
            val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            val plot = doc.selectFirst("meta[name=description]")?.attr("content")
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
            }
        } catch (e: ErrorLoadingException) {
            throw e
        } catch (e: Exception) {
            throwBlackboxReport("load", url, errorMessage = e.message ?: "Load failure")
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var linksFound = 0
        try {
            val resp = app.get(data)
            val doc = resp.document
            doc.select("a[href$=.mp4], a[href$=.m3u8], a[href$=.mkv], a[href$=.webm], a[href$=.ogv], source[src]")
                .forEach { el ->
                    val raw = (el.attr("href").ifBlank { el.attr("src") }).trim()
                    if (raw.isBlank()) return@forEach
                    val resolved = if (raw.startsWith("http")) raw else "$mainUrl/${encodeFilePath(raw)}"
                    val isHls = resolved.lowercase().contains(".m3u8")
                    callback.invoke(
                        newExtractorLink(
                            source = this.name,
                            name = "$name Direct",
                            url = resolved,
                            type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.referer = mainUrl
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    linksFound++
                }
            if (linksFound == 0) {
                throwBlackboxReport(
                    stage = "loadLinks",
                    targetUrl = data,
                    httpStatus = resp.code,
                    errorMessage = "0 video links matched on target page"
                )
            }
            return true
        } catch (e: ErrorLoadingException) {
            throw e
        } catch (e: Exception) {
            throwBlackboxReport("loadLinks", data, errorMessage = e.message ?: "Extractor error")
        }
    }
}

data class GenericCatalogResponse(
    @JsonProperty("items") val items: List<GenericCatalogItem>? = null
)

data class GenericCatalogItem(
    @JsonProperty("id") val id: Any? = null,
    @JsonProperty("title") val title: Any? = null,
    @JsonProperty("poster_url") val posterUrl: Any? = null
)
