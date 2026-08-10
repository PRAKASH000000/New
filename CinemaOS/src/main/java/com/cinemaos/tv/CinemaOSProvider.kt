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
        val cleanTitle = rawTitle.substringBeforeLast(" (").trim().replace(" ", "+")

        var foundMultiAudio = false

        // 1. Attempt to fetch all V1 Multi-Language Tracks via direct API
        val languages = listOf(
            "English" to "Alpha - English",
            "Arabic" to "Beta - Arabic dub",
            "French" to "Gamma - French dub",
            "Hindi" to "Delta - Hindi",
            "Indonesian" to "Epsilon - Indonesian dub",
            "Portuguese" to "Zeta - Portuguese",
            "Russian" to "Eta - Russian dub",
            "Spanish" to "Theta - Spanish dub",
            "Tagalog" to "Track9 - Tagalog dub",
            "Tamil" to "Track10 - Tamil"
        )

        for ((langKey, langName) in languages) {
            try {
                val apiUrl = if (isTv) {
                    "https://cinemaos.live/api/cinemaosv1?tmdbId=$tmdbId&type=tv&season=$season&episode=$episode&lang=$langKey"
                } else {
                    "https://cinemaos.live/api/cinemaosv1?tmdbId=$tmdbId&type=movie&title=$cleanTitle&lang=$langKey"
                }

                val apiResponse = app.get(
                    apiUrl, 
                    headers = mapOf(
                        "User-Agent" to "Mozilla/5.0 (iPhone; CPU iPhone OS 18_7 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.6 Mobile/15E148 Safari/604.1",
                        "Referer" to data,
                        "Accept" to "application/json, text/plain, */*",
                        "X-Requested-With" to "XMLHttpRequest"
                    )
                ).text

                val match = Regex(""""(?:url|file|stream|link)"\s*:\s*"([^"]+)"""").find(apiResponse)
                val streamUrl = match?.groupValues?.get(1)?.replace("\\/", "/") ?: if (apiResponse.startsWith("http")) apiResponse else ""

                if (streamUrl.isNotBlank() && !streamUrl.contains("error")) {
                    foundMultiAudio = true
                    val isM3u8 = streamUrl.contains(".m3u8") || streamUrl.contains("dash") || streamUrl.contains("m4s")
                    
                    callback.invoke(
                        newExtractorLink(
                            source = "CinemaOS V1",
                            name = langName,
                            url = streamUrl,
                            type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.referer = "https://cinemaos.live/"
                            this.quality = Qualities.P1080.value
                        }
                    )
                }
            } catch (e: Exception) {
                // Ignore API skips
            }
        }

        // 2. Fallback Mechanism: If the direct API is blocked and no languages are found, use WebView to grab the default English stream and subtitles.
        if (!foundMultiAudio) {
            val watchUrl = if (isTv) {
                "https://cinemaos.live/watch/tv/$tmdbId?season=$season&episode=$episode"
            } else {
                "https://cinemaos.live/watch/movie/$tmdbId"
            }

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
                // Silently handle WebView timeouts
            }
        }

        return true
    }
}
