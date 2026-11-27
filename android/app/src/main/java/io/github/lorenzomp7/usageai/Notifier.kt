package io.github.lorenzomp7.usageai

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

object Notifier {
    private const val CH_ALERTS = "alerts"
    private const val CH_ACCOUNT = "account"

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERTS, "Soglie di consumo", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "Avvisi quando superi le soglie o vai troppo veloce" }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ACCOUNT, "Accesso", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "Sessione di claude.ai scaduta" }
        )
    }

    fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun alert(context: Context, alert: Alert) =
        post(context, CH_ALERTS, alert.key.hashCode(), alert.title, alert.text)

    fun authExpired(context: Context) =
        post(context, CH_ACCOUNT, 1, "UsageAI: sessione scaduta",
            "Apri l'app e rifai l'accesso a claude.ai per continuare a ricevere gli avvisi.")

    private fun post(context: Context, channel: String, id: Int, title: String, text: String) {
        if (!canNotify(context)) return
        createChannels(context)
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_usage)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(id, n)
    }
}
