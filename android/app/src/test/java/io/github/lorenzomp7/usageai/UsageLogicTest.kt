package io.github.lorenzomp7.usageai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Duration
import java.time.Instant

class UsageLogicTest {
    private val now: Instant = Instant.parse("2026-11-27T12:00:00Z")

    private fun claudeJson(five: Double = 40.0, week: Double = 30.0,
                           fiveReset: Instant = now.plus(Duration.ofHours(2)),
                           weekReset: Instant = now.plus(Duration.ofDays(4))) = """
        {"five_hour":{"utilization":$five,"resets_at":"${fiveReset.toString().replace("Z", "+00:00")}"},
         "seven_day":{"utilization":$week,"resets_at":"$weekReset"},
         "seven_day_opus":{"utilization":5.0,"resets_at":null},
         "seven_day_oauth_apps":null,
         "extra_usage":{"is_enabled":false}}
    """.trimIndent()

    private fun claude(five: Double = 40.0, week: Double = 30.0,
                       fiveReset: Instant = now.plus(Duration.ofHours(2)),
                       weekReset: Instant = now.plus(Duration.ofDays(4))) =
        Parsers.claude(claudeJson(five, week, fiveReset, weekReset)).take(2)

    private inline fun <reified T : Throwable> assertThrows(block: () -> Unit) {
        try { block(); fail("doveva lanciare ${T::class.simpleName}") } catch (e: Throwable) {
            if (e !is T) throw e
        }
    }

    // ---------- logica comune ----------

    @Test fun claudeParsesAndOrders() {
        val ws = Parsers.claude(claudeJson())
        assertEquals(listOf("claude:five_hour", "claude:seven_day", "claude:seven_day_opus"), ws.map { it.key })
        assertEquals("Settimana Opus", ws[2].label)
        assertNull(ws[2].resetsAt)
        assertFalse(ws[0].isLong); assertTrue(ws[1].isLong)
        assertEquals("Claude · Settimana", ws[1].fullLabel)
    }

    @Test fun windowLabels() {
        assertEquals("Sessione 5 ore", windowLabel(Duration.ofHours(5)))
        assertEquals("Settimana", windowLabel(Duration.ofDays(7)))
        assertEquals("Mese", windowLabel(Duration.ofDays(30)))
    }

    @Test fun thresholdOncePerWindow() {
        val s = AlertSettings(thresholdsShort = listOf(50, 80), thresholdsLong = listOf(90), paceEnabled = false)
        var (alerts, state) = evaluate(claude(five = 60.0), s, emptyMap(), now)
        assertEquals(listOf("Claude · Sessione 5 ore: 60% (soglia 50%)"), alerts.map { it.title })
        evaluate(claude(five = 65.0), s, state, now).let { alerts = it.first; state = it.second }
        assertTrue(alerts.isEmpty())
        evaluate(claude(five = 85.0), s, state, now).let { alerts = it.first }
        assertTrue(alerts.single().title.contains("soglia 80%"))
    }

    @Test fun newWindowRearmsJitterDoesNot() {
        val s = AlertSettings(thresholdsShort = listOf(50), paceEnabled = false)
        val state = evaluate(claude(five = 60.0), s, emptyMap(), now).second
        val later = now.plus(Duration.ofHours(6))
        assertEquals(1, evaluate(claude(five = 55.0, fiveReset = later.plus(Duration.ofHours(3))), s, state, later).first.size)
        assertTrue(evaluate(claude(five = 62.0, fiveReset = now.plus(Duration.ofMinutes(125))), s, state, now).first.isEmpty())
    }

    @Test fun missingProviderKeepsState() {
        val s = AlertSettings(thresholdsShort = listOf(50), paceEnabled = false)
        var state = evaluate(claude(five = 60.0), s, emptyMap(), now).second
        state = evaluate(emptyList(), s, state, now).second
        assertTrue(evaluate(claude(five = 61.0), s, state, now).first.isEmpty())
    }

