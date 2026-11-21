package com.jesan.archive

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
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import java.net.URLEncoder

class ArchiveProvider : MainAPI() {
    override var mainUrl = "https://archive.org"
    override var name = "Archive"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Documentary)

    override val mainPage = mainPageOf(
        "mediatype:(movies) AND NOT collection:(test_collection)" to "Latest Movies",
        "collection:(feature_films) AND mediatype:(movies)" to "Feature Films",
        "collection:(sci_fi_horror) AND mediatype:(movies)" to "Sci-Fi & Horror Classics",
        "collection:(film_noir) AND mediatype:(movies)" to "Classic Film Noir",
        "collection:(animationandcartoons) AND mediatype:(movies)" to "Classic Cartoons",
        "collection:(short_films OR documentaries) AND mediatype:(movies)" to "Short Films & Documentaries"
    )

    private fun encodeFilePath(fileName: String): String {
        return fileName.split("/").joinToString("/") { part ->
            URLEncoder.encode(part, "UTF-8").replace("+", "%20")
        }
    }

    private fun parseArchiveSearchJson(jsonText: String): List<SearchResponse> {
        val list = mutableListOf<SearchResponse>()
        val docs = JSONObject(jsonText).optJSONObject("response")?.optJSONArray("docs") ?: return emptyList()
        for (i in 0 until docs.length()) {
            val item = docs.optJSONObject(i) ?: continue
            val id = item.optString("identifier").trim()
            val title = item.optString("title").trim().ifBlank { id }
            if (id.isBlank()) continue
            val detailUrl = "$mainUrl/details/$id"
            val posterUrl = "$mainUrl/services/img/$id"
            list.add(
                newMovieSearchResponse(title, detailUrl, TvType.Movie) {
                    this.posterUrl = posterUrl
                }
            )
        }
        return list
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val sortField = if (request.name == "Latest Movies") "addeddate+desc" else "downloads+desc"
        val encodedQuery = URLEncoder.encode(request.data, "UTF-8")
        val apiUrl = "$mainUrl/advancedsearch.php?q=$encodedQuery&fl[]=identifier,title&sort[]=$sortField&rows=24&page=$page&output=json"
        val resp = app.get(apiUrl)
        if (resp.code !in 200..299) {
            throw ErrorLoadingException("[CLOUDSTREAM_BLACKBOX_REPORT]\nProvider: $name\nStage: getMainPage\nTarget_URL: $apiUrl\nHTTP_Status: ${resp.code}")
        }
        val items = parseArchiveSearchJson(resp.text)
        return newHomePageResponse(HomePageList(request.name, items), items.size >= 24)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode("(${query.trim()}) AND mediatype:(movies)", "UTF-8")
        val apiUrl = "$mainUrl/advancedsearch.php?q=$encodedQuery&fl[]=identifier,title&rows=30&page=1&output=json"
        val resp = app.get(apiUrl)
        if (resp.code !in 200..299) return emptyList()
        return parseArchiveSearchJson(resp.text)
    }

    override suspend fun load(url: String): LoadResponse {
        val id = url.substringAfter("/details/").substringBefore("/").substringBefore("?")
        val metaUrl = "$mainUrl/metadata/$id"
        val rootJson = JSONObject(app.get(metaUrl).text)
        val metaJson = rootJson.optJSONObject("metadata")
        val filesArray = rootJson.optJSONArray("files")

        val title = metaJson?.optString("title")?.takeIf { it.isNotBlank() } ?: id
        val plot = metaJson?.optString("description")?.replace(Regex("<[^>]*>"), "")?.trim()
        val year = metaJson?.optString("year")?.take(4)?.toIntOrNull()
        val poster = "$mainUrl/services/img/$id"

        val videoFiles = mutableListOf<String>()
        if (filesArray != null) {
            for (i in 0 until filesArray.length()) {
                val fObj = filesArray.optJSONObject(i) ?: continue
                val fName = fObj.optString("name").trim()
                val lower = fName.lowercase()
                if ((lower.endsWith(".mp4") || lower.endsWith(".mkv")) && !lower.contains("_512kb")) {
                    videoFiles.add(fName)
                }
            }
        }

        val distinctVideos = videoFiles.distinct().sorted()
        if (distinctVideos.size >= 2) {
            val seRegex = Regex("(?i)s0*(\\d+)\\s*e0*(\\d+)")
            val epRegex = Regex("(?i)(?:ep|episode|e)\\s*0*(\\d+)")
            val episodes = distinctVideos.mapIndexed { idx, fileName ->
                val cleanName = fileName.substringAfterLast("/").substringBeforeLast(".")
                val seMatch = seRegex.find(cleanName)
                val epMatch = epRegex.find(cleanName)
                val seasonNum = seMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
                val episodeNum = seMatch?.groupValues?.getOrNull(2)?.toIntOrNull()
                    ?: epMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: (idx + 1)
                newEpisode("$id|$fileName") {
                    this.name = cleanName
                    this.season = seasonNum
                    this.episode = episodeNum
                }
            }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, id) {
            this.posterUrl = poster
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
        val isEpisodeData = data.contains("|")
        val id = if (isEpisodeData) {
            data.substringBefore("|")
        } else {
            data.substringAfter("/details/").substringBefore("/").substringBefore("?")
        }
        val targetFiles = mutableListOf<String>()

        if (isEpisodeData) {
            targetFiles.add(data.substringAfter("|"))
        } else {
            val metaUrl = "$mainUrl/metadata/$id"
            val files = JSONObject(app.get(metaUrl).text).optJSONArray("files")
            if (files != null) {
                for (i in 0 until files.length()) {
                    val fName = files.optJSONObject(i)?.optString("name")?.trim().orEmpty()
                    if (fName.endsWith(".mp4", true) || fName.endsWith(".mkv", true)) {
                        targetFiles.add(fName)
                    }
                }
            }
        }

        var found = false
        for (fileName in targetFiles.distinct()) {
            val encodedPath = encodeFilePath(fileName)
            val streamUrl = "$mainUrl/serve/$id/$encodedPath"
            val quality = when {
                fileName.contains("1080", true) -> Qualities.P1080.value
                fileName.contains("720", true) -> Qualities.P720.value
                fileName.contains("480", true) -> Qualities.P480.value
                else -> Qualities.Unknown.value
            }
            val label = fileName.substringAfterLast("/")
            callback.invoke(
                newExtractorLink(this.name, "$name ($label)", streamUrl, ExtractorLinkType.VIDEO) {
                    this.referer = "$mainUrl/details/$id"
                    this.quality = quality
                }
            )
            found = true
        }

        if (!found) {
            throw ErrorLoadingException("[CLOUDSTREAM_BLACKBOX_REPORT]\nProvider: $name\nStage: loadLinks\nTarget_ID: $id\nError: No playable MP4/MKV found")
        }
        return true
    }
}
