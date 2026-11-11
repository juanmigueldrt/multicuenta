package com.multicuenta.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat

/** Native notifications for messages reported by the web pages (see NOTIFICATION_JS). */
object Notifications {
    private const val MESSAGES = "messages"
    private const val CONNECTION = "connection"
    const val EXTRA_ACCOUNT = "account"

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(MESSAGES, "Mensajes", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "Mensajes nuevos de WhatsApp y Telegram" }
        )
        nm.createNotificationChannel(
            NotificationChannel(CONNECTION, "Conexión en segundo plano", NotificationManager.IMPORTANCE_MIN)
                .apply { description = "Mantiene las cuentas conectadas para recibir mensajes" }
        )
    }

    private fun openApp(context: Context, accountId: String?, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .apply { if (accountId != null) putExtra(EXTRA_ACCOUNT, accountId) }
        return PendingIntent.getActivity(
            context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** One notification per account and conversation [tag]; a newer message replaces the older one. */
    fun message(context: Context, acc: Account, title: String, body: String, tag: String) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val id = (acc.id + "/" + tag.ifEmpty { title }).hashCode()
        val n = NotificationCompat.Builder(context, MESSAGES)
            .setSmallIcon(R.drawable.ic_chat)
            .setColor(acc.service.color)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setSubText("${acc.service.label} · ${acc.name}")
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setGroup(acc.id)
            .setAutoCancel(true)
            .setContentIntent(openApp(context, acc.id, acc.id.hashCode()))
            .build()
        try {
            NotificationManagerCompat.from(context).notify(id, n)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS revoked meanwhile: nothing to show
        }
    }

    fun connection(context: Context) = NotificationCompat.Builder(context, CONNECTION)
        .setSmallIcon(R.drawable.ic_chat)
        .setContentTitle("Multicuenta conectado")
        .setContentText("Recibiendo mensajes de tus cuentas")
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_MIN)
        .setContentIntent(openApp(context, null, 0))
        .build()
}

/**
 * Foreground service with a silent notification. It holds no logic: its only job is to stop
 * Android from freezing or killing the process, so the web sessions stay connected and the
 * app does not reload when you come back to it.
 */
class KeepAliveService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(
            this, 1, Notifications.connection(this),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
        return START_STICKY
    }
}
