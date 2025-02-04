package com.keyiflerolsun

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import okhttp3.Interceptor
import org.json.JSONArray
import java.net.URI
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import javax.crypto.spec.IvParameterSpec
import java.util.Base64

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONException
import org.json.JSONObject

class InatBox : MainAPI() {
    private val contentUrl  = "https://dizibox.rest"
    private val categoryUrl = "https://dizilab.cfd"

    override var name                 = "InatBox"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport   = true
    override val supportedTypes       = setOf(TvType.Movie, TvType.TvSeries)
    override var sequentialMainPage   = false // ! Might change in the future

    private val urlToSearchResponse = mutableMapOf<String, SearchResponse>()
    private val aesKey = "ywevqtjrurkwtqgz"   // ! This is used for both secret key and iv. This is the embedded master key for loading categories like sport channels.

    // ! This urls come from ${categoryUrl}/ct.php | I assume they won't change in the near future
    override val mainPage = mainPageOf(
        "https://boxbc.sbs/CDN/001_STR/boxbc.sbs/spor_v2.php" to "Spor Kanalları",
        "${contentUrl}/ex/index.php"                          to "EXXEN",
        "${contentUrl}/ga/index.php"                          to "Gain",
        "${contentUrl}/blu/index.php"                         to "BluTV",
        "${contentUrl}/nf/index.php"                          to "Netflix",
        "${contentUrl}/dsny/index.php"                        to "Disney+",
        "${contentUrl}/amz/index.php"                         to "Amazon Prime",
        "${contentUrl}/hb/index.php"                          to "HBO Max",
        "${contentUrl}/tbi/index.php"                         to "Tabii",
        "${contentUrl}/film/mubi.php"                         to "Mubi",
        "${contentUrl}/ccc/index.php"                         to "TOD",
        "${contentUrl}/yabanci-dizi/index.php"                to "Yabancı Diziler",
        "${contentUrl}/yerli-dizi/index.php"                  to "Yerli Diziler",
        "${contentUrl}/film/yerli-filmler.php"                to "Yerli Filmler",
        "${contentUrl}/film/4k-film-exo.php"                  to "4K Film İzle | Exo"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val jsonResponse  = makeInatRequest(request.data) ?: return newHomePageResponse(request.name, emptyList())

        val searchResults = getSearchResponseList(jsonResponse)

        for (searchResponse in searchResults) {
            val url = searchResponse.url
            if (!urlToSearchResponse.containsKey(url)) {
                urlToSearchResponse[url] = searchResponse
            }
        }

        // Return a HomePageResponse with the parsed results
        return newHomePageResponse(request.name, searchResults)
    }


    // Function to make an encrypted request
    private suspend fun makeInatRequest(url: String): String? {
        // Extract hostname using URI
        val hostName = try {
            URI(url).host ?: throw IllegalArgumentException("Invalid URL: $url")
        } catch (e: Exception) {
            Log.e("InatBox", "Failed to extract hostname from URL: $url", e)
            return null
        }

        val headers = mapOf(
            "Cache-Control"    to "no-cache",
            "Content-Length"   to "37",
            "Content-Type"     to "application/x-www-form-urlencoded; charset=UTF-8",
            "Host"             to hostName,
            "Referer"          to "https://speedrestapi.com/",
            "X-Requested-With" to "com.bp.box"
        )

        val requestBody = "1=${aesKey}&0=${aesKey}"

        val interceptor = Interceptor { chain ->
            val request    = chain.request()
            val newRequest = request.newBuilder().header("User-Agent", "speedrestapi").build()
            chain.proceed(newRequest)
        }

        val response = app.post(
            url         = url,
            headers     = headers,
            requestBody = requestBody.toRequestBody(contentType = "application/x-www-form-urlencoded; charset=UTF-8".toMediaType()),
            interceptor = interceptor
        )

        if (response.isSuccessful) {
            val encryptedResponse = response.text
            // Log.d("InatBox", "Encrypted response: ${encryptedResponse}")
            return getJsonFromEncryptedInatResponse(encryptedResponse)
        } else {
            Log.e("InatBox", "Request failed")
            return null
        }
    }

