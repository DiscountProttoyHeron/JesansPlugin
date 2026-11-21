package com.jesan.hentaicity

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
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

// Zero-Failure Universal Deep-Extraction Provider (Custom HTML) for: https://www.hentaicity.com
// Preset: Archive.org (Public Domain) | Single All-Movies Mode: ON | CF Shield: OFF
// Card: .item | Title: h2, h3, .title | Poster: src
class HentaicityProvider : MainAPI() {
    override var mainUrl = "https://www.hentaicity.com"
    override var name = "Hentaicity"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val singleAllMoviesMode = true
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
    private val titleSelector = "h2, h3, .title"
    private val posterAttr = "src"
    private val searchPattern = "/customsearch.php?search={query}"
    private val paginationFormat = "/page/{page}/"
    private val customUserAgent = ""
    private val customReferer = ""
    private val customCookie = ""

    private val catalogPaths = listOf(
        "",
        "/pics/",
        "/categories/",
        "/characters/",
        "/channels/",
        "/members/"
    )

    // Per-Category Isolated Pagination & Lazy XML Sitemap State (keyed by request.name)
    private data class CategoryCrawlState(
        val seenCanonicalUrls: MutableSet<String> = Collections.synchronizedSet(linkedSetOf()),
        var catalogIndex: Int = 0,
        var catalogPage: Int = 1,
        var sitemapInitialized: Boolean = false,
        val pendingSitemaps: ArrayDeque<String> = ArrayDeque(),
        val visitedSitemaps: MutableSet<String> = linkedSetOf(),
        val sitemapBuffer: ArrayDeque<SearchResponse> = ArrayDeque()
    )

    private val categoryStates = ConcurrentHashMap<String, CategoryCrawlState>()
    private val catalogItemCache = ConcurrentHashMap<String, SearchResponse>()
    private val posterHydrateSemaphore = Semaphore(5)

    @Volatile
    private var workingSearchPattern: String? = null

