// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson

class HQPorner : MainAPI() {
    override var mainUrl              = "https://hqporner.com"
    override var name                 = "HQPorner"
    override val hasMainPage          = true
    override var lang                 = "en"
    override val hasQuickSearch       = false
    override val hasDownloadSupport   = true
    override val hasChromecastSupport = true
    override val supportedTypes       = setOf(TvType.NSFW)
    override val vpnStatus            = VPNStatus.MightBeNeeded

    override val mainPage = mainPageOf(
        "${mainUrl}/top"                   to "All Time Best",
        "${mainUrl}/top/month"             to "Month TOP",
        "${mainUrl}/top/week"              to "Week TOP",
        "${mainUrl}/category/1080p-porn"   to "1080p",
        "${mainUrl}/category/4k-porn"      to "4K",
        "${mainUrl}/category/60fps-porn"   to "60FPS",
        "${mainUrl}/category/amateur"      to "Amateur",
        "${mainUrl}/category/teen-porn"    to "Teen",
        "${mainUrl}/category/babe"         to "Babe",
        "${mainUrl}/category/pov"          to "POV",
        "${mainUrl}/category/orgasm"       to "Orgasm",
        "${mainUrl}/category/porn-massage" to "Sex Massage",
        "${mainUrl}/category/threesome"    to "Threesome",
        "${mainUrl}/category/group-sex"    to "Group Sex",
        "${mainUrl}/category/milf"         to "Milf",
        "${mainUrl}/category/mature"       to "Mature"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("${request.data}/${page}").document
        val home     = document.select("div.box.page-content div.row section").mapNotNull { it.toMainPageResult() }

        return newHomePageResponse(
            list    = HomePageList(
                name               = request.name,
                list               = home,
                isHorizontalImages = true
            ),
            hasNext = true
        )
    }

    private fun Element.toMainPageResult(): SearchResponse? {
        val lowerCaseTitle = this.selectFirst("h3 a")?.text() ?:"No Title"
        val title          = lowerCaseTitle.split(" ").joinToString(" ") { it.replaceFirstChar { char -> char.uppercase() } }
        val href           = fixUrlNull(this.selectFirst("h3 a")?.attr("href")) ?: return null
        val posterUrl      = fixUrlNull(this.selectFirst("img")?.attr("src"))

        return newMovieSearchResponse(title, LoadUrl(href, posterUrl).toJson(), TvType.NSFW) { this.posterUrl = posterUrl }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchResponse = mutableListOf<SearchResponse>()

        for (i in 1..5) {
            val document = app.get("${mainUrl}/?q=${query.replace(" ", "+")}&p=${i}").document

            val results = document.select("div.box.page-content div.row section").mapNotNull { it.toMainPageResult() }

            searchResponse.addAll(results)

            if (results.isEmpty()) break
        }

        return searchResponse
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val loadData = tryParseJson<LoadUrl>(url) ?: return null
        val document = app.get(loadData.href).document

        val lowerCaseTitle  = document.selectFirst("h1.main-h1")?.text() ?:"No Title"
        val title           = lowerCaseTitle.split(" ").joinToString(" ") { it.replaceFirstChar { char -> char.uppercase() } }
        val poster          = loadData.posterUrl
        val description     = title
        val tags            = document.select("p a[href*='/category']").map { it.text() }
        // val duration        = document.selectFirst("span.runtime")?.text()?.split(" ")?.first()?.trim()?.toIntOrNull()
        val recommendations = document.select("div.row div.row section").mapNotNull { it.toMainPageResult() }
        val actors          = document.select("li a[href*='/actress']").map { Actor(it.text()) }

        return newMovieLoadResponse(title, url, TvType.NSFW, loadData.href) {
            this.posterUrl       = poster
            this.plot            = description
            this.tags            = tags
            // this.duration        = duration
            this.recommendations = recommendations
            addActors(actors)
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        Log.d("HPRN", "data » ${data}")
        val document = app.get(data).document

        val rawURL = Regex("""url: '/blocks/altplayer\.php\?i=//(.*?)',""").find(document.toString())?.groupValues?.get(1) ?: return false
        val vidURL = "https://${rawURL}"
        Log.d("HPRN", "vidURL » ${vidURL}")

        loadExtractor(vidURL, "${mainUrl}/", subtitleCallback, callback)

        return true
    }
}

data class LoadUrl(
    val href: String,
    val posterUrl: String?
)