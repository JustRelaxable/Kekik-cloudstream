package com.keyiflerolsun

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.ui.player.ExtractorLinkGenerator
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.delay
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import org.json.JSONArray
import java.net.URI
import java.util.*
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import javax.crypto.spec.IvParameterSpec
import java.util.Base64

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONException
import org.json.JSONObject

class InatBox : MainAPI() {
    // URLs
    private val contentUrl = "https://dizibox.rest"
    private val categoryUrl = "https://dizilab.cfd"

    // Provider details
    override var name = "InatBox"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var sequentialMainPage = false //Might change in the future
    override var sequentialMainPageDelay = 100L
    override var sequentialMainPageScrollDelay = 100L

    private val urlToSearchResponse = mutableMapOf<String, SearchResponse>()
    private val urlToDescription = mutableMapOf<String, String>()

    // Main page categories
    override val mainPage = mainPageOf(
        "${contentUrl}/ex/index.php" to "EXXEN",
        "${contentUrl}/ga/index.php" to "Gain",
        "${contentUrl}/blu/index.php" to "BluTV",
        "${contentUrl}/nf/index.php" to "Netflix",
        "${contentUrl}/dsny/index.php" to "Disney+",
        "${contentUrl}/amz/index.php" to "Amazon Prime",
        "${contentUrl}/hb/index.php" to "HBO Max",
        "${contentUrl}/tbi/index.php" to "Tabii",
        "${contentUrl}/film/mubi.php" to "Mubi",
        "${contentUrl}/ccc/index.php" to "TOD",
        "${contentUrl}/yabanci-dizi/index.php" to "Yabancı Diziler",
        "${contentUrl}/yerli-dizi/index.php" to "Yerli Diziler",
        "${contentUrl}/film/yerli-filmler.php" to "Yerli Filmler",
        "${contentUrl}/film/4k-film-exo.php" to "4K Film İzle | Exo"
    )