    private val nonContentPathHints = listOf(
        "/category/", "/tag/", "/tags/", "/genre/", "/page/", "/author/",
        "/letter/", "/alphabet/", "/sort/", "/watchlist", "/account", "/feed",
        "/login", "/signin", "/register", "/wp-login", "/wp-admin", "/wp-content/",
        "/dmca", "/privacy", "/contact", "/about", "/terms", "/disclaimer",
        "/search", "/sitemap", "javascript:", "mailto:", "t.me/", "telegram"
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
    private val subtitleUrlRegex = Regex(
        "https?://[^\"'\\s<>\\\\]+\\.(?:vtt|srt|ass|ssa)(?:\\?[^\"'\\s<>\\\\]*)?",
        RegexOption.IGNORE_CASE
    )
    private val subtitleTrackObjRegex = Regex(
        "(?is)\\{[^{}]{0,400}?(?:file|src|url)\\s*[:=]\\s*[\"']([^\"']+\\.(?:vtt|srt|ass|ssa)(?:\\?[^\"']*)?)[\"'][^{}]{0,250}?\\}"
    )
    private val subtitleLabelInObjRegex = Regex(
        "(?i)(?:label|lang|language|srclang|title)\\s*[:=]\\s*[\"']([^\"']{1,32})[\"']"
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
    private val trackingParams = setOf("ref", "fbclid", "gclid")

    override val mainPage = mainPageOf(
        "" to "All Movies"
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
        return runCatching {
            URI(baseOverride.trimEnd('/') + "/").resolve(trimmed).toString()
        }.getOrElse {
            val base = baseOverride.trimEnd('/')
            when {
                trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
                trimmed.startsWith("//") -> "https:$trimmed"
                trimmed.startsWith("/") -> "$base$trimmed"
                else -> "$base/$trimmed"
            }
        }
    }

    /**
     * Canonical URL normalizer: forces https, strips www., drops utm_* / ref query params & fragments,
     * and trims trailing slashes to guarantee zero duplicate movie cards across categories & sitemaps.
     */
    private fun canonicalizeUrl(raw: String): String {
        val clean = raw.trim().substringBefore("#")
        return runCatching {
            val uri = URI(clean)
            val host = (uri.host ?: "").lowercase().removePrefix("www.")
            val path = (uri.path ?: "").trimEnd('/')
            val filteredQuery = uri.rawQuery
                ?.split("&")
                ?.filter { pair ->
                    val key = pair.substringBefore("=").lowercase()
                    pair.isNotBlank() && !key.startsWith("utm_") && key !in trackingParams
                }
                ?.sorted()
                ?.joinToString("&")
                .orEmpty()
            if (filteredQuery.isNotBlank()) "https://$host$path?$filteredQuery" else "https://$host$path"
        }.getOrElse {
            clean.lowercase().replace("http://", "https://").replace("://www.", "://").trimEnd('/')
        }
    }

    private fun isNonContentUrl(url: String): Boolean {
        val lower = url.lowercase().trimEnd('/')
        if (lower.isBlank() || canonicalizeUrl(lower) == canonicalizeUrl(mainUrl)) return true
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") ||
            lower.endsWith(".webp") || lower.endsWith(".xml") || lower.endsWith(".css") ||
            lower.endsWith(".js")
        ) return true
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

    private fun buildPagedUrl(categoryPath: String, page: Int, formatOverride: String = paginationFormat): String {
        val cleanPath = categoryPath.trim().trimEnd('/')
        val base = if (cleanPath.isBlank()) mainUrl else "$mainUrl/${cleanPath.trimStart('/')}"
        if (page <= 1) return base
        val suffix = formatOverride.replace("{page}", page.toString())
        return if (suffix.startsWith("?")) {
            if (base.contains("?")) "$base&${suffix.removePrefix("?")}" else "$base$suffix"
        } else {
            "${base.trimEnd('/')}/${suffix.trimStart('/')}"
        }
    }

    private fun deriveTitleFromSlug(url: String): String {
        val slug = runCatching {
            URI(url).path.orEmpty().trim('/').substringAfterLast('/')
        }.getOrDefault("")
        return slug
            .substringBeforeLast(".")
            .replace(Regex("[-_+]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .split(" ")
            .filter { it.isNotBlank() }
            .joinToString(" ") { w -> w.replaceFirstChar { it.uppercaseChar() } }
    }

    private fun Element.toSearchItem(): SearchResponse? {
        val anchor = this.selectFirst("a[href]") ?: return null
        val href = resolveUrl(anchor.attr("href"))
        if (isNonContentUrl(href)) return null
        val img = this.selectFirst("img")
        val rawTitle = this.selectFirst(titleSelector)?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: anchor.attr("title").trim().takeIf { it.isNotBlank() }
            ?: img?.attr("alt")?.trim()?.takeIf { it.isNotBlank() }
            ?: anchor.text().trim()
        val title = rawTitle
            .replace(Regex("(?i)^number\\s*\\d+\\s*[:\\-]\\s*"), "")
            .replace(Regex("(?i)\\bposter\\b"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (title.length < 2) return null
        val poster = extractPosterFromImg(img)
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = poster
        }
    }

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
                val canon = canonicalizeUrl(item.url)
                if (seenUrls.add(canon)) {
                    harvested.add(item)
                    catalogItemCache[canon] = item
                }
            }
        }
        return harvested
    }

    /**
     * Dual auto-pagination fetcher: tries configured paginationFormat first, then automatically
     * retries with ?page=N and ?paged=N if the primary format 404s or returns 0 unseen cards.
     */
    private suspend fun fetchUnseenCardsForPathPage(
        path: String,
        catPage: Int,
        seenCanonical: MutableSet<String>
    ): List<SearchResponse> {
        val formats = if (catPage <= 1) {
            listOf(paginationFormat)
        } else {
            listOf(paginationFormat, "/page/{page}/", "?page={page}", "?paged={page}").distinct()
        }
        for (fmt in formats) {
            val url = buildPagedUrl(path, catPage, fmt)
            val resp = runCatching { safeGet(url, mainUrl) }.getOrNull() ?: continue
            if (resp.code !in 200..299) continue
            val cards = harvestCards(resp.document)
            val unseen = mutableListOf<SearchResponse>()
            for (card in cards) {
                val canon = canonicalizeUrl(card.url)
                if (seenCanonical.add(canon)) {
                    unseen.add(card)
                    catalogItemCache[canon] = card
                }
            }
            if (unseen.isNotEmpty()) return unseen
        }
        return emptyList()
    }

    /**
     * Discovers root XML sitemaps via /robots.txt + standard WordPress/CMS sitemap endpoints.
     */
    private suspend fun initLazySitemapQueue(state: CategoryCrawlState) {
        if (state.sitemapInitialized) return
        state.sitemapInitialized = true
        val discovered = linkedSetOf<String>()
        runCatching {
            val robots = safeGet("$mainUrl/robots.txt", mainUrl)
            if (robots.code in 200..299) {
                Regex("(?im)^\\s*Sitemap\\s*:\\s*(https?://\\S+)").findAll(robots.text).forEach { m ->
                    discovered.add(m.groupValues[1].trim())
                }
            }
        }
        discovered.add("$mainUrl/sitemap_index.xml")
        discovered.add("$mainUrl/wp-sitemap.xml")
        discovered.add("$mainUrl/sitemap.xml")
        discovered.forEach { sm ->
            if (state.visitedSitemaps.add(sm)) {
                state.pendingSitemaps.addLast(sm)
            }
        }
    }

    /**
     * OOM-safe lazy XML sitemap pump: pulls ONE child sitemap at a time only when buffer < 25.
     */
    private suspend fun pumpNextSitemapBatch(
        state: CategoryCrawlState,
        batchSize: Int = 25
    ): List<SearchResponse> {
        initLazySitemapQueue(state)
        var xmlFetches = 0
        while (state.sitemapBuffer.size < batchSize && state.pendingSitemaps.isNotEmpty() && xmlFetches < 6) {
            val sitemapUrl = state.pendingSitemaps.removeFirst()
            xmlFetches++
            val xmlText = runCatching {
                val r = safeGet(sitemapUrl, mainUrl)
                if (r.code in 200..299) r.text else ""
            }.getOrDefault("")
            if (xmlText.isBlank()) continue

            val isIndex = xmlText.contains("<sitemapindex", ignoreCase = true)
            if (isIndex) {
                Regex("(?is)<sitemap\\b[^>]*>.*?<loc>\\s*([^<\\s]+)\\s*</loc>.*?</sitemap>")
                    .findAll(xmlText)
                    .forEach { m ->
                        val child = m.groupValues[1].trim()
                        val lowerChild = child.lowercase()
                        val isNoiseSitemap = lowerChild.contains("category") ||
                            lowerChild.contains("post_tag") ||
                            lowerChild.contains("author") ||
                            lowerChild.contains("page-sitemap")
                        if (!isNoiseSitemap && child.startsWith("http") && state.visitedSitemaps.add(child)) {
                            state.pendingSitemaps.addLast(child)
                        }
                    }
            } else {
                Regex("(?is)<url\\b[^>]*>(.*?)</url>").findAll(xmlText).forEach { blockMatch ->
                    val block = blockMatch.groupValues[1]
                    val loc = Regex("(?is)<loc>\\s*([^<\\s]+)\\s*</loc>").find(block)?.groupValues?.getOrNull(1)?.trim().orEmpty()
                    if (loc.isBlank() || isNonContentUrl(loc)) return@forEach
                    val canon = canonicalizeUrl(loc)
                    if (!state.seenCanonicalUrls.add(canon)) return@forEach
                    val imageLoc = Regex("(?is)<image:loc>\\s*([^<\\s]+)\\s*</image:loc>").find(block)?.groupValues?.getOrNull(1)?.trim()
                    val derivedTitle = deriveTitleFromSlug(loc)
                    if (derivedTitle.length < 2) return@forEach
                    val item = newMovieSearchResponse(derivedTitle, loc, TvType.Movie) {
                        this.posterUrl = imageLoc?.takeIf { it.startsWith("http") }
                    }
                    state.sitemapBuffer.addLast(item)
                    catalogItemCache[canon] = item
                }
            }
        }

        val rawBatch = mutableListOf<SearchResponse>()
        while (rawBatch.size < batchSize && state.sitemapBuffer.isNotEmpty()) {
            rawBatch.add(state.sitemapBuffer.removeFirst())
        }
        if (rawBatch.isEmpty()) return emptyList()

        // Concurrently hydrate missing posters (Semaphore(5), 4s per-page timeout)
        return coroutineScope {
            rawBatch.map { item ->
                async {
                    if (!item.posterUrl.isNullOrBlank()) {
                        item
                    } else {
                        posterHydrateSemaphore.withPermit {
                            val fetchedPoster = withTimeoutOrNull(4_000L) {
                                runCatching {
                                    val doc = safeGet(item.url, mainUrl).document
                                    resolveUrl(doc.selectFirst("meta[property=og:image]")?.attr("content"))
                                        .takeIf { it.isNotBlank() }
                                        ?: extractPosterFromImg(doc.selectFirst("article img, .poster img, img"))
                                }.getOrNull()
                            }
                            if (!fetchedPoster.isNullOrBlank()) {
                                val hydrated = newMovieSearchResponse(item.name, item.url, TvType.Movie) {
                                    this.posterUrl = fetchedPoster
                                }
                                catalogItemCache[canonicalizeUrl(item.url)] = hydrated
                                hydrated
                            } else {
                                item
                            }
                        }
                    }
                }
            }.awaitAll()
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val categoryKey = request.name
        if (page <= 1) {
            categoryStates[categoryKey] = CategoryCrawlState()
        }
        val state = categoryStates.computeIfAbsent(categoryKey) { CategoryCrawlState() }
        val useFullCatalogQueue = singleAllMoviesMode || request.data.isBlank()
        val pathsToWalk = if (useFullCatalogQueue) catalogPaths else listOf(request.data)

        try {
            // Phase A: Walk HTML catalog queue sequentially; immediately advance on empty/looping page
            while (state.catalogIndex < pathsToWalk.size) {
                val activePath = pathsToWalk[state.catalogIndex]
                val unseen = fetchUnseenCardsForPathPage(activePath, state.catalogPage, state.seenCanonicalUrls)
                if (unseen.isNotEmpty()) {
                    state.catalogPage += 1
                    return newHomePageResponse(HomePageList(request.name, unseen), true)
                } else {
                    // Infinite-scroll loop trap or end of category -> advance to next category immediately
                    state.catalogIndex += 1
                    state.catalogPage = 1
                }
            }

            // Phase B: OOM-safe lazy XML Sitemap exhaustive tail (for Single All Movies or Homepage feed)
            if (useFullCatalogQueue) {
                val sitemapBatch = pumpNextSitemapBatch(state, batchSize = 25)
                val hasMoreSitemaps = state.sitemapBuffer.isNotEmpty() || state.pendingSitemaps.isNotEmpty()
                return newHomePageResponse(
                    HomePageList(request.name, sitemapBatch),
                    sitemapBatch.isNotEmpty() && hasMoreSitemaps
                )
            }

            return newHomePageResponse(HomePageList(request.name, emptyList()), false)
        } catch (e: ErrorLoadingException) {
            throw e
        } catch (e: Exception) {
            throw ErrorLoadingException(
                "[CLOUDSTREAM_BLACKBOX_REPORT]\nProvider: $name\nStage: getMainPage\nCategory: $categoryKey\nPage: $page\nError: ${e.message}"
            )
        }
    }

    private fun applyQueryTokens(pattern: String, query: String): String {
        val trimmed = query.trim()
        val encoded = URLEncoder.encode(trimmed, "UTF-8")
        val rawEncoded = encoded.replace("+", "%20")
        val slug = trimmed.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
        return pattern
            .replace("{query}", encoded)
            .replace("{rawQuery}", rawEncoded)
            .replace("{slugQuery}", slug.ifBlank { encoded })
    }

    private fun parseJsonSearchResults(body: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        val seen = linkedSetOf<String>()
        fun scanArray(arr: JSONArray) {
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val title = obj.optString("title").ifBlank { obj.optString("name") }.trim()
                val rawUrl = obj.optString("url")
                    .ifBlank { obj.optString("link") }
                    .ifBlank { obj.optString("permalink") }
                    .ifBlank { obj.optString("slug").takeIf { it.isNotBlank() }?.let { "/${it.trimStart('/')}" }.orEmpty() }
                val resolved = resolveUrl(rawUrl, mainUrl)
                if (title.length < 2 || resolved.isBlank() || isNonContentUrl(resolved)) continue
                val poster = resolveUrl(
                    obj.optString("poster")
                        .ifBlank { obj.optString("img") }
                        .ifBlank { obj.optString("thumbnail") }
                        .ifBlank { obj.optString("image") },
                    mainUrl
                ).takeIf { it.isNotBlank() }
                val canon = canonicalizeUrl(resolved)
                if (seen.add(canon)) {
                    results.add(
                        newMovieSearchResponse(title, resolved, TvType.Movie) {
                            this.posterUrl = poster
                        }
                    )
                }
            }
        }
        val trimmed = body.trim()
        runCatching {
            when {
                trimmed.startsWith("[") -> scanArray(JSONArray(trimmed))
                trimmed.startsWith("{") -> {
                    val root = JSONObject(trimmed)
                    listOf("results", "data", "items", "movies", "posts", "hits").forEach { key ->
                        root.optJSONArray(key)?.let { scanArray(it) }
                    }
                    if (results.isEmpty()) {
                        val keys = root.keys()
                        while (keys.hasNext()) {
                            val k = keys.next()
                            root.optJSONArray(k)?.let { scanArray(it) }
                        }
                    }
                }
            }
        }
        return results
    }

    /**
     * Zero-404 Adaptive Multi-Tier Search Engine:
     * 1. Rejects <2-char queries
     * 2. Caches workingSearchPattern across searches
     * 3. Cascades verified GET search patterns + JSON API parser
     * 4. Falls back to local cached catalog & sitemap keyword matching on 404/empty
     */
    override suspend fun search(query: String): List<SearchResponse> {
        val trimmed = query.trim()
        if (trimmed.length < 2) return emptyList()

        val candidatePatterns = buildList {
            workingSearchPattern?.let { add(it) }
            if (searchPattern.isNotBlank()) add(searchPattern)
            add("/?s={query}")
            add("/search?q={query}")
            add("/search/{slugQuery}/")
            add("/find?q={query}")
        }.distinct()

        for (pattern in candidatePatterns) {
            val found = runCatching {
                val resp = if (pattern.startsWith("POST:")) {
                    val postPath = pattern.removePrefix("POST:")
                    val postUrl = "$mainUrl/${postPath.trimStart('/')}"
                    safePost(
                        postUrl,
                        mapOf(
                            "do" to "search",
                            "subaction" to "search",
                            "story" to trimmed
                        ),
                        mainUrl
                    )
                } else {
                    val path = applyQueryTokens(pattern, trimmed)
                    val url = if (path.startsWith("http://") || path.startsWith("https://")) {
                        path
                    } else {
                        "$mainUrl/${path.trimStart('/')}"
                    }
                    safeGet(url, mainUrl)
                }
                if (resp.code !in 200..299) {
                    emptyList()
                } else {
                    val ct = resp.headers["content-type"].orEmpty().lowercase()
                    val text = resp.text.trim()
                    if (ct.contains("application/json") || text.startsWith("{") || text.startsWith("[")) {
                        parseJsonSearchResults(text).ifEmpty { harvestCards(resp.document) }
                    } else {
                        harvestCards(resp.document)
                    }
                }
            }.getOrDefault(emptyList())

            if (found.isNotEmpty()) {
                workingSearchPattern = pattern
                return found
            }
        }

        // Local Catalog & Sitemap Fallback (never fail completely when site search is disabled/404)
        val tokens = trimmed.lowercase().split(Regex("\\s+")).filter { it.length >= 2 }
        val localPool = linkedMapOf<String, SearchResponse>()
        catalogItemCache.forEach { (k, v) -> localPool[k] = v }
        categoryStates.values.forEach { st ->
            st.sitemapBuffer.forEach { item ->
                localPool[canonicalizeUrl(item.url)] = item
            }
        }
        return localPool.values.filter { item ->
            val hay = (item.name + " " + item.url).lowercase()
            tokens.all { hay.contains(it) }
        }.take(30)
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

    private fun normalizeSubtitleLanguage(rawLabel: String?, urlHint: String): String {
        val combined = "${rawLabel.orEmpty()} $urlHint"
        val isCc = Regex("(?i)(?:\\bcc\\b|\\bsdh\\b|\\[cc\\]|closed.?caption)").containsMatchIn(combined)
        val cleanRaw = rawLabel.orEmpty()
            .replace(Regex("(?i)\\[?(?:cc|sdh)\\]?"), "")
            .trim()
        val codeCandidate = cleanRaw.lowercase().substringBefore("-").substringBefore("_").trim()
        val fileHint = urlHint.substringBefore("?").substringAfterLast("/").substringBeforeLast(".").lowercase()
        val normalized = when {
            codeCandidate in setOf("en", "eng", "english") || fileHint.endsWith(".en") || fileHint.contains("english") -> "English"
            codeCandidate in setOf("bn", "ben", "bangla", "bengali") || fileHint.endsWith(".bn") || fileHint.contains("bangla") || fileHint.contains("bengali") -> "Bangla"
            codeCandidate in setOf("hi", "hin", "hindi") || fileHint.endsWith(".hi") || fileHint.contains("hindi") -> "Hindi"
            codeCandidate in setOf("es", "spa", "spanish", "espanol") || fileHint.endsWith(".es") || fileHint.contains("spanish") -> "Spanish"
            codeCandidate in setOf("ar", "ara", "arabic") || fileHint.endsWith(".ar") || fileHint.contains("arabic") -> "Arabic"
            codeCandidate in setOf("fr", "fra", "fre", "french") || fileHint.endsWith(".fr") || fileHint.contains("french") -> "French"
            codeCandidate in setOf("ja", "jpn", "japanese") || fileHint.endsWith(".ja") || fileHint.contains("japanese") -> "Japanese"
            codeCandidate in setOf("ko", "kor", "korean") || fileHint.endsWith(".ko") || fileHint.contains("korean") -> "Korean"
            codeCandidate in setOf("de", "deu", "ger", "german") || fileHint.endsWith(".de") -> "German"
            codeCandidate in setOf("pt", "por", "portuguese") || fileHint.endsWith(".pt") -> "Portuguese"
            codeCandidate in setOf("id", "ind", "indonesian") || fileHint.endsWith(".id") -> "Indonesian"
            cleanRaw.length in 2..28 -> cleanRaw.replaceFirstChar { it.uppercaseChar() }
            else -> "English"
        }
        return if (isCc && !normalized.contains("[CC]")) "$normalized [CC]" else normalized
    }

    private suspend fun emitSubtitleIfNew(
        rawUrl: String,
        rawLabel: String?,
        pageUrl: String,
        emittedSubtitles: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        val resolved = resolveUrl(rawUrl, pageUrl)
        if (!resolved.startsWith("http")) return
        val canon = canonicalizeUrl(resolved)
        if (!emittedSubtitles.add(canon)) return
        val cleanLang = normalizeSubtitleLanguage(rawLabel, resolved)
        subtitleCallback.invoke(newSubtitleFile(cleanLang, resolved))
    }

    private suspend fun harvestSubtitlesFromDocument(
        doc: Document,
        pageUrl: String,
        emittedSubtitles: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        // Source 1: HTML5 <track> tags
        doc.select("track[src]").forEach { track ->
            val label = track.attr("label").ifBlank { track.attr("srclang") }
            emitSubtitleIfNew(track.attr("src"), label, pageUrl, emittedSubtitles, subtitleCallback)
        }
        // Source 2: Direct subtitle anchors (.vtt, .srt, .ass, .ssa)
        doc.select("a[href*=.vtt], a[href*=.srt], a[href*=.ass], a[href*=.ssa]").forEach { anchor ->
            emitSubtitleIfNew(anchor.attr("href"), anchor.text(), pageUrl, emittedSubtitles, subtitleCallback)
        }
        // Source 3: Inline player JS configs (JWPlayer/Plyr/Video.js/Artplayer) + JSON subtitle APIs
        val subJsonUrls = linkedSetOf<String>()
        doc.select("script").forEach { scriptEl ->
            val rawScript = scriptEl.data().replace("\\/", "/")
            if (rawScript.isBlank()) return@forEach
            subtitleTrackObjRegex.findAll(rawScript).forEach { m ->
                val subUrl = m.groupValues[1]
                val lbl = subtitleLabelInObjRegex.find(m.value)?.groupValues?.getOrNull(1)
                emitSubtitleIfNew(subUrl, lbl, pageUrl, emittedSubtitles, subtitleCallback)
            }
            subtitleUrlRegex.findAll(rawScript).forEach { m ->
                emitSubtitleIfNew(m.value, null, pageUrl, emittedSubtitles, subtitleCallback)
            }
            Regex("https?://[^\"'\\s<>\\\\]+subtitles\\.json(?:\\?[^\"'\\s<>\\\\]*)?", RegexOption.IGNORE_CASE)
                .findAll(rawScript)
                .forEach { m -> subJsonUrls.add(m.value) }
        }
        // Source 4: JSON subtitle APIs (subtitles.json)
        subJsonUrls.take(3).forEach { apiUrl ->
            runCatching {
                val body = safeGet(apiUrl, pageUrl).text.trim()
                val arr = when {
                    body.startsWith("[") -> JSONArray(body)
                    body.startsWith("{") -> JSONObject(body).optJSONArray("subtitles")
                        ?: JSONObject(body).optJSONArray("tracks")
                    else -> null
                }
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val u = o.optString("url").ifBlank { o.optString("file") }.ifBlank { o.optString("src") }
                        val l = o.optString("label").ifBlank { o.optString("lang") }.ifBlank { o.optString("language") }
                        if (u.isNotBlank()) emitSubtitleIfNew(u, l, pageUrl, emittedSubtitles, subtitleCallback)
                    }
                }
            }
        }
    }

    private fun isKnownExtractorHost(url: String): Boolean {
        val lower = url.lowercase()
        return knownExtractorHosts.any { lower.contains(it) }
    }

    private suspend fun invokeExtractorCounting(
        embedUrl: String,
        refererUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Int {
        var emitted = 0
        val countingCallback: (ExtractorLink) -> Unit = { link ->
            emitted++
            callback.invoke(link)
        }
        runCatching { loadExtractor(embedUrl, refererUrl, subtitleCallback, countingCallback) }
        return emitted
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
        emittedSubtitles: MutableSet<String>,
        triedEmbeds: MutableSet<String>,
        iframesFound: MutableList<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Int {
        var count = 0

        // Harvest subtitles from <track>, anchors, inline JS configs & subtitle APIs
        harvestSubtitlesFromDocument(doc, pageUrl, emittedSubtitles, subtitleCallback)

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
                    } else {
                        count += invokeExtractorCounting(resolved, pageUrl, subtitleCallback, callback)
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
        val emittedSubtitles = linkedSetOf<String>()
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
                doc, data, emittedStreams, emittedSubtitles, triedEmbeds, iframesFound, subtitleCallback, callback
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
                        if (subtitleUrlRegex.containsMatchIn(u)) {
                            emitSubtitleIfNew(u, null, data, emittedSubtitles, subtitleCallback)
                        } else if (triedEmbeds.add(u)) {
                            if (directStreamRegex.containsMatchIn(u)) {
                                if (emitDirectStream(u, data, "DooPlay AJAX", emittedStreams, callback)) linksFound++
                            } else {
                                linksFound += invokeExtractorCounting(u, data, subtitleCallback, callback)
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

            val intermediateQueue = mutableListOf<Pair<String, String>>()
            for ((candUrl, candLabel) in candidatePairs) {
                if (directStreamRegex.containsMatchIn(candUrl)) {
                    if (emitDirectStream(candUrl, data, candLabel, emittedStreams, callback)) linksFound++
                } else if (isKnownExtractorHost(candUrl)) {
                    if (triedEmbeds.add(candUrl)) {
                        linksFound += invokeExtractorCounting(candUrl, data, subtitleCallback, callback)
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
                            hopDoc, currentUrl, emittedStreams, emittedSubtitles, triedEmbeds, iframesFound, subtitleCallback, callback
                        )
                        var nextRedirect: String? = null
                        hopDoc.select("a[href], button[data-url]").forEach { el ->
                            val nextU = resolveUrl(el.attr("data-url").ifBlank { el.attr("href") }, currentUrl)
                            if (nextU.isBlank()) return@forEach
                            if (directStreamRegex.containsMatchIn(nextU)) {
                                if (emitDirectStream(nextU, currentUrl, hop1Label, emittedStreams, callback)) foundInChain++
                            } else if (isKnownExtractorHost(nextU)) {
                                if (triedEmbeds.add(nextU)) {
                                    foundInChain += invokeExtractorCounting(nextU, currentUrl, subtitleCallback, callback)
                                }
                            } else if (nextRedirect == null && hopDepth == 1 && !isNonContentUrl(nextU) && actionKeywordRegex.containsMatchIn(el.text() + " " + nextU)) {
                                nextRedirect = nextU
                            }
                        }
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

            if (linksFound == 0) {
                throw ErrorLoadingException(
                    """
                    [CLOUDSTREAM_BLACKBOX_REPORT]
                    Provider: ${this.name}
                    Stage: loadLinks
                    Target_URL: $data
                    HTTP_Status: ${lastStatus ?: "N/A"}
                    Subtitles_Found: ${emittedSubtitles.size}
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
