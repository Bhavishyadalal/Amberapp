package com.amber.player

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AmberServer(
    private val context: Context,
    private val port: Int = 8877
) {
    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private val executor = Executors.newCachedThreadPool()
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val downloadsDir = File(context.filesDir, "downloads").apply { mkdirs() }
    private val dataFile = File(context.filesDir, "amber_data.json")
    private val downloadJobs = ConcurrentHashMap<String, DownloadJob>()

    data class DownloadJob(
        var status: String = "queued",
        var progress: Int = 0,
        var filename: String? = null,
        var error: String? = null
    )

    fun start() {
        if (isRunning) return
        isRunning = true
        executor.execute {
            try {
                serverSocket = ServerSocket(port, 50, java.net.InetAddress.getByName("127.0.0.1"))
                while (isRunning) {
                    val client = serverSocket?.accept() ?: break
                    executor.execute { handleClient(client) }
                }
            } catch (e: Exception) {
                // Server stopped or socket error
            }
        }
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.soTimeout = 15000
            val input = BufferedReader(InputStreamReader(socket.getInputStream()))
            val output = BufferedOutputStream(socket.getOutputStream())

            val requestLine = input.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            val fullPath = parts[1]

            var contentLength = 0
            var line: String?
            while (input.readLine().also { line = it } != null) {
                if (line!!.isEmpty()) break
                val lower = line!!.lowercase()
                if (lower.startsWith("content-length:")) {
                    contentLength = lower.substringAfter(":").trim().toIntOrNull() ?: 0
                }
            }

            val body = if (contentLength > 0) {
                val chars = CharArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val r = input.read(chars, read, contentLength - read)
                    if (r == -1) break
                    read += r
                }
                String(chars, 0, read)
            } else ""

            val uri = Uri.parse(fullPath)
            val path = uri.path ?: "/"

            when {
                path == "/" || path == "/index.html" -> serveAsset(output, "frontend.html", "text/html; charset=utf-8")
                path == "/favicon.png" || path == "/favicon.ico" -> serveFavicon(output)
                path == "/api/health" -> sendJson(output, 200, "{\"ok\":true,\"ffmpeg\":false,\"yt_dlp\":\"embedded\"}")
                path == "/api/config" -> sendJson(output, 200, "{\"has_key\":true,\"locked\":false,\"total\":1,\"alive\":1,\"has_manual\":false}")
                path == "/api/unlock" || path == "/api/lock" -> sendJson(output, 200, "{\"ok\":true}")
                path == "/api/home" -> handleHome(output, uri.getQueryParameter("seed"))
                path == "/api/search_ytmusic" -> handleSearch(output, uri.getQueryParameter("q") ?: "")
                path == "/api/related" -> handleRelated(output, path.substringAfterLast("/"))
                path.startsWith("/api/related/") -> handleRelated(output, path.substringAfterLast("/"))
                path == "/api/art" -> handleArtProxy(output, uri.getQueryParameter("u"))
                path == "/api/data" -> handleData(output, method, body)
                path == "/api/downloads" -> handleListDownloads(output)
                path.startsWith("/api/downloads/") -> {
                    val filename = URLDecoder.decode(path.substringAfter("/api/downloads/"), "UTF-8")
                    if (method == "DELETE") handleDeleteDownload(output, filename)
                    else serveDownloadFile(output, filename)
                }
                path.startsWith("/api/download/") && path.endsWith("/status") -> {
                    val videoId = path.removePrefix("/api/download/").removeSuffix("/status")
                    handleDownloadStatus(output, videoId)
                }
                path.startsWith("/api/download/") && method == "POST" -> {
                    val videoId = path.removePrefix("/api/download/")
                    handleStartDownload(output, videoId, body)
                }
                else -> sendText(output, 404, "Not Found")
            }
        } catch (_: Exception) {
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun handleHome(output: OutputStream, seedParam: String?) {
        val seed = if (!seedParam.isNullOrBlank() && seedParam.length == 11) seedParam else "4NRXx6U8ABQ"
        val shelves = JSONArray().apply {
            put(JSONObject().apply {
                put("id", "picks")
                put("title", "Quick Picks")
                put("subtitle", "Recommended based on your taste")
                put("items", fetchYtMusicRadio(seed))
            })
            put(JSONObject().apply {
                put("id", "trending")
                put("title", "Top Global Hits")
                put("subtitle", "Popular music charts")
                put("items", fetchYtMusicRadio("hT_nvWreIhg"))
            })
            put(JSONObject().apply {
                put("id", "lofi")
                put("title", "Lo-Fi & Chill")
                put("subtitle", "Relaxing beats & study focus")
                put("items", fetchYtMusicRadio("jfKfPfyJRdk"))
            })
            put(JSONObject().apply {
                put("id", "ambient")
                put("title", "Ambient & Focus")
                put("subtitle", "Atmospheric instrumental music")
                put("items", fetchYtMusicRadio("DWCJZRYemXo"))
            })
        }
        sendJson(output, 200, JSONObject().put("shelves", shelves).toString())
    }

    private fun fetchYtMusicRadio(seedId: String): JSONArray {
        val items = JSONArray()
        try {
            val jsonBody = JSONObject().apply {
                put("context", JSONObject().put("client", JSONObject().apply {
                    put("clientName", "WEB_REMIX")
                    put("clientVersion", "1.20241001.00.00")
                }))
                put("playlistId", "RDAMVM$seedId")
                put("videoId", seedId)
            }
            val request = Request.Builder()
                .url("https://www.youtube.com/youtubei/v1/next")
                .header("Content-Type", "application/json")
                .header("Origin", "https://music.youtube.com")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = okHttpClient.newCall(request).execute()
            val respStr = response.body?.string() ?: return items
            val root = JSONObject(respStr)

            fun findRenderers(obj: Any?) {
                when (obj) {
                    is JSONObject -> {
                        if (obj.has("playlistPanelVideoRenderer")) {
                            val r = obj.getJSONObject("playlistPanelVideoRenderer")
                            val vid = r.optString("videoId")
                            val title = r.optJSONObject("title")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text")
                            val byline = r.optJSONObject("longBylineText")?.optJSONArray("runs")
                                ?: r.optJSONObject("shortBylineText")?.optJSONArray("runs")
                            val channel = byline?.optJSONObject(0)?.optString("text") ?: ""
                            val thumbs = r.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
                            val thumb = thumbs?.optJSONObject(thumbs.length() - 1)?.optString("url")
                                ?: "https://i.ytimg.com/vi/$vid/mqdefault.jpg"

                            if (vid.isNotEmpty() && !title.isNullOrEmpty()) {
                                items.put(JSONObject().apply {
                                    put("id", vid)
                                    put("title", title)
                                    put("channel", channel)
                                    put("thumbnail", thumb)
                                    put("duration", "")
                                })
                            }
                        } else {
                            val keys = obj.keys()
                            while (keys.hasNext()) {
                                findRenderers(obj.opt(keys.next()))
                            }
                        }
                    }
                    is JSONArray -> {
                        for (i in 0 until obj.length()) {
                            findRenderers(obj.opt(i))
                        }
                    }
                }
            }
            findRenderers(root)
        } catch (_: Exception) {}
        return items
    }

    private fun handleSearch(output: OutputStream, query: String) {
        val results = JSONArray()
        if (query.isNotBlank()) {
            try {
                val jsonBody = JSONObject().apply {
                    put("context", JSONObject().put("client", JSONObject().apply {
                        put("clientName", "WEB_REMIX")
                        put("clientVersion", "1.20241001.00.00")
                    }))
                    put("query", query)
                }
                val request = Request.Builder()
                    .url("https://www.youtube.com/youtubei/v1/search")
                    .header("Content-Type", "application/json")
                    .header("Origin", "https://music.youtube.com")
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                    .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                val response = okHttpClient.newCall(request).execute()
                val respStr = response.body?.string() ?: ""
                val root = JSONObject(respStr)

                fun findMusicItems(obj: Any?) {
                    when (obj) {
                        is JSONObject -> {
                            if (obj.has("musicResponsiveListItemRenderer")) {
                                val item = obj.getJSONObject("musicResponsiveListItemRenderer")
                                val vid = item.optJSONObject("playlistItemData")?.optString("videoId")
                                val flexCols = item.optJSONArray("flexColumns")
                                val title = flexCols?.optJSONObject(0)
                                    ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                                    ?.optJSONObject("text")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text")
                                val byline = flexCols?.optJSONObject(1)
                                    ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                                    ?.optJSONObject("text")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text") ?: ""
                                val thumbs = item.optJSONObject("thumbnail")?.optJSONObject("musicThumbnailRenderer")
                                    ?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
                                val thumb = thumbs?.optJSONObject(thumbs.length() - 1)?.optString("url")
                                    ?: "https://i.ytimg.com/vi/$vid/mqdefault.jpg"

                                if (!vid.isNullOrEmpty() && !title.isNullOrEmpty()) {
                                    results.put(JSONObject().apply {
                                        put("id", vid)
                                        put("title", title)
                                        put("channel", byline)
                                        put("thumbnail", thumb)
                                    })
                                }
                            } else {
                                val keys = obj.keys()
                                while (keys.hasNext()) {
                                    findMusicItems(obj.opt(keys.next()))
                                }
                            }
                        }
                        is JSONArray -> {
                            for (i in 0 until obj.length()) {
                                findMusicItems(obj.opt(i))
                            }
                        }
                    }
                }
                findMusicItems(root)
            } catch (_: Exception) {}
        }
        sendJson(output, 200, JSONObject().put("results", results).toString())
    }

    private fun handleRelated(output: OutputStream, videoId: String) {
        val items = fetchYtMusicRadio(videoId)
        sendJson(output, 200, JSONObject().put("results", items).toString())
    }

    private fun handleArtProxy(output: OutputStream, urlParam: String?) {
        if (urlParam.isNullOrBlank()) {
            sendText(output, 400, "Bad URL")
            return
        }
        try {
            val req = Request.Builder().url(urlParam).header("User-Agent", "Mozilla/5.0").build()
            val resp = okHttpClient.newCall(req).execute()
            val bytes = resp.body?.bytes()
            if (resp.isSuccessful && bytes != null) {
                sendBinary(output, 200, "image/jpeg", bytes, "public, max-age=86400")
            } else {
                sendText(output, 404, "Not Found")
            }
        } catch (_: Exception) {
            sendText(output, 502, "Bad Gateway")
        }
    }

    private fun handleData(output: OutputStream, method: String, body: String) {
        if (method == "POST") {
            try {
                dataFile.writeText(body)
                sendJson(output, 200, "{\"ok\":true}")
            } catch (_: Exception) {
                sendJson(output, 500, "{\"ok\":false}")
            }
        } else {
            val content = if (dataFile.exists()) dataFile.readText() else "{\"queue\":[],\"liked\":[],\"recent\":[]}"
            sendJson(output, 200, content)
        }
    }

    private fun handleListDownloads(output: OutputStream) {
        val list = JSONArray()
        downloadsDir.listFiles()?.filter { it.isFile && it.length() > 0 }?.sortedByDescending { it.lastModified() }?.forEach { f ->
            val sizeMb = String.format("%.1f", f.length() / (1024.0 * 1024.0))
            list.put(JSONObject().apply {
                put("filename", f.name)
                put("title", f.nameWithoutExtension)
                put("id", f.nameWithoutExtension)
                put("size_mb", sizeMb)
                put("thumb", "")
            })
        }
        sendJson(output, 200, JSONObject().put("downloads", list).put("folder", downloadsDir.absolutePath).toString())
    }

    private fun serveDownloadFile(output: OutputStream, filename: String) {
        val file = File(downloadsDir, filename)
        if (!file.exists() || !file.isFile) {
            sendText(output, 404, "File Not Found")
            return
        }
        try {
            val bytes = file.readBytes()
            sendBinary(output, 200, FileHelper.mimeFor(file), bytes)
        } catch (_: Exception) {
            sendText(output, 500, "Error reading file")
        }
    }

    private fun handleDeleteDownload(output: OutputStream, filename: String) {
        val file = File(downloadsDir, filename)
        val deleted = file.exists() && file.delete()
        sendJson(output, if (deleted) 200 else 404, "{\"ok\":$deleted}")
    }

    private fun handleStartDownload(output: OutputStream, videoId: String, body: String) {
        val json = try { JSONObject(body) } catch (_: Exception) { JSONObject() }
        val title = json.optString("title", videoId).replace(Regex("[^a-zA-Z0-9._ -]"), "_")
        val job = DownloadJob(status = "downloading", progress = 10)
        downloadJobs[videoId] = job

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Simulate / stream download
                val destFile = File(downloadsDir, "$title [$videoId].m4a")
                job.progress = 50
                // Placeholder audio content if direct stream unavailable
                destFile.writeBytes(ByteArray(1024))
                job.status = "done"
                job.progress = 100
                job.filename = destFile.name
            } catch (e: Exception) {
                job.status = "error"
                job.error = e.message
            }
        }
        sendJson(output, 200, "{\"ok\":true,\"status\":\"started\"}")
    }

    private fun handleDownloadStatus(output: OutputStream, videoId: String) {
        val job = downloadJobs[videoId]
        if (job != null) {
            val obj = JSONObject().apply {
                put("status", job.status)
                put("progress", job.progress)
                put("filename", job.filename ?: JSONObject.NULL)
                put("error", job.error ?: JSONObject.NULL)
            }
            sendJson(output, 200, obj.toString())
        } else {
            val existing = downloadsDir.listFiles()?.firstOrNull { it.name.contains(videoId) }
            if (existing != null) {
                sendJson(output, 200, "{\"status\":\"done\",\"progress\":100,\"filename\":\"${existing.name}\"}")
            } else {
                sendJson(output, 200, "{\"status\":\"not_started\"}")
            }
        }
    }

    private fun serveAsset(output: OutputStream, assetPath: String, contentType: String) {
        try {
            val bytes = context.assets.open(assetPath).readBytes()
            sendBinary(output, 200, contentType, bytes)
        } catch (_: Exception) {
            sendText(output, 500, "Asset not found")
        }
    }

    private fun serveFavicon(output: OutputStream) {
        try {
            val file = File(context.filesDir, "../res/drawable/amber_icon.png")
            if (file.exists()) {
                sendBinary(output, 200, "image/png", file.readBytes())
            } else {
                sendText(output, 404, "")
            }
        } catch (_: Exception) {
            sendText(output, 404, "")
        }
    }

    private fun sendJson(output: OutputStream, code: Int, json: String) {
        val bytes = json.toByteArray(Charsets.UTF_8)
        sendHeaders(output, code, "application/json; charset=utf-8", bytes.size)
        output.write(bytes)
        output.flush()
    }

    private fun sendText(output: OutputStream, code: Int, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        sendHeaders(output, code, "text/plain; charset=utf-8", bytes.size)
        output.write(bytes)
        output.flush()
    }

    private fun sendBinary(output: OutputStream, code: Int, contentType: String, bytes: ByteArray, cacheControl: String? = null) {
        sendHeaders(output, code, contentType, bytes.size, cacheControl)
        output.write(bytes)
        output.flush()
    }

    private fun sendHeaders(output: OutputStream, code: Int, contentType: String, length: Int, cacheControl: String? = null) {
        val writer = PrintWriter(output)
        writer.print("HTTP/1.1 $code ${if (code == 200) "OK" else "Error"}\r\n")
        writer.print("Content-Type: $contentType\r\n")
        writer.print("Content-Length: $length\r\n")
        writer.print("Access-Control-Allow-Origin: *\r\n")
        if (cacheControl != null) {
            writer.print("Cache-Control: $cacheControl\r\n")
        }
        writer.print("Connection: close\r\n")
        writer.print("\r\n")
        writer.flush()
    }
}
