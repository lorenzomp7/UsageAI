package io.github.lorenzomp7.usageai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException
import kotlin.math.abs
import kotlin.math.roundToInt

/** Logica pura (niente Android): stessa logica dell'app Windows (windows/claude_usage_alert/core.py). */

val FIVE_HOURS: Duration = Duration.ofHours(5)
val SEVEN_DAYS: Duration = Duration.ofDays(7)
private val NEW_WINDOW_TOLERANCE: Duration = Duration.ofMinutes(30)
private const val PACE_MIN_UTILIZATION = 20.0

data class UsageWindow(
    val key: String,
    val label: String,
    val utilization: Double,
    val resetsAt: Instant?,
    val length: Duration,
) {
    val isWeekly: Boolean get() = key.startsWith("seven_day")
}

data class AlertSettings(
    val thresholds5h: List<Int> = listOf(80),
    val thresholdsWeek: List<Int> = listOf(50, 75, 90),
    val paceEnabled: Boolean = true,
    val paceMargin: Int = 10,
)

data class WindowState(
    val resetsAt: String? = null,
    val notified: List<Int> = emptyList(),
    val paceNotified: Boolean = false,
)

data class Alert(val key: String, val title: String, val text: String)

fun labelFor(key: String): String = when {
    key == "five_hour" -> "Sessione 5 ore"
    key == "seven_day" -> "Settimana (tutti i modelli)"
    key.startsWith("seven_day_") -> "Settimana · " + key.removePrefix("seven_day_")
        .replace('_', ' ').trim().split(' ')
        .joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
    else -> key.replace('_', ' ')
}

fun parseInstant(value: String?): Instant? {
    if (value.isNullOrBlank()) return null
    return try {
        OffsetDateTime.parse(value).toInstant()
    } catch (e: DateTimeParseException) {
        try { Instant.parse(value) } catch (e2: DateTimeParseException) { null }
    }
}

private fun JsonElement?.asObjectOrNull(): JsonObject? = this as? JsonObject
private fun JsonElement?.asPrimitiveOrNull(): JsonPrimitive? = this as? JsonPrimitive

/** Risposta JSON di /usage -> finestre ordinate (5 ore, settimana, altre settimanali). */
fun parseUsage(json: String): List<UsageWindow> {
    val root = Json.parseToJsonElement(json).asObjectOrNull() ?: return emptyList()
    val out = mutableListOf<UsageWindow>()
    for ((key, value) in root) {
        if (key != "five_hour" && !key.startsWith("seven_day")) continue
        val obj = value.asObjectOrNull() ?: continue
        val prim = obj["utilization"].asPrimitiveOrNull() ?: continue
        if (prim.isString) continue
        val util = prim.doubleOrNull ?: continue
        out += UsageWindow(
            key = key,
            label = labelFor(key),
            utilization = util.coerceAtLeast(0.0),
            resetsAt = parseInstant(obj["resets_at"].asPrimitiveOrNull()?.contentOrNull),
            length = if (key == "five_hour") FIVE_HOURS else SEVEN_DAYS,
        )
    }
    return out.sortedWith(compareBy({ if (it.key == "five_hour") 0 else if (it.key == "seven_day") 1 else 2 }, { it.key }))
}

/** Sceglie l'organizzazione con l'abbonamento Claude tra quelle restituite da /api/organizations. */
fun pickOrgId(json: String): String? {
    val arr = Json.parseToJsonElement(json) as? JsonArray ?: return null
    val orgs = arr.mapNotNull { it.asObjectOrNull() }
        .filter { it["uuid"].asPrimitiveOrNull()?.contentOrNull != null }
    fun caps(o: JsonObject): List<String> = (o["capabilities"] as? JsonArray)
        ?.mapNotNull { it.asPrimitiveOrNull()?.contentOrNull } ?: emptyList()
    val chosen = orgs.firstOrNull { o -> caps(o).any { it.startsWith("claude_") } }
        ?: orgs.firstOrNull { o -> "chat" in caps(o) }
        ?: orgs.firstOrNull()
    return chosen?.get("uuid")?.jsonPrimitive?.contentOrNull
}

fun expectedUtilization(w: UsageWindow, now: Instant): Double? {
    val reset = w.resetsAt ?: return null
    val remaining = Duration.between(now, reset).toMillis().toDouble()
    val elapsed = (1.0 - remaining / w.length.toMillis()).coerceIn(0.0, 1.0)
    return elapsed * 100.0
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
    val local = resetsAt.atZone(zone)
    val whenText = "%s %02d:%02d".format(DAYS_IT[local.dayOfWeek.value - 1], local.hour, local.minute)
    return if (hours >= 24) "reset $whenText (tra ${hours / 24} g ${hours % 24} h)"
    else "reset $whenText (tra $hours h $minutes min)"
}

private fun sameWindow(oldIso: String?, new: Instant?): Boolean {
    val old = parseInstant(oldIso)
    if (old == null || new == null) return old == null && new == null
    return abs(Duration.between(old, new).toMillis()) <= NEW_WINDOW_TOLERANCE.toMillis()
}

private fun pct(v: Double) = v.roundToInt().toString()

/** Decide le notifiche. Ogni soglia scatta una volta sola per finestra. */
fun evaluate(
    windows: List<UsageWindow>,
    settings: AlertSettings,
    state: Map<String, WindowState>,
    now: Instant,
): Pair<List<Alert>, Map<String, WindowState>> {
    val alerts = mutableListOf<Alert>()
    val newState = mutableMapOf<String, WindowState>()

    for (w in windows) {
        val old = state[w.key]
        var ws = if (old == null || !sameWindow(old.resetsAt, w.resetsAt)) {
            WindowState(resetsAt = w.resetsAt?.toString())
        } else old

        val thresholds = if (w.key == "five_hour") settings.thresholds5h else settings.thresholdsWeek
        val crossed = thresholds.filter { w.utilization >= it && it !in ws.notified }.sorted()
        if (crossed.isNotEmpty()) {
            alerts += Alert(
                key = w.key,
                title = "${w.label}: ${pct(w.utilization)}% (soglia ${crossed.last()}%)",
                text = formatReset(w.resetsAt, now),
            )
            ws = ws.copy(notified = (ws.notified + crossed).toSortedSet().toList())
        }

        if (w.isWeekly && settings.paceEnabled) {
            val exp = expectedUtilization(w, now)
            if (exp != null) {
                val ahead = w.utilization - exp
                if (ahead > settings.paceMargin && w.utilization >= PACE_MIN_UTILIZATION && !ws.paceNotified) {
                    alerts += Alert(
                        key = w.key + "_pace",
                        title = "${w.label}: stai andando troppo veloce",
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

// --- Serializzazione dello stato (SharedPreferences) ---

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
    val root = try { Json.parseToJsonElement(json).jsonObject } catch (e: Exception) { return emptyMap() }
    return root.mapNotNull { (k, v) ->
        val o = v.asObjectOrNull() ?: return@mapNotNull null
        k to WindowState(
            resetsAt = o["resets_at"].asPrimitiveOrNull()?.contentOrNull,
            notified = (o["notified"] as? JsonArray)?.mapNotNull { it.asPrimitiveOrNull()?.intOrNull } ?: emptyList(),
            paceNotified = o["pace_notified"].asPrimitiveOrNull()?.booleanOrNull ?: false,
        )
    }.toMap()
}