    @Test fun paceAlertAndRearm() {
        assertEquals(300.0 / 7, expectedUtilization(claude()[1], now)!!, 0.01)
        val s = AlertSettings(thresholdsLong = emptyList(), paceMargin = 10)
        var (alerts, state) = evaluate(claude(week = 60.0), s, emptyMap(), now)
        val pace = alerts.single { it.key == "claude:seven_day:pace" }
        assertTrue(pace.text, pace.text.contains("saresti al 43%"))
        evaluate(claude(week = 61.0), s, state, now).let { alerts = it.first; state = it.second }
        assertTrue(alerts.none { it.key.endsWith(":pace") })
        state = evaluate(claude(week = 40.0), s, state, now).second
        assertFalse(state["claude:seven_day"]!!.paceNotified)
    }

    @Test fun paceOnMonthlyWindow() {
        val month = UsageWindow("cursor", "cursor:plan", "Uso incluso nel mese", 70.0,
            now.plus(Duration.ofDays(20)), Duration.ofDays(30))
        val alerts = evaluate(listOf(month), AlertSettings(thresholdsLong = emptyList()), emptyMap(), now).first
        assertTrue(alerts.single().text.contains("saresti al 33%"))
    }

    @Test fun paceQuietAtStartOfWeek() {
        val ws = claude(week = 15.0, weekReset = now.plus(Duration.ofDays(6)).plus(Duration.ofHours(23)))
        assertTrue(evaluate(ws, AlertSettings(thresholdsLong = emptyList()), emptyMap(), now).first.isEmpty())
    }

    @Test fun thresholdsParsing() {
        assertEquals(listOf(50, 75, 90), parseThresholds("75, 50;90  50"))
        assertEquals(emptyList<Int>(), parseThresholds(""))
        assertThrows<IllegalArgumentException> { parseThresholds("0, 50") }
        assertThrows<IllegalArgumentException> { parseThresholds("abc") }
    }

    @Test fun serializationRoundTrips() {
        val st = mapOf("claude:five_hour" to WindowState("2026-11-27T14:00:00Z", listOf(50, 80), true))
        assertEquals(st, stateFromJson(stateToJson(st)))
        assertEquals(listOf(50), stateFromJson("""{"five_hour":{"notified":[50]}}""")["claude:five_hour"]!!.notified)
        assertTrue(stateFromJson("garbage").isEmpty())
        val ws = Parsers.claude(claudeJson())
        assertEquals(ws, windowsFromJson(windowsToJson(ws)))
        assertEquals(mapOf("a" to "b"), mapFromJson(mapToJson(mapOf("a" to "b"))))
    }

    @Test fun cookieHeaders() {
        assertEquals("WorkosCursorSessionToken=abc", cookieHeader(" abc ", "WorkosCursorSessionToken"))
        assertEquals("a=1; b=2", cookieHeader("Cookie: a=1; b=2", "x"))
    }

    // ---------- servizi ----------

    @Test fun claudeOrgPicking() {
        assertEquals("max", Parsers.claudeOrgId("""[{"uuid":"api","capabilities":["api"]},
            {"uuid":"free","capabilities":["chat"]},{"uuid":"max","capabilities":["chat","claude_max"]}]"""))
        assertNull(Parsers.claudeOrgId("[]"))
    }

    @Test fun codex() {
        val json = """{"plan_type":"plus","rate_limit":{
            "primary_window":{"used_percent":42,"reset_at":1764259200,"limit_window_seconds":18000},
            "secondary_window":{"used_percent":17,"reset_at":1764700000,"limit_window_seconds":604800}}}"""
        val ws = Parsers.codex(json)
        assertEquals(listOf("Sessione 5 ore" to 42.0, "Settimana" to 17.0), ws.map { it.label to it.utilization })
        assertEquals(Instant.ofEpochSecond(1764259200), ws[0].resetsAt)
        assertEquals("tok", Parsers.codexAccessToken("""{"accessToken":"tok","user":{}}"""))
        assertNull(Parsers.codexAccessToken("{}"))
    }

