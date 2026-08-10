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

        try {
            // 1. Fetch HTML to find security tokens
            val watchHtml = app.get(watchUrl, headers = defaultHeaders).text
            
            // Deep extraction: Try multiple regex patterns to guarantee we catch the tokens
            var secret = Regex("""["']?secret["']?\s*[:=]\s*["']([^"']+)["']""").find(watchHtml)?.groupValues?.get(1)
            var gt = Regex("""["']?_gt["']?\s*[:=]\s*["']([^"']+)["']""").find(watchHtml)?.groupValues?.get(1)
            val imdbId = Regex("""["']?imdbId["']?\s*[:=]\s*["'](tt\d+)["']""").find(watchHtml)?.groupValues?.get(1) ?: ""
            val ry = Regex("""["']?(?:ry|year)["']?\s*[:=]\s*["'](\d{4})["']""").find(watchHtml)?.groupValues?.get(1) ?: ""
            val rawTitle = Regex("""<title>([^<]+)</title>""").find(watchHtml)?.groupValues?.get(1)?.replace("Watch ", "") ?: ""
            val encodedTitle = URLEncoder.encode(rawTitle.substringBeforeLast(" (").trim(), "UTF-8")

            // Failsafe: If tokens aren't in the HTML, check the JS files linked in the page
            if (secret == null || gt == null) {
                val scriptUrls = Regex("""<script[^>]+src=["']([^"']+)["']""").findAll(watchHtml).mapNotNull { it.groupValues[1] }.toList()
                for (scriptUrl in scriptUrls) {
                    try {
                        val absoluteUrl = if (scriptUrl.startsWith("http")) scriptUrl else "$mainUrl$scriptUrl"
                        val scriptText = app.get(absoluteUrl, headers = defaultHeaders).text
                        if (secret == null) secret = Regex("""["']?secret["']?\s*[:=]\s*["']([^"']+)["']""").find(scriptText)?.groupValues?.get(1)
                        if (gt == null) gt = Regex("""["']?_gt["']?\s*[:=]\s*["']([^"']+)["']""").find(scriptText)?.groupValues?.get(1)
                        if (secret != null && gt != null) break
                    } catch (e: Exception) {}
                }
            }

            // OpenSubtitles External Fetch
            if (imdbId.isNotBlank()) {
                try {
                    val subResponse = app.get("https://cinemaos.live/api/opensubtitles?imdbId=$imdbId", headers = defaultHeaders).text
                    val subUrls = Regex(""""(?:url|file|src)"\s*:\s*"([^"]+)"""").findAll(subResponse).map { it.groupValues[1].replace("\\/", "/") }.toList()
                    val subLangs = Regex(""""(?:name|label|language)"\s*:\s*"([^"]+)"""").findAll(subResponse).map { it.groupValues[1] }.toList()
                    
                    subUrls.forEachIndexed { index, url ->
                        val lang = if (index < subLangs.size) subLangs[index] else "English"
                        subtitleCallback.invoke(SubtitleFile(lang, url))
                    }
                } catch (e: Exception) {}
            }

            // 2. Define the exact scraper list seen in your logs
            val scrapers = listOf("mb2", "v2", "v1", "k9", "f8", "vf", "b5", "s3", "z2", "s7", "fc", "vc", "h0", "q4")

            // 3. Process scrapers SYNCHRONOUSLY to prevent Cloudstream from choking on parallel requests
            for (scraperId in scrapers) {
                try {
                    val scrapeUrl = if (isTv) {
                        "https://cinemaos.live/api/providerv4/scrape?type=tv&tmdbId=$tmdbId&imdbId=$imdbId&seasonId=$season&episodeId=$episode&t=$encodedTitle&ry=$ry&secret=${secret ?: ""}&_gt=${gt ?: ""}&scraper=$scraperId"
                    } else {
                        "https://cinemaos.live/api/providerv4/scrape?type=movie&tmdbId=$tmdbId&imdbId=$imdbId&t=$encodedTitle&ry=$ry&secret=${secret ?: ""}&_gt=${gt ?: ""}&scraper=$scraperId"
                    }

                    val scrapeResponse = app.get(
                        scrapeUrl,
                        headers = mapOf(
                            "User-Agent" to defaultHeaders["User-Agent"]!!,
                            "Referer" to watchUrl,
                            "Accept" to "application/json, text/plain, */*",
                            "X-Requested-With" to "XMLHttpRequest"
                        )
                    ).text

                    // Extremely loose Regex parsing so we don't miss links due to nested curly brackets
                    val foundUrls = Regex(""""(?:url|file|stream|link|src)"\s*:\s*"([^"]+)"""").findAll(scrapeResponse).map { it.groupValues[1].replace("\\/", "/") }.toList()
                    val foundLabels = Regex(""""(?:name|label|title|server|language)"\s*:\s*"([^"]+)"""").findAll(scrapeResponse).map { it.groupValues[1] }.toList()

                    foundUrls.forEachIndexed { index, serverUrl ->
                        if (serverUrl.isBlank() || serverUrl.contains("error")) return@forEachIndexed

                        val rawLabel = if (index < foundLabels.size) foundLabels[index] else "Server"
                        val serverName = "$rawLabel [$scraperId]"

                        if (serverUrl.contains(".vtt") || serverUrl.contains(".srt")) {
                            subtitleCallback.invoke(SubtitleFile(serverName, serverUrl))
                        } 
                        else if (serverUrl.contains(".mpd") || serverUrl.contains(".m3u8") || serverUrl.contains(".m4s") || serverUrl.contains(".mp4")) {
                            foundLinks = true
                            val isDash = serverUrl.contains(".mpd")
                            val isM3u8 = serverUrl.contains(".m3u8") || serverUrl.contains("m4s")
                            
                            callback.invoke(
                                newExtractorLink(
                                    source = "CinemaOS V1",
                                    name = serverName,
                                    url = serverUrl,
                                    type = when {
                                        isDash -> ExtractorLinkType.DASH
                                        isM3u8 -> ExtractorLinkType.M3U8
                                        else -> ExtractorLinkType.VIDEO
                                    }
                                ) {
                                    this.referer = "https://cinemaos.live/"
                                    this.quality = Qualities.P1080.value
                                }
                            )
                        } 
                        else if (serverUrl.startsWith("http")) {
                            foundLinks = true
                            try {
                                loadExtractor(serverUrl, serverName, subtitleCallback, callback)
                            } catch (e: Exception) {}
                        }
                    }
                } catch (e: Exception) {
                    // Fail silently for this specific scraper, move to the next one
                }
            }
        } catch (e: Exception) {}

        // Ultimate Failsafe if the CinemaOS API is completely rejecting us
        if (!foundLinks) {
            val fallbackServers = listOf(
                "VidSrc (Backup)" to "https://vidsrc.xyz/embed/movie?tmdb=$tmdbId",
                "Embed.su (Backup)" to "https://embed.su/embed/movie/$tmdbId"
            )
            fallbackServers.forEach { (fallbackName, fallbackUrl) ->
                try {
                    loadExtractor(fallbackUrl, fallbackName, subtitleCallback, callback)
                } catch (e: Exception) {}
            }
        }

        return true
    }
}
