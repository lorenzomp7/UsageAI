package io.github.lorenzomp7.usageai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Duration
import java.time.Instant

/** Modifiche UsageAI 34, 50, 52, 56, 65: stesse fixture e stessi casi di windows/tests/test_piano_rev66.py. */
class PianoRev66Test {
    private val now: Instant = Instant.parse("2026-09-09T12:00:00Z")

    private fun fixture(path: String): String =
        listOf(File("../../fixtures/$path"), File("../fixtures/$path")).first { it.exists() }.readText()

    // ---------- Claude ----------

    @Test fun claudeLimitsArePrimaryAndNameFable() {
        val ws = Parsers.claude(fixture("claude/usage.ClaudeUsageParserTest_kt.json"), now).associateBy { it.key }
        assertEquals(listOf("claude:five_hour", "claude:seven_day", "claude:seven_day_fable"), ws.keys.toList())
        assertEquals(21.0, ws.getValue("claude:five_hour").utilization, 0.0)
        assertEquals(Duration.ofHours(5), ws.getValue("claude:five_hour").length)
        assertEquals("Settimana Fable", ws.getValue("claude:seven_day_fable").label)
        assertFalse("claude:extra_usage" in ws)
    }

    @Test fun claudeFlatKeysFillWhatLimitsLack() {
        val ws = Parsers.claude(fixture("claude/usage-flat.ClaudeUsageParserTest_kt.json"), now).associateBy { it.key }
        assertEquals(setOf("claude:five_hour", "claude:seven_day", "claude:seven_day_opus", "claude:seven_day_fable"), ws.keys)
        assertEquals(5.0, ws.getValue("claude:seven_day_fable").utilization, 0.0)
        val mixed = Parsers.claude("""{"limits":[{"kind":"session","group":"session","percent":10}],
            "seven_day":{"utilization":90.5,"resets_at":"2026-09-14T00:00:00Z"}}""", now).associateBy { it.key }
        assertEquals(90.5, mixed.getValue("claude:seven_day").utilization, 0.0)
        assertEquals(10.0, mixed.getValue("claude:five_hour").utilization, 0.0)
        val unreadable = Parsers.claude("""{"iguana_necktie":{"utilization":5.0},"limits":[{"kind":"weekly_scoped",
            "percent":"unavailable","scope":{"model":{"display_name":"Fable"}}}]}""", now)
        assertEquals(listOf("claude:seven_day_fable" to 5.0), unreadable.map { it.key to it.utilization })
    }

    @Test fun claudeExtraUsageAndResets() {
        val ws = Parsers.claude(fixture("claude/usage-extra-reset.codexbar_docs.json"), now).associateBy { it.key }
        val extra = ws.getValue("claude:extra_usage")
        assertEquals(25.0, extra.utilization, 0.001)
        assertTrue(extra.label, "12,50 su 50,00 EUR" in extra.label)
        assertEquals(Instant.parse("2026-10-01T00:00:00Z"), extra.resetsAt)
        val resets = ws.getValue("claude:resets")
        assertTrue(resets.isInfo)
        assertTrue(resets.note!!, resets.note!!.startsWith("3 (il primo scade il "))
        val (_, state) = evaluate(ws.values.toList(), AlertSettings(), emptyMap(), now)
        assertFalse("claude:resets" in state)
        assertTrue("claude:extra_usage" in state)
    }

    @Test fun claudeResetsIgnoredWhenNotEligibleOrOdd() {
        for (block in listOf(
            """{"eligible":false,"grants":[{"resets_left":1,"paused":false}]}""",
            """{"grants":[{"resets_left":1,"paused":false}]}""",
            """{"eligible":true,"grants":[{"resets_left":"many","paused":false}]}""",
            """{"eligible":true,"grants":[{"resets_left":1}]}""",
            """{"eligible":true,"grants":[{"resets_left":1,"paused":false,"ends_at":"next tuesday"}]}""",
            """{"eligible":true,"grants":[{"resets_left":60,"paused":false}]}""",
        )) {
            val ws = Parsers.claude("""{"five_hour":{"utilization":1.0},"cedar_ember":$block}""", now)
            assertTrue(block, ws.none { it.key == "claude:resets" })
        }
    }