    @Test fun gemini() {
        val body = ")]}'\n" + """[{"data":{"viewer":{"rpcs":{"getUsageInfo":{"subscriptionTier":"TIER1","pools":[
            {"poolType":"GXU_FIVE_HOURLY","percentageUsed":0.25,"gxu":{"windowRefreshesAt":"2026-11-27T15:00:00Z"}},
            {"poolType":"GXU_WEEKLY","percentageUsed":0.6,"gxu":{"windowRefreshesAt":"2026-12-02T08:00:00Z"}}]}}}}}]"""
        val ws = Parsers.gemini(body)
        assertEquals(listOf("Sessione 5 ore" to 25.0, "Settimana" to 60.0), ws.map { it.label to it.utilization })
        assertThrows<AuthException> { Parsers.gemini("""[{"data":{"viewer":null}}]""") }
        assertThrows<AuthException> { Parsers.gemini("<!doctype html>") }
        assertThrows<ApiException> { Parsers.gemini("""[{"data":{"x":1}}]""") }
    }

    @Test fun cursor() {
        val json = """{"billingCycleStart":"2026-11-18T08:09:13.000Z","billingCycleEnd":"2026-12-18T08:09:13.000Z",
            "membershipType":"pro","isUnlimited":false,"individualUsage":{
            "plan":{"enabled":true,"used":1516,"limit":2000,"remaining":484,"autoPercentUsed":3.0,"apiPercentUsed":81.0},
            "onDemand":{"enabled":true,"used":500,"limit":2000,"remaining":1500}},"teamUsage":{}}"""
        val ws = Parsers.cursor(json).associateBy { it.key }
        assertEquals(75.8, ws["cursor:plan"]!!.utilization, 0.001)
        assertEquals(81.0, ws["cursor:api"]!!.utilization, 0.001)
        assertEquals(25.0, ws["cursor:ondemand"]!!.utilization, 0.001)
        assertTrue(ws["cursor:ondemand"]!!.label.contains("20 $"))
        assertEquals(Duration.ofDays(30), ws["cursor:plan"]!!.length)
    }

    @Test fun copilot() {
        val json = """{"copilot_plan":"individual","quota_reset_date":"2026-12-01","quota_snapshots":{
            "chat":{"unlimited":true},"completions":{"unlimited":true},
            "premium_interactions":{"entitlement":300,"remaining":120,"percent_remaining":40.0,"unlimited":false}}}"""
        val w = Parsers.copilot(json).single()
        assertEquals("Richieste premium", w.label)
        assertEquals(60.0, w.utilization, 0.001)
        assertEquals(Instant.parse("2026-12-01T00:00:00Z"), w.resetsAt)
        assertEquals(Duration.ofDays(30), w.length)
        assertEquals(80.0, Parsers.copilot("""{"quota_snapshots":{"premium_interactions":{"entitlement":50,"remaining":10}}}""")
            .single().utilization, 0.001)
        assertThrows<ApiException> { Parsers.copilot("""{"quota_snapshots":{"chat":{"unlimited":true}}}""") }
    }

    @Test fun perplexity() {
        val json = """{"balance_cents":1500,"renewal_date_ts":1767225600,"current_period_purchased_cents":0,
            "total_usage_cents":3500,"credit_grants":[{"type":"recurring","amount_cents":5000},
            {"type":"promotional","amount_cents":1000,"expires_at_ts":1893456000},
            {"type":"promotional","amount_cents":9999,"expires_at_ts":1000}]}"""
        val ws = Parsers.perplexity(json, now).associateBy { it.key }
        assertEquals(70.0, ws["perplexity:recurring"]!!.utilization, 0.001)
        assertEquals(Instant.ofEpochSecond(1767225600), ws["perplexity:recurring"]!!.resetsAt)
        assertEquals(0.0, ws["perplexity:promo"]!!.utilization, 0.001)
        val more = Parsers.perplexity(json.replace("3500", "5500"), now).associateBy { it.key }
        assertEquals(50.0, more["perplexity:promo"]!!.utilization, 0.001)
    }

