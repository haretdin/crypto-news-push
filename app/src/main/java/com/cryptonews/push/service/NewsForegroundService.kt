package com.cryptonews.push.service

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.cryptonews.push.NewsApp
import com.cryptonews.push.data.NewsNotifier

class NewsForegroundService : Service() {
    private val repository by lazy { (application as NewsApp).repository }
    private val notifier by lazy { NewsNotifier(this) }

    override fun onCreate() {
        super.onCreate()
        notifier.ensureChannels()
        ServiceCompat.startForeground(
            this,
            NewsNotifier.SERVICE_NOTIFICATION_ID,
            notifier.buildServiceNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
        repository.connect(
            onStatusChanged = {},
            onFailure = {}
        )
    }

    override fun onDestroy() {
        repository.disconnect()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