    @Test fun claudePaceOnlyWhenRecognisable() {
        val ws = Parsers.claude("""{"seven_day":{"utilization":40.0,"resets_at":"2026-09-14T00:00:00Z",
            "pace":{"projected_utilization":92}},"five_hour":{"utilization":30.0,"pace":{"runs_out_at":"2026-09-09T15:00:00Z"}}}""", now)
            .associateBy { it.key }
        assertEquals("circa 92% al reset", ws.getValue("claude:seven_day:pace").note)
        assertTrue(ws.getValue("claude:five_hour:pace").note!!.startsWith("finisce verso "))
        val odd = Parsers.claude("""{"seven_day":{"utilization":40.0,"pace":{"strange":[1,2]}}}""", now)
        assertEquals(listOf("claude:seven_day"), odd.map { it.key })
    }

    // ---------- Copilot ----------

    @Test fun copilotAiCreditsAndPlaceholders() {
        val a = Parsers.copilot(fixture("copilot/user-credits.CopilotUsageModelsTests_swift.json")).associateBy { it.key }
        assertEquals(setOf("copilot:credits_used"), a.keys)
        assertEquals("31 usati", a.getValue("copilot:credits_used").note)
        val b = Parsers.copilot(fixture("copilot/user-monthly-quotas.CopilotUsageModelsTests_swift.json")).associateBy { it.key }
        assertEquals(75.0, b.getValue("copilot:monthly").utilization, 0.0)
        assertFalse("copilot:premium_interactions" in b)
    }

    @Test fun copilotIncludedCreditsAndOverage() {
        val ws = Parsers.copilot("""{"token_based_billing":true,"quota_reset_date":"2026-10-01","quota_snapshots":
            {"premium_interactions":{"entitlement":300,"remaining":-20,"percent_remaining":0,"credits_used":320}}}""")
            .associateBy { it.key }
        assertEquals("Crediti AI del piano", ws.getValue("copilot:premium_interactions").label)
        assertEquals(100.0, ws.getValue("copilot:premium_interactions").utilization, 0.0)
        assertEquals("320 usati su 300 compresi", ws.getValue("copilot:credits_used").note)
        assertTrue(ws.getValue("copilot:overage").note!!.startsWith("20 crediti oltre i compresi"))
        val old = Parsers.copilot("""{"quota_snapshots":{"premium_interactions":{"percent_remaining":60}}}""")
        assertEquals(listOf("copilot:premium_interactions" to "Richieste premium"), old.map { it.key to it.label })
    }

    // ---------- Perplexity ----------

    @Test fun perplexityExpiringCredits() {
        val ws = Parsers.perplexity("""{"total_usage_cents":0,"credit_grants":[{"type":"recurring","amount_cents":5000},
            {"type":"promotional","amount_cents":1000,"expires_at_ts":1790000000},
            {"type":"promotional","amount_cents":250,"expires_at_ts":1800000000},
            {"type":"promotional","amount_cents":999,"expires_at_ts":1000}]}""", now).associateBy { it.key }
        val w = ws.getValue("perplexity:expiring")
        assertTrue(w.isInfo)
        assertTrue(w.note!!, w.note!!.startsWith("10,00 $ scadono il "))
    }

    @Test fun perplexityQuotaIsPrudent() {
        val a = Parsers.perplexityQuota("""{"remaining":120,"limit":300,"reset_at":"2026-10-01T00:00:00Z"}""")
        assertEquals(listOf("perplexity:quota" to 60.0), a.map { it.key to it.utilization })
        val b = Parsers.perplexityQuota("""{"pro_search":{"used":30,"total":300}}""")
        assertEquals("perplexity:quota:pro_search", b.single().key)
        assertEquals(10.0, b.single().utilization, 0.0)
        assertTrue(Parsers.perplexityQuota("""{"something":"else","count":3}""").isEmpty())
        assertTrue(Parsers.perplexityQuota("[1,2]").isEmpty())
        assertTrue(Parsers.perplexityQuota("<html>").isEmpty())
    }
}