    // Function to decrypt the encrypted response and parse JSON
    private fun getJsonFromEncryptedInatResponse(response: String): String? {
        try {
            val algorithm = "AES/CBC/PKCS5Padding"
            val keySpec   = SecretKeySpec(aesKey.toByteArray(), "AES")

            // First decryption iteration
            val cipher1 = Cipher.getInstance(algorithm)
            cipher1.init(Cipher.DECRYPT_MODE, keySpec, IvParameterSpec(aesKey.toByteArray()))
            val firstIterationData = cipher1.doFinal(Base64.getDecoder().decode(response.split(":")[0]))

            // Second decryption iteration
            val cipher2 = Cipher.getInstance(algorithm)
            cipher2.init(Cipher.DECRYPT_MODE, keySpec, IvParameterSpec(aesKey.toByteArray()))
            val secondIterationData = cipher2.doFinal(Base64.getDecoder().decode(String(firstIterationData).split(":")[0]))

            // Parse JSON
            val jsonString = String(secondIterationData)
            return jsonString
        } catch (e: Exception) {
            Log.e("InatBox", "Decryption failed: ${e.message}")
            return null
        }
    }

    // Helper function to parse the JSON response and return SearchResponse list
    private fun getSearchResponseList(jsonResponse: String): List<SearchResponse> {
        val searchResults = mutableListOf<SearchResponse>()
        try {
            val jsonArray = JSONArray(jsonResponse)

            for (i in 0 until jsonArray.length()) {
                val item = jsonArray.getJSONObject(i)

                if (!inatContentAllowed(item)) {
                    continue
                }

                //Let's pass item directly to the next step
                if (item.has("diziType")) {
                    val name      = item.getString("diziName")
                    val url       = item.getString("diziUrl")
                    val type      = item.getString("diziType")
                    val posterUrl = item.getString("diziImg")

                    val searchResponse = when (type) {
                        "dizi" -> newTvSeriesSearchResponse(name, item.toString()) {
                            this.posterUrl = posterUrl
                        }

                        "film" -> newMovieSearchResponse(name, item.toString()) {
                            this.posterUrl = posterUrl
                        }

                        else   -> null // Ignore unsupported types
                    }
                    searchResponse?.let { searchResults.add(it) }
                } else if (item.has("chName") && item.has("chUrl") && item.has("chImg")) {
                    // Handle the case where diziType is missing but chName, chUrl, and chImg are present
                    val name      = item.getString("chName")
                    var url       = item.getString("chUrl")
                    val posterUrl = item.getString("chImg")
                    val chType    = item.getString("chType")

                    val searchResponse = when (chType) {
                        "live_url", "tekli_regex_lb_sh_3" -> LiveSearchResponse(
                            name      = name,
                            url       = item.toString(),
                            apiName   = this.name,
                            type      = TvType.Live,
                            posterUrl = posterUrl
                        )

                        else -> newMovieSearchResponse(name, item.toString()) {
                            this.posterUrl = posterUrl
                        }
                    }
                    searchResults.add(searchResponse)
                }
            }
        } catch (e: Exception) {
            Log.e("InatBox", "Failed to parse JSON response: ${e.message}")
        }

        return searchResults
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (urlToSearchResponse.isEmpty()) {
            for (pageData in mainPage) {
                val url           = pageData.data
                val jsonResponse  = makeInatRequest(url) ?: continue

                val searchResults = getSearchResponseList(jsonResponse)

                for (searchResponse in searchResults) {
                    val contentUrl = searchResponse.url
                    if (!urlToSearchResponse.containsKey(contentUrl)) {
                        urlToSearchResponse[contentUrl] = searchResponse
                    }
                }
            }
        }

        val matchingResults = mutableListOf<SearchResponse>()

        val regex = try {
            Regex(query, RegexOption.IGNORE_CASE)
        } catch (e: Exception) {
            Regex(Regex.escape(query), RegexOption.IGNORE_CASE)
        }

        for ((_, searchResponse) in urlToSearchResponse) {
            if (regex.containsMatchIn(searchResponse.name)) {
                matchingResults.add(searchResponse)
            }
        }

        return matchingResults.distinctBy { it.name }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> {
        return search(query)
    }

    override suspend fun load(url: String): LoadResponse? {
        val item = JSONObject(url)

        if(!inatContentAllowed(item)){
            return null
        }

        if (item.has("diziType")) {
            val name = item.getString("diziName")
            val type = item.getString("diziType")

            return when (type) {
                "dizi" -> parseTvSeriesResponse(item)
                "film" -> parseMovieResponse(item)
                else   -> null
            }

        } else if (item.has("chName") && item.has("chUrl") && item.has("chImg")) {
            // Handle the case where diziType is missing but chName, chUrl, and chImg are present
            val name   = item.getString("chName")
            val chType = item.getString("chType")

            val loadResponse = when (chType) {
                "live_url", "tekli_regex_lb_sh_3" -> parseLiveStreamLoadResponse(item)
                else                              -> parseMovieResponse(item)
            }
            return loadResponse
        } else {
            return null
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        Log.d("InatBox", "data: ${data}")
        return try {
            // Check if the data is a JSON array (for TV series episodes)
            if (data.startsWith("[")) {
                // Parse the JSON array
                val sourcesJsonArray = JSONArray(data)

                // Iterate over each source in the JSON array
                for (i in 0 until sourcesJsonArray.length()) {
                    val sourceJsonObject = sourcesJsonArray.getJSONObject(i)

                    // Extract source details
                    val sourceName = sourceJsonObject.optString("sourceName", "")
                    var sourceUrl  = sourceJsonObject.optString("sourceUrl")
                    sourceUrl      = sourceUrl.vkSourceFix()
                    if (sourceUrl.contains(".m3u8")) {
                        callback.invoke(
                            ExtractorLink(
                                source  = this.name,
                                name    = this.name,
                                url     = sourceUrl,
                                referer = "https://google.com/",
                                quality = Qualities.Unknown.value,
                                type    = ExtractorLinkType.M3U8
                            )
                        )
                    } else {
                        loadExtractor(sourceUrl, subtitleCallback, callback)
                    }
                }
            } else {
                var sourceUrl      = data
                sourceUrl          = sourceUrl.vkSourceFix()
                val extractorFound = loadExtractor(sourceUrl, subtitleCallback, callback)

                //When no extractor found, try to load it as stream
                if (!extractorFound && sourceUrl.contains(".m3u8")) {
                    callback.invoke(
                        ExtractorLink(
                            source  = this.name,
                            name    = this.name,
                            url     = sourceUrl,
                            referer = "https://google.com/",
                            quality = Qualities.Unknown.value,
                            type    = ExtractorLinkType.M3U8
                        )
                    )
                }
            }
            true
        } catch (e: Exception) {
            // Log detailed error information
            Log.e(
                "InatBox",
                """
            Failed to load links:
            - Exception: ${e::class.simpleName}
            - Message: ${e.message}
            - Stack Trace: ${e.stackTrace.joinToString("\n")}
            - Input Data: ${data.take(500)} (first 500 characters)
            """.trimIndent()
            )

            false
        }
    }

    // Helper function to parse a TV series response
    private suspend fun parseTvSeriesResponse(item: JSONObject,tvType: TvType = TvType.TvSeries): TvSeriesLoadResponse? {
        // Map to store episodes grouped by season and episode number
        val episodeEntries = mutableMapOf<Pair<Int, Int>, MutableList<Episode>>()
        val episodes       = mutableListOf<Episode>()

        val url       = item.getString("diziUrl")
        val posterUrl = item.getString("diziImg")
        val plot      = item.getString("diziDetay")

        val jsonResponse = makeInatRequest(url) ?: return null
        val jsonArray    = JSONArray(jsonResponse)

        // Get the SearchResponse for the given URL
        val searchResponse = urlToSearchResponse[item.toString()]

        try {
            // Iterate over each season in the JSON array
            for (i in 0 until jsonArray.length()) {
                val seasonItem = jsonArray.getJSONObject(i)

                // Extract season details
                val seasonName = seasonItem.getString("diziName")
                val seasonUrl  = seasonItem.getString("diziUrl")
                val posterUrl  = seasonItem.getString("diziImg")

                // Fetch the episode data for this season
                val episodeResponse = makeInatRequest(seasonUrl) ?: continue
                val episodeArray    = try {
                    JSONArray(episodeResponse)
                } catch (e: Exception) {
                    Log.e("InatBox", "Failed to parse episode JSON for season: $seasonName", e)
                    continue
                }

                // Iterate over each episode in the season
                for (j in 0 until episodeArray.length()) {
                    Log.d("InatBox", "Episode array length: ${episodeArray.length()}")
                    try {
                        val episodeItem = episodeArray.getJSONObject(j)

                        // Extract episode details
                        val episodeName = episodeItem.getString("chName")
                        val episodeUrl  = episodeItem.getString("chUrl")

                        // Extract season and episode numbers from the name (e.g., "S01 - 01.BÖLÜM")
                        val seasonEpisodeRegex = Regex("""S(\d+).*?(\d+).BÖLÜM""")
                        val matchResult        = seasonEpisodeRegex.find(episodeName)
                        val season             = matchResult?.groupValues?.get(1)?.toIntOrNull()
                        val episode            = matchResult?.groupValues?.get(2)?.toIntOrNull()

                        if (season == null || episode == null) {
                            episodes.add(
                                Episode(
                                    data = episodeUrl,
                                    name = episodeName
                                )
                            )
                        } else {
                            // Create an Episode object
                            val episodeObj = Episode(
                                data    = episodeUrl,
                                name    = episodeName,
                                season  = season,
                                episode = episode
                            )

                            // Group episodes by season and episode number
                            val key = Pair(season, episode)
                            if (!episodeEntries.containsKey(key)) {
                                episodeEntries[key] = mutableListOf()
                            }
                            episodeEntries[key]?.add(episodeObj)
                        }
                    } catch (e: JSONException) {
                        continue
                    }
                }
            }

            // Create a JSON array for episodes with the same season and episode number
            for ((key, episodeList) in episodeEntries) {
                val (season, episode) = key

                // Create a JSON array for the sources
                val sourcesJsonArray = JSONArray()
                for (episodeObj in episodeList) {
                    val sourceName = episodeObj.name // Use the episode name as the source name
                    val sourceUrl  = episodeObj.data

                    // Create a JSON object for the source
                    val sourceJsonObject = JSONObject().apply {
                        put("sourceName", sourceName)
                        put("sourceUrl", sourceUrl)
                    }

                    // Add the source JSON object to the array
                    sourcesJsonArray.put(sourceJsonObject)
                }

                // Create a new Episode object with the JSON array as the data
                episodes.add(
                    Episode(
                        data    = sourcesJsonArray.toString(), // Convert JSON array to string
                        season  = season,
                        episode = episode
                    )
                )
            }

            // Get the name and poster URL from the first season
            val firstSeason = jsonArray.getJSONObject(0)
            val posterUrl   = firstSeason.getString("diziImg")

            // Return a TvSeriesLoadResponse
            if (searchResponse != null) {
                return newTvSeriesLoadResponse(
                    searchResponse.name,
                    url,
                    tvType,
                    episodes
                ) {
                    this.posterUrl = posterUrl
                    this.plot      = plot
                }
            } else {
                return null
            }
        } catch (e: Exception) {
            Log.e(
                "InatBox",
                "Failed to parse TV series response: ${e.message}\nStacktrace:${
                    e.stackTrace.joinToString("\n")
                }"
            )
            return null
        }
    }

    // Helper function to parse a movie response
    private suspend fun parseMovieResponse(item: JSONObject): MovieLoadResponse? {
        try {
            if (item.has("diziType")) {
                val name      = item.getString("diziName")
                var url       = item.getString("diziUrl")
                val posterUrl = item.getString("diziImg")
                val plot      = item.getString("diziDetay")

                val jsonResponse = makeInatRequest(url) ?: return null
                val jsonObject   = JSONArray(jsonResponse).getJSONObject(0)
                url = jsonObject.getString("chUrl")

                return newMovieLoadResponse(name, url, TvType.Movie, url) {
                    this.posterUrl = posterUrl
                    this.plot      = plot
                }
            } else {
                val name      = item.getString("chName")
                var url       = item.getString("chUrl")
                val posterUrl = item.getString("chImg")

                //val jsonResponse = makeInatRequest(url) ?: return null
                //val firstItem = JSONObject(jsonResponse)
                //val dataUrl = firstItem.getString("chUrl")

                // Return a MovieLoadResponse
                return newMovieLoadResponse(name, url, TvType.Movie, url) {
                    this.posterUrl = posterUrl
                }
            }
        } catch (e: Exception) {
            Log.e("InatBox", "Failed to parse movie response: ${e.message}")
            return null
        }
    }

    private suspend fun parseLiveStreamLoadResponse(item: JSONObject): LiveStreamLoadResponse? {
        try {
            val name      = item.getString("chName")
            var url       = item.getString("chUrl")
            val posterUrl = item.getString("chImg")

            val jsonResponse = makeInatRequest(url) ?: return null
            val firstItem    = JSONObject(jsonResponse)
            val dataUrl      = firstItem.getString("chUrl")

            // Return a MovieLoadResponse
            return LiveStreamLoadResponse(
                name      = name,
                url       = url,
                apiName   = this.name,
                dataUrl   = dataUrl,
                posterUrl = posterUrl
            )
        } catch (e: Exception) {
            Log.e("InatBox", "Failed to parse movie response: ${e.message}")
            return null
        }
    }

    private fun inatContentAllowed(item: JSONObject): Boolean {
        var type = ""

        if (item.has("diziType")) {
            type = item.getString("diziType")

        } else {
            type = item.getString("chType")
        }

        return when (type) {
            "link", "web" -> false
            else          -> true
        }
    }

    private fun String.vkSourceFix(): String{
        if (this.startsWith("act")) {
            return "https://vk.com/al_video.php?${this}"
        }
        return this
    }
}