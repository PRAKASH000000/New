package com.cinemaos.tv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

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
            
            // Extract episodes dynamically from the DOM layout
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
        
        val watchUrl = if (isTv) {
            "https://cinemaos.live/watch/tv/$tmdbId?season=$season&episode=$episode"
        } else {
            "https://cinemaos.live/watch/movie/$tmdbId"
        }

        var foundLinks = false
        var scrapeApiUrl = ""

        // 1. Intercept the Scrape API call to get the dynamic Javascript tokens (secret & _gt)
        val scrapeInterceptor = com.lagradost.cloudstream3.network.WebViewResolver(
            Regex("""api/providerv4/scrape""")
        )

        try {
            val response = app.get(watchUrl, interceptor = scrapeInterceptor)
            if (response.url.contains("scrape")) {
                scrapeApiUrl = response.url // We successfully stole the authorized URL!
            }
        } catch (e: Exception) {
            // Ignore interception timeouts
        }

        // 2. Fetch the JSON from the captured API and parse it using a resilient Window Search
        if (scrapeApiUrl.isNotBlank()) {
            try {
                val scrapeResponse = app.get(
                    scrapeApiUrl,
                    headers = mapOf(
                        "User-Agent" to defaultHeaders["User-Agent"]!!,
                        "Referer" to watchUrl,
                        "Accept" to "application/json, text/plain, */*"
                    )
                ).text

                // Find absolutely every URL mentioned in the JSON payload
                val urlMatches = Regex(""""(?:url|file|stream|link|src)"\s*:\s*"([^"]+)"""").findAll(scrapeResponse)
                
                var serverCount = 1

                for (match in urlMatches) {
                    val streamUrl = match.groupValues[1].replace("\\/", "/")
                    if (!streamUrl.startsWith("http")) continue

                    // Create a text window around the URL to find its assigned language/label, bypassing nested { } brackets
                    val startIdx = maxOf(0, match.range.first - 250)
                    val endIdx = minOf(scrapeResponse.length, match.range.last + 250)
                    val window = scrapeResponse.substring(startIdx, endIdx)
                    
                    val labelMatch = Regex(""""(?:name|label|title|server|language)"\s*:\s*"([^"]+)"""").find(window)
                    val serverName = labelMatch?.groupValues?.get(1) ?: "Server $serverCount"

                    // Handle Subtitles
                    if (streamUrl.contains(".vtt") || streamUrl.contains(".srt")) {
                        subtitleCallback.invoke(SubtitleFile(serverName, streamUrl))
                    } 
                    // Handle Video/Media Streams
                    else {
                        foundLinks = true
                        
                        // If it is an internal AWS DASH/HLS/MP4 link
                        if (streamUrl.contains(".mpd") || streamUrl.contains(".m3u8") || streamUrl.contains(".m4s") || streamUrl.contains(".mp4")) {
                            val isDash = streamUrl.contains(".mpd")
                            val isM3u8 = streamUrl.contains(".m3u8") || streamUrl.contains("m4s")
                            
                            val linkType = when {
                                isDash -> ExtractorLinkType.DASH
                                isM3u8 -> ExtractorLinkType.M3U8
                                else -> ExtractorLinkType.VIDEO
                            }
                            
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
                        // If it is an external iframe embed (like VidSrc, Wyzie, etc.)
                        else {
                            try {
                                loadExtractor(streamUrl, subtitleCallback, callback)
                            } catch (e: Exception) {
                                // Skip unsupported public iframe embeds
                            }
                        }
                    }
                    serverCount++
                }
            } catch (e: Exception) {
                // Ignore parsing errors
            }
        }

        // 3. Absolute Failsafe: If the JS token scraper fails, fall back to grabbing the default native track
        if (!foundLinks) {
            val mediaInterceptor = com.lagradost.cloudstream3.network.WebViewResolver(
                Regex("""(?i)\.mpd|\.m3u8|manifest|\.vtt""")
            )

            try {
                val response = app.get(watchUrl, interceptor = mediaInterceptor)
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
                // Silently handle timeouts
            }
        }

        return true
    }
}
