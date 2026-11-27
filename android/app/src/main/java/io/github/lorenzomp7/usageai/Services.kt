package io.github.lorenzomp7.usageai

import java.net.URLEncoder

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
    val loginCookie: String?,   // cookie che indica che l'accesso è fatto
    val pasteLabel: String,
    val pasteHelp: String,
    val usagePage: String,
) {
    val name: String get() = PROVIDER_NAMES[id] ?: id
    abstract suspend fun fetch(http: Http, store: Store, credential: String): List<UsageWindow>

    protected suspend fun cookies(http: Http, credential: String, default: String): String {
        val url = cookieUrl
        return if (credential == WEBVIEW_LOGIN && url != null) http.webViewCookies(url) else cookieHeader(credential, default)
    }
}

object ClaudeService : Service(
    id = "claude",
    loginUrl = "https://claude.ai/login",
    cookieUrl = "https://claude.ai",
    loginCookie = "sessionKey",
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
    loginCookie = "__Secure-next-auth.session-token",
    pasteLabel = "Cookie di chatgpt.com",
    pasteHelp = "Se il login nell'app non va (con Google non funziona): sul PC apri chatgpt.com in Chrome → F12 → " +
        "Network → ricarica → clicca una richiesta a chatgpt.com → Request Headers → copia tutto il valore di «cookie».",
    usagePage = "https://chatgpt.com/codex/settings/usage",
) {
    override suspend fun fetch(http: Http, store: Store, credential: String): List<UsageWindow> {
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
    loginCookie = null,
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
    loginCookie = "WorkosCursorSessionToken",
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
    loginCookie = null,
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

val SERVICES: List<Service> = listOf(ClaudeService, CodexService, GeminiService, CursorService, CopilotService)
