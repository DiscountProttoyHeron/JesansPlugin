package com.jesan.youtube

import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.toNewSearchResponseList
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.StringUtils.encodeUri
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink

class YoutubeProvider : MainAPI() {

    override var mainUrl = "https://www.youtube.com"
    override var name = "YouTube"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie)

    private val apiBaseUrl = "https://inv.nadeko.net"

    private val userAgent = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    override val mainPage = com.lagradost.cloudstream3.mainPageOf(
        "trending" to "Trending",
        "popular" to "Popular"
    )

    private data class SearchEntry(
        val title: String? = null,
        val videoId: String? = null,
        val videoThumbnails: List<Thumbnail> = emptyList(),
        val author: String? = null
    )

    private data class Thumbnail(
        val url: String? = null
    )

    private data class VideoEntry(
        val title: String? = null,
        val description: String? = null,
        val videoId: String? = null,
        val author: String? = null,
        val authorThumbnails: List<Thumbnail> = emptyList(),
        val recommendedVideos: List<SearchEntry> = emptyList(),
        val lengthSeconds: Long? = null
    )

    private fun normalizeVideoUrl(raw: String?): String {
        val value = raw?.trim().orEmpty()
        if (value.isBlank()) {
            throw ErrorLoadingException("YouTube URL is empty")
        }

        val candidate = when {
            value.startsWith("https://", true) || value.startsWith("http://", true) -> value
            value.startsWith("//") -> "https:$value"
            value.startsWith("www.youtube.com", true) ||
                value.startsWith("youtube.com", true) ||
                value.startsWith("m.youtube.com", true) ||
                value.startsWith("youtu.be", true) -> "https://$value"
            value.matches(Regex("[A-Za-z0-9_-]{11}")) -> "$mainUrl/watch?v=$value"
            else -> throw ErrorLoadingException("Unsupported YouTube URL: $value")
        }

        val host = Regex("(?i)^https?://([^/:?#]+)").find(candidate)
            ?.groupValues?.getOrNull(1)?.lowercase()
            ?: throw ErrorLoadingException("Invalid URL scheme or host: $candidate")

        if (host !in setOf("youtube.com", "www.youtube.com", "m.youtube.com", "youtu.be")) {
            throw ErrorLoadingException("Unsupported YouTube host: $host")
        }

        return candidate
    }

    private fun extractVideoId(raw: String): String? {
        val value = raw.trim()
        if (value.matches(Regex("[A-Za-z0-9_-]{11}"))) return value

        val normalized = runCatching { normalizeVideoUrl(value) }.getOrNull() ?: return null

        return Regex("(?:[?&]v=|youtu\\.be/|/shorts/|/live/|/embed/)([A-Za-z0-9_-]{11})")
            .find(normalized)
            ?.groupValues
            ?.getOrNull(1)
    }

    private fun reportError(stage: String, url: String, status: Int?, message: String): Nothing {
        throw ErrorLoadingException(
            """
            [CLOUDSTREAM_BLACKBOX_REPORT]
            Provider: $name
            Stage: $stage
            Target_URL: $url
            HTTP_Status: ${status ?: "N/A"}
            Info: $message
            """.trimIndent()
        )
    }

    private suspend fun fetchEntries(path: String, stage: String): List<SearchEntry> {
        val url = "$apiBaseUrl$path"
        try {
            val response = app.get(
                url,
                headers = mapOf(
                    "User-Agent" to userAgent,
                    "Accept" to "application/json"
                )
            )

            if (response.code !in 200..299) {
                reportError(stage, url, response.code, "Invidious API returned a non-success status")
            }

            return tryParseJson<List<SearchEntry>>(response.text)
                ?: reportError(stage, url, response.code, "Unable to parse API JSON response")
        } catch (e: ErrorLoadingException) {
            throw e
        } catch (e: Exception) {
            reportError(stage, url, null, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun SearchEntry.toSearchResponse(): SearchResponse? {
        val id = videoId?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) } ?: return null
        val videoTitle = title?.takeIf { it.isNotBlank() } ?: return null

        return newMovieSearchResponse(
            videoTitle,
            "$mainUrl/watch?v=$id",
            TvType.Movie
        ) {
            posterUrl = videoThumbnails.lastOrNull()?.url
                ?: "https://i.ytimg.com/vi/$id/hqdefault.jpg"
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        if (page > 1) {
            return newHomePageResponse(emptyList(), false)
        }

        val sections = mutableListOf<HomePageList>()

        val trending = fetchEntries(
            "/api/v1/trending?fields=videoId,title,videoThumbnails,author",
            "getMainPage_trending"
        )

        val trendingItems = trending.mapNotNull { it.toSearchResponse() }
        if (trendingItems.isNotEmpty()) {
            sections.add(HomePageList("Trending", trendingItems, false))
        }

        val popular = runCatching {
            fetchEntries(
                "/api/v1/popular?fields=videoId,title,videoThumbnails,author",
                "getMainPage_popular"
            ).mapNotNull { it.toSearchResponse() }
        }.getOrDefault(emptyList())

        if (popular.isNotEmpty()) {
            sections.add(HomePageList("Popular", popular, false))
        }

        if (sections.isEmpty()) {
            reportError("getMainPage", apiBaseUrl, null, "No videos returned by the Invidious API")
        }

        return newHomePageResponse(sections, false)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return emptyList<SearchResponse>().toNewSearchResponseList()

        val safePage = page.coerceAtLeast(1)
        val path = "/api/v1/search?q=${cleanQuery.encodeUri()}&page=$safePage&type=video&fields=videoId,title,videoThumbnails,author"

        return fetchEntries(path, "search")
            .mapNotNull { it.toSearchResponse() }
            .toNewSearchResponseList()
    }

    override suspend fun load(url: String): LoadResponse {
        val videoId = extractVideoId(url)
            ?: throw ErrorLoadingException("Invalid YouTube video URL or video ID: $url")

        val apiUrl = "$apiBaseUrl/api/v1/videos/$videoId?fields=videoId,title,description,author,authorThumbnails,recommendedVideos,lengthSeconds"

        try {
            val response = app.get(
                apiUrl,
                headers = mapOf(
                    "User-Agent" to userAgent,
                    "Accept" to "application/json"
                )
            )

            if (response.code !in 200..299) {
                reportError("load", apiUrl, response.code, "Unable to retrieve YouTube video metadata")
            }

            val video = tryParseJson<VideoEntry>(response.text)
                ?: reportError("load", apiUrl, response.code, "Invalid video metadata JSON")

            val title = video.title?.takeIf { it.isNotBlank() } ?: "YouTube Video"
            val youtubeUrl = "$mainUrl/watch?v=$videoId"

            return newMovieLoadResponse(
                title,
                youtubeUrl,
                TvType.Movie,
                videoId
            ) {
                plot = video.description
                posterUrl = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
                duration = video.lengthSeconds?.takeIf { it > 0 }?.toInt()
                tags = listOf("YouTube")

                val author = video.author?.takeIf { it.isNotBlank() }
                if (author != null) {
                    actors = listOf(
                        ActorData(
                            Actor(
                                author,
                                video.authorThumbnails.lastOrNull()?.url ?: ""
                            ),
                            roleString = "Channel"
                        )
                    )
                }

                recommendations = video.recommendedVideos.mapNotNull {
                    it.toSearchResponse()
                }
            }
        } catch (e: ErrorLoadingException) {
            throw e
        } catch (e: Exception) {
            reportError("load", apiUrl, null, e.message ?: e.javaClass.simpleName)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val videoId = extractVideoId(data)
            ?: throw ErrorLoadingException("Invalid YouTube video ID: $data")

        val youtubeUrl = "$mainUrl/watch?v=$videoId"
        var extracted = false

        try {
            extracted = loadExtractor(
                youtubeUrl,
                youtubeUrl,
                subtitleCallback,
                callback
            )
        } catch (e: Exception) {
            // Continue to the Invidious DASH fallback below.
        }

        val manifestUrl = "$apiBaseUrl/api/manifest/dash/id/$videoId"

        try {
            callback.invoke(
                newExtractorLink(
                    this.name,
                    "YouTube DASH",
                    manifestUrl,
                    ExtractorLinkType.DASH
                ) {
                    this.referer = youtubeUrl
                    this.quality = Qualities.Unknown.value
                }
            )
            return true
        } catch (e: Exception) {
            if (!extracted) {
                throw ErrorLoadingException(
                    "YouTube extraction failed. Video: $videoId. Error: ${e.message ?: e.javaClass.simpleName}"
                )
            }
        }

        return extracted
    }
}
