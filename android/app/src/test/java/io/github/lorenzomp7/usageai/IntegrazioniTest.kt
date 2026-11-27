package io.github.lorenzomp7.usageai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.time.Duration
import java.time.Instant

/** Integrazioni del Piano di battaglia (UsageAI 1-4). Le fixture sono le stesse dei test di Windows. */
class IntegrazioniTest {
    private val now: Instant = Instant.parse("2026-11-27T12:00:00Z")

    /** I test girano in android/app: le fixture stanno nella cartella fixtures/ della repo. */
    private fun fixture(path: String): String {
        val f = listOf(File("../../fixtures/$path"), File("../fixtures/$path"), File("fixtures/$path")).first { it.exists() }
        return f.readText()
    }

    private inline fun <reified T : Throwable> assertThrows(block: () -> Unit) {
        try { block(); fail("doveva lanciare ${T::class.simpleName}") } catch (e: Throwable) {
            if (e !is T) throw e
        }
    }

    // ---------- Codex (UsageAI 2) ----------

    @Test fun codexFullPayloadFromCodexCliTest() {
        val ws = Parsers.codex(fixture("codex/wham-usage.rate_limits_rs.json")).associateBy { it.key }
        assertEquals(42.0, ws.getValue("codex:primary_window").utilization, 0.0)
        assertEquals(Duration.ofHours(1), ws.getValue("codex:primary_window").length)
        assertEquals(Duration.ofDays(1), ws.getValue("codex:secondary_window").length)
        val extra = ws.getValue("codex:extra:codex_other:primary")
        assertEquals(88.0, extra.utilization, 0.0)
        assertTrue(extra.label.startsWith("Codex other · "))
        assertEquals(Duration.ofMinutes(30), extra.length)
        val spend = ws.getValue("codex:spend")
        assertEquals(32.0, spend.utilization, 0.0)
        assertTrue(spend.label, "(8000 su 25000)" in spend.label)
        assertNotNull(spend.resetsAt)
        assertEquals("3", ws.getValue("codex:resets").note)
        assertNull(ws["codex:reached"])
    }

    @Test fun codexReachedReasonAndCredits() {
        val json = fixture("codex/wham-usage.rate_limits_rs.json")
            .replace("\"limit_reached\": false,\n    \"primary_window\": {\n      \"used_percent\": 42",
                "\"limit_reached\": true,\n    \"primary_window\": {\n      \"used_percent\": 42")
            .replace("\"plan_type\": \"pro\",", "\"plan_type\": \"pro\", \"credits\": {\"has_credits\": true, \"unlimited\": false, \"balance\": \"125.5\"},")
        val ws = Parsers.codex(json).associateBy { it.key }
        assertEquals(Parsers.CODEX_REACHED_REASONS["workspace_member_usage_limit_reached"], ws.getValue("codex:reached").note)
        assertEquals("125,5 rimasti", ws.getValue("codex:credits").note)
    }

    @Test fun infoRowsNeverAlertAndSurviveSerialization() {
        val ws = Parsers.codex(fixture("codex/wham-usage.rate_limits_rs.json"))
        val (alerts, state) = evaluate(ws, AlertSettings(thresholdsShort = listOf(1), thresholdsLong = listOf(1)),
            emptyMap(), Instant.parse("2024-12-31T23:00:00Z"))
        assertTrue(alerts.none { it.key == "codex:resets" })
        assertFalse("codex:resets" in state)
        val back = windowsFromJson(windowsToJson(ws))
        assertEquals("3", back.first { it.key == "codex:resets" }.note)
        assertTrue(UsageWidget.headline(ws).none { it.isInfo })
    }

    // ---------- Kimi Code (UsageAI 3) ----------

    @Test fun kimiFixture() {
        val ws = Parsers.kimi(fixture("kimi/usages.managed-usage_test_ts.json"), now).associateBy { it.key }
        val week = ws.getValue("kimi:weekly")
        assertEquals("Settimana", week.label)
        assertEquals(40.0, week.utilization, 0.0)
        assertEquals(Instant.parse("2026-12-01T09:00:00.123456Z"), week.resetsAt)
        val five = ws.getValue("kimi:limit:18000s")
        assertEquals("Sessione 5 ore", five.label)
        assertEquals(60.0, five.utilization, 0.0)
        val day = ws.getValue("kimi:limit:86400s")
        assertEquals(80.0, day.utilization, 0.0)
        assertEquals(now.plus(Duration.ofHours(2)), day.resetsAt)
        assertEquals(25.0, ws.getValue("kimi:extra_month").utilization, 0.0)
        assertEquals("100,00 USD rimasti su 200,00 USD", ws.getValue("kimi:booster").note)
    }

