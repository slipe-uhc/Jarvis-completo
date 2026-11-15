package com.slipe.jarvis

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

object Notifier {
    const val CH_SOUND = "jv_sound"
    const val CH_SILENT = "jv_silent"
    const val CH_SERVICE = "jv_service"

    fun ensure(c: Context) {
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CH_SOUND, "Avisos da rotina", NotificationManager.IMPORTANCE_HIGH))
        val silent = NotificationChannel(CH_SILENT, "Avisos silenciosos", NotificationManager.IMPORTANCE_LOW)
        silent.setSound(null, null)
        silent.enableVibration(false)
        nm.createNotificationChannel(silent)
        val svc = NotificationChannel(CH_SERVICE, "Jarvis ouvindo", NotificationManager.IMPORTANCE_LOW)
        svc.setSound(null, null)
        nm.createNotificationChannel(svc)
    }

    fun openPending(c: Context): PendingIntent =
        PendingIntent.getActivity(
            c, 0, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    fun notify(c: Context, title: String, body: String, sound: Boolean) {
        ensure(c)
        val quiet = !sound || !Core.soundOn(c) || Core.mode(c) == "silencioso"
        val b = Notification.Builder(c, if (quiet) CH_SILENT else CH_SOUND)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(openPending(c))
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify((System.currentTimeMillis() % 100000).toInt() + 100, b.build())
    }

    fun notifyTap(c: Context, label: String, i: Intent) {
        ensure(c)
        val pi = PendingIntent.getActivity(c, 2, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = Notification.Builder(c, CH_SOUND)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Jarvis")
            .setContentText("Toque para abrir: $label")
            .setAutoCancel(true)
            .setContentIntent(pi)
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(77, b.build())
    }
}
