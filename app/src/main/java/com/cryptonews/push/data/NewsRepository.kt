package com.cryptonews.push.data

import androidx.core.text.HtmlCompat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

class NewsRepository(
    private val dao: NewsDao,
    private val notifier: NewsNotifier,
    private val translator: DeepSeekTranslator,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
) {
    private val rssUrl = "https://rss-public.bwe-ws.com/"
    val newsItems: Flow<List<NewsEntity>> = dao.observeRecent(200)
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    @Volatile
    private var webSocket: WebSocket? = null
    private var pingJob: Job? = null
    private var rssJob: Job? = null

    fun connect(onStatusChanged: (String) -> Unit, onFailure: (String) -> Unit) {
        connectWebSocket(onStatusChanged, onFailure)
        startRssPolling(onStatusChanged, onFailure)
    }

    fun disconnect() {
        stopPingLoop()
        stopRssPolling()
        webSocket?.close(1000, "App requested disconnect")
        webSocket = null
    }

    suspend fun translate(item: NewsEntity): String = withContext(ioDispatcher) {
        val result = translator.translateToEnglish(item)
        dao.updateTranslation(item.id, result.translatedText)
        result.translatedText
    }

    private fun startPingLoop(socket: WebSocket) {
        stopPingLoop()
        pingJob = scope.launch {
            while (true) {
                socket.send("ping")
                delay(20_000)
            }
        }
    }

    private fun stopPingLoop() {
        pingJob?.cancel()
        pingJob = null
    }

    private fun connectWebSocket(onStatusChanged: (String) -> Unit, onFailure: (String) -> Unit) {
        if (webSocket != null) return

        val request = Request.Builder()
            .url("wss://bwenews-api.bwe-ws.com/ws")
            .build()

        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                onStatusChanged("Connected to BWEnews")
                startPingLoop(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text == "pong") {
                    onStatusChanged("Connection healthy")
                    return
                }

                runCatching {
                    json.decodeFromString(NewsPayload.serializer(), text)
                }.onSuccess { payload ->
                    scope.launch {
                        saveIfNew(payload.toEntity())
                    }
                }.onFailure {
                    onFailure("Failed to parse message: ${it.message}")
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                onStatusChanged("Closing: $code $reason")
                stopPingLoop()
                this@NewsRepository.webSocket = null
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                onStatusChanged("Closed: $code $reason")
                stopPingLoop()
                this@NewsRepository.webSocket = null
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                stopPingLoop()
                this@NewsRepository.webSocket = null
                onFailure("WebSocket error: ${t.message}")
            }
        })
    }

    private fun startRssPolling(onStatusChanged: (String) -> Unit, onFailure: (String) -> Unit) {
        if (rssJob != null) return
        rssJob = scope.launch {
            while (true) {
                try {
                    val inserted = fetchAndStoreRssItems()
                    onStatusChanged("RSS synced: $inserted new items")
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (t: Throwable) {
                    onFailure("RSS polling error: ${t.message}")
                }
                delay(60_000)
            }
        }
    }

    private fun stopRssPolling() {
        rssJob?.cancel()
        rssJob = null
    }

    private suspend fun fetchAndStoreRssItems(): Int {
        val request = Request.Builder().url(rssUrl).build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("RSS request failed with HTTP ${response.code}")
            }
            val body = response.body?.string().orEmpty()
            val items = parseRss(body)
            var insertedCount = 0
            items.forEach { entity ->
                if (saveIfNew(entity)) {
                    insertedCount += 1
                }
            }
            return insertedCount
        }
    }

    private suspend fun saveIfNew(entity: NewsEntity): Boolean {
        if (dao.countByUniqueKey(entity.uniqueKey) > 0) {
            return false
        }
        val id = dao.insert(entity)
        notifier.showNewsNotification(entity.copy(id = id))
        return true
    }

    private fun parseRss(xml: String): List<NewsEntity> {
        val itemRegex = Regex("""<item>(.*?)</item>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        return itemRegex.findAll(xml).mapNotNull { match ->
            val block = match.groupValues[1]
            val title = extractTag(block, "title")
            val link = extractTag(block, "link")
            val pubDate = extractTag(block, "pubDate")
            toRssEntity(title, link, pubDate)
        }.toList()
    }

    private fun toRssEntity(rawTitle: String, link: String, pubDate: String): NewsEntity? {
        if (rawTitle.isBlank() || pubDate.isBlank()) return null

        val cleanedTitle = HtmlCompat.fromHtml(rawTitle, HtmlCompat.FROM_HTML_MODE_LEGACY)
            .toString()
            .replace('\u00A0', ' ')
            .trim()
        val sourceUrl = Regex("""(?i)\bsource:\s*(https?://\S+)""")
            .find(cleanedTitle)
            ?.groupValues
            ?.get(1)
            ?.trim()
            ?.trimEnd('.', ',', ')')
            .orEmpty()
        val displayTitle = cleanedTitle
            .replace(Regex("""(?i)\bsource:\s*https?://\S+"""), "")
            .replace(Regex("""https?://t\.me/BWEnews/\S+""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""[ \t]+\n"""), "\n")
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trim()
        val firstLine = cleanedTitle.lineSequence().firstOrNull()?.trim().orEmpty()
        val sourceName = firstLine.substringBefore(":", "BWEnews RSS").trim().ifBlank { "BWEnews RSS" }
        val coins = Regex("""\$([A-Za-z0-9]{2,15})""")
            .findAll(displayTitle)
            .map { it.groupValues[1].uppercase() }
            .distinct()
            .toList()
        val timestamp = ZonedDateTime.parse(pubDate, DateTimeFormatter.RFC_1123_DATE_TIME).toEpochSecond()

        return rssItemToEntity(
            sourceName = sourceName,
            newsTitle = displayTitle,
            coinsIncluded = coins,
            url = sourceUrl,
            timestamp = timestamp
        )
    }

    private fun extractTag(block: String, tagName: String): String {
        val regex = Regex("""<$tagName>(.*?)</$tagName>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        return regex.find(block)?.groupValues?.get(1)?.trim().orEmpty()
    }

    companion object {
        fun create(appDatabase: AppDatabase, notifier: NewsNotifier): NewsRepository {
            val json = Json { ignoreUnknownKeys = true }
            val httpClient = OkHttpClient.Builder()
                .pingInterval(20, TimeUnit.SECONDS)
                .build()
            return NewsRepository(
                dao = appDatabase.newsDao(),
                notifier = notifier,
                translator = DeepSeekTranslator(httpClient, json),
                json = json,
                httpClient = httpClient
            )
        }
    }
}
