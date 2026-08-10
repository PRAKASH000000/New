package com.cinemaos.tv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class CinemaOSProvider : MainAPI() {
    override var mainUrl = "https://cinemaos.live"
    override var name = "CinemaOS"
    override val hasMainPage = true

    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*|q=0.8"
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

        val rawTitle = document.search("meta[property=og:title]").attr("content").replace("Watch ", "")
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
        
        // 1. Fetch the movie title dynamically from the load page to match your API requirements
        val doc = app.get(data, headers = defaultHeaders).document
        val rawTitle = doc.selectFirst("meta[property=og:title]")?.attr("content")?.replace("Watch ", "") ?: "Movie"
        val cleanTitle = rawTitle.substringBeforeLast(" (").trim().replace(" ", "+")

        // 2. Call your direct CinemaOS V2 backend API endpoint
        val apiUrl = "https://cinemaos.live/api/cinemaosv2?tmdbId=$tmdbId&type=movie&title=$cleanTitle"
        
        try {
            val apiResponse = app.get(
                apiUrl, 
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (iPhone; CPU iPhone OS 18_7 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.6 Mobile/15E148 Safari/604.1",
                    "Referer" to data
                )
            ).text

            // 3. Extract the stream URL from the JSON response
            // (If the response returns the raw stream URL or JSON string, we parse it)
            if (apiResponse.isNotBlank()) {
                // If the response itself is the direct link or contains a stream URL:
                val streamUrl = if (apiResponse.startsWith("http")) apiResponse else {
                    // Quick check if it's a JSON string containing the link
                    // e.g. finding url field or fallback to text
                    apiResponse.substringAfter("\"url\":\"").substringBefore("\"")
                }.ifBlank { apiResponse }

                val finalUrl = if (streamUrl.startsWith("http")) streamUrl else apiResponse

                val isM3u8 = finalUrl.contains(".m3u8") || finalUrl.contains("m3s")
                val linkType = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO

                callback.invoke(
                    newExtractorLink(
                        source = "CinemaOS",
                        name = "CinemaOS V2 (Private)",
                        url = finalUrl,
                        type = linkType
                    ) {
                        this.referer = "https://cinemaos.live/"
                        this.quality = Qualities.P1080.value
                    }
                )
            }
        } catch (e: Exception) {
            // Fallback if API needs custom error handling
        }

        return true
    }
}
