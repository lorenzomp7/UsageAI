package io.github.lorenzomp7.usageai

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt

/** Widget per la schermata Home: barre 5 ore e settimana, ritmo, ultimo controllo. */
class UsageWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        render(context, manager, ids)
    }

    companion object {
        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, UsageWidget::class.java))
            if (ids.isNotEmpty()) render(context, manager, ids)
        }

        private fun render(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val store = Store(context)
            val now = Instant.now()
            val windows = store.lastJson?.let { runCatching { parseUsage(it) }.getOrNull() } ?: emptyList()
            val five = windows.firstOrNull { it.key == "five_hour" }
            val week = windows.firstOrNull { it.key == "seven_day" }

            val views = RemoteViews(context.packageName, R.layout.widget_usage)
            views.setTextViewText(R.id.w_five_value, five?.let { "${it.utilization.roundToInt()}%" } ?: "—")
            views.setProgressBar(R.id.w_five_bar, 100, five?.utilization?.roundToInt()?.coerceIn(0, 100) ?: 0, false)
            views.setTextViewText(R.id.w_five_reset, five?.let { formatReset(it.resetsAt, now) } ?: "")
            views.setTextViewText(R.id.w_week_value, week?.let { "${it.utilization.roundToInt()}%" } ?: "—")
            views.setProgressBar(R.id.w_week_bar, 100, week?.utilization?.roundToInt()?.coerceIn(0, 100) ?: 0, false)
            views.setTextViewText(R.id.w_week_reset, week?.let { paceText(it, now) ?: formatReset(it.resetsAt, now) } ?: "")

            val footer = store.lastError ?: if (store.lastCheck > 0) {
                val t = Instant.ofEpochMilli(store.lastCheck).atZone(ZoneId.systemDefault())
                "Aggiornato alle %02d:%02d".format(t.hour, t.minute)
            } else "Tocca per aprire"
            views.setTextViewText(R.id.w_footer, footer)

            val open = PendingIntent.getActivity(
                context, 1, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            views.setOnClickPendingIntent(R.id.w_root, open)
            manager.updateAppWidget(ids, views)
        }
    }
}
