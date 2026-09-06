package com.jiabihuan.tvnetguard

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.jiabihuan.tvnetguard.data.RuleStore
import com.jiabihuan.tvnetguard.util.Prefs

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        RuleStore.init(this)
        createChannel()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                Guard.CHANNEL_ID,
                getString(R.string.notify_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
                description = "显示实时上下行速率与被限速应用数量"
            }
            nm.createNotificationChannel(channel)
        }
    }

    object Guard {
        const val CHANNEL_ID = "tvnetguard_guard"
        const val NOTIFY_ID = 20240901
    }
}
