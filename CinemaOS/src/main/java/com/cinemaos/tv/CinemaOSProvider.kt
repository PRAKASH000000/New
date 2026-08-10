package com.cinemaos.tv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink

class CinemaOSProvider : MainAPI() {
    override var mainUrl = "https://cinemaos.live"
    override var name = "CinemaOS"
    override val hasMainPage = true

        override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // 1. Download the website's HTML while pretending to be Google Chrome
        val document = app.get(
            mainUrl,
            headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
            )
        ).document
        
        val movies = ArrayList<SearchResponse>()

        // 2. Search for every movie card using the CSS class we found
        document.select("a.group.block").forEach { element ->
            
            // 3. Extract the puzzle pieces
            val title = element.selectFirst("img")?.attr("alt") ?: return@forEach
            val url = element.attr("href")
            val posterUrl = element.selectFirst("img")?.attr("src")

            // 4. Build the Cloudstream movie object
            movies.add(
                newMovieSearchResponse(title, url, TvType.Movie) {
                    this.posterUrl = posterUrl
                }
            )
        }

        // 5. Send the list to the TV screen!
        return newHomePageResponse(
            listOf(HomePageList("Latest Movies", movies)),
            hasNext = false
        )
    }


    override suspend fun search(query: String): List<SearchResponse> {
        return emptyList()
    }

        override suspend fun load(url: String): LoadResponse? {
        // 1. Download the movie page while disguised as Google Chrome
        val document = app.get(
            url,
            headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
            )
        ).document

        // 2. Extract the data from the hidden meta tags
        val rawTitle = document.selectFirst("meta[property=og:title]")?.attr("content")?.replace("Watch ", "") ?: ""
        val title = rawTitle.substringBeforeLast(" (").trim()
        val year = rawTitle.substringAfterLast("(").replace(")", "").toIntOrNull()
        
        val plot = document.selectFirst("meta[property=og:description]")?.attr("content")
        val backgroundPoster = document.selectFirst("meta[property=og:image]")?.attr("content")

        // 3. Send the packaged data to the TV screen
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = backgroundPoster
            this.plot = plot
            this.year = year
        }
    }


        override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        // 1. Extract the TMDB ID from the movie page URL
        val tmdbId = data.substringAfterLast("/")
        
        // 2. Build your custom player URL with the theme parameter included
        val playerUrl = "https://cinemaos.tech/player/$tmdbId?theme=ffffff"
        
        // 3. Download the player page to find the actual video file
        val document = app.get(playerUrl).document
        
        // Scenario A: Your player embeds a 3rd-party iframe
        val iframe = document.selectFirst("iframe")?.attr("src")
        if (iframe != null) {
            val fixedIframe = if (iframe.startsWith("//")) "https:$iframe" else iframe
            loadExtractor(fixedIframe, subtitleCallback, callback)
        }
        
        // Scenario B: Your player hosts the raw .mp4 or .m3u8 file directly
        val rawVideo = document.selectFirst("video source, video")?.attr("src")
        if (rawVideo != null) {
            callback.invoke(
                ExtractorLink(
                    source = "CinemaOS",
                    name = "CinemaOS",
                    url = rawVideo,
                    referer = playerUrl,
                    quality = Qualities.P1080.value,
                    isM3u8 = rawVideo.contains(".m3u8")
                )
            )
        }
        
        return true
    }

