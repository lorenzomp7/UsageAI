package io.github.lorenzomp7.usageai

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
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
    private lateinit var servicesBox: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var refreshButton: Button
    private lateinit var tShortField: EditText
    private lateinit var tLongField: EditText
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

    // ---------- mattoncini dell'interfaccia ----------

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun text(s: String, sizeSp: Float = 15f, bold: Boolean = false, dim: Float = 1f) = TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        alpha = dim
    }

    private fun section(title: String) = text(title, 18f, bold = true).apply { setPadding(0, dp(24), 0, dp(4)) }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun smallButton(label: String, onClick: () -> Unit) = button(label, onClick).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
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
        col.addView(text("Consumo dei tuoi abbonamenti AI, controllato ogni 15 minuti", 14f, dim = 0.7f))

        statusText = text("", 13f, dim = 0.7f).apply { setPadding(0, dp(8), 0, 0) }
        col.addView(statusText)
        refreshButton = button("Aggiorna ora") { refresh() }
        col.addView(refreshButton)

        servicesBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(servicesBox)

        col.addView(section("Avvisi"))
        col.addView(text("Valgono per tutti i servizi.", 12f, dim = 0.6f))
        col.addView(text("Soglie sessioni brevi, es. 5 ore (%)", 14f))
        tShortField = field("es. 80").also { col.addView(it) }
        col.addView(text("Soglie periodi lunghi, settimana o mese (%)", 14f))
        tLongField = field("es. 50, 75, 90").also { col.addView(it) }
        col.addView(text("Più soglie separate da virgola. Vuoto = nessun avviso.", 12f, dim = 0.6f))
        paceBox = CheckBox(this).apply { text = "Avvisami se consumo un periodo lungo più veloce del ritmo costante" }
        col.addView(paceBox)
        col.addView(text("…oltre quanti punti di anticipo", 14f))
        marginField = field("10").also { col.addView(it) }
        col.addView(button("Salva impostazioni") { saveSettings() })

        col.addView(text(
            "Le credenziali restano cifrate su questo telefono e vanno solo al sito del servizio. " +
                "Gli indirizzi usati per leggere il consumo non sono documentati dai produttori e potrebbero cambiare.",
            12f, dim = 0.6f,
        ).apply { setPadding(0, dp(16), 0, 0) })
        return ScrollView(this).apply { addView(col) }
    }

    private fun render() {
        val now = Instant.now()
        val windows = store.lastWindows
        val errors = store.errors
        servicesBox.removeAllViews()

        for (s in SERVICES) {
            val credential = store.credential(s.id)
            servicesBox.addView(section(s.name))
            val mine = windows.filter { it.provider == s.id }
            if (credential == null) {
                servicesBox.addView(text("Non configurato.", 14f, dim = 0.7f))
            } else if (mine.isEmpty() && s.id !in errors) {
                servicesBox.addView(text("In attesa del primo controllo.", 14f, dim = 0.7f))
            }
            for (w in mine) {
                val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, 0) }
                val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
                head.addView(text(w.label, 15f).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                head.addView(text("${w.utilization.roundToInt()}%", 20f, bold = true))
                row.addView(head)
                row.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = 100
                    progress = w.utilization.roundToInt().coerceIn(0, 100)
                })
                row.addView(text(formatReset(w.resetsAt, now), 13f, dim = 0.7f))
                if (w.isLong) paceText(w, now)?.let { row.addView(text("Ritmo: $it", 13f, dim = 0.7f)) }
                servicesBox.addView(row)
            }
            errors[s.id]?.let { servicesBox.addView(text("⚠ $it", 13f).apply { setPadding(0, dp(6), 0, 0) }) }

            val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(4), 0, 0) }
            if (s.loginUrl != null) buttons.addView(smallButton("Accedi") { openLogin(s, null) })
            buttons.addView(smallButton("Incolla") { askCredential(s) })
            if (credential != null) buttons.addView(smallButton("Rimuovi") { remove(s) })
            servicesBox.addView(buttons)
            val links = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            links.addView(smallButton("Pagina consumo") { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(s.usagePage))) })
            if (s.id == ClaudeService.id) links.addView(smallButton("Link email") { askLoginLink(s) })
            servicesBox.addView(links)
        }

        val last = if (store.lastCheck > 0) {
            val t = Instant.ofEpochMilli(store.lastCheck).atZone(ZoneId.systemDefault())
            "Ultimo controllo: %02d/%02d %02d:%02d".format(t.dayOfMonth, t.monthValue, t.hour, t.minute)
        } else "Nessun controllo ancora"
        val notif = if (Notifier.canNotify(this)) "" else "\nNotifiche disattivate: abilitale nelle impostazioni di Android."
        statusText.text = last + notif
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
        tShortField.setText(s.thresholdsShort.joinToString(", "))
        tLongField.setText(s.thresholdsLong.joinToString(", "))
        paceBox.isChecked = s.paceEnabled
        marginField.setText(s.paceMargin.toString())
    }

    private fun saveSettings() {
        try {
            val margin = marginField.text.toString().trim().toIntOrNull()
                ?: throw IllegalArgumentException("Il margine deve essere un numero intero")
            require(margin in 1..50) { "Il margine sul ritmo va da 1 a 50 punti" }
            store.settings = AlertSettings(
                thresholdsShort = parseThresholds(tShortField.text.toString()),
                thresholdsLong = parseThresholds(tLongField.text.toString()),
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

    private fun openLogin(s: Service, url: String?) {
        @Suppress("DEPRECATION")
        startActivityForResult(
            Intent(this, LoginActivity::class.java)
                .putExtra(LoginActivity.EXTRA_SERVICE, s.id)
                .putExtra(LoginActivity.EXTRA_URL, url),
            REQ_LOGIN,
        )
    }

    private fun askLoginLink(s: Service) {
        val input = EditText(this).apply { hint = "https://claude.ai/…"; setSingleLine() }
        AlertDialog.Builder(this)
            .setTitle("Link di accesso")
            .setMessage("Su claude.ai/login scegli l'email, poi copia qui il link che ti arriva (tieni premuto sul pulsante nell'email → Copia link).")
            .setView(input)
            .setPositiveButton("Apri") { _, _ ->
                val url = input.text.toString().trim()
                if (url.startsWith("https://claude.ai/")) openLogin(s, url)
                else Toast.makeText(this, "Il link deve iniziare con https://claude.ai/", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun askCredential(s: Service) {
        val input = EditText(this).apply {
            hint = s.pasteLabel
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle("${s.name}: incolla ${s.pasteLabel}")
            .setMessage(s.pasteHelp)
            .setView(input)
            .setPositiveButton("Salva") { _, _ ->
                val value = input.text.toString().trim()
                if (value.length < 10) {
                    Toast.makeText(this, "Il valore sembra troppo corto", Toast.LENGTH_LONG).show()
                } else {
                    store.setCredential(s.id, value)
                    refresh()
                }
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun remove(s: Service) {
        AlertDialog.Builder(this)
            .setTitle("Rimuovere ${s.name}?")
            .setMessage("L'app smette di controllarlo e dimentica la sua credenziale.")
            .setPositiveButton("Rimuovi") { _, _ ->
                store.setCredential(s.id, null)
                UsageWidget.updateAll(this)
                render()
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    companion object {
        private const val REQ_LOGIN = 10
    }
}
