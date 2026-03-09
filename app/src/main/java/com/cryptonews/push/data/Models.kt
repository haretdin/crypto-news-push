package com.cryptonews.push.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

const val FEED_TYPE_WEBSOCKET = "websocket"
const val FEED_TYPE_RSS = "rss"

@Serializable
data class NewsPayload(
    @SerialName("source_name") val sourceName: String,
    @SerialName("news_title") val newsTitle: String,
    @SerialName("coins_included") val coinsIncluded: List<String>,
    val url: String,
    val timestamp: Long
)

@Entity(tableName = "news_items")
data class NewsEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val feedType: String = FEED_TYPE_WEBSOCKET,
    val uniqueKey: String,
    val sourceName: String,
    val newsTitle: String,
    val coinsIncludedCsv: String,
    val url: String,
    val timestamp: Long,
    val translatedText: String? = null,
    val receivedAt: Long = System.currentTimeMillis()
) {
    fun formattedTime(): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return formatter.format(Date(timestamp * 1000))
    }

    fun coinList(): List<String> =
        coinsIncludedCsv.split(',').map { it.trim() }.filter { it.isNotEmpty() }
}

fun NewsPayload.toEntity(): NewsEntity = NewsEntity(
    feedType = FEED_TYPE_WEBSOCKET,
    uniqueKey = "ws:$url:$timestamp",
    sourceName = sourceName,
    newsTitle = newsTitle,
    coinsIncludedCsv = coinsIncluded.joinToString(", "),
    url = url,
    timestamp = timestamp
)

fun rssItemToEntity(
    sourceName: String,
    newsTitle: String,
    coinsIncluded: List<String>,
    url: String,
    timestamp: Long
): NewsEntity = NewsEntity(
    feedType = FEED_TYPE_RSS,
    uniqueKey = "rss:$timestamp:${newsTitle.hashCode()}",
    sourceName = sourceName,
    newsTitle = newsTitle,
    coinsIncludedCsv = coinsIncluded.joinToString(", "),
    url = url,
    timestamp = timestamp
)

data class TranslationResult(
    val translatedText: String
)
