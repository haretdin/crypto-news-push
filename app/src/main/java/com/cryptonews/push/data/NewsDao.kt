package com.cryptonews.push.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface NewsDao {
    @Query("SELECT * FROM news_items ORDER BY timestamp DESC, id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<NewsEntity>>

    @Query("SELECT COUNT(*) FROM news_items WHERE uniqueKey = :uniqueKey")
    suspend fun countByUniqueKey(uniqueKey: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: NewsEntity): Long

    @Query("UPDATE news_items SET translatedText = :translatedText WHERE id = :id")
    suspend fun updateTranslation(id: Long, translatedText: String)
}
