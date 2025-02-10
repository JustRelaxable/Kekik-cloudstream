package com.YTS

import android.content.Context
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.subtitles.SubtitleResource
import com.lagradost.cloudstream3.utils.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URL
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder

open class YTS(val context: Context) : MainAPI() {
    override var mainUrl              = "https://en.yts-official.mx"
    override var name                 = "YTS"
    override val hasMainPage          = true
    override var lang                 = "en"
    override val hasQuickSearch       = true
    override val hasDownloadSupport   = true
    override val supportedTypes       = setOf(TvType.Movie,TvType.Torrent)

    val subtitlesUrl = "https://yifysubtitles.ch"
    val turkceAltyaziOrgUrl = "https://turkcealtyazi.org"

    override val mainPage = mainPageOf(
        "browse-movies?keyword=&quality=all&genre=all&rating=0&year=0&order_by=latest" to "Latest",
        "browse-movies?keyword=&quality=all&genre=all&rating=0&year=0&order_by=featured" to "Featured",
        "browse-movies?keyword=&quality=2160p&genre=all&rating=0&year=0&order_by=latest" to "4K Movies",
        "browse-movies?keyword=&quality=1080p&genre=all&rating=0&year=0&order_by=latest" to "1080p Movies",
    )

    private val tempDir = File(context.getExternalFilesDir(null), "subtitles").apply { mkdirs() }
    private var webServer: Thread? = null

    init {
        startWebServer()
    }

