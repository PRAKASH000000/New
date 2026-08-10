package com.cinemaos.tv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.net.URLEncoder

class CinemaOSProvider : MainAPI() {
    override var mainUrl = "https://cinemaos.live"
    override var name = "CinemaOS V1"
    override val hasMainPage = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (iPhone; CPU iPhone OS 18_7 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.6 Mobile/15E148 Safari/604.1",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl, headers = defaultHeaders).document
        
        val items = document.select("a.group.block").mapNotNull { element ->
            val title = element.selectFirst("img")?.attr("alt") ?: return@mapNotNull null
            val url = fixUrlNull(element.attr("href")) ?: return@mapNotNull null
            val posterUrl = fixUrlNull(element.selectFirst("img")?.attr("src"))
            val isTv = url.contains("/tv/")

            if (isTv) {
                newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                    this.posterUrl = posterUrl
                }
            } else {
                newMovieSearchResponse(title, url, TvType.Movie) {
                    this.posterUrl = posterUrl
                }
            }
        }

        return newHomePageResponse(
            listOf(HomePageList("Trending", items)),
            hasNext = false
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search?q=$query"
        val document = app.get(searchUrl, headers = defaultHeaders).document

        return document.select("a.group.block").mapNotNull { element ->
            val title = element.selectFirst("img")?.attr("alt") ?: return@mapNotNull null
            val url = fixUrlNull(element.attr("href")) ?: return@mapNotNull null
            val posterUrl = fixUrlNull(element.selectFirst("img")?.attr("src"))
            val isTv = url.contains("/tv/")

            if (isTv) {
                newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                    this.posterUrl = posterUrl
                }
            } else {
                newMovieSearchResponse(title, url, TvType.Movie) {
                    this.posterUrl = posterUrl
                }
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = defaultHeaders).document

        val rawTitle = document.selectFirst("meta[property=og:title]")?.attr("content")?.replace("Watch ", "") ?: ""
        val title = rawTitle.substringBeforeLast(" (").trim()
        val year = rawTitle.substringAfterLast("(").replace(")", "").toIntOrNull()
        val plot = document.selectFirst("meta[property=og:description]")?.attr("content")
        val backgroundPoster = fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))
        
        val isTv = url.contains("/tv/")

        if (isTv) {
            val episodes = mutableListOf<Episode>()
            
            // Extract episodes from the UI layout dynamically
            document.select("a[href*=/watch/tv/]").forEach { epElement ->
                val epUrl = fixUrlNull(epElement.attr("href")) ?: return@forEach
                val seasonNum = Regex("""season=(\d+)""").find(epUrl)?.groupValues?.get(1)?.toIntOrNull()
                val episodeNum = Regex("""episode=(\d+)""").find(epUrl)?.groupValues?.get(1)?.toIntOrNull()
                
                if (seasonNum != null && episodeNum != null) {
                    episodes.add(
                        newEpisode(epUrl) {
                            this.name = "Season $seasonNum Episode $episodeNum"
                            this.season = seasonNum
                            this.episode = episodeNum
                        }
                    )
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.distinctBy { it.data }) {
                this.posterUrl = backgroundPoster
                this.plot = plot
                this.year = year
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = backgroundPoster
                this.plot = plot
                this.year = year
            }
        }
    }

    override suspend fun loadLinks(
        data: String, 
        isCasting: Boolean, 
        subtitleCallback: (SubtitleFile) -> Unit, 
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val isTv = data.contains("/tv/")
        val tmdbId = if (isTv) data.substringAfter("/tv/").substringBefore("?") else data.substringAfterLast("/")
        val season = if (isTv) Regex("""season=(\d+)""").find(data)?.groupValues?.get(1) ?: "1" else null
        val episode = if (isTv) Regex("""episode=(\d+)""").find(data)?.groupValues?.get(1) ?: "1" else null
        
        val doc = app.get(data, headers = defaultHeaders).document
        val rawTitle = doc.selectFirst("meta[property=og:title]")?.attr("content")?.replace("Watch ", "") ?: ""
        val encodedTitle = URLEncoder.encode(rawTitle.substringBeforeLast(" (").trim(), "UTF-8")

        val watchUrl = if (isTv) {
            "https://cinemaos.live/watch/tv/$tmdbId?season=$season&episode=$episode"
        } else {
            "https://cinemaos.live/watch/movie/$tmdbId"
        }

        var foundLinks = false

        try {
            // 1. Fetch the watch page HTML to steal the secure tokens embedded in the site's code
            val watchHtml = app.get(watchUrl, headers = defaultHeaders).text
            
            val secret = Regex("""["']?secret["']?\s*[:=]\s*["']([^"']+)["']""").find(watchHtml)?.groupValues?.get(1) ?: ""
            val gt = Regex("""["']?_gt["']?\s*[:=]\s*["']([^"']+)["']""").find(watchHtml)?.groupValues?.get(1) ?: ""
            val imdbId = Regex("""["']?imdbId["']?\s*[:=]\s*["'](tt\d+)["']""").find(watchHtml)?.groupValues?.get(1) ?: ""
            val ry = Regex("""["']?(?:ry|year)["']?\s*[:=]\s*["'](\d{4})["']""").find(watchHtml)?.groupValues?.get(1) ?: ""

            // 2. Build the API URL exactly how your network log structured it
            val scrapeUrl = if (isTv) {
                "https://cinemaos.live/api/providerv4/scrape?type=tv&tmdbId=$tmdbId&imdbId=$imdbId&seasonId=$season&episodeId=$episode&t=$encodedTitle&ry=$ry&secret=$secret&_gt=$gt&scraper=mb2"
            } else {
                "https://cinemaos.live/api/providerv4/scrape?type=movie&tmdbId=$tmdbId&imdbId=$imdbId&t=$encodedTitle&ry=$ry&secret=$secret&_gt=$gt&scraper=v2"
            }

            // 3. Query the scrape API
            val scrapeResponse = app.get(
                scrapeUrl,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (iPhone; CPU iPhone OS 18_7 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.6 Mobile/15E148 Safari/604.1",
                    "Referer" to watchUrl,
                    "Accept" to "application/json, text/plain, */*",
                    "X-Requested-With" to "XMLHttpRequest"
                )
            ).text

            // 4. Extract subtitle files from the JSON response
            val subs = Regex("""\{[^}]*?(?:\.vtt|\.srt)[^}]*?\}""").findAll(scrapeResponse)
            subs.forEach { match ->
                val block = match.value
                val subUrl = Regex(""""(?:url|file|src)"\s*:\s*"([^"]+)"""").find(block)?.groupValues?.get(1)?.replace("\\/", "/") ?: return@forEach
                val subLang = Regex(""""(?:name|label|language)"\s*:\s*"([^"]+)"""").find(block)?.groupValues?.get(1) ?: "English"
                if (subUrl.isNotBlank()) {
                    subtitleCallback.invoke(SubtitleFile(subLang, subUrl))
                }
            }

            // 5. Extract multi-audio/server streams directly from the JSON
            val streamMatches = Regex("""\{[^}]*?(?:\.mpd|\.m3u8|\.m4s)[^}]*?\}""").findAll(scrapeResponse).toList()
            
            if (streamMatches.isNotEmpty()) {
                streamMatches.forEachIndexed { index, match ->
                    val block = match.value
                    val streamUrl = Regex(""""(?:url|file|stream|link)"\s*:\s*"([^"]+)"""").find(block)?.groupValues?.get(1)?.replace("\\/", "/") ?: return@forEachIndexed
                    val serverName = Regex(""""(?:name|title|label|server|language)"\s*:\s*"([^"]+)"""").find(block)?.groupValues?.get(1) ?: "CinemaOS Server ${index + 1}"
                    
                    if (streamUrl.isNotBlank() && !streamUrl.contains("error")) {
                        foundLinks = true
                        val isDash = streamUrl.contains(".mpd")
                        val linkType = if (isDash) ExtractorLinkType.DASH else ExtractorLinkType.M3U8

                        callback.invoke(
                            newExtractorLink(
                                source = "CinemaOS V1",
                                name = serverName,
                                url = streamUrl,
                                type = linkType
                            ) {
                                this.referer = "https://cinemaos.live/"
                                this.quality = Qualities.P1080.value
                            }
                        )
                    }
                }
            } else {
                // Secondary fallback if the JSON is structured flat instead of nested blocks
                val flatUrls = Regex(""""(?:url|file|stream|link)"\s*:\s*"([^"]+(?:\.mpd|\.m3u8|\.m4s)[^"]*)"""").findAll(scrapeResponse)
                flatUrls.forEachIndexed { index, match ->
                    val streamUrl = match.groupValues[1].replace("\\/", "/")
                    if (streamUrl.isNotBlank() && !streamUrl.contains("error")) {
                        foundLinks = true
                        val isDash = streamUrl.contains(".mpd")
                        
                        callback.invoke(
                            newExtractorLink(
                                source = "CinemaOS V1",
                                name = "CinemaOS Stream ${index + 1}",
                                url = streamUrl,
                                type = if (isDash) ExtractorLinkType.DASH else ExtractorLinkType.M3U8
                            ) {
                                this.referer = "https://cinemaos.live/"
                                this.quality = Qualities.P1080.value
                            }
                        )
                    }
                }
            }
        } catch (e: Exception) {
            // Ignore API faults and trigger WebView fallback
        }

        // 6. Final Failsafe: If tokens fail to extract or the API denies the request, fall back to capturing default playback
        if (!foundLinks) {
            val interceptor = com.lagradost.cloudstream3.network.WebViewResolver(
                Regex("""(?i)\.mpd|\.m3u8|manifest|\.vtt""")
            )

            try {
                val response = app.get(watchUrl, interceptor = interceptor)
                val caughtUrl = response.url

                if (caughtUrl.isNotBlank() && !caughtUrl.contains("/watch/")) {
                    if (caughtUrl.contains(".vtt")) {
                        subtitleCallback.invoke(SubtitleFile("English", caughtUrl))
                    } else {
                        val isDash = caughtUrl.contains(".mpd")
                        callback.invoke(
                            newExtractorLink(
                                source = "CinemaOS V1",
                                name = "Alpha - English (Default)",
                                url = caughtUrl,
                                type = if (isDash) ExtractorLinkType.DASH else ExtractorLinkType.M3U8
                            ) {
                                this.referer = "https://cinemaos.live/"
                                this.quality = Qualities.P1080.value
                            }
                        )
                    }
                }
            } catch (e: Exception) {
                // Ignore silent timeouts
            }
        }

        return true
    }
}
