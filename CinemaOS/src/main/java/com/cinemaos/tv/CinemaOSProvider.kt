package com.cinemaos.tv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.* 

class CinemaOSProvider : MainAPI() {
    override var mainUrl = "https://cinemaos.live"
    override var name = "CinemaOS"
    override val hasMainPage = true

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(
            mainUrl,
            headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
            )
        ).document
        
        val movies = ArrayList<SearchResponse>()

        document.select("a.group.block").forEach { element ->
            val title = element.selectFirst("img")?.attr("alt") ?: return@forEach
            val url = element.attr("href")
            val posterUrl = element.selectFirst("img")?.attr("src")

            movies.add(
                newMovieSearchResponse(title, url, TvType.Movie) {
                    this.posterUrl = posterUrl
                }
            )
        }

        return newHomePageResponse(
            listOf(HomePageList("Latest Movies", movies)),
            hasNext = false
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return emptyList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(
            url,
            headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
            )
        ).document

        val rawTitle = document.selectFirst("meta[property=og:title]")?.attr("content")?.replace("Watch ", "") ?: ""
        val title = rawTitle.substringBeforeLast(" (").trim()
        val year = rawTitle.substringAfterLast("(").replace(")", "").toIntOrNull()
        
        val plot = document.selectFirst("meta[property=og:description]")?.attr("content")
        val backgroundPoster = document.selectFirst("meta[property=og:image]")?.attr("content")

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = backgroundPoster
            this.plot = plot
            this.year = year
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val tmdbId = data.substringAfterLast("/")
        val playerUrl = "https://cinemaos.tech/player/$tmdbId?theme=ffffff"
        val document = app.get(playerUrl).document
        
        val iframe = document.selectFirst("iframe")?.attr("src")
        if (iframe != null) {
            val fixedIframe = if (iframe.startsWith("//")) "https:$iframe" else iframe
            loadExtractor(fixedIframe, subtitleCallback, callback)
        }
        
        val rawVideo = document.selectFirst("video source, video")?.attr("src")
        if (rawVideo != null) {
            // Re-formatted to the new Cloudstream DSL Builder syntax
            callback.invoke(
                newExtractorLink(
                    source = "CinemaOS",
                    name = "CinemaOS",
                    url = rawVideo
                ) {
                    this.referer = playerUrl
                    this.quality = Qualities.P1080.value
                    this.isM3u8 = rawVideo.contains(".m3u8")
                }
            )
        }
        
        return true
    }
}
