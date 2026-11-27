package io.github.lorenzomp7.usageai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Lettura delle risposte di ogni servizio (logica pura, testata in UsageLogicTest).
 * Corrisponde a windows/usageai/providers (un file .py per servizio).
 */
object Parsers {

    private fun parseJson(text: String): JsonElement {
        val t = text.trim()
        if (t.startsWith("<")) throw ApiException(
            "Il servizio ha risposto con una pagina web invece che con i dati " +
                "(probabile blocco anti-bot o accesso scaduto).")
        return try { Json.parseToJsonElement(t) } catch (e: Exception) {
            throw ApiException("Risposta non leggibile: il servizio potrebbe aver cambiato formato.")
        }
    }

    private fun JsonElement?.obj(): JsonObject? = this as? JsonObject
    private fun JsonElement?.num(): Double? {
        val p = this as? JsonPrimitive ?: return null
        if (p is JsonNull) return null
        if (p.booleanOrNull != null && !p.isString) return null
        return p.doubleOrNull
    }
    private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

    private fun fromEpoch(v: JsonElement?): Instant? {
        var n = v.num() ?: return null
        if (n <= 0) return null
        if (n > 1e12) n /= 1000
        return Instant.ofEpochMilli((n * 1000).toLong())
    }

    // ---------------- Claude ----------------

