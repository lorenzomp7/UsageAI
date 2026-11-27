package io.github.lorenzomp7.usageai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Logica pura comune a tutti i servizi (niente Android).
 * È la stessa dell'app Windows: windows/usageai/core.py.
 */

class AuthException(message: String) : Exception(message)
class ApiException(message: String, val status: Int = 0, val body: String = "") : Exception(message)

/** Accetta un header Cookie completo oppure il solo valore del cookie principale. */
fun cookieHeader(credential: String, defaultName: String): String {
    var c = credential.trim()
    if (c.lowercase().startsWith("cookie:")) c = c.substring(7).trim()
    return if ('=' in c) c else "$defaultName=$c"
}

val SHORT_WINDOW_MAX: Duration = Duration.ofHours(12)
private val NEW_WINDOW_TOLERANCE: Duration = Duration.ofMinutes(30)
private const val PACE_MIN_UTILIZATION = 20.0

val PROVIDER_NAMES = linkedMapOf(
    "claude" to "Claude",
    "codex" to "ChatGPT/Codex",
    "gemini" to "Gemini",
    "cursor" to "Cursor",
    "copilot" to "Copilot",
    "perplexity" to "Perplexity",
    "grok" to "Grok",
    "mistral" to "Mistral",
    "openrouter" to "OpenRouter",
)

data class UsageWindow(
    val provider: String,
    val key: String,
    val label: String,
    val utilization: Double,
    val resetsAt: Instant?,
    val length: Duration?,
) {
    val isLong: Boolean get() = length == null || length > SHORT_WINDOW_MAX
    val fullLabel: String get() = "${PROVIDER_NAMES[provider] ?: provider} · $label"
}

data class AlertSettings(
    val thresholdsShort: List<Int> = listOf(80),
    val thresholdsLong: List<Int> = listOf(50, 75, 90),
    val paceEnabled: Boolean = true,
    val paceMargin: Int = 10,
)

data class WindowState(
    val resetsAt: String? = null,
    val notified: List<Int> = emptyList(),
    val paceNotified: Boolean = false,
)

data class Alert(val key: String, val title: String, val text: String)

// ---------- utilità ----------

fun parseInstant(value: String?): Instant? {
    if (value.isNullOrBlank()) return null
    val v = value.trim()
    return try {
        OffsetDateTime.parse(v).toInstant()
    } catch (e: DateTimeParseException) {
        try { Instant.parse(v) } catch (e2: DateTimeParseException) { null }
    }
}

fun windowLabel(length: Duration?): String {
    if (length == null) return "Periodo"
    val hours = length.seconds / 3600.0
    return when {
        abs(hours - 5) < 0.1 -> "Sessione 5 ore"
        abs(hours - 24 * 7) < 1 -> "Settimana"
        hours in (27.0 * 24)..(32.0 * 24) -> "Mese"
        hours < 48 -> "Finestra ${hours.roundToInt()} ore"
        else -> "Finestra ${(hours / 24).roundToInt()} giorni"
    }
}

/** Durata del periodo mensile che finisce in [end] (stesso giorno del mese prima, o l'ultimo disponibile). */
fun monthLengthEndingAt(end: Instant): Duration {
    val z = end.atZone(java.time.ZoneOffset.UTC)
    return Duration.between(z.minusMonths(1), z)
}

fun expectedUtilization(w: UsageWindow, now: Instant): Double? {
    val reset = w.resetsAt ?: return null
    val len = w.length ?: return null
    if (len.isZero || len.isNegative) return null
    val remaining = Duration.between(now, reset).toMillis().toDouble()
    return (1.0 - remaining / len.toMillis()).coerceIn(0.0, 1.0) * 100.0
}

fun paceText(w: UsageWindow, now: Instant): String? {
    val exp = expectedUtilization(w, now) ?: return null
    val diff = w.utilization - exp
    return when {
        diff > 2 -> "in anticipo di ${diff.roundToInt()} punti sul ritmo (ideale ${exp.roundToInt()}%)"
        diff < -2 -> "margine di ${(-diff).roundToInt()} punti sul ritmo (ideale ${exp.roundToInt()}%)"
        else -> "in linea con il ritmo (ideale ${exp.roundToInt()}%)"
    }
}

private val DAYS_IT = listOf("lun", "mar", "mer", "gio", "ven", "sab", "dom")

fun formatReset(resetsAt: Instant?, now: Instant, zone: ZoneId = ZoneId.systemDefault()): String {
    if (resetsAt == null) return "nessuna finestra attiva"
    val secs = Duration.between(now, resetsAt).seconds
    if (secs <= 0) return "reset in corso"
    val hours = secs / 3600
    val minutes = (secs % 3600) / 60
    val t = resetsAt.atZone(zone)
    val day = DAYS_IT[t.dayOfWeek.value - 1]
    val whenText = if (hours >= 24 * 6) "%s %02d/%02d %02d:%02d".format(day, t.dayOfMonth, t.monthValue, t.hour, t.minute)
    else "%s %02d:%02d".format(day, t.hour, t.minute)
    return if (hours >= 24) "reset $whenText (tra ${hours / 24} g ${hours % 24} h)"
    else "reset $whenText (tra $hours h $minutes min)"
}

private fun sameWindow(oldIso: String?, new: Instant?): Boolean {
    val old = parseInstant(oldIso)
    if (old == null || new == null) return old == null && new == null
    return abs(Duration.between(old, new).toMillis()) <= NEW_WINDOW_TOLERANCE.toMillis()
}