    @Test fun kimiFieldVariants() {
        for (v in listOf(
            """{"usage":{"used":1,"limit":10,"reset_at":"2026-12-01T00:00:00Z"}}""",
            """{"usage":{"remaining":9,"limit":10,"resetTime":"2026-12-01T00:00:00Z"}}""",
            """{"usage":{"used":"1","limit":"10","resetAt":"2026-12-01T00:00:00Z"}}""",
        )) {
            val w = Parsers.kimi(v, now).single()
            assertEquals(v, 10.0, w.utilization, 0.001)
            assertEquals(v, Instant.parse("2026-12-01T00:00:00Z"), w.resetsAt)
        }
        val labels = Parsers.kimi("""{"limits":[{"name":"Daily cap","detail":{"used":5,"limit":100}},
            {"detail":{"used":1,"limit":4},"window":{"duration":2,"timeUnit":"DAY"}}]}""", now).map { it.label }
        assertEquals(listOf("Daily cap", "Finestra 2 giorni"), labels)
        assertThrows<ApiException> { Parsers.kimi("""{"usage":{"name":"Weekly limit"}}""", now) }
    }

    // ---------- Grok (UsageAI 4) ----------

    @Test fun grokMonthlyCredits() {
        val w = Parsers.grok("""{"config":{"monthlyLimit":{"val":2000},"used":{"val":500},
            "currentPeriod":{"start":"2026-11-01T00:00:00Z","end":"2026-12-01T00:00:00Z"}}}""").single()
        assertEquals(25.0, w.utilization, 0.0)
        assertEquals("Crediti · Mese", w.label)
    }

    @Test fun grokDeviceCodeResponse() {
        val d = Parsers.grokDeviceCode("""{"device_code":"dev-1","user_code":"ABCD-1234",
            "verification_uri":"https://accounts.x.ai/device","verification_uri_complete":"https://accounts.x.ai/device?user_code=ABCD-1234",
            "expires_in":900,"interval":5}""")
        assertEquals("ABCD-1234", d.userCode)
        assertEquals(5L, d.interval)
        assertEquals(900L, d.expiresIn)
        assertThrows<ApiException> {
            Parsers.grokDeviceCode("""{"device_code":"d","user_code":"AB","verification_uri":"http://evil.example"}""")
        }
        assertEquals("authorization_pending", Parsers.oauthError("""{"error":"authorization_pending"}"""))
        assertNull(Parsers.oauthError("<html>"))
    }

    // ---------- Accesso con codice: token salvati ----------

    private fun b64(s: String) = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray())
    private fun jwt(payload: String) = "${b64("""{"alg":"none"}""")}.${b64(payload)}.sig"

    @Test fun codexDeviceAuthResponses() {
        // forme prese dai test di Codex CLI (app-server/tests/suite/v2/account.rs)
        val (id, code, interval) = Parsers.codexUserCode("""{"device_auth_id":"device-auth-123","user_code":"CODE-12345","interval":"0"}""")
        assertEquals("device-auth-123", id)
        assertEquals("CODE-12345", code)
        assertEquals(1L, interval)
        assertEquals("poll-code-321" to "code-verifier-321", Parsers.codexDeviceToken(
            """{"authorization_code":"poll-code-321","code_challenge":"code-challenge-321","code_verifier":"code-verifier-321"}"""))
        val idToken = jwt("""{"sub":"user-1","https://api.openai.com/auth":{"chatgpt_account_id":"acc-9"}}""")
        val access = jwt("""{"exp":${now.plusSeconds(3600).epochSecond}}""")
        val t = Parsers.oauthTokens("""{"access_token":"$access","refresh_token":"rt-1","id_token":"$idToken"}""", now)
        assertEquals("acc-9", t.accountId)
        assertEquals(now.plusSeconds(3600), t.expiresAt)
        assertFalse(t.expiringSoon(now))
        assertTrue(t.expiringSoon(now.plusSeconds(3500)))
    }

    @Test fun oauthCredentialRoundTripAndRefreshKeepsOldValues() {
        val t = Parsers.OAuthToken("acc", "rt", Instant.ofEpochSecond(1_800_000_000), "account", "user")
        assertEquals(t, Parsers.oauthCredential(t.toCredential()))
        assertNull(Parsers.oauthCredential("sk-ant-sid01-abc"))      // credenziale normale incollata
        assertNull(Parsers.oauthCredential("""{"foo":1}"""))
        val refreshed = Parsers.oauthTokens("""{"access_token":"new","expires_in":60}""", now, t)
        assertEquals("rt", refreshed.refresh)
        assertEquals("account", refreshed.accountId)
        assertEquals(now.plusSeconds(60), refreshed.expiresAt)
        assertThrows<AuthException> { Parsers.oauthTokens("""{"error":"invalid_grant"}""", now) }
    }

    // ---------- UsageAI 1: fixture registrate ----------

    @Test fun recordedFixturesAreReadable() {
        val root = listOf(File("../../fixtures"), File("../fixtures")).firstOrNull { it.isDirectory } ?: return
        root.walkTopDown().filter { it.isFile && it.parentFile.name == "registrate" && it.name.endsWith(".json") }
            .filter { !it.name.endsWith(".grpc.json") && "reset-credits" !in it.name }
            .forEach { f ->
                val text = f.readText()
                val ws = when (f.parentFile.parentFile.name) {
                    "codex" -> Parsers.codex(text)
                    "kimi" -> Parsers.kimi(text, now)
                    "grok" -> Parsers.grok(text)
                    else -> return@forEach
                }
                assertTrue(f.path, ws.isNotEmpty())
            }
    }
}
