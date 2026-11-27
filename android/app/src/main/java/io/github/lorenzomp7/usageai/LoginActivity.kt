package io.github.lorenzomp7.usageai

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast

/**
 * Accesso a claude.ai in una WebView. Appena compare il cookie sessionKey lo salva (cifrato)
 * e torna alla schermata principale.
 *
 * Extra EXTRA_URL: un link di accesso ricevuto via email da aprire qui dentro.
 */
class LoginActivity : Activity() {
    private lateinit var web: WebView
    private var done = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Accedi a claude.ai"
        web = WebView(this)
        setContentView(web)

        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(web, true)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                checkForSession()
            }
        }

        val start = intent.getStringExtra(EXTRA_URL)?.takeIf { it.startsWith("https://claude.ai/") }
            ?: "https://claude.ai/login"
        web.loadUrl(start)
        Toast.makeText(
            this,
            "Accedi con l'email. L'accesso con Google non funziona dentro le app: se lo usi, " +
                "incolla il sessionKey dalla schermata principale.",
            Toast.LENGTH_LONG,
        ).show()
    }

    private fun checkForSession() {
        if (done) return
        val key = ClaudeApi.sessionKeyFromCookies(CookieManager.getInstance().getCookie("https://claude.ai"))
            ?: return
        done = true
        CookieManager.getInstance().flush()
        val store = Store(this)
        store.clearAccount()
        store.sessionKey = key
        Toast.makeText(this, "Accesso fatto", Toast.LENGTH_SHORT).show()
        setResult(RESULT_OK)
        finish()
    }

    @Deprecated("Gestione del tasto Indietro dentro la WebView")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "url"
    }
}
