package com.cinemaos.tv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

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
        
        try {
            // Request the backend API for the specific movie ID dynamically
            val apiUrl = "https://cinemaos.live/api/cinemaosv1?tmdbId=$tmdbId&type=movie"
            val apiResponse = app.get(apiUrl, headers = mapOf("Referer" to data)).text

            // Extract all available stream links and labels dynamically
            val urlRegex = Regex(""""url"\s*:\s*"([^"]+)"""")
            val labelRegex = Regex(""""(?:name|title|label|server)"\s*:\s*"([^"]+)"""")
            
            val urls = urlRegex.findAll(apiResponse).map { it.groupValues[1].replace("\\/", "/") }.toList()
            val labels = labelRegex.findAll(apiResponse).map { it.groupValues[1] }.toList()

            if (urls.isNotEmpty()) {
                for (i in urls.indices) {
                    val streamUrl = urls[i]
                    val serverName = if (i < labels.size) labels[i] else "Server ${i + 1}"

                    if (streamUrl.isNotBlank()) {
                        val isM3u8 = streamUrl.contains(".m3u8") || streamUrl.contains("dash")
                        callback.invoke(
                            newExtractorLink(
                                source = "CinemaOS",
                                name = serverName,
                                url = streamUrl,
                                type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            ) {
                                this.referer = "https://cinemaos.live/"
                                this.quality = Qualities.P1080.value
                            }
                        )
                    }
                }
            } else {
                // Fallback if JSON layout differs
                val singleMatch = Regex(""""(?:url|file|stream|link)"\s*:\s*"([^"]+)"""").find(apiResponse)
                val singleUrl = singleMatch?.groupValues?.get(1)?.replace("\\/", "/") ?: ""
                
                if (singleUrl.isNotBlank()) {
                    callback.invoke(
                        newExtractorLink(
                            source = "CinemaOS",
                            name = "Primary Server",
                            url = singleUrl,
                            type = if (singleUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.referer = "https://cinemaos.live/"
                            this.quality = Qualities.P1080.value
                        }
                    )
                }
            }
        } catch (e: Exception) {
            // Secondary fallback to universal community embed extractors if the site API fails
            val embedUrls = listOf(
                "https://vidsrc.xyz/embed/movie?tmdb=$tmdbId",
                "https://embed.su/embed/movie/$tmdbId"
            )
            embedUrls.forEach { embedUrl ->
                loadExtractor(embedUrl, subtitleCallback, callback)
            }
        }

        return true
    }
}
