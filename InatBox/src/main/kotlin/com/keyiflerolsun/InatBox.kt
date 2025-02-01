package com.keyiflerolsun

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
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
            "User-Agent" to "speedrestapi",
            "X-Requested-With" to "com.bp.box"
        )

        val requestBody = "1=$randomAESKey&0=$randomAESKey"

        val response = app.post(
            url = url,
            headers = headers,
            requestBody = requestBody.toRequestBody(contentType = "application/x-www-form-urlencoded; charset=UTF-8".toMediaType())
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
            val firstIterationData = cipher1.doFinal(Base64.getDecoder().decode(response.split(":")[0]))

            // Second decryption iteration
            val cipher2 = Cipher.getInstance(algorithm)
            cipher2.init(Cipher.DECRYPT_MODE, keySpec, IvParameterSpec(randomAESKey.toByteArray()))
            val secondIterationData = cipher2.doFinal(Base64.getDecoder().decode(String(firstIterationData).split(":")[0]))

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
        val jsonResponse = makeInatRequest(request.data) ?: return newHomePageResponse(request.name, emptyList())

        // Parse the JSON response into a list of SearchResponse objects
        val searchResults = parseJsonResponse(jsonResponse)

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
            }
        } catch (e: Exception) {
            Log.e("InatBox", "Failed to parse JSON response: ${e.message}")
        }

        return searchResults
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // Return an empty list since search is not supported
        return emptyList()
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> {
        // Return an empty list since quick search is not supported
        return emptyList()
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
    private suspend fun parseTvSeriesResponse(jsonArray: JSONArray, url: String): TvSeriesLoadResponse? {
        val episodes = mutableListOf<Episode>()

        try {
            for (i in 0 until jsonArray.length()) {
                val item = jsonArray.getJSONObject(i)

                // Extract fields from the JSON object
                val name = item.getString("chName")
                val episodeUrl = item.getString("chUrl")
                val posterUrl = item.getString("chImg")

                // Extract season and episode numbers from the name (e.g., "S01 - 01.BÖLÜM")
                val seasonEpisodeRegex = Regex("""S(\d+).*?(\d+).BÖLÜM""")
                val matchResult = seasonEpisodeRegex.find(name)
                val season = matchResult?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val episode = matchResult?.groupValues?.get(2)?.toIntOrNull() ?: 1

                // Create an Episode object
                episodes.add(
                    Episode(
                        data = episodeUrl,
                        name = name,
                        season = season,
                        episode = episode,
                        posterUrl = posterUrl
                    )
                )
            }

            // Get the name and poster URL from the first item
            val firstItem = jsonArray.getJSONObject(0)
            val name = firstItem.getString("chName")
            val posterUrl = firstItem.getString("chImg")

            // Return a TvSeriesLoadResponse
            return newTvSeriesLoadResponse(name, url, TvType.TvSeries, episodes) {
                this.posterUrl = posterUrl
            }
        } catch (e: Exception) {
            Log.e("InatBox", "Failed to parse TV series response: ${e.message}")
            return null
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