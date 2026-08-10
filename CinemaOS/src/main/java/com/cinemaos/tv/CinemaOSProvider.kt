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
            
            // Example basic extraction of episodes if the site loads them in the DOM
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
        // Construct the direct watch URL to feed into the hidden WebView
        val isTv = data.contains("/tv/")
        val watchUrl = if (isTv) {
            val tmdbId = data.substringAfter("/tv/").substringBefore("?")
            val season = Regex("""season=(\d+)""").find(data)?.groupValues?.get(1) ?: "1"
            val episode = Regex("""episode=(\d+)""").find(data)?.groupValues?.get(1) ?: "1"
            "https://cinemaos.live/watch/tv/$tmdbId?season=$season&episode=$episode"
        } else {
            val tmdbId = data.substringAfterLast("/")
            "https://cinemaos.live/watch/movie/$tmdbId"
        }

        // We use the WebView interceptor to catch the secure AWS DASH (.mpd) streams and VTT subtitles shown in your logs
        val interceptor = com.lagradost.cloudstream3.network.WebViewResolver(
            Regex("""(?i)\.mpd|\.m3u8|manifest|\.vtt""")
        )

        try {
            // This loads the page invisibly, letting the site's JS generate the "secret" and "_gt" tokens natively
            val response = app.get(watchUrl, interceptor = interceptor)
            val caughtUrl = response.url

            if (caughtUrl.isNotBlank() && !caughtUrl.contains("/watch/")) {
                
                // If it caught a subtitle file, pass it to the subtitle callback
                if (caughtUrl.contains(".vtt")) {
                    subtitleCallback.invoke(
                        SubtitleFile("English", caughtUrl)
                    )
                } 
                // If it caught the media stream (.mpd / .m3u8), pass it to the video callback
                else {
                    val isDash = caughtUrl.contains(".mpd")
                    val linkType = if (isDash) ExtractorLinkType.DASH else ExtractorLinkType.M3U8

                    callback.invoke(
                        newExtractorLink(
                            source = "CinemaOS V1",
                            name = "CinemaOS Stream",
                            url = caughtUrl,
                            type = linkType
                        ) {
                            this.referer = "https://cinemaos.live/"
                            this.quality = Qualities.P1080.value
                        }
                    )
                }
            }
        } catch (e: Exception) {
            // Silently handle timeouts or blocks
        }

        return true
    }
}
