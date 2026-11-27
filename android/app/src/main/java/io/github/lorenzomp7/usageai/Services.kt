package io.github.lorenzomp7.usageai

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.net.URLEncoder
import java.time.Duration
import java.time.Instant

/** Credenziale speciale: usa i cookie del login fatto nella WebView dell'app. */
const val WEBVIEW_LOGIN = "webview"

/**
 * Un servizio di cui leggere il consumo. Gli endpoint non sono documentati dai produttori:
 * sono quelli che usano le loro pagine web. Se cambiano, va aggiornato qui e in Parsers.kt.
 */
abstract class Service(
    val id: String,
    val loginUrl: String?,      // null: niente login nell'app (Google lo blocca, o serve un token)
    val cookieUrl: String?,     // sito dei cookie della WebView
    val loginCookies: List<String>,   // cookie (o prefisso) che indica che l'accesso è fatto
    val pasteLabel: String,
    val pasteHelp: String,
    val usagePage: String,
) {
    val name: String get() = PROVIDER_NAMES[id] ?: id
    /** Accesso con codice dal browser, se il servizio lo offre. */
    open val deviceLogin: DeviceLogin? get() = null
    abstract suspend fun fetch(http: Http, store: Store, credential: String): List<UsageWindow>

    protected suspend fun cookies(http: Http, credential: String, default: String): String {
        val url = cookieUrl
        return if (credential == WEBVIEW_LOGIN && url != null) http.webViewCookies(url) else cookieHeader(credential, default)
    }
}

// ---------------- Accesso con codice (device flow) ----------------

/** Codice da mostrare all'utente e dati per controllare se ha confermato. */
data class DeviceStart(
    val userCode: String,
    val url: String,
    val intervalSeconds: Long,
    val expiresAt: Instant,
    val state: Map<String, String>,
)

sealed class DevicePoll {
    object Pending : DevicePoll()
    object SlowDown : DevicePoll()
    data class Done(val credential: String) : DevicePoll()
}

/** L'utente apre una pagina, inserisce il codice e l'app riceve un token (salvato cifrato). */
interface DeviceLogin {
    val help: String
    suspend fun start(http: Http): DeviceStart
    suspend fun poll(http: Http, start: DeviceStart): DevicePoll
}

