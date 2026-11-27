package io.github.lorenzomp7.usageai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class UsageLogicTest {
    private val now: Instant = Instant.parse("2026-11-27T12:00:00Z")

    private fun sample(five: Double = 40.0, week: Double = 30.0,
                       fiveReset: Instant = now.plus(Duration.ofHours(2)),
                       weekReset: Instant = now.plus(Duration.ofDays(4))) = """
        {"five_hour":{"utilization":$five,"resets_at":"${fiveReset.toString().replace("Z", "+00:00")}"},
         "seven_day":{"utilization":$week,"resets_at":"$weekReset"},
         "seven_day_opus":{"utilization":5.0,"resets_at":null},
         "seven_day_oauth_apps":null,
         "extra_usage":{"is_enabled":false}}
    """.trimIndent()

    @Test fun parsesAndOrders() {
        val ws = parseUsage(sample())
        assertEquals(listOf("five_hour", "seven_day", "seven_day_opus"), ws.map { it.key })
        assertEquals(now.plus(Duration.ofDays(4)), ws[1].resetsAt)
        assertNull(ws[2].resetsAt)
        assertEquals("Settimana · Opus", ws[2].label)
    }

    @Test fun parsesFractionalTimestamp() {
        val ws = parseUsage("""{"five_hour":{"utilization":1,"resets_at":"2026-11-27T14:00:00.512345+00:00"}}""")
        assertEquals(Instant.parse("2026-11-27T14:00:00.512345Z"), ws[0].resetsAt)
    }

    @Test fun thresholdOncePerWindow() {
        val s = AlertSettings(thresholds5h = listOf(50, 80), thresholdsWeek = listOf(90), paceEnabled = false)
        var (alerts, state) = evaluate(parseUsage(sample(five = 60.0)), s, emptyMap(), now)
        assertEquals(listOf("Sessione 5 ore: 60% (soglia 50%)"), alerts.map { it.title })
        evaluate(parseUsage(sample(five = 65.0)), s, state, now).let { alerts = it.first; state = it.second }
        assertTrue(alerts.isEmpty())
        evaluate(parseUsage(sample(five = 85.0)), s, state, now).let { alerts = it.first }
        assertTrue(alerts.single().title.contains("soglia 80%"))
    }

    @Test fun jumpGivesSingleAlert() {
        val s = AlertSettings(thresholds5h = listOf(50, 80), paceEnabled = false)
        val (alerts, state) = evaluate(parseUsage(sample(five = 90.0)), s, emptyMap(), now)
        assertTrue(alerts.single().title.contains("soglia 80%"))
        assertEquals(listOf(50, 80), state["five_hour"]!!.notified)
    }

    @Test fun newWindowRearms() {
        val s = AlertSettings(thresholds5h = listOf(50), paceEnabled = false)
        val state = evaluate(parseUsage(sample(five = 60.0)), s, emptyMap(), now).second
        val later = now.plus(Duration.ofHours(6))
        val alerts = evaluate(parseUsage(sample(five = 55.0, fiveReset = later.plus(Duration.ofHours(3)))), s, state, later).first
        assertEquals(1, alerts.size)
        val jitter = evaluate(parseUsage(sample(five = 62.0, fiveReset = now.plus(Duration.ofMinutes(125)))), s, state, now).first
        assertTrue(jitter.isEmpty())
    }

    @Test fun paceAlertAndRearm() {
        val w = parseUsage(sample())[1]
        assertEquals(300.0 / 7, expectedUtilization(w, now)!!, 0.01)
        val s = AlertSettings(thresholdsWeek = emptyList(), paceMargin = 10)
        var (alerts, state) = evaluate(parseUsage(sample(week = 60.0)), s, emptyMap(), now)
        val pace = alerts.single { it.key == "seven_day_pace" }
        assertTrue(pace.text, pace.text.contains("saresti al 43%"))
        evaluate(parseUsage(sample(week = 61.0)), s, state, now).let { alerts = it.first; state = it.second }
        assertTrue(alerts.none { it.key == "seven_day_pace" })
        state = evaluate(parseUsage(sample(week = 40.0)), s, state, now).second
        assertFalse(state["seven_day"]!!.paceNotified)
    }

    @Test fun paceQuietAtStartOfWeek() {
        val s = AlertSettings(thresholdsWeek = emptyList())
        val data = sample(week = 15.0, weekReset = now.plus(Duration.ofDays(6)).plus(Duration.ofHours(23)))
        assertTrue(evaluate(parseUsage(data), s, emptyMap(), now).first.isEmpty())
    }

    @Test fun thresholdsParsing() {
        assertEquals(listOf(50, 75, 90), parseThresholds("75, 50;90  50"))
        assertEquals(emptyList<Int>(), parseThresholds(""))
        var failed = 0
        for (bad in listOf("0, 50", "abc")) {
            try { parseThresholds(bad) } catch (e: IllegalArgumentException) { failed++ }
        }
        assertEquals(2, failed)
    }

    @Test fun orgPicking() {
        val json = """[{"uuid":"api","capabilities":["api"]},{"uuid":"free","capabilities":["chat"]},
            {"uuid":"max","capabilities":["chat","claude_max"]}]"""
        assertEquals("max", pickOrgId(json))
        assertEquals("a", pickOrgId("""[{"uuid":"a","capabilities":["chat"]}]"""))
        assertNull(pickOrgId("[]"))
    }

    @Test fun stateRoundTrip() {
        val st = mapOf("five_hour" to WindowState("2026-11-27T14:00:00Z", listOf(50, 80), true),
                       "seven_day" to WindowState(null, emptyList(), false))
        assertEquals(st, stateFromJson(stateToJson(st)))
        assertTrue(stateFromJson("garbage").isEmpty())
    }
}
