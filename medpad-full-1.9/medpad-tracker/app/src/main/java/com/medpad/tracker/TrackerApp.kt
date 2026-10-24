package com.medpad.tracker

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

class TrackerApp : Application() {

    companion object {
        const val CHANNEL_TRACKING = "medpad_tracking"
        const val CHANNEL_ALERTS = "medpad_alerts"

        lateinit var instance: TrackerApp
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Session.init(this)
        com.medpad.tracker.sync.TrackerContext.init(this)
        createChannels()
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_TRACKING,
                getString(R.string.notif_channel_tracking),
                NotificationManager.IMPORTANCE_LOW
            )
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALERTS,
                getString(R.string.notif_channel_alerts),
                NotificationManager.IMPORTANCE_HIGH
            )
        )
    }
}
