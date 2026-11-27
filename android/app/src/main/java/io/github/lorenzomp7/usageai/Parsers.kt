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

    /**
     * Claude. «limits» (session, weekly_all, weekly_scoped con il modello, es. Fable) come fonte principale e
     * chiavi piatte per ciò che manca: dal parser e dai test di Usage Limits Mobile (MIT). «extra_usage» e
     * «cedar_ember» (reset dei limiti): dalla documentazione di CodexBar (MIT). «pace»: formato non
     * documentato, letto in modo prudente. Come windows/usageai/providers/claude.py.
     */
    fun claude(json: String, now: Instant = Instant.now()): List<UsageWindow> {
        val root = parseJson(json).obj() ?: return emptyList()
        val five = Duration.ofHours(5); val week = Duration.ofDays(7)
        fun slug(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifEmpty { "modello" }
        fun title(s: String) = s.replace('_', ' ').trim().split(' ').filter { it.isNotEmpty() }
            .joinToString(" ") { w -> w.lowercase().replaceFirstChar { it.uppercase() } }
        fun pct(e: JsonElement?): Double? {
            val p = e as? JsonPrimitive ?: return null
            if (p is JsonNull || (!p.isString && p.booleanOrNull != null)) return null
            return p.doubleOrNull
        }
        fun reset(o: JsonObject) = parseInstant((o["resets_at"] ?: o["resetsAt"]).str())
        val found = linkedMapOf<String, UsageWindow>()

        (root["limits"] as? JsonArray)?.mapNotNull { it.obj() }?.forEach { item ->
            val p = pct(item["percent"]) ?: return@forEach
            val kind = item["kind"].str() ?: ""
            val group = item["group"].str() ?: ""
            val model = item["scope"].obj()?.get("model").obj()?.get("display_name").str()?.trim()
            val (key, label, length) = when {
                kind == "session" -> Triple("claude:five_hour", "Sessione 5 ore", five)
                kind == "weekly_all" -> Triple("claude:seven_day", "Settimana", week)
                kind == "weekly_scoped" && !model.isNullOrEmpty() -> Triple("claude:seven_day_${slug(model)}", "Settimana $model", week)
                kind.isNotEmpty() -> Triple("claude:${slug(kind)}", title(kind),
                    if (group == "weekly") week else if (group == "session") five else null)
                else -> return@forEach
            }
            if (key !in found) found[key] = UsageWindow("claude", key, label, p.coerceAtLeast(0.0), reset(item), length)
        }
        for ((key, value) in root) {
            if (key != "five_hour" && !key.startsWith("seven_day") && key != "iguana_necktie") continue
            val o = value.obj() ?: continue
            val prim = o["utilization"] as? JsonPrimitive ?: continue
            if (prim.isString || prim is JsonNull) continue
            val util = prim.doubleOrNull ?: continue
            val w = when (key) {
                "five_hour" -> UsageWindow("claude", "claude:five_hour", "Sessione 5 ore", util.coerceAtLeast(0.0), reset(o), five)
                "seven_day" -> UsageWindow("claude", "claude:seven_day", "Settimana", util.coerceAtLeast(0.0), reset(o), week)
                "iguana_necktie" -> UsageWindow("claude", "claude:seven_day_fable", "Settimana Fable", util.coerceAtLeast(0.0), reset(o), week)
                else -> UsageWindow("claude", "claude:$key", "Settimana " + title(key.removePrefix("seven_day_")),
                    util.coerceAtLeast(0.0), reset(o), week)
            }
            if (w.key !in found) found[w.key] = w
        }
        val order = mapOf("claude:five_hour" to 0, "claude:seven_day" to 1)
        val out = found.values.sortedWith(compareBy({ order[it.key] ?: 2 }, { it.key })).toMutableList()
        if (out.isEmpty()) return out

        root["extra_usage"].obj()?.let { ex ->
            if ((ex["is_enabled"] as? JsonPrimitive)?.booleanOrNull != true) return@let
            val limit = (ex["monthly_limit"]?.takeIf { it !is JsonNull } ?: ex["monthly_credit_limit"]).num()
            val used = ex["used_credits"].num()
            val p = if (used != null && limit != null && limit > 0) used / limit * 100 else pct(ex["utilization"])
            if (p == null) return@let
            val cur = (ex["currency"].str() ?: "USD").uppercase()
            var label = "Spesa extra del mese"
            if (used != null && limit != null && limit > 0)
                label += " (${"%.2f".format(java.util.Locale.US, used / 100)} su ${"%.2f".format(java.util.Locale.US, limit / 100)} $cur)".replace('.', ',')
            // Claude non dice quando si azzera: come CodexBar, il primo del mese successivo (UTC).
            val first = now.atZone(ZoneOffset.UTC).toLocalDate().withDayOfMonth(1).atStartOfDay(ZoneOffset.UTC)
            val next = first.plusMonths(1)
            out += UsageWindow("claude", "claude:extra_usage", label, p.coerceAtLeast(0.0), next.toInstant(),
                Duration.between(first, next))
        }

        val notes = mutableListOf<UsageWindow>()
        for ((key, value) in root) {
            val o = value.obj() ?: continue
            val pace = o["pace"] ?: continue
            val target = out.firstOrNull { it.key == "claude:$key" || (key == "iguana_necktie" && it.key == "claude:seven_day_fable") } ?: continue
            claudePaceText(pace)?.let { notes += UsageWindow("claude", "${target.key}:pace", "Previsione di Claude (${target.label})", 0.0, null, null, note = it) }
        }
        claudeResets(root["cedar_ember"], now)?.let { notes += it }
        return out + notes
    }

    private val PACE_PERCENT = listOf("projected_utilization", "projected_percent", "projected", "utilization_at_reset",
        "percent_at_reset", "estimated_utilization")
    private val PACE_TIME = listOf("runs_out_at", "exhausted_at", "exhausts_at", "depletes_at", "projected_exhaustion_at")

    private fun claudePaceText(pace: JsonElement): String? {
        val o = pace.obj()
        if (o != null) {
            for (k in PACE_TIME) parseInstant(o[k].str())?.let { t ->
                val z = t.atZone(java.time.ZoneId.systemDefault())
                return "finisce verso %02d/%02d %02d:%02d".format(z.dayOfMonth, z.monthValue, z.hour, z.minute)
            }
            for (k in PACE_PERCENT) {
                val p = o[k] as? JsonPrimitive ?: continue
                if (p is JsonNull || (!p.isString && p.booleanOrNull != null)) continue
                p.doubleOrNull?.let { return "circa ${"%.0f".format(it)}% al reset" }
            }
            return (o["message"] ?: o["text"]).str()?.trim()?.takeIf { it.isNotEmpty() }
        }
        val s = pace.str()?.trim() ?: return null
        return s.takeIf { it.isNotEmpty() && (' ' in it) }
    }

    /** Reset dei limiti disponibili dal blocco cedar_ember (regole di CodexBar). Solo lettura. */
    private fun claudeResets(block: JsonElement?, now: Instant): UsageWindow? {
        val b = block.obj() ?: return null
        if ((b["eligible"] as? JsonPrimitive)?.let { !it.isString && it.booleanOrNull == true } != true) return null
        val grants = b["grants"] as? JsonArray ?: return null
        if (grants.size > 200) return null
        var count = 0L
        var firstEnd: Instant? = null
        for (g in grants.mapNotNull { it.obj() }) {
            val leftP = g["resets_left"] as? JsonPrimitive ?: continue
            if (leftP.isString || leftP is JsonNull) continue
            val left = leftP.contentOrNull?.toLongOrNull() ?: continue
            val paused = g["paused"] as? JsonPrimitive
            if (left < 0 || paused == null || paused.isString || paused.booleanOrNull != false) continue
            val startsText = g["starts_at"].str(); val endsText = g["ends_at"].str()
            val starts = if (startsText != null) parseInstant(startsText) ?: continue else null
            val ends = if (endsText != null) parseInstant(endsText) ?: continue else null
            if ((starts != null && starts.isAfter(now)) || (ends != null && !ends.isAfter(now)) || left == 0L) continue
            count += left
            if (ends != null && (firstEnd == null || ends.isBefore(firstEnd))) firstEnd = ends
        }
        if (count <= 0 || count > 50) return null
        val tail = firstEnd?.atZone(java.time.ZoneId.systemDefault())?.let { " (il primo scade il %02d/%02d)".format(it.dayOfMonth, it.monthValue) } ?: ""
        return UsageWindow("claude", "claude:resets", "Reset dei limiti disponibili", 0.0, null, null, note = "$count$tail")
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

    /**
     * Risposta di wham/usage. Formato dal codice di Codex CLI (github.com/openai/codex, Apache-2.0:
     * RateLimitStatusPayload e test app-server/tests/suite/v2/rate_limits.rs): rate_limit,
     * additional_rate_limits, spend_control, credits, rate_limit_reached_type, rate_limit_reset_credits.
     */
    fun codex(json: String): List<UsageWindow> {
        val root = parseJson(json).obj() ?: return emptyList()
        val out = mutableListOf<UsageWindow>()
        var reached = false
        root["rate_limit"].obj()?.let { rl ->
            out += codexWindows(rl, "codex:", null)
            reached = (rl["limit_reached"] as? JsonPrimitive)?.booleanOrNull == true
        }
        (root["additional_rate_limits"] as? JsonArray)?.mapNotNull { it.obj() }?.forEach { extra ->
            val rl = extra["rate_limit"].obj() ?: return@forEach
            val name = extra["limit_name"].str() ?: extra["metered_feature"].str() ?: "aggiuntivo"
            out += codexWindows(rl, "codex:extra:$name:", limitTitle(name))
            if ((rl["limit_reached"] as? JsonPrimitive)?.booleanOrNull == true) reached = true
        }
        val spend = root["spend_control"].obj()
        val lim = spend?.get("individual_limit").obj()
        val spendPct = lim?.get("used_percent").num()
        if (lim != null && spendPct != null) {
            out += UsageWindow("codex", "codex:spend",
                "Spesa del workspace (${amount(lim["used"])} su ${amount(lim["limit"])})",
                spendPct.coerceAtLeast(0.0), fromEpoch(lim["reset_at"]), null)
            if ((spend?.get("reached") as? JsonPrimitive)?.booleanOrNull == true) reached = true
        }
        if (out.isEmpty()) return out
        root["credits"].obj()?.let { c ->
            creditsNote(c["has_credits"], c["unlimited"], c["balance"])?.let { out += it }
        }
        root["rate_limit_reset_credits"].obj()?.let { rr -> resetsNote(rr["available_count"].num(), null)?.let { out += it } }
        if (reached) root["rate_limit_reached_type"].obj()?.get("type").str()?.let { out += reachedNote(it) }
        return out
    }

    val CODEX_REACHED_REASONS = mapOf(
        "rate_limit_reached" to "limite d'uso raggiunto",
        "workspace_owner_credits_depleted" to "crediti del workspace esauriti",
        "workspace_member_credits_depleted" to "crediti del workspace esauriti (chiedi al proprietario)",
        "workspace_owner_usage_limit_reached" to "limite di spesa del workspace raggiunto",
        "workspace_member_usage_limit_reached" to "il tuo limite di spesa nel workspace è raggiunto",
    )

    private fun limitTitle(name: String): String {
        val t = name.replace('_', ' ').replace('-', ' ').trim()
        return if (t.isEmpty()) "Limite aggiuntivo" else t.replaceFirstChar { it.uppercase() }
    }

    private fun amount(v: JsonElement?): String {
        val n = v.num() ?: return v.str() ?: "?"
        val s = if (n == Math.floor(n)) "%.0f".format(java.util.Locale.US, n) else "%.2f".format(java.util.Locale.US, n).trimEnd('0')
        return s.replace('.', ',')
    }

    private fun codexWindows(rl: JsonObject, prefix: String, title: String?): List<UsageWindow> {
        val out = mutableListOf<UsageWindow>()
        for (slot in listOf("primary_window", "secondary_window")) {
            val w = rl[slot].obj() ?: continue
            val used = w["used_percent"].num() ?: continue
            val secs = w["limit_window_seconds"].num()
            val length = if (secs != null && secs > 0) Duration.ofSeconds(secs.toLong())
            else if (slot == "primary_window") Duration.ofHours(5) else Duration.ofDays(7)
            val key = if (title == null) "$prefix$slot" else prefix + slot.substringBefore('_')
            val label = if (title == null) windowLabel(length) else "$title · ${windowLabel(length)}"
            out += UsageWindow("codex", key, label, used.coerceAtLeast(0.0), fromEpoch(w["reset_at"]), length)
        }
        return out
    }

    private fun creditsNote(has: JsonElement?, unlimited: JsonElement?, balance: JsonElement?): UsageWindow? {
        if ((unlimited as? JsonPrimitive)?.booleanOrNull == true)
            return UsageWindow("codex", "codex:credits", "Crediti ChatGPT", 0.0, null, null, note = "illimitati")
        val b = balance.str()?.takeIf { it.isNotBlank() } ?: return null
        if ((has as? JsonPrimitive)?.booleanOrNull != true) return null
        return UsageWindow("codex", "codex:credits", "Crediti ChatGPT", 0.0, null, null,
            note = "${amount(JsonPrimitive(b))} rimasti")
    }

    private fun resetsNote(count: Double?, firstExpiry: Instant?): UsageWindow? {
        if (count == null || count <= 0) return null
        val tail = firstExpiry?.atZone(java.time.ZoneId.systemDefault())
            ?.let { " (il primo scade il %02d/%02d)".format(it.dayOfMonth, it.monthValue) } ?: ""
        return UsageWindow("codex", "codex:resets", "Reset dei limiti disponibili", 0.0, null, null,
            note = "${count.toLong()}$tail")
    }

    private fun reachedNote(reason: String) = UsageWindow("codex", "codex:reached", "Limite raggiunto", 100.0,
        null, null, note = CODEX_REACHED_REASONS[reason] ?: reason.replace('_', ' '))

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

    /**
     * Copilot: crediti AI (dal 1/6/2026) o richieste premium. Campi da CodexBar (MIT): token_based_billing,
     * credits_used, quote segnaposto a zero, monthly_quotas / limited_user_quotas come riserva.
     */
    fun copilot(json: String): List<UsageWindow> {
        val root = parseJson(json).obj() ?: throw ApiException("Risposta di Copilot cambiata.")
        val snaps = (root["quota_snapshots"] ?: root["quotaSnapshots"]).obj() ?: JsonObject(emptyMap())
        val resetRaw = (root["quota_reset_date"] ?: root["quotaResetDate"]).str()
        val reset = parseInstant(resetRaw) ?: resetRaw?.trim()?.take(10)?.let {
            runCatching { LocalDate.parse(it).atStartOfDay().toInstant(ZoneOffset.UTC) }.getOrNull()
        }
        val length = reset?.let { monthLengthEndingAt(it) } ?: Duration.ofDays(30)
        val credits = (root["token_based_billing"] as? JsonPrimitive)?.booleanOrNull == true
        fun fmt(n: Double) = if (n == Math.floor(n)) "%.0f".format(n) else "%.1f".format(java.util.Locale.US, n).replace('.', ',')
        val out = mutableListOf<UsageWindow>()
        val notes = mutableListOf<UsageWindow>()
        for ((key, label) in listOf("premium_interactions" to (if (credits) "Crediti AI del piano" else "Richieste premium"), "chat" to "Chat")) {
            val s = snaps[key].obj() ?: continue
            val ent = s["entitlement"].num(); val rem = s["remaining"].num()
            val usedCredits = s["credits_used"].num()
            if (key == "premium_interactions" && usedCredits != null && (credits || usedCredits > 0)) {
                notes += UsageWindow("copilot", "copilot:credits_used", "Crediti AI", 0.0, null, null,
                    note = "${fmt(usedCredits)} usati" + (if (ent != null && ent > 0) " su ${fmt(ent)} compresi" else ""))
                if (ent != null && ent > 0 && usedCredits > ent) {
                    val until = reset?.atZone(java.time.ZoneId.systemDefault())?.let { ", fino al %02d/%02d".format(it.dayOfMonth, it.monthValue) } ?: ""
                    notes += UsageWindow("copilot", "copilot:overage", "Uso aggiuntivo in corso", 0.0, null, null,
                        note = "${fmt(usedCredits - ent)} crediti oltre i compresi$until")
                }
            }
            if ((s["unlimited"] as? JsonPrimitive)?.booleanOrNull == true) continue
            if (credits && ent == 0.0 && (rem == null || rem == 0.0)) continue   // quota finta a zero
            var remaining = s["percent_remaining"].num()
            if (remaining == null) {
                if (ent == null || ent <= 0 || rem == null) continue
                remaining = rem / ent * 100
            }
            out += UsageWindow("copilot", "copilot:$key", label, (100 - remaining).coerceAtLeast(0.0), reset, length)
        }
        if (out.isEmpty()) {
            val monthly = root["monthly_quotas"].obj(); val left = root["limited_user_quotas"].obj()
            if (monthly != null && left != null) for (k in listOf("premium_interactions", "completions", "chat")) {
                val total = monthly[k].num(); val rest = left[k].num()
                if (total != null && total > 0 && rest != null) {
                    out += UsageWindow("copilot", "copilot:monthly", "Quota del mese",
                        ((total - rest) / total * 100).coerceIn(0.0, 100.0), reset, length)
                    break
                }
            }
        }
        if (out.isEmpty() && notes.isEmpty()) throw ApiException("Copilot non riporta limiti per questo account (piano illimitato o senza Copilot).")
        return out + notes
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
        val expiring = grants.mapNotNull { it.obj() }.mapNotNull { g ->
            val exp = (g["expires_at_ts"] ?: g["expiresAtTs"]).num(); val amount = (g["amount_cents"] ?: g["amountCents"]).num()
            if (exp != null && exp > now.epochSecond && amount != null && amount > 0) exp to amount else null
        }
        if (expiring.isNotEmpty()) {
            val exp = expiring.minOf { it.first }
            val amount = expiring.filter { it.first == exp }.sumOf { it.second }
            val z = Instant.ofEpochSecond(exp.toLong()).atZone(java.time.ZoneId.systemDefault())
            out += UsageWindow("perplexity", "perplexity:expiring", "Crediti in scadenza", 0.0, null, null,
                note = "${"%.2f".format(java.util.Locale.US, amount / 100).replace('.', ',')} $ scadono il %02d/%02d".format(z.dayOfMonth, z.monthValue))
        }
        return out
    }

    /** /rest/billing/quota: formato non documentato, lettura prudente (vuoto se non riconosciuto). */
    fun perplexityQuota(json: String): List<UsageWindow> {
        val root = runCatching { parseJson(json).obj() }.getOrNull() ?: return emptyList()
        val candidates = listOf("" to root) + root.entries.mapNotNull { (k, v) -> v.obj()?.let { k to it } }
        val out = mutableListOf<UsageWindow>()
        for ((name, d) in candidates) {
            val total = listOf("limit", "total", "quota", "max", "monthly_limit", "monthlyLimit", "allowance")
                .firstNotNullOfOrNull { d[it].num() } ?: continue
            if (total <= 0) continue
            val rem = listOf("remaining", "queries_remaining", "remaining_queries", "remainingQueries", "queriesRemaining", "left")
                .firstNotNullOfOrNull { d[it].num() }
            val used = listOf("used", "queries_used", "usage", "count", "consumed").firstNotNullOfOrNull { d[it].num() }
                ?: rem?.let { total - it } ?: continue
            val reset = listOf("reset_at", "resets_at", "reset_ts", "reset_date_ts", "resetAt", "resetsAt", "renewal_date_ts")
                .firstNotNullOfOrNull { k -> fromEpoch(d[k]) ?: parseInstant(d[k].str()) }
            out += UsageWindow("perplexity", "perplexity:quota" + (if (name.isNotEmpty()) ":$name" else ""),
                "Ricerche del mese" + (if (name.isNotEmpty()) " (${name.replace('_', ' ')})" else ""),
                (used / total * 100).coerceIn(0.0, 100.0), reset, if (reset != null) Duration.ofDays(30) else null)
            if (name.isEmpty()) break
        }
        return out
    }

    // ---------------- Grok ----------------

    fun grok(json: String): List<UsageWindow> {
        val config = parseJson(json).obj()?.get("config").obj() ?: throw ApiException("Risposta di Grok cambiata.")
        fun amount(e: JsonElement?): Double? = e.obj()?.get("val").num() ?: e.num()
        var percent = config["creditUsagePercent"].num()
        // Senza percentuale: crediti del periodo (monthlyLimit/used), poi il tetto on-demand.
        // Campi confermati dal codice della CLI di Grok (extensions/billing.rs, BillingConfig).
        for ((capKey, usedKey) in listOf("monthlyLimit" to "used", "onDemandCap" to "onDemandUsed")) {
            if (percent != null) break
            val cap = amount(config[capKey]); val used = amount(config[usedKey])
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

    // ---------------- Kimi Code ----------------

    /**
     * api.kimi.com/coding/v1/usages. Lettura tollerante come nella CLI di Kimi Code
     * (github.com/MoonshotAI/kimi-code, managed-usage.ts, MIT): used o remaining, resetAt o reset_at,
     * durata con duration + timeUnit.
     */
    fun kimi(json: String, now: Instant = Instant.now()): List<UsageWindow> {
        val root = parseJson(json).obj() ?: throw ApiException("Risposta di Kimi cambiata.")
        val out = mutableListOf<UsageWindow>()
        fun first(objs: List<JsonObject?>, vararg keys: String): JsonElement? {
            for (o in objs) for (k in keys) {
                val v = o?.get(k) ?: continue
                if (v is JsonNull || (v as? JsonPrimitive)?.contentOrNull == "") continue
                return v
            }
            return null
        }
        fun usedLimit(d: JsonObject): Pair<Double?, Double?> {
            val limit = d["limit"].num()
            var used = d["used"].num()
            if (used == null) { val rem = d["remaining"].num(); if (rem != null && limit != null) used = limit - rem }
            return used to limit
        }
        fun reset(objs: List<JsonObject?>): Instant? {
            first(objs, "reset_at", "resetAt", "reset_time", "resetTime").str()?.let { v ->
                // nanosecondi → millisecondi
                val t = if ('.' in v && v.endsWith("Z")) v.substringBefore('.') + "." + v.substringAfter('.').dropLast(1).take(6) + "Z" else v
                parseInstant(t)?.let { return it }
            }
            val secs = first(objs, "reset_in", "resetIn", "ttl").num()
            return if (secs != null && secs > 0) now.plusSeconds(secs.toLong()) else null
        }
        fun label(name: String?, length: Duration?, fallback: String): String {
            if (length != null) return windowLabel(length)
            val n = name?.takeIf { it.isNotEmpty() } ?: return fallback
            return if ("week" in n.lowercase()) "Settimana" else n
        }
        root["usage"].obj()?.let { u ->
            val (used, limit) = usedLimit(u)
            if (used != null && limit != null && limit > 0) {
                val name = first(listOf(u), "name", "title").str()
                val length = if (name == null || "week" in name.lowercase()) Duration.ofDays(7) else null
                out += UsageWindow("kimi", "kimi:weekly", label(name, length, "Settimana"),
                    (used / limit * 100).coerceIn(0.0, 100.0), reset(listOf(u)), length)
            }
        }
        (root["limits"] as? JsonArray)?.forEachIndexed { idx, e ->
            val item = e.obj() ?: return@forEachIndexed
            val detail = item["detail"].obj() ?: item
            val window = item["window"].obj()
            val (used, limit) = usedLimit(detail)
            if (used == null || limit == null || limit <= 0) return@forEachIndexed
            val duration = first(listOf(window, item, detail), "duration").num()
            val unit = (first(listOf(window, item, detail), "timeUnit", "time_unit").str() ?: "").uppercase()
            val unitSecs = when {
                "MINUTE" in unit -> 60L; "HOUR" in unit -> 3600L; "DAY" in unit -> 86400L
                "WEEK" in unit -> 7 * 86400L; else -> 1L
            }
            val length = if (duration != null && duration > 0) Duration.ofSeconds(duration.toLong() * unitSecs) else null
            var key = if (length != null) "kimi:limit:${length.seconds}s" else "kimi:limit:$idx"
            if (out.any { it.key == key }) key = "$key:$idx"
            val name = first(listOf(item, detail), "name", "title", "scope").str()
            out += UsageWindow("kimi", key, label(name, length, "Limite ${idx + 1}"),
                (used / limit * 100).coerceIn(0.0, 100.0), reset(listOf(detail, item)), length)
        }
        val wallet = (root["boosterWallet"] ?: root["booster_wallet"]).obj()
        val balance = wallet?.get("balance").obj()
        if (wallet != null && balance != null && balance["type"].str() == "BOOSTER") {
            fun cents(v: JsonElement?): Long? = v.num()?.let { val c = it / 1_000_000; if (c > 0 && c < 1) 1L else Math.round(c) }
            fun money(c: Long, cur: String) = "%.2f".format(java.util.Locale.US, c / 100.0).replace('.', ',') + " $cur"
            val total = cents(balance["amount"])
            val left = cents(balance["amountLeft"]) ?: 0L
            val cap = wallet["monthlyChargeLimit"].obj()
            val spent = wallet["monthlyUsed"].obj()
            val capCents = cap?.get("priceInCents").num()
            val spentCents = spent?.get("priceInCents").num() ?: 0.0
            val currency = cap?.get("currency").str()?.takeIf { it.isNotEmpty() }
                ?: spent?.get("currency").str()?.takeIf { it.isNotEmpty() } ?: "USD"
            if (total != null && total > 0) {
                if ((wallet["monthlyChargeLimitEnabled"] as? JsonPrimitive)?.booleanOrNull == true && capCents != null && capCents > 0) {
                    out += UsageWindow("kimi", "kimi:extra_month", "Spesa extra del mese (limite ${money(capCents.toLong(), currency)})",
                        (spentCents / capCents * 100).coerceIn(0.0, 100.0), null, null)
                }
                out += UsageWindow("kimi", "kimi:booster", "Credito extra", 0.0, null, null,
                    note = "${money(left, currency)} rimasti su ${money(total, currency)}")
            }
        }
        if (out.none { !it.isInfo }) throw ApiException("Kimi non riporta limiti per questo account (serve un abbonamento Kimi Code).")
        return out
    }

    // ---------------- Accesso con codice (Codex, Grok) ----------------

    /** Claim del payload di un JWT (senza verificare la firma: serve solo a leggere scadenza e account). */
    fun jwtClaims(token: String): JsonObject? {
        val parts = token.split('.')
        if (parts.size < 2) return null
        return runCatching {
            val bytes = java.util.Base64.getUrlDecoder().decode(parts[1].trimEnd('='))
            Json.parseToJsonElement(String(bytes, Charsets.UTF_8)) as? JsonObject
        }.getOrNull()
    }

    fun jwtExpiry(token: String): Instant? = jwtClaims(token)?.get("exp").num()?.let { Instant.ofEpochSecond(it.toLong()) }

    /** chatgpt_account_id dal token di OpenAI (claim "https://api.openai.com/auth"). */
    fun openAiAccountId(idToken: String?): String? {
        if (idToken == null) return null
        val claims = jwtClaims(idToken) ?: return null
        return claims["https://api.openai.com/auth"].obj()?.get("chatgpt_account_id").str()
            ?: claims["chatgpt_account_id"].str()
    }

    /** Codex: risposta di /api/accounts/deviceauth/usercode → (device_auth_id, user_code, intervallo s). */
    fun codexUserCode(json: String): Triple<String, String, Long> {
        val o = parseJson(json).obj() ?: throw ApiException("Risposta di OpenAI cambiata.")
        val id = o["device_auth_id"].str() ?: throw ApiException("OpenAI non ha dato un codice.")
        val code = (o["user_code"] ?: o["usercode"]).str() ?: throw ApiException("OpenAI non ha dato un codice.")
        val interval = o["interval"].str()?.trim()?.toLongOrNull() ?: o["interval"].num()?.toLong() ?: 5L
        return Triple(id, code, interval.coerceIn(1, 30))
    }

    /** Codex: risposta di /api/accounts/deviceauth/token quando l'utente ha confermato → (codice, verifier). */
    fun codexDeviceToken(json: String): Pair<String, String> {
        val o = parseJson(json).obj() ?: throw ApiException("Risposta di OpenAI cambiata.")
        val code = o["authorization_code"].str() ?: throw ApiException("OpenAI non ha confermato l'accesso.")
        val verifier = o["code_verifier"].str() ?: throw ApiException("OpenAI non ha confermato l'accesso.")
        return code to verifier
    }

    /** Grok: risposta di /oauth2/device/code. */
    data class DeviceCode(val deviceCode: String, val userCode: String, val verificationUri: String,
                          val verificationUriComplete: String?, val interval: Long, val expiresIn: Long)

    fun grokDeviceCode(json: String): DeviceCode {
        val o = parseJson(json).obj() ?: throw ApiException("Risposta di xAI cambiata.")
        val code = o["user_code"].str() ?: throw ApiException("xAI non ha dato un codice.")
        require(code.all { it.isLetterOrDigit() || it == '-' }) { "Codice di xAI non valido" }
        val uri = o["verification_uri"].str() ?: throw ApiException("xAI non ha dato l'indirizzo di accesso.")
        for (u in listOfNotNull(uri, o["verification_uri_complete"].str()))
            if (!u.startsWith("https://")) throw ApiException("Indirizzo di accesso di xAI non sicuro.")
        return DeviceCode(o["device_code"].str() ?: throw ApiException("xAI non ha dato un codice."), code, uri,
            o["verification_uri_complete"].str(), (o["interval"].num()?.toLong() ?: 5L).coerceIn(1, 30),
            (o["expires_in"].num()?.toLong() ?: 600L).coerceAtLeast(600))
    }

    /** Token OAuth salvato come credenziale (JSON cifrato nello Store). */
    data class OAuthToken(val access: String, val refresh: String?, val expiresAt: Instant?,
                          val accountId: String? = null, val userId: String? = null) {
        fun expiringSoon(now: Instant): Boolean = expiresAt != null && now.isAfter(expiresAt.minusSeconds(120))
        fun toCredential(): String = kotlinx.serialization.json.buildJsonObject {
            put("type", JsonPrimitive("oauth"))
            put("access", JsonPrimitive(access))
            refresh?.let { put("refresh", JsonPrimitive(it)) }
            expiresAt?.let { put("expires_at", JsonPrimitive(it.epochSecond)) }
            accountId?.let { put("account_id", JsonPrimitive(it)) }
            userId?.let { put("user_id", JsonPrimitive(it)) }
        }.toString()
    }

    fun oauthCredential(credential: String): OAuthToken? {
        val t = credential.trim()
        if (!t.startsWith("{")) return null
        val o = runCatching { Json.parseToJsonElement(t) as? JsonObject }.getOrNull() ?: return null
        if (o["type"].str() != "oauth") return null
        val access = o["access"].str() ?: return null
        return OAuthToken(access, o["refresh"].str(), o["expires_at"].num()?.let { Instant.ofEpochSecond(it.toLong()) },
            o["account_id"].str(), o["user_id"].str())
    }

    /**
     * Risposta di un endpoint token OAuth (access_token, refresh_token, expires_in, id_token).
     * [previous]: il refresh_token resta quello vecchio se il server non ne manda uno nuovo.
     */
    fun oauthTokens(json: String, now: Instant, previous: OAuthToken? = null): OAuthToken {
        val o = parseJson(json).obj() ?: throw ApiException("Risposta di accesso cambiata.")
        val access = o["access_token"].str() ?: throw AuthException("Accesso rifiutato: rifai «Accedi con codice».")
        val idToken = o["id_token"].str()
        val exp = o["expires_in"].num()?.let { now.plusSeconds(it.toLong()) } ?: jwtExpiry(access)
        return OAuthToken(access, o["refresh_token"].str() ?: previous?.refresh, exp,
            openAiAccountId(idToken) ?: previous?.accountId,
            idToken?.let { jwtClaims(it)?.get("sub").str() } ?: previous?.userId)
    }

    /** Errore standard del device flow: authorization_pending, slow_down, access_denied, expired_token. */
    fun oauthError(json: String): String? = runCatching { parseJson(json).obj()?.get("error").str() }.getOrNull()
}
