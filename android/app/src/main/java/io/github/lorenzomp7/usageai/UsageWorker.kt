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
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.util.concurrent.TimeUnit

/** Un controllo completo di tutti i servizi configurati: scarica, valuta, notifica, aggiorna il widget. */
object UsageChecker {
    suspend fun check(context: Context) {
        val store = Store(context)
        val http = Http(context)
        val now = Instant.now()
        val previous = store.lastWindows
        val errors = store.errors.toMutableMap()
        val warned = store.authWarned.toMutableSet()
        val fresh = mutableListOf<UsageWindow>()
        val kept = mutableListOf<UsageWindow>()
        val active = SERVICES.mapNotNull { s -> store.credential(s.id)?.let { s to it } }

        for ((service, credential) in active) {
            try {
                val ws = service.fetch(http, store, credential)
                fresh += ws
                errors.remove(service.id)
                warned.remove(service.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AuthException) {
                errors[service.id] = e.message ?: "Accesso scaduto"
                kept += previous.filter { it.provider == service.id }
                if (service.id !in warned) {
                    warned += service.id
                    Notifier.authExpired(context, service.name, e.message ?: "")
                }
            } catch (e: ApiException) {
                errors[service.id] = e.message ?: "Errore"
                kept += previous.filter { it.provider == service.id }
            } catch (e: Exception) {
                errors[service.id] = "Errore inatteso: ${e.javaClass.simpleName}"
                kept += previous.filter { it.provider == service.id }
            }
        }

        val activeIds = active.map { it.first.id }.toSet()
        val (alerts, newState) = evaluate(fresh, store.settings, store.state, now)
        store.state = newState
        store.lastWindows = (fresh + kept).sortedBy { w -> SERVICES.indexOfFirst { it.id == w.provider } }
        store.errors = errors.filterKeys { it in activeIds }
        store.authWarned = warned
        store.lastCheck = now.toEpochMilli()
        alerts.forEach { Notifier.alert(context, it) }
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
