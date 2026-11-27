package io.github.lorenzomp7.usageai

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit

/** Un controllo completo: scarica, valuta le soglie, notifica, aggiorna il widget. */
object UsageChecker {
    suspend fun check(context: Context) {
        val store = Store(context)
        if (store.sessionKey == null) {
            store.lastError = "Accesso a claude.ai non ancora fatto"
            UsageWidget.updateAll(context)
            return
        }
        try {
            val json = ClaudeApi(context, store).fetchUsageJson()
            val windows = parseUsage(json)
            if (windows.isEmpty()) throw ApiException("La risposta non contiene dati di consumo")
            val now = Instant.now()
            val (alerts, newState) = evaluate(windows, store.settings, store.state, now)
            store.state = newState
            store.lastJson = json
            store.lastCheck = now.toEpochMilli()
            store.lastError = null
            store.authWarned = false
            alerts.forEach { Notifier.alert(context, it) }
        } catch (e: AuthException) {
            store.lastError = e.message
            if (!store.authWarned) {
                store.authWarned = true
                Notifier.authExpired(context)
            }
        } catch (e: ApiException) {
            store.lastError = e.message
        } catch (e: IOException) {
            store.lastError = "Rete non disponibile"
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            store.lastError = "Errore inatteso: ${e.javaClass.simpleName}"
        }
        UsageWidget.updateAll(context)
    }
}

class UsageWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        UsageChecker.check(applicationContext)
        return Result.success()
    }

    companion object {
        private const val PERIODIC = "usage_periodic"
        private const val NOW = "usage_now"

        private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** Controllo ogni 15 minuti (il minimo concesso da Android per i lavori periodici). */
        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<UsageWorker>(15, TimeUnit.MINUTES)
                .setConstraints(network)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, req)
        }

        fun runNow(context: Context) {
            val req = OneTimeWorkRequestBuilder<UsageWorker>().setConstraints(network).build()
            WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, req)
        }
    }
}
