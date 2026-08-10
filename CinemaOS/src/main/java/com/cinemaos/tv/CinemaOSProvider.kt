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
            // 1. Fetch HTML to steal the security tokens natively
            val watchHtml = app.get(watchUrl, headers = defaultHeaders).text
            
            val secret = Regex("""["']?secret["']?\s*[:=]\s*["']([^"']+)["']""").find(watchHtml)?.groupValues?.get(1) ?: ""
            val gt = Regex("""["']?_gt["']?\s*[:=]\s*["']([^"']+)["']""").find(watchHtml)?.groupValues?.get(1) ?: ""
            val imdbId = Regex("""["']?imdbId["']?\s*[:=]\s*["'](tt\d+)["']""").find(watchHtml)?.groupValues?.get(1) ?: ""
            val ry = Regex("""["']?(?:ry|year)["']?\s*[:=]\s*["'](\d{4})["']""").find(watchHtml)?.groupValues?.get(1) ?: ""
            
            val rawTitle = Regex("""<title>([^<]+)</title>""").find(watchHtml)?.groupValues?.get(1)?.replace("Watch ", "") ?: ""
            val encodedTitle = URLEncoder.encode(rawTitle.substringBeforeLast(" (").trim(), "UTF-8")

            // Grab OpenSubtitles explicitly using the endpoint from your logs
            if (imdbId.isNotBlank()) {
                try {
                    val subResponse = app.get("https://cinemaos.live/api/opensubtitles?imdbId=$imdbId", headers = defaultHeaders).text
                    val subBlocks = Regex("""\{[^{}]*?"(?:url|file|src)"\s*:\s*"[^"]+"[^{}]*?\}""").findAll(subResponse)
                    for (subBlock in subBlocks) {
                        val subUrl = Regex(""""(?:url|file|src)"\s*:\s*"([^"]+)"""").find(subBlock.value)?.groupValues?.get(1)?.replace("\\/", "/") ?: continue
                        val subLang = Regex(""""(?:name|label|language)"\s*:\s*"([^"]+)"""").find(subBlock.value)?.groupValues?.get(1) ?: "English"
                        subtitleCallback.invoke(SubtitleFile(subLang, subUrl))
                    }
                } catch (e: Exception) {
                    // Ignore opensubtitles failure
                }
            }

            // 2. Map out all the scrapers from your logs + the site's dynamic list
            val dynamicScrapersMatch = Regex("""["']?scrapers?["']?\s*[:=]\s*\[(.*?)\]""").find(watchHtml)?.groupValues?.get(1)
            val scrapers = if (!dynamicScrapersMatch.isNullOrBlank()) {
                Regex("""["']([^"']+)["']""").findAll(dynamicScrapersMatch).map { it.groupValues[1] }.toList()
            } else {
                listOf("k9", "f8", "vf", "b5", "s3", "z2", "s7", "fc", "vc", "h0", "v2", "mb2", "q4")
            }

            // 3. Process ALL scrapers in PARALLEL natively via Cloudstream's apmap
            scrapers.apmap { scraperId ->
                try {
                    val scrapeUrl = if (isTv) {
                        "https://cinemaos.live/api/providerv4/scrape?type=tv&tmdbId=$tmdbId&imdbId=$imdbId&seasonId=$season&episodeId=$episode&t=$encodedTitle&ry=$ry&secret=$secret&_gt=$gt&scraper=$scraperId"
                    } else {
                        "https://cinemaos.live/api/providerv4/scrape?type=movie&tmdbId=$tmdbId&imdbId=$imdbId&t=$encodedTitle&ry=$ry&secret=$secret&_gt=$gt&scraper=$scraperId"
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

                    val serverBlocks = Regex("""\{[^{}]*?"(?:url|file|stream|link|src)"\s*:\s*"[^"]+"[^{}]*?\}""").findAll(scrapeResponse)
                    
                    serverBlocks.forEachIndexed { index, match ->
                        val block = match.value
                        val serverUrl = Regex(""""(?:url|file|stream|link|src)"\s*:\s*"([^"]+)"""").find(block)?.groupValues?.get(1)?.replace("\\/", "/") ?: return@forEachIndexed
                        val rawServerName = Regex(""""(?:name|label|title|server|language)"\s*:\s*"([^"]+)"""").find(block)?.groupValues?.get(1) ?: "Server"
                        val serverName = "$rawServerName [$scraperId]"

                        if (serverUrl.isBlank() || serverUrl.contains("error")) return@forEachIndexed

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
                    // Move to next scraper
                }
            }
        } catch (e: Exception) {}

        // Ultimate Failsafe if tokens fail completely
        if (!foundLinks) {
            val fallbackServers = listOf(
                "Backup Server 1" to "https://vidsrc.xyz/embed/movie?tmdb=$tmdbId",
                "Backup Server 2" to "https://embed.su/embed/movie/$tmdbId"
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
