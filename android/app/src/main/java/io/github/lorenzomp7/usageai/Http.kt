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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume

/**
 * Richieste HTTP per tutti i servizi.
 *
 * Se un sito risponde con una pagina HTML (di solito la verifica anti-bot di Cloudflare) e la
 * richiesta usa i cookie della WebView, la ripete dentro una WebView invisibile, che per il sito
 * è un browser vero.
 */
class Http(private val context: Context) {
    class Resp(val status: Int, val body: String)

    suspend fun webViewCookies(url: String): String = withContext(Dispatchers.Main) {
        CookieManager.getInstance().getCookie(url) ?: ""
    }

    suspend fun setWebViewCookie(url: String, cookie: String) = withContext(Dispatchers.Main) {
        CookieManager.getInstance().setCookie(url, cookie)
        CookieManager.getInstance().flush()
    }

    private suspend fun userAgent(): String = withContext(Dispatchers.Main) { WebSettings.getDefaultUserAgent(context) }

    /** GET semplice. Non lancia per i codici HTTP, solo per la rete. */
    suspend fun get(url: String, headers: Map<String, String>): Resp = request("GET", url, headers, null)

    /** POST con corpo (JSON o modulo). Non lancia per i codici HTTP, solo per la rete. */
    suspend fun post(url: String, headers: Map<String, String>, body: String, contentType: String): Resp =
        request("POST", url, headers + ("Content-Type" to contentType), body)

    private suspend fun request(method: String, url: String, headers: Map<String, String>, body: String?): Resp {
        val ua = userAgent()
        return withContext(Dispatchers.IO) {
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 20_000
                conn.readTimeout = 20_000
                conn.instanceFollowRedirects = true
                conn.requestMethod = method
                conn.setRequestProperty("User-Agent", ua)
                headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
                if (body != null) {
                    conn.doOutput = true
                    conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
                val status = conn.responseCode
                val stream = if (status >= 400) conn.errorStream else conn.inputStream
                Resp(status, stream?.bufferedReader()?.use { it.readText() } ?: "")
            } catch (e: IOException) {
                throw ApiException("Rete non disponibile")
            } finally {
                conn.disconnect()
            }
        }
    }

    /**
     * GET che si aspetta dati. Con [webViewFallback] una risposta HTML viene ripetuta nella WebView
     * (che manda i suoi cookie; [headers] senza "Cookie" vengono aggiunti alla richiesta).
     */
    suspend fun fetch(url: String, headers: Map<String, String>, webViewFallback: Boolean): Resp {
        val r = get(url, headers)
        if (webViewFallback && r.body.trimStart().startsWith("<")) {
            webViewGet(url, headers - "Cookie")?.let { return Resp(200, it) }
        }
        return r
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun webViewGet(url: String, headers: Map<String, String>): String? = withContext(Dispatchers.Main) {
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
                            if ((text.startsWith("{") || text.startsWith("[") || text.startsWith(")]}'")) && cont.isActive) {
                                cont.resume(text)
                                main.post { view.destroy() }
                            }
                            // altrimenti è la pagina di verifica: aspetto il redirect
                        }
                    }
                }
                cont.invokeOnCancellation { main.post { wv.destroy() } }
                wv.loadUrl(url, headers + ("Accept" to "application/json"))
            }
        }
    }

    companion object {
        /** Controlla il codice HTTP come in windows/usageai/providers/base.py e restituisce il corpo. */
        fun require(r: Resp, authCodes: Set<Int> = setOf(401, 403)): String {
            when {
                r.status in authCodes -> throw AuthException("Accesso scaduto o non valido: rifai l'accesso o aggiorna la credenziale.")
                r.status == 429 -> throw ApiException("Troppe richieste (HTTP 429): riprovo al prossimo controllo.", 429, r.body)
                r.status >= 500 -> throw ApiException("Servizio non disponibile (HTTP ${r.status}).", r.status, r.body)
                r.status != 200 -> throw ApiException("Risposta inattesa (HTTP ${r.status}).", r.status, r.body)
            }
            return r.body
        }
    }
}
