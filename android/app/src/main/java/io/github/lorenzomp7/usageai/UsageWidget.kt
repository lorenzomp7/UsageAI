package io.github.lorenzomp7.usageai

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt

/** Widget per la Home: per ogni servizio la finestra più consumata (fino a 4 righe). */
class UsageWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        render(context, manager, ids)
    }

    companion object {
        private val ROWS = listOf(
            intArrayOf(R.id.w_row1, R.id.w_label1, R.id.w_value1, R.id.w_bar1, R.id.w_sub1),
            intArrayOf(R.id.w_row2, R.id.w_label2, R.id.w_value2, R.id.w_bar2, R.id.w_sub2),
            intArrayOf(R.id.w_row3, R.id.w_label3, R.id.w_value3, R.id.w_bar3, R.id.w_sub3),
            intArrayOf(R.id.w_row4, R.id.w_label4, R.id.w_value4, R.id.w_bar4, R.id.w_sub4),
        )

        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, UsageWidget::class.java))
            if (ids.isNotEmpty()) render(context, manager, ids)
        }

        /** La finestra più consumata di ogni servizio; se sono più di 4, le 4 più alte. */
        fun headline(windows: List<UsageWindow>): List<UsageWindow> {
            val perProvider = windows.groupBy { it.provider }.values.map { ws -> ws.maxByOrNull { it.utilization }!! }
            val top = perProvider.sortedByDescending { it.utilization }.take(ROWS.size).toSet()
            return perProvider.filter { it in top }
        }

        private fun render(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val store = Store(context)
            val now = Instant.now()
            val shown = headline(store.lastWindows)
            val errors = store.errors
            val state = store.state

            val views = RemoteViews(context.packageName, R.layout.widget_usage)
            ROWS.forEachIndexed { i, r ->
                val w = shown.getOrNull(i)
                if (w == null) {
                    views.setViewVisibility(r[0], View.GONE)
                    return@forEachIndexed
                }
                views.setViewVisibility(r[0], View.VISIBLE)
                views.setTextViewText(r[1], (if (w.provider in errors) "⚠ " else "") + w.fullLabel)
                views.setTextViewText(r[2], "${w.utilization.roundToInt()}%")
                views.setProgressBar(r[3], 100, w.utilization.roundToInt().coerceIn(0, 100), false)
                val sub = if (w.isLong) todayText(w, state[w.key])?.replaceFirstChar { it.uppercase() }
                    ?: paceText(w, now) ?: formatReset(w.resetsAt, now) else formatReset(w.resetsAt, now)
                views.setTextViewText(r[4], sub)
            }
            views.setViewVisibility(R.id.w_empty, if (shown.isEmpty()) View.VISIBLE else View.GONE)

            val footer = if (store.lastCheck > 0) {
                val t = Instant.ofEpochMilli(store.lastCheck).atZone(ZoneId.systemDefault())
                val base = "Aggiornato alle %02d:%02d".format(t.hour, t.minute)
                if (errors.isNotEmpty()) "$base · ${errors.size} con errori" else base
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