private fun form(vararg pairs: Pair<String, String>): String =
    pairs.joinToString("&") { (k, v) -> URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8") }

/**
 * Codex: stessi passi di «codex login --device-auth» (codice di Codex CLI, login/src/device_code_auth.rs,
 * Apache-2.0). Usa l'identificativo client della CLI di Codex: OpenAI potrebbe cambiarlo o limitarlo,
 * per questo restano anche l'accesso nell'app e il cookie.
 */
object CodexDeviceLogin : DeviceLogin {
    private const val ISSUER = "https://auth.openai.com"
    const val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"

    override val help = "Va abilitato una volta in ChatGPT → Impostazioni → Sicurezza → «Accesso con codice " +
        "dispositivo per Codex». Poi apri la pagina, accedi e inserisci il codice (vale 15 minuti)."

    override suspend fun start(http: Http): DeviceStart {
        val body = buildJsonObject { put("client_id", JsonPrimitive(CLIENT_ID)) }.toString()
        val r = http.post("$ISSUER/api/accounts/deviceauth/usercode", mapOf("Accept" to "application/json"),
            body, "application/json")
        if (r.status == 404) throw ApiException("L'accesso con codice non è attivo: abilitalo in ChatGPT → " +
            "Impostazioni → Sicurezza, poi riprova.", 404)
        val (id, code, interval) = Parsers.codexUserCode(Http.require(r))
        return DeviceStart(code, "$ISSUER/codex/device", interval, Instant.now().plus(Duration.ofMinutes(15)),
            mapOf("device_auth_id" to id))
    }

    override suspend fun poll(http: Http, start: DeviceStart): DevicePoll {
        val body = buildJsonObject {
            put("device_auth_id", JsonPrimitive(start.state.getValue("device_auth_id")))
            put("user_code", JsonPrimitive(start.userCode))
        }.toString()
        val r = http.post("$ISSUER/api/accounts/deviceauth/token", mapOf("Accept" to "application/json"),
            body, "application/json")
        if (r.status == 403 || r.status == 404) return DevicePoll.Pending
        val (code, verifier) = Parsers.codexDeviceToken(Http.require(r, emptySet()))
        val t = http.post("$ISSUER/oauth/token", mapOf("Accept" to "application/json"), form(
            "grant_type" to "authorization_code",
            "code" to code,
            "redirect_uri" to "$ISSUER/deviceauth/callback",
            "client_id" to CLIENT_ID,
            "code_verifier" to verifier,
        ), "application/x-www-form-urlencoded")
        return DevicePoll.Done(Parsers.oauthTokens(Http.require(t, emptySet()), Instant.now()).toCredential())
    }

    /** Rinnovo del token, come fa la CLI (POST JSON a /oauth/token). */
    suspend fun refresh(http: Http, token: Parsers.OAuthToken): Parsers.OAuthToken {
        val rt = token.refresh ?: throw AuthException("Accesso a ChatGPT scaduto: rifai «Codice».")
        val body = buildJsonObject {
            put("client_id", JsonPrimitive(CLIENT_ID))
            put("grant_type", JsonPrimitive("refresh_token"))
            put("refresh_token", JsonPrimitive(rt))
        }.toString()
        val r = http.post("$ISSUER/oauth/token", mapOf("Accept" to "application/json"), body, "application/json")
        if (r.status == 400 || r.status == 401) throw AuthException("Accesso a ChatGPT scaduto: rifai «Codice».")
        return Parsers.oauthTokens(Http.require(r), Instant.now(), token)
    }
}

/**
 * Grok: stessi passi di «grok login --device-auth» (codice della CLI di Grok, auth/device_code.rs,
 * Apache-2.0): issuer auth.x.ai, flusso standard OAuth 2.0 per dispositivi (RFC 8628).
 */
object GrokDeviceLogin : DeviceLogin {
    private const val ISSUER = "https://auth.x.ai"
    const val CLIENT_ID = "b1a00492-073a-47ea-816f-4c329264a828"
    private const val SCOPES = "openid profile email offline_access grok-cli:access api:access"
    private val HEADERS = mapOf("Accept" to "application/json", "x-grok-client-surface" to "ui")

    override val help = "Apri la pagina, accedi con il tuo account xAI/Grok e conferma il codice."

    override suspend fun start(http: Http): DeviceStart {
        val r = http.post("$ISSUER/oauth2/device/code", HEADERS,
            form("client_id" to CLIENT_ID, "scope" to SCOPES, "referrer" to "grok-build"),
            "application/x-www-form-urlencoded")
        if (r.status == 404) throw ApiException("xAI non offre l'accesso con codice per questo account.", 404)
        val d = Parsers.grokDeviceCode(Http.require(r, emptySet()))
        val url = d.verificationUriComplete ?: (d.verificationUri + (if ('?' in d.verificationUri) "&" else "?") +
            "user_code=" + URLEncoder.encode(d.userCode, "UTF-8"))
        return DeviceStart(d.userCode, url, d.interval, Instant.now().plusSeconds(d.expiresIn),
            mapOf("device_code" to d.deviceCode))
    }

    override suspend fun poll(http: Http, start: DeviceStart): DevicePoll {
        val r = http.post("$ISSUER/oauth2/token", HEADERS, form(
            "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
            "device_code" to start.state.getValue("device_code"),
            "client_id" to CLIENT_ID,
        ), "application/x-www-form-urlencoded")
        if (r.status == 200) return DevicePoll.Done(Parsers.oauthTokens(r.body, Instant.now()).toCredential())
        return when (Parsers.oauthError(r.body)) {
            "authorization_pending" -> DevicePoll.Pending
            "slow_down" -> DevicePoll.SlowDown
            "access_denied" -> throw ApiException("Accesso negato su xAI.")
            "expired_token" -> throw ApiException("Il codice è scaduto: riprova.")
            else -> throw ApiException("Accesso a xAI non riuscito (HTTP ${r.status}).", r.status, r.body)
        }
    }

    suspend fun refresh(http: Http, token: Parsers.OAuthToken): Parsers.OAuthToken {
        val rt = token.refresh ?: throw AuthException("Accesso a Grok scaduto: rifai «Codice».")
        val r = http.post("$ISSUER/oauth2/token", HEADERS,
            form("grant_type" to "refresh_token", "refresh_token" to rt, "client_id" to CLIENT_ID),
            "application/x-www-form-urlencoded")
        if (r.status == 400 || r.status == 401) throw AuthException("Accesso a Grok scaduto: rifai «Codice».")
        return Parsers.oauthTokens(Http.require(r), Instant.now(), token)
    }
}

object ClaudeService : Service(
    id = "claude",
    loginUrl = "https://claude.ai/login",
    cookieUrl = "https://claude.ai",
    loginCookies = listOf("sessionKey"),
    pasteLabel = "sessionKey",
    pasteHelp = "Sul PC: claude.ai in Chrome → F12 → Application → Cookies → https://claude.ai → copia il valore di «sessionKey».",
    usagePage = "https://claude.ai/settings/usage",
) {
    private const val BASE = "https://claude.ai/api"

    private suspend fun getJson(http: Http, path: String): String {
        val headers = mapOf(
            "Accept" to "application/json",
            "Referer" to "https://claude.ai/settings/usage",
            "Cookie" to http.webViewCookies("https://claude.ai"),
        )
        val r = http.fetch(BASE + path, headers, webViewFallback = true)
        if (r.status == 403) {
            val low = r.body.lowercase()
            if (r.body.trimStart().startsWith("{") && ("auth" in low || "session" in low))
                throw AuthException("Sessione di claude.ai scaduta: rifai l'accesso.")
            throw ApiException("Richiesta bloccata da claude.ai (HTTP 403): riprovo al prossimo controllo.", 403, r.body)
        }
        return Http.require(r, setOf(401))
    }

    override suspend fun fetch(http: Http, store: Store, credential: String): List<UsageWindow> {
        // Il sessionKey (dal login o incollato) va messo tra i cookie della WebView,
        // così la richiesta porta anche gli altri cookie (es. quelli di Cloudflare).
        val key = credential.removePrefix("sessionKey=").trim()
        val host = "https://claude.ai"
        if (!http.webViewCookies(host).contains("sessionKey=$key")) {
            http.setWebViewCookie(host, "sessionKey=$key; Domain=.claude.ai; Path=/; Secure")
        }
        store.cache(id, "org")?.let { org ->
            try {
                return Parsers.claude(getJson(http, "/organizations/$org/usage"))
            } catch (e: AuthException) {
                // organizzazione cambiata? la ricalcolo sotto
            } catch (e: ApiException) {
                // idem
            }
        }
        val org = Parsers.claudeOrgId(getJson(http, "/organizations"))
            ?: throw ApiException("Nessuna organizzazione trovata per questo account")
        store.setCache(id, "org", org)
        return Parsers.claude(getJson(http, "/organizations/$org/usage"))
    }
}

object CodexService : Service(
    id = "codex",
    loginUrl = "https://chatgpt.com/auth/login",
    cookieUrl = "https://chatgpt.com",
    loginCookies = listOf("__Secure-next-auth.session-token"),
    pasteLabel = "Cookie di chatgpt.com",
    pasteHelp = "Più semplice: usa «Codice» (accesso dal browser con un codice). Oppure, se il login nell'app non va " +
        "(con Google non funziona): sul PC apri chatgpt.com in Chrome → F12 → " +
        "Network → ricarica → clicca una richiesta a chatgpt.com → Request Headers → copia tutto il valore di «cookie».",
    usagePage = "https://chatgpt.com/codex/settings/usage",
) {
    override val deviceLogin: DeviceLogin get() = CodexDeviceLogin

    private suspend fun fetchWithToken(http: Http, store: Store, saved: Parsers.OAuthToken): List<UsageWindow> {
        var token = saved
        if (token.expiringSoon(Instant.now())) {
            token = CodexDeviceLogin.refresh(http, token)
            store.updateCredential(id, token.toCredential())
        }
        val headers = mutableMapOf("Accept" to "application/json", "Authorization" to "Bearer ${token.access}")
        token.accountId?.let { headers["ChatGPT-Account-Id"] = it }
        val usage = http.get("https://chatgpt.com/backend-api/wham/usage", headers)
        if (usage.status == 401) throw AuthException("Accesso a ChatGPT scaduto: rifai «Codice».")
        val windows = Parsers.codex(Http.require(usage))
        if (windows.isEmpty()) throw ApiException("Nessun limite di Codex per questo account (serve un piano Plus o superiore).")
        return windows
    }

    override suspend fun fetch(http: Http, store: Store, credential: String): List<UsageWindow> {
        Parsers.oauthCredential(credential)?.let { return fetchWithToken(http, store, it) }
        val viaWebView = credential == WEBVIEW_LOGIN
        val cookie = cookies(http, credential, "__Secure-next-auth.session-token")
        val session = http.fetch("https://chatgpt.com/api/auth/session",
            mapOf("Accept" to "application/json", "Cookie" to cookie), viaWebView)
        val token = Parsers.codexAccessToken(Http.require(session))
            ?: throw AuthException("Sessione di chatgpt.com scaduta: rifai l'accesso.")
        val usage = http.fetch("https://chatgpt.com/backend-api/wham/usage",
            mapOf("Accept" to "application/json", "Cookie" to cookie, "Authorization" to "Bearer $token"), viaWebView)
        val windows = Parsers.codex(Http.require(usage))
        if (windows.isEmpty()) throw ApiException("Nessun limite di Codex per questo account (serve un piano Plus o superiore).")
        return windows
    }
}

object GeminiService : Service(
    id = "gemini",
    loginUrl = null,
    cookieUrl = null,
    loginCookies = emptyList(),
    pasteLabel = "Cookie di gemini.google.com",
    pasteHelp = "Google non permette l'accesso dentro le app. Sul PC apri gemini.google.com/usage in Chrome → F12 → " +
        "Network → ricarica → clicca la richiesta «usage» → Request Headers → copia tutto il valore di «cookie». " +
        "Google rinnova spesso questi cookie: se l'app segnala l'accesso scaduto, ricopiali.",
    usagePage = "https://gemini.google.com/usage",
) {
    private const val SIGNATURE = "2/dWbCvkVB0X16r9NPnxifeOAAqdCqMf0xeWvau9yPy7g="
    private const val VARIABLES = "{\"g1Request\":{\"entrypoints\":[\"GEMINI_USAGE_SETTINGS_UPGRADE_BANNER\"]}," +
        "\"workspaceRequest\":{\"recommendationsForSurfaceRequests\":[]}}"

    override suspend fun fetch(http: Http, store: Store, credential: String): List<UsageWindow> {
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
        val url = "https://gemini.google.com/_/BardChatUi/graphql/schemas/GEMINI_WEB_GRAPHQL/executeQuery" +
            "?query_signature=${enc(SIGNATURE)}&variables=${enc(VARIABLES)}"
        val r = http.get(url, mapOf(
            "Accept" to "application/json",
            "Referer" to "https://gemini.google.com/usage",
            "Cookie" to cookieHeader(credential, "__Secure-1PSID"),
        ))
        return Parsers.gemini(Http.require(r))
    }
}

object CursorService : Service(
    id = "cursor",
    loginUrl = "https://cursor.com/dashboard",
    cookieUrl = "https://cursor.com",
    loginCookies = listOf("WorkosCursorSessionToken"),
    pasteLabel = "WorkosCursorSessionToken",
    pasteHelp = "Sul PC: cursor.com/dashboard in Chrome → F12 → Application → Cookies → https://cursor.com → " +
        "copia il valore di «WorkosCursorSessionToken».",
    usagePage = "https://cursor.com/dashboard",
) {
    override suspend fun fetch(http: Http, store: Store, credential: String): List<UsageWindow> {
        val r = http.fetch("https://cursor.com/api/usage-summary", mapOf(
            "Accept" to "application/json",
            "Referer" to "https://cursor.com/dashboard",
            "Cookie" to cookies(http, credential, "WorkosCursorSessionToken"),
        ), credential == WEBVIEW_LOGIN)
        return Parsers.cursor(Http.require(r))
    }
}

object CopilotService : Service(
    id = "copilot",
    loginUrl = null,
    cookieUrl = null,
    loginCookies = emptyList(),
    pasteLabel = "Token GitHub",
    pasteHelp = "Sul PC installa GitHub CLI, esegui «gh auth login» e poi «gh auth token», e incolla qui il token che stampa.",
    usagePage = "https://github.com/settings/copilot",
) {
    override suspend fun fetch(http: Http, store: Store, credential: String): List<UsageWindow> {
        val r = http.get("https://api.github.com/copilot_internal/user", mapOf(
            "Authorization" to "token ${credential.trim()}",
            "Accept" to "application/json",
            "X-Github-Api-Version" to "2025-04-01",
        ))
        if (r.status == 404) throw ApiException("Questo account GitHub non ha Copilot, o il token non è accettato.", 404)
        return Parsers.copilot(Http.require(r))
    }
}

private val PERPLEXITY_COOKIES = listOf(
    "__Secure-authjs.session-token", "authjs.session-token",
    "__Secure-next-auth.session-token", "next-auth.session-token",
)

object PerplexityService : Service(
    id = "perplexity",
    loginUrl = "https://www.perplexity.ai/",
    cookieUrl = "https://www.perplexity.ai",
    loginCookies = PERPLEXITY_COOKIES,
    pasteLabel = "Cookie o session-token di perplexity.ai",
    pasteHelp = "Sul PC: www.perplexity.ai in Chrome → F12 → Application → Cookies → https://www.perplexity.ai → " +
        "copia il valore di «__Secure-authjs.session-token» (o «__Secure-next-auth.session-token»).",
    usagePage = "https://www.perplexity.ai/account/usage",
) {
    private const val URL = "https://www.perplexity.ai/rest/billing/credits?version=2.18&source=default"

    override suspend fun fetch(http: Http, store: Store, credential: String): List<UsageWindow> {
        val viaWebView = credential == WEBVIEW_LOGIN
        val raw = credential.trim().removePrefix("Cookie:").removePrefix("cookie:").trim()
        val candidates = when {
            viaWebView -> listOf(http.webViewCookies(cookieUrl!!))
            '=' in raw -> listOf(raw)
            else -> {
                val known = store.cache(id, "cookie_name")
                (listOfNotNull(known) + PERPLEXITY_COOKIES.filter { it != known }).map { "$it=$raw" }
            }
        }
        for (cookie in candidates) {
            val r = http.fetch(URL, mapOf(
                "Accept" to "application/json",
                "Cookie" to cookie,
                "Origin" to "https://www.perplexity.ai",
                "Referer" to "https://www.perplexity.ai/account/usage",
            ), viaWebView)
            if (r.status == 401 || r.status == 403) continue
            val body = Http.require(r)
            if (!viaWebView && '=' !in raw) store.setCache(id, "cookie_name", cookie.substringBefore('='))
            return Parsers.perplexity(body)
        }
        throw AuthException("Sessione di Perplexity scaduta: rifai l'accesso o copia di nuovo il cookie.")
    }
}

object GrokService : Service(
    id = "grok",
    loginUrl = null,
    cookieUrl = null,
    loginCookies = emptyList(),
    pasteLabel = "Token della CLI di Grok",
    pasteHelp = "Più semplice: usa «Codice» e accedi dal telefono. In alternativa, sul PC esegui «grok login» con la " +
        "CLI ufficiale di Grok, apri il file .grok/auth.json nella tua cartella utente e copia il valore di «key».",
    usagePage = "https://grok.com",
) {
    override val deviceLogin: DeviceLogin get() = GrokDeviceLogin

    override suspend fun fetch(http: Http, store: Store, credential: String): List<UsageWindow> {
        var oauth = Parsers.oauthCredential(credential)
        if (oauth != null && oauth.expiringSoon(Instant.now())) {
            oauth = GrokDeviceLogin.refresh(http, oauth)
            store.updateCredential(id, oauth.toCredential())
        }
        val headers = mutableMapOf(
            "Authorization" to "Bearer ${oauth?.access ?: credential.trim()}",
            "x-xai-token-auth" to "xai-grok-cli",
            "Accept" to "application/json",
        )
        oauth?.userId?.let { headers["x-userid"] = it }
        val r = http.get("https://cli-chat-proxy.grok.com/v1/billing?format=credits", headers)
        if (r.status == 401 || r.status == 403) throw AuthException(
            if (oauth != null) "Accesso a Grok scaduto: rifai «Codice»."
            else "Il token di Grok è scaduto: esegui «grok login» e ricopialo, oppure usa «Codice».")
        return Parsers.grok(Http.require(r))
    }
}

object MistralService : Service(
    id = "mistral",
    loginUrl = "https://admin.mistral.ai/subscription",
    cookieUrl = "https://admin.mistral.ai",
    loginCookies = listOf("ory_session_"),
    pasteLabel = "Cookie di admin.mistral.ai",
    pasteHelp = "Sul PC: admin.mistral.ai/subscription in Chrome → F12 → Network → ricarica → clicca la richiesta " +
        "«subscription» → Request Headers → copia tutto il valore di «cookie» (deve contenere «ory_session_…»).",
    usagePage = "https://admin.mistral.ai/subscription",
) {
    override suspend fun fetch(http: Http, store: Store, credential: String): List<UsageWindow> {
        // Pagina HTML: niente ripetizione nella WebView (servirebbe solo per risposte JSON).
        val r = http.get("https://admin.mistral.ai/subscription", mapOf(
            "Accept" to "text/html",
            "Cookie" to cookies(http, credential, "ory_session"),
        ))
        if (r.status == 401 || r.status == 403) throw AuthException("Sessione di Mistral scaduta: rifai l'accesso.")
        return Parsers.mistralHtml(Http.require(r))
    }
}

object OpenRouterService : Service(
    id = "openrouter",
    loginUrl = null,
    cookieUrl = null,
    loginCookies = emptyList(),
    pasteLabel = "Chiave API di OpenRouter",
    pasteHelp = "Crea una chiave su openrouter.ai/settings/keys (inizia con sk-or-) e incollala qui. Se le dai un " +
        "limite di spesa l'app ti avvisa quando ti avvicini; il credito prepagato compare se OpenRouter lo concede.",
    usagePage = "https://openrouter.ai/activity",
) {
    override suspend fun fetch(http: Http, store: Store, credential: String): List<UsageWindow> {
        val headers = mapOf("Authorization" to "Bearer ${credential.trim()}", "Accept" to "application/json")
        val key = http.get("https://openrouter.ai/api/v1/key", headers)
        val out = Parsers.openRouterKey(Http.require(key, setOf(401))).toMutableList()
        runCatching { http.get("https://openrouter.ai/api/v1/credits", headers) }.getOrNull()
            ?.takeIf { it.status == 200 }?.let { out += Parsers.openRouterCredits(it.body) }
        if (out.isEmpty()) throw ApiException("La chiave non ha un limite di spesa e il credito non è leggibile: " +
            "imposta un limite alla chiave su openrouter.ai.")
        return out
    }
}

object KimiService : Service(
    id = "kimi",
    loginUrl = null,
    cookieUrl = null,
    loginCookies = emptyList(),
    pasteLabel = "Chiave API di Kimi Code",
    pasteHelp = "Crea una chiave API nella console di Kimi Code (kimi.com/code → API keys) e incollala qui. " +
        "Serve l'abbonamento Kimi Code: le chiavi della piattaforma API di Moonshot non hanno questi limiti.",
    usagePage = "https://www.kimi.com/code",
) {
    override suspend fun fetch(http: Http, store: Store, credential: String): List<UsageWindow> {
        val r = http.get("https://api.kimi.com/coding/v1/usages", mapOf(
            "Authorization" to "Bearer ${credential.trim()}",
            "Accept" to "application/json",
        ))
        if (r.status == 401 || r.status == 403) throw AuthException("La chiave di Kimi Code non è valida o è scaduta.")
        if (r.status == 404) throw ApiException("Kimi non espone i consumi per questa chiave (serve Kimi Code).", 404)
        return Parsers.kimi(Http.require(r))
    }
}

val SERVICES: List<Service> = listOf(
    ClaudeService, CodexService, GeminiService, CursorService, CopilotService,
    PerplexityService, GrokService, MistralService, OpenRouterService, KimiService,
)
