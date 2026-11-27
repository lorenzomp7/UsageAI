package io.github.lorenzomp7.usageai

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.webkit.CookieManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt

class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var store: Store
    private lateinit var usageBox: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var accountText: TextView
    private lateinit var refreshButton: Button
    private lateinit var t5Field: EditText
    private lateinit var tWeekField: EditText
    private lateinit var paceBox: CheckBox
    private lateinit var marginField: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        Notifier.createChannels(this)
        UsageWorker.schedule(this)
        if (Build.VERSION.SDK_INT >= 33 && !Notifier.canNotify(this)) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        setContentView(buildUi())
        loadSettingsIntoFields()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    @Deprecated("Risultato del login")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_LOGIN && resultCode == RESULT_OK) refresh()
    }

    // ---------- interfaccia ----------

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun text(s: String, sizeSp: Float = 15f, bold: Boolean = false) = TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun section(title: String) = text(title, 18f, bold = true).apply { setPadding(0, dp(24), 0, dp(8)) }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun field(hint: String) = EditText(this).apply {
        this.hint = hint
        inputType = InputType.TYPE_CLASS_TEXT
        setSingleLine()
    }

    private fun buildUi(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(32))
        }
        col.addView(text("UsageAI", 26f, bold = true))
        col.addView(text("Consumo del tuo piano Claude, controllato ogni 15 minuti", 14f).apply { alpha = 0.7f })

        usageBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(usageBox)
        statusText = text("", 13f).apply { alpha = 0.7f; setPadding(0, dp(12), 0, 0) }
        col.addView(statusText)
        refreshButton = button("Aggiorna ora") { refresh() }
        col.addView(refreshButton)

        col.addView(section("Avvisi"))
        col.addView(text("Soglie sessione 5 ore (%)", 14f))
        t5Field = field("es. 80").also { col.addView(it) }
        col.addView(text("Soglie settimanali (%)", 14f))
        tWeekField = field("es. 50, 75, 90").also { col.addView(it) }
        col.addView(text("Più soglie separate da virgola. Vuoto = nessun avviso.", 12f).apply { alpha = 0.6f })
        paceBox = CheckBox(this).apply { text = "Avvisami se consumo la settimana più veloce del ritmo costante" }
        col.addView(paceBox)
        col.addView(text("…oltre quanti punti di anticipo", 14f))
        marginField = field("10").also { col.addView(it) }
        col.addView(button("Salva impostazioni") { saveSettings() })

        col.addView(section("Account"))
        accountText = text("", 14f)
        col.addView(accountText)
        col.addView(button("Accedi a claude.ai") { openLogin(null) })
        col.addView(button("Accedi con il link ricevuto via email") { askLoginLink() })
        col.addView(button("Incolla il sessionKey") { askSessionKey() })
        col.addView(button("Esci e cancella la sessione") { logout() })
        col.addView(
            text(
                "La sessione resta cifrata su questo telefono e viene usata solo per leggere " +
                    "claude.ai/api/…/usage, la stessa fonte della pagina Impostazioni > Utilizzo. " +
                    "L'endpoint non è documentato da Anthropic e potrebbe cambiare.",
                12f,
            ).apply { alpha = 0.6f; setPadding(0, dp(12), 0, 0) }
        )
        return ScrollView(this).apply { addView(col) }
    }

    private fun render() {
        val now = Instant.now()
        usageBox.removeAllViews()
        val windows = store.lastJson?.let { runCatching { parseUsage(it) }.getOrNull() } ?: emptyList()
        if (windows.isEmpty()) {
            usageBox.addView(text("Nessun dato ancora.", 15f).apply { setPadding(0, dp(20), 0, 0) })
        }
        for (w in windows) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(20), 0, 0)
            }
            val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
            head.addView(text(w.label, 15f).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            head.addView(text("${w.utilization.roundToInt()}%", 22f, bold = true))
            row.addView(head)
            row.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                progress = w.utilization.roundToInt().coerceIn(0, 100)
            })
            row.addView(text(formatReset(w.resetsAt, now), 13f).apply { alpha = 0.7f })
            if (w.isWeekly) paceText(w, now)?.let { row.addView(text("Ritmo: $it", 13f).apply { alpha = 0.7f }) }
            usageBox.addView(row)
        }

        val last = if (store.lastCheck > 0) {
            val t = Instant.ofEpochMilli(store.lastCheck).atZone(ZoneId.systemDefault())
            "Ultimo controllo: %02d/%02d %02d:%02d".format(t.dayOfMonth, t.monthValue, t.hour, t.minute)
        } else "Nessun controllo ancora"
        statusText.text = store.lastError?.let { "⚠ $it\n$last" } ?: last
        val notif = if (Notifier.canNotify(this)) "" else "\nNotifiche disattivate: abilitale nelle impostazioni di Android."
        accountText.text = (if (store.sessionKey != null) "Accesso a claude.ai fatto." else "Non hai ancora fatto l'accesso.") + notif
    }

    private fun refresh() {
        refreshButton.isEnabled = false
        refreshButton.text = "Controllo in corso…"
        scope.launch {
            UsageChecker.check(applicationContext)
            refreshButton.isEnabled = true
            refreshButton.text = "Aggiorna ora"
            render()
        }
    }

    private fun loadSettingsIntoFields() {
        val s = store.settings
        t5Field.setText(s.thresholds5h.joinToString(", "))
        tWeekField.setText(s.thresholdsWeek.joinToString(", "))
        paceBox.isChecked = s.paceEnabled
        marginField.setText(s.paceMargin.toString())
    }

    private fun saveSettings() {
        try {
            val margin = marginField.text.toString().trim().toIntOrNull()
                ?: throw IllegalArgumentException("Il margine deve essere un numero intero")
            require(margin in 1..50) { "Il margine sul ritmo va da 1 a 50 punti" }
            store.settings = AlertSettings(
                thresholds5h = parseThresholds(t5Field.text.toString()),
                thresholdsWeek = parseThresholds(tWeekField.text.toString()),
                paceEnabled = paceBox.isChecked,
                paceMargin = margin,
            )
            Toast.makeText(this, "Impostazioni salvate", Toast.LENGTH_SHORT).show()
            loadSettingsIntoFields()
        } catch (e: IllegalArgumentException) {
            Toast.makeText(this, e.message, Toast.LENGTH_LONG).show()
        }
    }

    // ---------- account ----------

    private fun openLogin(url: String?) {
        @Suppress("DEPRECATION")
        startActivityForResult(
            Intent(this, LoginActivity::class.java).putExtra(LoginActivity.EXTRA_URL, url), REQ_LOGIN,
        )
    }

    private fun askLoginLink() {
        val input = EditText(this).apply { hint = "https://claude.ai/…"; setSingleLine() }
        AlertDialog.Builder(this)
            .setTitle("Link di accesso")
            .setMessage("Su claude.ai/login scegli l'email, poi copia qui il link che ti arriva (tieni premuto sul pulsante nell'email → Copia link).")
            .setView(input)
            .setPositiveButton("Apri") { _, _ ->
                val url = input.text.toString().trim()
                if (url.startsWith("https://claude.ai/")) openLogin(url)
                else Toast.makeText(this, "Il link deve iniziare con https://claude.ai/", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun askSessionKey() {
        val input = EditText(this).apply {
            hint = "sk-ant-sid01-…"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle("Incolla il sessionKey")
            .setMessage("Sul PC: claude.ai in Chrome → F12 → Application → Cookies → https://claude.ai → copia il valore di «sessionKey».")
            .setView(input)
            .setPositiveButton("Salva") { _, _ ->
                val key = input.text.toString().trim()
                if (key.length < 20) {
                    Toast.makeText(this, "Il valore sembra troppo corto", Toast.LENGTH_LONG).show()
                } else {
                    store.clearAccount()
                    store.sessionKey = key
                    refresh()
                }
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun logout() {
        store.clearAccount()
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        UsageWidget.updateAll(this)
        render()
    }

    companion object {
        private const val REQ_LOGIN = 10
    }
}