    fun claude(json: String): List<UsageWindow> {
        val root = parseJson(json).obj() ?: return emptyList()
        val out = mutableListOf<UsageWindow>()
        for ((key, value) in root) {
            if (key != "five_hour" && !key.startsWith("seven_day")) continue
            val o = value.obj() ?: continue
            val prim = o["utilization"] as? JsonPrimitive ?: continue
            if (prim.isString) continue
            val util = prim.doubleOrNull ?: continue
            val (label, length) = when {
                key == "five_hour" -> "Sessione 5 ore" to Duration.ofHours(5)
                key == "seven_day" -> "Settimana" to Duration.ofDays(7)
                else -> "Settimana " + key.removePrefix("seven_day_").replace('_', ' ').trim()
                    .split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } } to Duration.ofDays(7)
            }
            out += UsageWindow("claude", "claude:$key", label, util.coerceAtLeast(0.0),
                parseInstant(o["resets_at"].str()), length)
        }
        val order = mapOf("claude:five_hour" to 0, "claude:seven_day" to 1)
        return out.sortedWith(compareBy({ order[it.key] ?: 2 }, { it.key }))
    }

    fun claudeOrgId(json: String): String? {
        val arr = parseJson(json) as? JsonArray ?: return null
        val orgs = arr.mapNotNull { it.obj() }.filter { it["uuid"].str() != null }
        fun caps(o: JsonObject) = (o["capabilities"] as? JsonArray)?.mapNotNull { it.str() } ?: emptyList()
        val chosen = orgs.firstOrNull { o -> caps(o).any { it.startsWith("claude_") } }
            ?: orgs.firstOrNull { o -> "chat" in caps(o) }
            ?: orgs.firstOrNull()
        return chosen?.get("uuid").str()
    }

    // ---------------- ChatGPT / Codex ----------------

    fun codexAccessToken(json: String): String? = parseJson(json).obj()?.get("accessToken").str()

    fun codex(json: String): List<UsageWindow> {
        val rl = parseJson(json).obj()?.get("rate_limit").obj() ?: return emptyList()
        val out = mutableListOf<UsageWindow>()
        for (slot in listOf("primary_window", "secondary_window")) {
            val w = rl[slot].obj() ?: continue
            val used = w["used_percent"].num() ?: continue
            val secs = w["limit_window_seconds"].num()
            val length = if (secs != null && secs > 0) Duration.ofSeconds(secs.toLong())
            else if (slot == "primary_window") Duration.ofHours(5) else Duration.ofDays(7)
            out += UsageWindow("codex", "codex:$slot", windowLabel(length), used.coerceAtLeast(0.0),
                fromEpoch(w["reset_at"]), length)
        }
        return out
    }

    // ---------------- Gemini ----------------

    fun gemini(text: String): List<UsageWindow> {
        var t = text.trim()
        if (t.startsWith(")]}'")) t = t.substring(4).trim()
        if (t.startsWith("<")) throw AuthException("Accesso a Gemini scaduto: copia di nuovo i cookie.")
        val parsed = try { Json.parseToJsonElement(t) } catch (e: Exception) {
            throw ApiException("Risposta di Gemini non leggibile: Google potrebbe averla cambiata.")
        }
        val results = when (parsed) {
            is JsonArray -> parsed.toList()
            is JsonObject -> listOf(parsed)
            else -> throw ApiException("Risposta di Gemini cambiata.")
        }
        val data = results.mapNotNull { it.obj()?.get("data").obj() }.firstOrNull { "viewer" in it }
            ?: throw ApiException("Risposta di Gemini cambiata.")
        val viewer = data["viewer"]
        if (viewer == null || viewer is JsonNull) throw AuthException("Accesso a Gemini scaduto: copia di nuovo i cookie.")
        val pools = viewer.obj()?.get("rpcs").obj()?.get("getUsageInfo").obj()?.get("pools") as? JsonArray
            ?: throw ApiException("Risposta di Gemini cambiata.")
        val out = mutableListOf<UsageWindow>()
        for ((type, spec) in listOf(
            "GXU_FIVE_HOURLY" to ("Sessione 5 ore" to Duration.ofHours(5)),
            "GXU_WEEKLY" to ("Settimana" to Duration.ofDays(7)),
        )) {
            val pool = pools.mapNotNull { it.obj() }.firstOrNull { it["poolType"].str() == type } ?: continue
            val frac = pool["percentageUsed"].num() ?: continue
            if (frac < 0) continue
            val reset = parseInstant(pool["gxu"].obj()?.get("windowRefreshesAt").str())
            out += UsageWindow("gemini", "gemini:${type.lowercase()}", spec.first,
                (frac * 100).coerceAtMost(100.0), reset, spec.second)
        }
        if (out.isEmpty()) throw ApiException("Questo account Google non riporta limiti dell'app Gemini.")
        return out
    }

    // ---------------- Cursor ----------------

    fun cursor(json: String): List<UsageWindow> {
        val root = parseJson(json).obj() ?: throw ApiException("Risposta di Cursor cambiata.")
        val start = parseInstant(root["billingCycleStart"].str())
        val end = parseInstant(root["billingCycleEnd"].str())
        val length = if (start != null && end != null && end.isAfter(start)) Duration.between(start, end) else null
        val usage = root["individualUsage"].obj()
        val plan = usage?.get("plan").obj()
        val out = mutableListOf<UsageWindow>()
        fun add(key: String, label: String, value: Double?) {
            if (value != null && value >= 0) out += UsageWindow("cursor", "cursor:$key", label,
                value.coerceAtMost(100.0), end, length)
        }
        var total = plan?.get("totalPercentUsed").num()
        if (total == null) {
            val used = plan?.get("used").num(); val limit = plan?.get("limit").num()
            if (used != null && limit != null && limit > 0) total = used / limit * 100
        }
        if (total == null) {
            val overall = usage?.get("overall").obj()
            val used = overall?.get("used").num(); val limit = overall?.get("limit").num()
            if (used != null && limit != null && limit > 0) total = used / limit * 100
        }
        add("plan", "Uso incluso nel mese", total)
        add("api", "Pool modelli API", plan?.get("apiPercentUsed").num())
        add("auto", "Pool Auto/Composer", plan?.get("autoPercentUsed").num())
        val od = usage?.get("onDemand").obj()
        val odUsed = od?.get("used").num(); val odLimit = od?.get("limit").num()
        if (odUsed != null && odLimit != null && odLimit > 0) {
            add("ondemand", "Spesa extra (limite ${"%.0f".format(odLimit / 100)} $)", odUsed / odLimit * 100)
        }
        if (out.isEmpty()) {
            if ((root["isUnlimited"] as? JsonPrimitive)?.booleanOrNull == true)
                throw ApiException("Il piano Cursor risulta illimitato: niente da controllare.")
            throw ApiException("Cursor non riporta l'uso incluso per questo account.")
        }
        return out
    }

    // ---------------- Copilot ----------------

    fun copilot(json: String): List<UsageWindow> {
        val root = parseJson(json).obj() ?: throw ApiException("Risposta di Copilot cambiata.")
        val snaps = (root["quota_snapshots"] ?: root["quotaSnapshots"]).obj() ?: JsonObject(emptyMap())
        val resetRaw = (root["quota_reset_date"] ?: root["quotaResetDate"]).str()
        val reset = parseInstant(resetRaw) ?: resetRaw?.trim()?.take(10)?.let {
            runCatching { LocalDate.parse(it).atStartOfDay().toInstant(ZoneOffset.UTC) }.getOrNull()
        }
        val length = reset?.let {
            val end = it.atZone(ZoneOffset.UTC)
            Duration.between(end.minusMonths(1), end)
        } ?: Duration.ofDays(30)
        val out = mutableListOf<UsageWindow>()
        for ((key, label) in listOf("premium_interactions" to "Richieste premium", "chat" to "Chat")) {
            val s = snaps[key].obj() ?: continue
            if ((s["unlimited"] as? JsonPrimitive)?.booleanOrNull == true) continue
            var remaining = s["percent_remaining"].num()
            if (remaining == null) {
                val ent = s["entitlement"].num(); val rem = s["remaining"].num()
                if (ent == null || ent <= 0 || rem == null) continue
                remaining = rem / ent * 100
            }
            out += UsageWindow("copilot", "copilot:$key", label, (100 - remaining).coerceAtLeast(0.0), reset, length)
        }
        if (out.isEmpty()) throw ApiException("Copilot non riporta limiti per questo account (piano illimitato o senza Copilot).")
        return out
    }
}
