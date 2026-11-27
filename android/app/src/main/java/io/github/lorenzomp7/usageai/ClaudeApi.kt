package io.github.lorenzomp7.usageai

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume

class AuthException(message: String) : Exception(message)
class ApiException(message: String) : Exception(message)

/**
 * Legge il consumo da claude.ai con lo stesso endpoint della pagina Impostazioni > Utilizzo.
 * Non è un'API documentata: se Anthropic la cambia, va aggiornato qui.
 *
 * Prima prova una richiesta HTTP normale con i cookie della WebView; se Cloudflare la blocca,
 * ripete la richiesta dentro una WebView invisibile (che per Cloudflare è un browser vero).
 */
class ClaudeApi(private val context: Context, private val store: Store) {

    suspend fun fetchUsageJson(): String {
        val key = store.sessionKey ?: throw AuthException("Accesso a claude.ai non ancora fatto")
        ensureCookie(key)
        val cached = store.orgId
        if (cached != null) {
            try {
                return getJson("/organizations/$cached/usage")
            } catch (e: AuthException) {
                // forse è cambiata l'organizzazione: ricalcolo sotto
            } catch (e: ApiException) {
                // idem
            }
        }
        val org = resolveOrg()
        return getJson("/organizations/$org/usage")
    }

    private suspend fun resolveOrg(): String {
        val id = pickOrgId(getJson("/organizations"))
            ?: throw ApiException("Nessuna organizzazione trovata per questo account")
        store.orgId = id
        return id
    }

    private suspend fun ensureCookie(key: String) = withContext(Dispatchers.Main) {
        val cm = CookieManager.getInstance()
        val current = cm.getCookie(BASE_HOST) ?: ""
        if (!current.contains("sessionKey=$key")) {
            cm.setCookie(BASE_HOST, "sessionKey=$key; Domain=.claude.ai; Path=/; Secure")
            cm.flush()
        }
    }

    private suspend fun getJson(path: String): String {
        val url = "$BASE_HOST/api$path"
        val (cookies, ua) = withContext(Dispatchers.Main) {
            (CookieManager.getInstance().getCookie(BASE_HOST) ?: "") to WebSettings.getDefaultUserAgent(context)
        }
        val (status, body) = withContext(Dispatchers.IO) { httpGet(url, cookies, ua) }
        val text = body.trim()
        val isJson = text.startsWith("{") || text.startsWith("[")

        if (status == 200 && isJson) return text
        if (status == 401) throw AuthException(EXPIRED)
        if (isJson && isAuthError(text)) throw AuthException(EXPIRED)
        if (isJson) throw ApiException("claude.ai ha risposto con un errore (HTTP $status). Riprovo al prossimo controllo.")

        // Risposta HTML (Cloudflare) o inattesa: riprovo con la WebView.
        val viaWebView = webViewGet(url) ?: throw ApiException("claude.ai non risponde (HTTP $status). Riprovo al prossimo controllo.")
        if (isAuthError(viaWebView)) throw AuthException(EXPIRED)
        if (viaWebView.startsWith("{") && Json.parseToJsonElement(viaWebView).let { it is JsonObject && "error" in it }) {
            throw ApiException("claude.ai ha risposto con un errore. Riprovo al prossimo controllo.")
        }
        return viaWebView
    }

    private fun httpGet(url: String, cookies: String, ua: String): Pair<Int, String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 20_000
            conn.readTimeout = 20_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Cookie", cookies)
            conn.setRequestProperty("User-Agent", ua)
            conn.setRequestProperty("Referer", "$BASE_HOST/settings/usage")
            val status = conn.responseCode
            val stream = if (status >= 400) conn.errorStream else conn.inputStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            return status to body
        } catch (e: IOException) {
            throw IOException("Rete non disponibile", e)
        } finally {
            conn.disconnect()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun webViewGet(url: String): String? = withContext(Dispatchers.Main) {
        withTimeoutOrNull(30_000L) {
            suspendCancellableCoroutine<String> { cont ->
                val main = Handler(Looper.getMainLooper())
                val wv = WebView(context.applicationContext)
                wv.settings.javaScriptEnabled = true
                wv.settings.domStorageEnabled = true
                wv.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, finishedUrl: String?) {
                        view.evaluateJavascript(
                            "(function(){return document.body ? document.body.innerText : ''})()"
                        ) { raw ->
                            val text = runCatching {
                                (Json.parseToJsonElement(raw ?: "null") as? JsonPrimitive)?.contentOrNull
                            }.getOrNull()?.trim() ?: ""
                            if ((text.startsWith("{") || text.startsWith("[")) && cont.isActive) {
                                cont.resume(text)
                                main.post { view.destroy() }
                            }
                            // altrimenti è la pagina di verifica di Cloudflare: aspetto il redirect
                        }
                    }
                }
                cont.invokeOnCancellation { main.post { wv.destroy() } }
                wv.loadUrl(url, mapOf("Accept" to "application/json"))
            }
        }
    }

    private fun isAuthError(json: String): Boolean {
        val obj = runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull() ?: return false
        val err = obj["error"] ?: return false
        val text = err.toString().lowercase()
        return listOf("authentication", "unauthorized", "session", "login").any { it in text }
    }

    companion object {
        const val BASE_HOST = "https://claude.ai"
        private const val EXPIRED = "Sessione scaduta: rifai l'accesso a claude.ai"

        /** Estrae il sessionKey dalla stringa dei cookie della WebView. */
        fun sessionKeyFromCookies(cookies: String?): String? =
            cookies?.split(";")?.map { it.trim() }
                ?.firstOrNull { it.startsWith("sessionKey=") }
                ?.removePrefix("sessionKey=")
                ?.takeIf { it.isNotBlank() }
    }
}
