package com.cinemaos.tv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink

class CinemaOSProvider : MainAPI() {
    override var mainUrl = "https://cinemaos.live"
    override var name = "CinemaOS"
    override val hasMainPage = true

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // 1. Download the website's HTML
        val document = app.get(mainUrl).document
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
        return null
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        return true
    }
}
