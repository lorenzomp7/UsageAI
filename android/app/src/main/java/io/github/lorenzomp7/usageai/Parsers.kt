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
        val length = reset?.let { monthLengthEndingAt(it) } ?: Duration.ofDays(30)
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

    // ---------------- Perplexity ----------------

    fun perplexity(json: String, now: Instant = Instant.now()): List<UsageWindow> {
        val d = parseJson(json).obj() ?: throw ApiException("Risposta di Perplexity cambiata.")
        fun f(snake: String, camel: String) = d[snake] ?: d[camel]
        val grants = f("credit_grants", "creditGrants") as? JsonArray
        val used = f("total_usage_cents", "totalUsageCents").num()
        if (grants == null || used == null) throw ApiException("Risposta di Perplexity cambiata.")
        fun total(kind: String, onlyValid: Boolean = false): Double = grants.mapNotNull { it.obj() }
            .filter { it["type"].str() == kind }
            .filter { g ->
                val exp = (g["expires_at_ts"] ?: g["expiresAtTs"]).num()
                !onlyValid || exp == null || exp > now.epochSecond
            }
            .sumOf { (it["amount_cents"] ?: it["amountCents"]).num() ?: 0.0 }
            .coerceAtLeast(0.0)
        val recurring = total("recurring")
        val promo = total("promotional", onlyValid = true)
        val purchased = maxOf(total("purchased"), f("current_period_purchased_cents", "currentPeriodPurchasedCents").num() ?: 0.0)
        val recUsed = minOf(used, recurring)
        var left = used - recUsed
        val purUsed = minOf(left, purchased)
        left -= purUsed
        val promoUsed = minOf(left, promo)
        val renewal = fromEpoch(f("renewal_date_ts", "renewalDateTs"))
        val out = mutableListOf<UsageWindow>()
        if (recurring > 0) out += UsageWindow("perplexity", "perplexity:recurring", "Crediti del piano",
            (recUsed / recurring * 100).coerceAtMost(100.0), renewal, null)
        if (purchased > 0) out += UsageWindow("perplexity", "perplexity:purchased", "Crediti acquistati",
            (purUsed / purchased * 100).coerceAtMost(100.0), null, null)
        if (promo > 0) out += UsageWindow("perplexity", "perplexity:promo", "Crediti bonus",
            (promoUsed / promo * 100).coerceAtMost(100.0), null, null)
        if (out.isEmpty()) throw ApiException("Perplexity non riporta crediti per questo account.")
        return out
    }

    // ---------------- Grok ----------------

    fun grok(json: String): List<UsageWindow> {
        val config = parseJson(json).obj()?.get("config").obj() ?: throw ApiException("Risposta di Grok cambiata.")
        fun amount(e: JsonElement?): Double? = e.obj()?.get("val").num() ?: e.num()
        var percent = config["creditUsagePercent"].num()
        if (percent == null) {
            val cap = amount(config["onDemandCap"]); val used = amount(config["onDemandUsed"])
            if (cap != null && cap > 0 && used != null) percent = used / cap * 100
        }
        val period = config["currentPeriod"].obj()
        var end = parseInstant(period?.get("end").str())
        val start = if (end != null) parseInstant(period?.get("start").str()) else parseInstant(config["billingPeriodStart"].str())
        if (end == null) end = parseInstant(config["billingPeriodEnd"].str())
        val length = if (start != null && end != null && end.isAfter(start)) Duration.between(start, end) else null
        if (percent == null) throw ApiException("Grok non riporta la percentuale di consumo per questo periodo.")
        return listOf(UsageWindow("grok", "grok:credits", if (length != null) "Crediti · ${windowLabel(length)}" else "Crediti",
            percent.coerceIn(0.0, 100.0), end, length))
    }

    // ---------------- Mistral ----------------

    /** Indice subito dopo la fine dell'oggetto/array JSON che comincia in [start], o null. */
    private fun jsonEnd(text: String, start: Int): Int? {
        var depth = 0
        var inString = false
        var escaped = false
        var i = start
        while (i < text.length) {
            val c = text[i]
            if (inString) {
                if (escaped) escaped = false
                else if (c == '\\') escaped = true
                else if (c == '"') inString = false
            } else when (c) {
                '"' -> inString = true
                '[', '{' -> depth++
                ']', '}' -> { depth--; if (depth == 0) return i + 1 }
            }
            i++
        }
        return null
    }

    private fun flightStream(html: String): String {
        val sb = StringBuilder()
        val marker = "self.__next_f.push("
        var from = 0
        while (true) {
            val m = html.indexOf(marker, from)
            if (m < 0) break
            var start = m + marker.length
            while (start < html.length && html[start].isWhitespace()) start++
            from = start
            if (start >= html.length || html[start] != '[') continue
            val end = jsonEnd(html, start) ?: continue
            val arr = runCatching { Json.parseToJsonElement(html.substring(start, end)) as? JsonArray }.getOrNull()
            if (arr != null && arr.size >= 2 && arr[0].num() == 1.0) arr[1].str()?.let { sb.append(it) }
            from = end
        }
        return sb.toString()
    }

    private fun findBudget(text: String, key: String): JsonObject? {
        val re = Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*\\{")
        for (m in re.findAll(text)) {
            val start = m.range.last
            val end = jsonEnd(text, start) ?: continue
            val o = runCatching { Json.parseToJsonElement(text.substring(start, end)).obj() }.getOrNull() ?: continue
            if (o["usage_percentage"].num() != null) return o
        }
        return null
    }

    fun mistralHtml(html: String): List<UsageWindow> {
        val out = mutableListOf<UsageWindow>()
        for (source in listOf(flightStream(html), html)) {
            for ((key, label) in listOf("api_budget" to "Budget API incluso", "vibe_budget" to "Vibe (piano mensile)")) {
                if (out.any { it.key == "mistral:$key" }) continue
                val b = findBudget(source, key) ?: continue
                val pct = b["usage_percentage"].num() ?: continue
                val limit = b["initial_budget"].num()
                if (limit != null && limit <= 0) continue
                val reset = parseInstant(b["reset_at"].str())
                val currency = (b["currency"].str() ?: "").uppercase()
                val text = if (limit != null && limit > 0) "$label (${"%.0f".format(limit)} $currency)".replace(" )", ")") else label
                out += UsageWindow("mistral", "mistral:$key", text, pct.coerceIn(0.0, 100.0), reset, reset?.let { monthLengthEndingAt(it) })
            }
            if (out.isNotEmpty()) break
        }
        if (out.isEmpty()) {
            val low = html.lowercase()
            if ("auth.mistral.ai" in low && ("login" in low || "sign in" in low))
                throw AuthException("Sessione di Mistral scaduta: rifai l'accesso.")
            throw ApiException("La pagina abbonamento di Mistral non mostra budget inclusi (o è cambiata).")
        }
        return out
    }

    // ---------------- OpenRouter ----------------

    /** OpenRouter azzera i limiti a mezzanotte UTC: ogni giorno, ogni lunedì o il primo del mese. */
    fun openRouterNextReset(kind: String?, now: Instant): Pair<Instant?, Duration?> {
        val midnight = now.atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay(ZoneOffset.UTC)
        return when (kind) {
            "daily" -> midnight.plusDays(1).toInstant() to Duration.ofDays(1)
            "weekly" -> midnight.plusDays((7 - (midnight.dayOfWeek.value - 1)).toLong()).toInstant() to Duration.ofDays(7)
            "monthly" -> {
                val next = midnight.withDayOfMonth(1).plusMonths(1).toInstant()
                next to monthLengthEndingAt(next)
            }
            else -> null to null
        }
    }

    fun openRouterKey(json: String, now: Instant = Instant.now()): List<UsageWindow> {
        val d = parseJson(json).obj()?.get("data").obj() ?: throw ApiException("Risposta di OpenRouter cambiata.")
        val limit = d["limit"].num()
        if (limit == null || limit <= 0) return emptyList()
        val kind = d["limit_reset"].str()
        val remaining = d["limit_remaining"].num()
        val used = if (remaining != null) limit - remaining else
            d[when (kind) { "daily" -> "usage_daily"; "weekly" -> "usage_weekly"; "monthly" -> "usage_monthly"; else -> "usage" }].num() ?: 0.0
        val (reset, length) = openRouterNextReset(kind, now)
        return listOf(UsageWindow("openrouter", "openrouter:key", "Limite della chiave (${"%.0f".format(limit)} $)",
            (used / limit * 100).coerceIn(0.0, 100.0), reset, length))
    }

    fun openRouterCredits(json: String): List<UsageWindow> {
        val d = runCatching { parseJson(json).obj()?.get("data").obj() }.getOrNull() ?: return emptyList()
        val total = d["total_credits"].num(); val used = d["total_usage"].num()
        if (total == null || total <= 0 || used == null) return emptyList()
        return listOf(UsageWindow("openrouter", "openrouter:credits",
            "Credito prepagato (restano ${"%.2f".format(java.util.Locale.US, total - used)} $)",
            (used / total * 100).coerceIn(0.0, 100.0), null, null))
    }
}