    private fun startWebServer() {
        webServer = Thread {
            try {
                val serverSocket = ServerSocket(1235)
                println("Web server running at http://localhost:1235/")

                while (true) {
                    val clientSocket = serverSocket.accept()
                    handleClient(clientSocket)
                }
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
        webServer?.start()
    }

    private fun handleClient(client: Socket) {
        client.getInputStream().bufferedReader().use { reader ->
            val requestLine = reader.readLine() ?: return
            println("Request: $requestLine")

            val requestedFile = URLDecoder.decode(requestLine.split(" ")[1].trimStart('/'))
            val file = File(tempDir, requestedFile)

            client.getOutputStream().bufferedWriter().use { writer ->
                try {
                    if (file.exists() && file.isFile) {
                        val fileBytes = file.readBytes()
                        writer.write("HTTP/1.1 200 OK\r\n")
                        writer.write("Content-Length: ${fileBytes.size}\r\n")
                        writer.write("Content-Type: text/plain\r\n")
                        writer.write("\r\n")
                        writer.flush()
                        client.getOutputStream().write(fileBytes)
                    } else {
                        val response = "File not found"
                        writer.write("HTTP/1.1 404 Not Found\r\n")
                        writer.write("Content-Length: ${response.length}\r\n")
                        writer.write("Content-Type: text/plain\r\n")
                        writer.write("\r\n")
                        writer.write(response)
                        writer.flush()
                    }
                } catch (e: java.net.SocketException) {
                    println("Client closed the connection prematurely: ${e.message}")
                } catch (e: Exception) {
                    println("An error occurred: ${e.message}")
                }
            }
            client.close()
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/${request.data}&page=$page").document
        val home     = document.select("div.row div.browse-movie-wrap").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(
            list    = HomePageList(
                name               = request.name,
                list               = home,
                isHorizontalImages = false
            ),
            hasNext = true
        )
    }

    private fun Element.toSearchResult(): SearchResponse {
        val title     = this.select("div.browse-movie-bottom a").text().trim()
        val href      = fixUrl(this.select("a").attr("href"))
        val posterUrl = fixUrlNull(this.select("img").attr("src"))
        val year=this.selectFirst("a div.browse-movie-year")?.text()?.toIntOrNull()
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
            this.year = year
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchResponse = mutableListOf<SearchResponse>()

        for (i in 1..3) {
            val document = app.get("${mainUrl}/browse-movies?keyword=$query&quality=all&genre=all&rating=0&order_by=latest&year=0&page=$i").document
            val results = document.select("div.row div.browse-movie-wrap").mapNotNull { it.toSearchResult() }
            if (!searchResponse.containsAll(results)) {
                searchResponse.addAll(results)
            } else {
                break
            }

            if (results.isEmpty()) break
        }

        return searchResponse
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("#mobile-movie-info h1")?.text()?.trim() ?:"No Title"
        val poster = getURL(document.select("#movie-poster img").attr("src"))
        val year = document.selectFirst("#mobile-movie-info h2")?.text()?.trim()?.toIntOrNull()
        val tags = document.selectFirst("#mobile-movie-info > h2:nth-child(3)")?.text()?.trim()
            ?.split(" / ")
            ?.map { it.trim() }
        val rating= document.select("#movie-info > div.bottom-info > div:nth-child(2) > span:nth-child(2)").text().toRatingInt()
        val imdbId = Regex("movie-imdb\\/(tt\\d+)").find(document.html())?.groupValues?.get(1)

        //Data from TürkçeAltyazı.Org
        val turkceAltyazıOrgDocument = app.get("${turkceAltyaziOrgUrl}/mov/${imdbId?.substringAfter("tt")}/").document
        val plot = turkceAltyazıOrgDocument.selectFirst(".ozet-goster")?.text()
        val actors = turkceAltyazıOrgDocument.select("div[itemprop=actors] li").mapNotNull {
            val firstChild = it.firstElementChild()
            val firstChildsFirstChild = firstChild?.firstElementChild()
            val actorName = firstChild?.attr("title")
            val actorImage = "${turkceAltyaziOrgUrl}/${firstChildsFirstChild?.attr("src")}"
            actorName?.let { name -> Actor(name = name, image = actorImage) }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.rating=rating
            this.addActors(actors)
            this.tags = tags
            this.addImdbId(imdbId)
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val document = app.get(data).document

        val imdbId = Regex("movie-imdb\\/(tt\\d+)").find(document.html())?.groupValues?.get(1)
        if(imdbId != null){
            val subtitleFetchUrl = "${subtitlesUrl}/movie-imdb/${imdbId}"
            val document = app.get(subtitleFetchUrl).document

            val rows = document.select("tr[data-id]")
            for (row in rows) {
                val turkishSpan = row.selectFirst("td.flag-cell span.sub-lang")?.takeIf { it.text() == "Turkish" }

                if (turkishSpan != null) {
                    val subtitlePath = row.selectFirst("td a")?.attr("href")

                    if (!subtitlePath.isNullOrEmpty()) {
                        val zipSubtitleUrl = "https://yifysubtitles.ch/subtitle" + subtitlePath.removePrefix("/subtitles") + ".zip"

                        val extractedFile = downloadAndExtractZip(zipSubtitleUrl, tempDir)

                        if(extractedFile!=null){
                            //val subtitleUrl = uploadFileToCatbox(extractedFile)
                            val subtitleUrl = "http://localhost:1235/${extractedFile.name}"
                            if(subtitleUrl != null){
                                subtitleCallback.invoke(
                                    SubtitleFile("Türkçe - Yify",subtitleUrl)
                                )
                            }
                        }
                    }
                }
            }
        }

        document.select("p.hidden-md.hidden-lg a").amap {
            val href=getURL(it.attr("href").replace(" ","%20"))
            val quality =it.ownText().substringBefore(".").replace("p","").toInt()
            callback.invoke(
                ExtractorLink(
                    "$name $quality",
                    name,
                    fixUrl( href),
                    "",
                    quality,
                    INFER_TYPE
                )
            )
        }
        return true
    }

    fun getURL(url: String): String {
        return "${mainUrl}$url"
    }

    suspend fun downloadAndExtractZip(zipUrl: String, outputDir: File): File? {
        return try {
            // Create the output directory if it doesn't exist
            if (!outputDir.exists()) outputDir.mkdirs()

            // Download the ZIP file
            val url = URL(zipUrl)
            val connection = url.openConnection()
            connection.connect()

            val zipInputStream = ZipInputStream(connection.getInputStream())

            var entry: ZipEntry?
            var subtitleFile: File? = null

            // Extract files from the ZIP
            while (zipInputStream.nextEntry.also { entry = it } != null) {
                val entryName = entry!!.name
                val outputFile = File(outputDir, entryName)

                // Ensure the output file is within the output directory (security check)
                if (!outputFile.canonicalPath.startsWith(outputDir.canonicalPath)) {
                    throw SecurityException("ZIP entry is outside the target directory")
                }

                // Write the file to the output directory
                FileOutputStream(outputFile).use { outputStream ->
                    zipInputStream.copyTo(outputStream)
                }

                // Assume the first .srt file is the subtitle file
                if (entryName.endsWith(".srt")) {
                    subtitleFile = outputFile
                }
            }

            zipInputStream.close()
            subtitleFile // Return the extracted subtitle file
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    suspend fun uploadFileToCatbox(file: File): String? {
        val client = OkHttpClient()

        // Create the request body
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("reqtype", "fileupload")
            .addFormDataPart("time", "12h")
            .addFormDataPart(
                "fileToUpload",
                file.name,
                file.asRequestBody("application/octet-stream".toMediaType())
            )
            .build()

        // Create the request
        val request = Request.Builder()
            .url("https://litterbox.catbox.moe/resources/internals/api.php")
            .post(requestBody)
            .build()

        // Execute the request
        return try {
            val response: Response = client.newCall(request).execute()
            if (response.isSuccessful) {
                // Return the response body (file URL)
                response.body?.string()
            } else {
                println("Failed to upload file: ${response.code} - ${response.message}")
                null
            }
        } catch (e: IOException) {
            println("Error uploading file: ${e.message}")
            null
        }
    }
}
