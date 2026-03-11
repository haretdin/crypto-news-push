package com.cryptonews.push.data

import androidx.core.text.HtmlCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
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
import java.time.Instant
import java.time.OffsetDateTime
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
    private enum class FeedParser {
        BWE_STYLE,
        GENERIC
    }

    private data class RssFeed(
        val name: String,
        val url: String,
        val parser: FeedParser = FeedParser.GENERIC
    )

    private data class RssSyncResult(
        val insertedCount: Int,
        val successfulFeeds: Int,
        val failedFeeds: List<String>
    )

    private val rssFeeds = listOf(
        RssFeed(name = "BWEnews", url = "https://rss-public.bwe-ws.com/", parser = FeedParser.BWE_STYLE),
        RssFeed(name = "CoinDesk", url = "https://www.coindesk.com/arc/outboundfeeds/rss/"),
        RssFeed(name = "Cointelegraph", url = "https://cointelegraph.com/rss"),
        RssFeed(name = "TechCrunch", url = "https://techcrunch.com/feed/"),
        RssFeed(name = "Google", url = "https://blog.google/feed/"),
        RssFeed(name = "Microsoft", url = "https://blogs.microsoft.com/feed/")
    )

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

    suspend fun refreshNow(): Int = withContext(ioDispatcher) {
        fetchAndStoreRssItems().insertedCount
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
                onStatusChanged("Connected to BWEnews live feed")
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
                    val result = fetchAndStoreRssItems()
                    onStatusChanged(
                        "Feeds synced: ${result.insertedCount} new items from ${result.successfulFeeds}/${rssFeeds.size} feeds"
                    )
                    if (result.successfulFeeds == 0 && result.failedFeeds.isNotEmpty()) {
                        onFailure("All feeds failed: ${result.failedFeeds.joinToString()}")
                    }
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

    private suspend fun fetchAndStoreRssItems(): RssSyncResult {
        var insertedCount = 0
        var successfulFeeds = 0
        val failures = mutableListOf<String>()

        rssFeeds.forEach { feed ->
            runCatching {
                val request = Request.Builder().url(feed.url).build()
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        error("${feed.name} returned HTTP ${response.code}")
                    }
                    val body = response.body?.string().orEmpty()
                    parseFeed(feed, body)
                }
            }.onSuccess { items ->
                successfulFeeds += 1
                items.forEach { entity ->
                    if (saveIfNew(entity)) {
                        insertedCount += 1
                    }
                }
            }.onFailure { throwable ->
                failures += "${feed.name}: ${throwable.message ?: "unknown error"}"
            }
        }

        return RssSyncResult(
            insertedCount = insertedCount,
            successfulFeeds = successfulFeeds,
            failedFeeds = failures
        )
    }

    private suspend fun saveIfNew(entity: NewsEntity): Boolean {
        if (dao.countByUniqueKey(entity.uniqueKey) > 0) {
            return false
        }
        val id = dao.insert(entity)
        notifier.showNewsNotification(entity.copy(id = id))
        return true
    }

    private fun parseFeed(feed: RssFeed, xml: String): List<NewsEntity> = when (feed.parser) {
        FeedParser.BWE_STYLE -> parseBweRss(xml)
        FeedParser.GENERIC -> parseGenericFeed(feed, xml)
    }

    private fun parseBweRss(xml: String): List<NewsEntity> {
        val itemRegex = Regex(
            """<item>(.*?)</item>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        return itemRegex.findAll(xml).mapNotNull { match ->
            val block = match.groupValues[1]
            val title = extractTag(block, "title")
            val link = extractTag(block, "link")
            val pubDate = extractTag(block, "pubDate")
            toBweRssEntity(title, link, pubDate)
        }.toList()
    }

    private fun parseGenericFeed(feed: RssFeed, xml: String): List<NewsEntity> {
        val rssItems = parseGenericRssItems(feed, xml)
        if (rssItems.isNotEmpty()) {
            return rssItems
        }
        return parseAtomEntries(feed, xml)
    }

    private fun parseGenericRssItems(feed: RssFeed, xml: String): List<NewsEntity> {
        val itemRegex = Regex(
            """<item>(.*?)</item>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        return itemRegex.findAll(xml).mapNotNull { match ->
            val block = match.groupValues[1]
            val title = extractTag(block, "title")
            val link = extractTag(block, "link")
            val pubDate = extractTag(block, "pubDate")
            val description = extractTag(block, "description").ifBlank {
                extractTag(block, "content:encoded")
            }
            toGenericEntity(feed, title, link, pubDate, description)
        }.toList()
    }

    private fun parseAtomEntries(feed: RssFeed, xml: String): List<NewsEntity> {
        val entryRegex = Regex(
            """<entry\b.*?>(.*?)</entry>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        return entryRegex.findAll(xml).mapNotNull { match ->
            val block = match.groupValues[1]
            val title = extractTag(block, "title")
            val link = extractAtomLink(block)
            val published = extractTag(block, "published").ifBlank {
                extractTag(block, "updated")
            }
            val summary = extractTag(block, "summary").ifBlank {
                extractTag(block, "content")
            }
            toGenericEntity(feed, title, link, published, summary)
        }.toList()
    }

    private fun toBweRssEntity(rawTitle: String, link: String, pubDate: String): NewsEntity? {
        if (rawTitle.isBlank() || pubDate.isBlank()) return null

        val cleanedTitle = cleanHtmlText(rawTitle)
        val sourceUrl = Regex("""(?i)\bsource:\s*(https?://\S+)""")
            .find(cleanedTitle)
            ?.groupValues
            ?.get(1)
            ?.let(::normalizeUrl)
            .orEmpty()
        val displayTitle = cleanedTitle
            .replace(Regex("""(?i)\bsource:\s*https?://\S+"""), "")
            .replace(Regex("""https?://t\.me/BWEnews/\S+""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""[ \t]+\n"""), "\n")
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trim()
        val firstLine = cleanedTitle.lineSequence().firstOrNull()?.trim().orEmpty()
        val sourceName = firstLine.substringBefore(":", "BWEnews RSS").trim().ifBlank { "BWEnews RSS" }
        val coins = extractCoins(displayTitle)
        val timestamp = parseTimestamp(pubDate) ?: return null

        return rssItemToEntity(
            sourceName = sourceName,
            newsTitle = displayTitle,
            coinsIncluded = coins,
            url = sourceUrl.ifBlank { normalizeUrl(link) },
            timestamp = timestamp
        )
    }

    private fun toGenericEntity(
        feed: RssFeed,
        rawTitle: String,
        link: String,
        publishedAt: String,
        rawSummary: String
    ): NewsEntity? {
        if (rawTitle.isBlank() || publishedAt.isBlank()) return null

        val title = cleanHtmlText(rawTitle)
        val summary = cleanHtmlText(rawSummary)
        val body = buildString {
            append(title)
            if (summary.isNotBlank() && !summary.equals(title, ignoreCase = true)) {
                append("\n\n")
                append(limitText(summary, 360))
            }
        }.trim()
        val timestamp = parseTimestamp(publishedAt) ?: return null
        return rssItemToEntity(
            sourceName = feed.name,
            newsTitle = body,
            coinsIncluded = extractCoins("$title\n$summary"),
            url = normalizeUrl(link),
            timestamp = timestamp
        )
    }

    private fun cleanHtmlText(raw: String): String {
        var text = raw
            .replace(Regex("""(?i)<!\[CDATA\[|]]>"""), "")
            .replace(Regex("""(?i)<br\s*/?>"""), "\n")

        repeat(2) {
            text = HtmlCompat.fromHtml(text, HtmlCompat.FROM_HTML_MODE_LEGACY).toString()
        }

        return text
            .replace(Regex("""<[^>]+>"""), " ")
            .replace('\u00A0', ' ')
            .replace(Regex("""[ \t]+\n"""), "\n")
            .replace(Regex("""\n{3,}"""), "\n\n")
            .replace(Regex("""[ \t]{2,}"""), " ")
            .trim()
    }

    private fun normalizeUrl(raw: String): String = HtmlCompat.fromHtml(
        raw.trim(),
        HtmlCompat.FROM_HTML_MODE_LEGACY
    ).toString().trim().trimEnd('.', ',', ')')

    private fun extractCoins(text: String): List<String> = Regex("""\$([A-Za-z0-9]{2,15})""")
        .findAll(text)
        .map { it.groupValues[1].uppercase() }
        .distinct()
        .toList()

    private fun limitText(text: String, maxLength: Int): String {
        if (text.length <= maxLength) return text
        return text.take(maxLength).trimEnd() + "..."
    }

    private fun parseTimestamp(value: String): Long? = runCatching {
        ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toEpochSecond()
    }.recoverCatching {
        OffsetDateTime.parse(value).toEpochSecond()
    }.recoverCatching {
        Instant.parse(value).epochSecond
    }.getOrNull()

    private fun extractTag(block: String, tagName: String): String {
        val escapedTag = Regex.escape(tagName)
        val regex = Regex(
            """<$escapedTag(?:\s[^>]*)?>(.*?)</$escapedTag>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        return regex.find(block)?.groupValues?.get(1)?.trim().orEmpty()
    }

    private fun extractAtomLink(block: String): String {
        val alternateRegex = Regex(
            """<link\b[^>]*rel=["']alternate["'][^>]*href=["']([^"']+)["'][^>]*/?>""",
            RegexOption.IGNORE_CASE
        )
        val hrefRegex = Regex(
            """<link\b[^>]*href=["']([^"']+)["'][^>]*/?>""",
            RegexOption.IGNORE_CASE
        )
        return alternateRegex.find(block)?.groupValues?.get(1)
            ?: hrefRegex.find(block)?.groupValues?.get(1)
            ?: ""
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