private fun pct(v: Double) = v.roundToInt().toString()

/**
 * Decide le notifiche. Ogni soglia scatta una volta sola per finestra.
 * Lo stato dei servizi assenti da [windows] resta com'è.
 */
fun evaluate(
    windows: List<UsageWindow>,
    settings: AlertSettings,
    state: Map<String, WindowState>,
    now: Instant,
): Pair<List<Alert>, Map<String, WindowState>> {
    val alerts = mutableListOf<Alert>()
    val newState = state.toMutableMap()

    for (w in windows) {
        val old = state[w.key]
        var ws = if (old == null || !sameWindow(old.resetsAt, w.resetsAt)) {
            WindowState(resetsAt = w.resetsAt?.toString())
        } else old

        val thresholds = if (w.isLong) settings.thresholdsLong else settings.thresholdsShort
        val crossed = thresholds.filter { w.utilization >= it && it !in ws.notified }.sorted()
        if (crossed.isNotEmpty()) {
            alerts += Alert(
                key = w.key,
                title = "${w.fullLabel}: ${pct(w.utilization)}% (soglia ${crossed.last()}%)",
                text = formatReset(w.resetsAt, now),
            )
            ws = ws.copy(notified = (ws.notified + crossed).toSortedSet().toList())
        }

        if (w.isLong && settings.paceEnabled) {
            val exp = expectedUtilization(w, now)
            if (exp != null) {
                val ahead = w.utilization - exp
                if (ahead > settings.paceMargin && w.utilization >= PACE_MIN_UTILIZATION && !ws.paceNotified) {
                    alerts += Alert(
                        key = w.key + ":pace",
                        title = "${w.fullLabel}: stai andando troppo veloce",
                        text = "Hai usato il ${pct(w.utilization)}%, a ritmo costante saresti al " +
                            "${pct(exp)}%. ${formatReset(w.resetsAt, now)}",
                    )
                    ws = ws.copy(paceNotified = true)
                } else if (ahead <= 0) {
                    ws = ws.copy(paceNotified = false)
                }
            }
        }
        newState[w.key] = ws
    }
    return alerts to newState
}

/** "50, 75 90" -> [50, 75, 90]. Lancia IllegalArgumentException se non valido. */
fun parseThresholds(text: String): List<Int> {
    val parts = text.split(',', ';', ' ').map { it.trim() }.filter { it.isNotEmpty() }
    val values = parts.map {
        it.toIntOrNull() ?: throw IllegalArgumentException("«$it» non è un numero intero")
    }.toSortedSet().toList()
    require(values.all { it in 1..100 }) { "Le soglie devono stare tra 1 e 100" }
    return values
}

// ---------- serializzazione (SharedPreferences) ----------

private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull

fun stateToJson(state: Map<String, WindowState>): String = buildJsonObject {
    for ((k, v) in state) {
        put(k, buildJsonObject {
            put("resets_at", v.resetsAt)
            put("notified", buildJsonArray { v.notified.forEach { add(JsonPrimitive(it)) } })
            put("pace_notified", v.paceNotified)
        })
    }
}.toString()

fun stateFromJson(json: String?): Map<String, WindowState> {
    if (json.isNullOrBlank()) return emptyMap()
    val root = runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull() ?: return emptyMap()
    return root.mapNotNull { (k0, v) ->
        val o = v as? JsonObject ?: return@mapNotNull null
        val k = if (':' in k0) k0 else "claude:$k0"   // versione 1: solo Claude, chiavi senza prefisso
        k to WindowState(
            resetsAt = o.str("resets_at"),
            notified = (o["notified"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.intOrNull } ?: emptyList(),
            paceNotified = (o["pace_notified"] as? JsonPrimitive)?.booleanOrNull ?: false,
        )
    }.toMap()
}

fun windowsToJson(windows: List<UsageWindow>): String = buildJsonArray {
    for (w in windows) {
        add(buildJsonObject {
            put("provider", w.provider)
            put("key", w.key)
            put("label", w.label)
            put("utilization", w.utilization)
            put("resets_at", w.resetsAt?.toString())
            put("length_s", w.length?.seconds)
        })
    }
}.toString()

fun windowsFromJson(json: String?): List<UsageWindow> {
    if (json.isNullOrBlank()) return emptyList()
    val arr = runCatching { Json.parseToJsonElement(json) as? JsonArray }.getOrNull() ?: return emptyList()
    return arr.mapNotNull { e ->
        val o = e as? JsonObject ?: return@mapNotNull null
        UsageWindow(
            provider = o.str("provider") ?: return@mapNotNull null,
            key = o.str("key") ?: return@mapNotNull null,
            label = o.str("label") ?: "",
            utilization = (o["utilization"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null,
            resetsAt = parseInstant(o.str("resets_at")),
            length = (o["length_s"] as? JsonPrimitive)?.longOrNull?.let { Duration.ofSeconds(it) },
        )
    }
}

fun mapToJson(map: Map<String, String>): String = buildJsonObject {
    for ((k, v) in map) put(k, v)
}.toString()

fun mapFromJson(json: String?): Map<String, String> {
    if (json.isNullOrBlank()) return emptyMap()
    val o = runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull() ?: return emptyMap()
    return o.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }.toMap()
}