    // AES key for decryption
    private val randomAESKey = "C3V4HUpUbGDOjxEl"

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
            "Cache-Control" to "no-cache",
            "Content-Length" to "37",
            "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
            "Host" to hostName,
            "Referer" to "https://speedrestapi.com/",
            "X-Requested-With" to "com.bp.box"
        )

        val requestBody = "1=$randomAESKey&0=$randomAESKey"

        val interceptor = Interceptor { chain ->
            val request = chain.request()
            val newRequest = request.newBuilder()
                .header("User-Agent", "speedrestapi")
                .build()
            chain.proceed(newRequest)
        }

        val response = app.post(
            url = url,
            headers = headers,
            requestBody = requestBody.toRequestBody(contentType = "application/x-www-form-urlencoded; charset=UTF-8".toMediaType()),
            interceptor = interceptor
        )

        if (response.isSuccessful) {
            val encryptedResponse = response.text
            Log.d("InatBox", "Encrypted response: ${encryptedResponse}")
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
            val keySpec = SecretKeySpec(randomAESKey.toByteArray(), "AES")

            // First decryption iteration
            val cipher1 = Cipher.getInstance(algorithm)
            cipher1.init(Cipher.DECRYPT_MODE, keySpec, IvParameterSpec(randomAESKey.toByteArray()))
            val firstIterationData =
                cipher1.doFinal(Base64.getDecoder().decode(response.split(":")[0]))

            // Second decryption iteration
            val cipher2 = Cipher.getInstance(algorithm)
            cipher2.init(Cipher.DECRYPT_MODE, keySpec, IvParameterSpec(randomAESKey.toByteArray()))
            val secondIterationData = cipher2.doFinal(
                Base64.getDecoder().decode(String(firstIterationData).split(":")[0])
            )

            // Parse JSON
            val jsonString = String(secondIterationData)
            return jsonString
        } catch (e: Exception) {
            Log.e("InatBox", "Decryption failed: ${e.message}")
            return null
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Fetch the data from the category URL
        val jsonResponse =
            makeInatRequest(request.data) ?: return newHomePageResponse(request.name, emptyList())

        // Parse the JSON response into a list of SearchResponse objects
        val searchResults = parseJsonResponse(jsonResponse)

        for (searchResponse in searchResults) {
            val url = searchResponse.url
            if (!urlToSearchResponse.containsKey(url)) {
                urlToSearchResponse[url] = searchResponse
            }
        }

        // Return a HomePageResponse with the parsed results
        return newHomePageResponse(request.name, searchResults)
    }

    // Helper function to parse the JSON response
    private fun parseJsonResponse(jsonResponse: String): List<SearchResponse> {
        val searchResults = mutableListOf<SearchResponse>()

        try {
            // Parse the JSON string into a list of maps
            val jsonArray = JSONArray(jsonResponse)

            for (i in 0 until jsonArray.length()) {
                val item = jsonArray.getJSONObject(i)

                // Check if the response contains diziType (TV series or movie)
                if (item.has("diziType")) {
                    // Extract fields from the JSON object
                    val name = item.getString("diziName")
                    val url = item.getString("diziUrl")
                    val type = item.getString("diziType")
                    val posterUrl = item.getString("diziImg")

                    // Create a SearchResponse based on the type
                    val searchResponse = when (type) {
                        "dizi" -> newTvSeriesSearchResponse(name, url, TvType.TvSeries) {
                            this.posterUrl = posterUrl
                        }

                        "film" -> newMovieSearchResponse(name, url, TvType.Movie) {
                            this.posterUrl = posterUrl
                        }

                        else -> null // Ignore unsupported types
                    }

                    // Add the SearchResponse to the list if it's not null
                    searchResponse?.let { searchResults.add(it) }
                } else if (item.has("chName") && item.has("chUrl") && item.has("chImg")) {
                    // Handle the case where diziType is missing but chName, chUrl, and chImg are present
                    val name = item.getString("chName")
                    val url = item.getString("chUrl")
                    val posterUrl = item.getString("chImg")

                    // Create a MovieSearchResponse
                    val searchResponse = newMovieSearchResponse(name, url, TvType.Movie) {
                        this.posterUrl = posterUrl
                    }

                    // Add the SearchResponse to the list
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
                val url = pageData.data
                val jsonResponse = makeInatRequest(url) ?: continue

                val searchResults = parseJsonResponse(jsonResponse)

                for (searchResponse in searchResults) {
                    val contentUrl = searchResponse.url
                    if (!urlToSearchResponse.containsKey(contentUrl)) {
                        urlToSearchResponse[contentUrl] = searchResponse
                    }
                }

                delay(sequentialMainPageDelay)
            }
        }

        val matchingResults = mutableListOf<SearchResponse>()
        for ((_, searchResponse) in urlToSearchResponse) {
            if (searchResponse.name.contains(query, ignoreCase = true)) {
                matchingResults.add(searchResponse)
            }
        }

        return matchingResults
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> {
        return search(query)
    }

    override suspend fun load(url: String): LoadResponse? {
        // Fetch the data from the URL
        val jsonResponse = makeInatRequest(url) ?: return null

        // Parse the JSON response
        val jsonArray = try {
            JSONArray(jsonResponse)
        } catch (e: Exception) {
            Log.e("InatBox", "Failed to parse JSON response: ${e.message}")
            return null
        }

        // Check if the response is for a TV series or a movie
        return if (jsonArray.length() > 0 && jsonArray.getJSONObject(0).has("diziType")) {
            // This is a TV series response
            parseTvSeriesResponse(jsonArray, url)
        } else {
            // This is a movie response
            parseMovieResponse(jsonArray, url)
        }
    }

    // Helper function to parse a TV series response
    private suspend fun parseTvSeriesResponse(
        jsonArray: JSONArray,
        url: String
    ): TvSeriesLoadResponse? {
        // Map to store episodes grouped by season and episode number
        val episodeEntries = mutableMapOf<Pair<Int, Int>, MutableList<Episode>>()
        val episodes = mutableListOf<Episode>()

        // Get the SearchResponse for the given URL
        val searchResponse = urlToSearchResponse[url]

        try {
            // Iterate over each season in the JSON array
            for (i in 0 until jsonArray.length()) {
                val seasonItem = jsonArray.getJSONObject(i)

                // Extract season details
                val seasonName = seasonItem.getString("diziName")
                val seasonUrl = seasonItem.getString("diziUrl")
                val posterUrl = seasonItem.getString("diziImg")

                // Fetch the episode data for this season
                val episodeResponse = makeInatRequest(seasonUrl) ?: continue
                val episodeArray = try {
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
                        val episodeUrl = episodeItem.getString("chUrl")

                        // Extract season and episode numbers from the name (e.g., "S01 - 01.BÖLÜM")
                        val seasonEpisodeRegex = Regex("""S(\d+).*?(\d+).BÖLÜM""")
                        val matchResult = seasonEpisodeRegex.find(episodeName)
                        val season = matchResult?.groupValues?.get(1)?.toIntOrNull()
                        val episode = matchResult?.groupValues?.get(2)?.toIntOrNull()

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
                                data = episodeUrl,
                                name = episodeName,
                                season = season,
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
                    val sourceUrl = episodeObj.data

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
                        data = sourcesJsonArray.toString(), // Convert JSON array to string
                        season = season,
                        episode = episode
                    )
                )
            }

            // Get the name and poster URL from the first season
            val firstSeason = jsonArray.getJSONObject(0)
            val posterUrl = firstSeason.getString("diziImg")

            // Return a TvSeriesLoadResponse
            if (searchResponse != null) {
                return newTvSeriesLoadResponse(
                    searchResponse.name,
                    url,
                    TvType.TvSeries,
                    episodes
                ) {
                    this.posterUrl = posterUrl
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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
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
                    var sourceUrl = sourceJsonObject.optString("sourceUrl")
                    if (sourceUrl.startsWith("act")) {
                        sourceUrl = "https://vk.com/al_video.php?${sourceUrl}"
                    }

                    loadExtractor(sourceUrl, subtitleCallback, callback)
                }
            } else {
                loadExtractor(data, subtitleCallback, callback)
            }

            // Return true to indicate success
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

            // Return false to indicate failure
            false
        }
    }

    // Helper function to parse a movie response
    private suspend fun parseMovieResponse(jsonArray: JSONArray, url: String): MovieLoadResponse? {
        try {
            val firstItem = jsonArray.getJSONObject(0)

            // Extract fields from the JSON object
            val name = firstItem.getString("chName")
            val dataUrl = firstItem.getString("chUrl")
            val posterUrl = firstItem.getString("chImg")

            // Return a MovieLoadResponse
            return newMovieLoadResponse(name, url, TvType.Movie, dataUrl) {
                this.posterUrl = posterUrl
            }
        } catch (e: Exception) {
            Log.e("InatBox", "Failed to parse movie response: ${e.message}")
            return null
        }
    }
}