    @Test fun grok() {
        val w = Parsers.grok("""{"config":{"creditUsagePercent":37.5,
            "currentPeriod":{"start":"2026-11-23T00:00:00Z","end":"2026-11-30T00:00:00Z"},"prepaidBalance":{"val":0}}}""").single()
        assertEquals(37.5, w.utilization, 0.001)
        assertEquals("Crediti · Settimana", w.label)
        val alt = Parsers.grok("""{"config":{"onDemandCap":{"val":2000},"onDemandUsed":{"val":500},
            "billingPeriodStart":"2026-11-01T00:00:00Z","billingPeriodEnd":"2026-12-01T00:00:00Z"}}""").single()
        assertEquals(25.0, alt.utilization, 0.001)
        assertEquals("Crediti · Mese", alt.label)
        assertThrows<ApiException> { Parsers.grok("""{"config":{"currentPeriod":{"end":"2026-11-30T00:00:00Z"}}}""") }
    }

    @Test fun mistral() {
        val budget = """{"subscription":{"budget":{"api_budget":{"usage_percentage":42.0,"initial_budget":15,
            "currency":"eur","reset_at":"2026-12-01T00:00:00Z"},"vibe_budget":{"usage_percentage":80,
            "initial_budget":10,"currency":"eur","reset_at":null}}}}""".replace("\n", "")
        val flight = "0:[\"\$\",\"div\",null,{}]\n5:" + budget + "\n"
        val encoded = kotlinx.serialization.json.JsonPrimitive(flight).toString()
        val html = "<html><script>self.__next_f.push([1,$encoded])</script><script>self.__next_f.push([2,null])</script></html>"
        val ws = Parsers.mistralHtml(html).associateBy { it.key }
        assertEquals(42.0, ws["mistral:api_budget"]!!.utilization, 0.001)
        assertEquals("Budget API incluso (15 EUR)", ws["mistral:api_budget"]!!.label)
        assertEquals(Duration.ofDays(30), ws["mistral:api_budget"]!!.length)
        assertNull(ws["mistral:vibe_budget"]!!.resetsAt)
        assertThrows<AuthException> { Parsers.mistralHtml("""<a href="https://auth.mistral.ai/self-service/login">Login</a>""") }
        assertThrows<ApiException> { Parsers.mistralHtml("<html>niente</html>") }
    }

    @Test fun openRouter() {
        val (daily, dayLen) = Parsers.openRouterNextReset("daily", now)
        assertEquals(Instant.parse("2026-11-28T00:00:00Z"), daily); assertEquals(Duration.ofDays(1), dayLen)
        assertEquals(Instant.parse("2026-11-30T00:00:00Z"), Parsers.openRouterNextReset("weekly", now).first)
        val (monthly, monthLen) = Parsers.openRouterNextReset("monthly", now)
        assertEquals(Instant.parse("2026-12-01T00:00:00Z"), monthly); assertEquals(Duration.ofDays(30), monthLen)
        val w = Parsers.openRouterKey("""{"data":{"limit":50,"limit_remaining":12.5,"limit_reset":"monthly","usage":100}}""", now).single()
        assertEquals(75.0, w.utilization, 0.001)
        assertEquals("Limite della chiave (50 $)", w.label)
        assertTrue(Parsers.openRouterKey("""{"data":{"limit":null,"usage":3}}""", now).isEmpty())
        val c = Parsers.openRouterCredits("""{"data":{"total_credits":20,"total_usage":5}}""").single()
        assertEquals(25.0, c.utilization, 0.001)
        assertTrue(c.label, c.label.contains("15.00 $"))
        assertTrue(Parsers.openRouterCredits("""{"error":"forbidden"}""").isEmpty())
    }

