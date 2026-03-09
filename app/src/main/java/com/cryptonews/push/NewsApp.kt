package com.cryptonews.push

import android.app.Application
import androidx.room.Room
import com.cryptonews.push.data.AppDatabase
import com.cryptonews.push.data.NewsNotifier
import com.cryptonews.push.data.NewsRepository

class NewsApp : Application() {
    lateinit var database: AppDatabase
        private set

    lateinit var repository: NewsRepository
        private set

    override fun onCreate() {
        super.onCreate()
        val notifier = NewsNotifier(this)
        notifier.ensureChannels()

        database = Room.databaseBuilder(
            applicationContext,
            AppDatabase::class.java,
            "crypto-news.db"
        ).fallbackToDestructiveMigration().build()

        repository = NewsRepository.create(database, notifier)
    }
}
