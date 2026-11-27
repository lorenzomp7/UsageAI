package io.github.lorenzomp7.usageai

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast

/**
 * Accesso a un servizio dentro una WebView. Appena compare il cookie di sessione salva la
 * credenziale (cifrata) e torna alla schermata principale.
 *
 * Extra: EXTRA_SERVICE (id del servizio), EXTRA_URL (es. link di accesso ricevuto via email).
 */
class LoginActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var service: Service
    private var done = false
    private var initialCookie: String? = null   // cookie già presente prima del login (forse scaduto)

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        service = SERVICES.firstOrNull { it.id == intent.getStringExtra(EXTRA_SERVICE) } ?: run { finish(); return }
        val loginUrl = service.loginUrl ?: run { finish(); return }
        title = "Accedi a ${service.name}"
        web = WebView(this)
        setContentView(web)

        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(web, true)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                checkForSession(url ?: "")
            }
        }

        val host = service.cookieUrl ?: loginUrl
        initialCookie = sessionCookie()
        val start = intent.getStringExtra(EXTRA_URL)?.takeIf { it.startsWith("$host/") } ?: loginUrl
        web.loadUrl(start)
        Toast.makeText(
            this,
            "Accedi con email e password (o GitHub/Microsoft se il sito li offre). L'accesso con Google " +
                "non funziona dentro le app: in quel caso usa «Incolla».",
            Toast.LENGTH_LONG,
        ).show()
    }

    private fun sessionCookie(): String? {
        val host = service.cookieUrl ?: return null
        val cookies = CookieManager.getInstance().getCookie(host) ?: return null
        // Il nome può essere un prefisso (es. «ory_session_» di Mistral) o diviso in pezzi «.0», «.1».
        return cookies.split(";").map { it.trim() }.firstOrNull { c ->
            service.loginCookies.any { name -> c.startsWith(name) && '=' in c }
        }
    }

    private fun checkForSession(url: String) {
        if (done) return
        val match = sessionCookie() ?: return
        // Un cookie già presente all'apertura vale solo se il sito non mostra più la pagina di accesso.
        val onLoginPage = listOf("/login", "/auth", "signin", "sign-in").any { it in url.lowercase() }
        if (match == initialCookie && onLoginPage) return
        done = true
        CookieManager.getInstance().flush()
        // Per Claude salvo il sessionKey (funziona anche se i cookie della WebView vengono cancellati);
        // per gli altri uso direttamente i cookie della WebView, che il sito rinnova da solo.
        val credential = if (service.id == ClaudeService.id) match.substringAfter("=") else WEBVIEW_LOGIN
        Store(this).setCredential(service.id, credential)
        Toast.makeText(this, "Accesso a ${service.name} fatto", Toast.LENGTH_SHORT).show()
        setResult(RESULT_OK)
        finish()
    }

    @Deprecated("Gestione del tasto Indietro dentro la WebView")
    override fun onBackPressed() {
        if (::web.isInitialized && web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        if (::web.isInitialized) web.destroy()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_SERVICE = "service"
        const val EXTRA_URL = "url"
    }
}
