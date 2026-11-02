package com.potatoremote

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import org.webrtc.PeerConnectionFactory

class PotatoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        createChannels()
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(this)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val sharing = NotificationChannel(
            CHANNEL_SHARING,
            getString(R.string.notification_channel_sharing),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Session Potato Remote active"
            setShowBadge(false)
        }
        val alerts = NotificationChannel(
            CHANNEL_ALERTS,
            getString(R.string.notification_channel_alerts),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Connexions de spectateurs et activité de contrôle"
            setShowBadge(false)
        }
        manager.createNotificationChannels(listOf(sharing, alerts))
    }

    companion object {
        const val CHANNEL_SHARING = "potato_sharing"
        const val CHANNEL_ALERTS = "potato_alerts"
    }
}