    // ---------- quota giornaliera ----------

    private fun week(util: Double, reset: Instant = now.plus(Duration.ofDays(4))) =
        UsageWindow("claude", "claude:seven_day", "Settimana", util, reset, Duration.ofDays(7))
    private val utc = java.time.ZoneOffset.UTC
    private fun dayAlerts(a: List<Alert>) = a.filter { it.key.endsWith(":day") }

    @Test fun dailyQuotaValues() {
        assertEquals(100.0 / 7, dailyQuota(week(0.0))!!, 1e-9)
        assertEquals(100.0 / 30, dailyQuota(UsageWindow("cursor", "cursor:plan", "Mese", 0.0, now, Duration.ofDays(30)))!!, 1e-9)
        assertNull(dailyQuota(UsageWindow("claude", "claude:five_hour", "5 ore", 0.0, now, Duration.ofHours(5))))
    }

    @Test fun dailyQuotaAlertOncePerDayAndNextDay() {
        val s = AlertSettings(thresholdsLong = emptyList(), paceEnabled = false)
        val t = Instant.parse("2026-11-26T09:00:00Z")
        var st = evaluate(listOf(week(20.0)), s, emptyMap(), t, utc).second
        evaluate(listOf(week(30.0)), s, st, t.plus(Duration.ofHours(2)), utc).let { assertTrue(dayAlerts(it.first).isEmpty()); st = it.second }
        evaluate(listOf(week(35.0)), s, st, t.plus(Duration.ofHours(3)), utc).let {
            val a = dayAlerts(it.first).single()
            assertTrue(a.text, a.text.contains("15,0 punti") && a.text.contains("14,3%"))
            st = it.second
        }
        evaluate(listOf(week(40.0)), s, st, t.plus(Duration.ofHours(5)), utc).let { assertTrue(dayAlerts(it.first).isEmpty()); st = it.second }
        val t2 = Instant.parse("2026-11-27T08:00:00Z")
        evaluate(listOf(week(41.0)), s, st, t2, utc).let { assertTrue(dayAlerts(it.first).isEmpty()); st = it.second }
        assertEquals(40.0, st["claude:seven_day"]!!.dayStart!!, 1e-9)
        assertTrue(todayText(week(41.0), st["claude:seven_day"])!!.startsWith("oggi +1,0% su 14,3% al giorno"))
        assertEquals(1, dayAlerts(evaluate(listOf(week(56.0)), s, st, t2.plus(Duration.ofHours(4)), utc).first).size)
        assertEquals(st, stateFromJson(stateToJson(st)))
    }

    @Test fun dailyQuotaAfterResetAndDisabled() {
        val s = AlertSettings(thresholdsLong = emptyList(), paceEnabled = false)
        val t = Instant.parse("2026-11-26T09:00:00Z")
        val st = evaluate(listOf(week(90.0, t.plus(Duration.ofHours(1)))), s, emptyMap(), t, utc).second
        val (alerts, st2) = evaluate(listOf(week(16.0, t.plus(Duration.ofDays(7)).plus(Duration.ofHours(1)))), s, st, t.plus(Duration.ofHours(3)), utc)
        assertEquals(1, dayAlerts(alerts).size)
        assertEquals(0.0, st2["claude:seven_day"]!!.dayStart!!, 1e-9)
        val off = AlertSettings(thresholdsLong = emptyList(), paceEnabled = false, dailyEnabled = false)
        val s3 = evaluate(listOf(week(10.0)), off, emptyMap(), t, utc).second
        assertTrue(dayAlerts(evaluate(listOf(week(60.0)), off, s3, t.plus(Duration.ofHours(1)), utc).first).isEmpty())
    }
}
