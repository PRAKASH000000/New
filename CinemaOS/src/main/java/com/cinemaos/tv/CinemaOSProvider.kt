package com.cinemaos.tv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver // The new interceptor tool

class CinemaOSProvider : MainAPI() {
    override var mainUrl = "https://cinemaos.live"
    override var name = "CinemaOS"
    override val hasMainPage = true

    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl, headers = defaultHeaders).document
        
        val movies = document.select("a.group.block").mapNotNull { element ->
            val title = element.selectFirst("img")?.attr("alt") ?: return@mapNotNull null
            val url = fixUrlNull(element.attr("href")) ?: return@mapNotNull null
            val posterUrl = fixUrlNull(element.selectFirst("img")?.attr("src"))

            newMovieSearchResponse(title, url, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        }

        return newHomePageResponse(
            listOf(HomePageList("Latest Movies", movies)),
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

            newMovieSearchResponse(title, url, TvType.Movie) {
                this.posterUrl = posterUrl
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

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = backgroundPoster
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
        val tmdbId = data.substringAfterLast("/")
        val playerUrl = "https://cinemaos.tech/player/$tmdbId?theme=ffffff"
        
        // 1. Create the Interceptor net to catch video files or known embed links in the background
        val interceptor = WebViewResolver(
            Regex("""(?i)\.(mp4|m3u8)|vidsrc|autoembed|embedsu""")
        )

        try {
            // 2. Open the hidden browser and let JavaScript run until it hits our net
            val response = app.get(playerUrl, interceptor = interceptor)
            val caughtUrl = response.url

            // 3. If it caught a raw video file from your private servers:
            if (caughtUrl.contains(".m3u8") || caughtUrl.contains(".mp4")) {
                val linkType = if (caughtUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                callback.invoke(
                    newExtractorLink(
                        source = "CinemaOS",
                        name = "CinemaOS Private Server",
                        url = caughtUrl,
                        type = linkType
                    ) {
                        this.referer = playerUrl
                        this.quality = Qualities.P1080.value
                    }
                )
            } else {
                // 4. If it caught an iframe instead, send it to the built-in decoders
                loadExtractor(caughtUrl, subtitleCallback, callback)
            }
            
            // 5. Fallback: Parse the rendered HTML just in case the JS injected an iframe directly into the page
            val document = response.document
            val iframe = document.selectFirst("iframe")?.attr("src")
            if (iframe != null) {
                loadExtractor(fixUrl(iframe), subtitleCallback, callback)
            }

        } catch (e: Exception) {
            // Safety net just in case the WebView times out
            val embedUrls = listOf(
                "https://vidsrc.me/embed/movie?tmdb=$tmdbId",
                "https://autoembed.co/movie/tmdb/$tmdbId"
            )
            embedUrls.forEach { embedUrl ->
                loadExtractor(embedUrl, subtitleCallback, callback)
            }
        }
        
        return true
    }
}
