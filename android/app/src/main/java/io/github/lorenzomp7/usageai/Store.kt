package io.github.lorenzomp7.usageai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Impostazioni e stato in SharedPreferences; le credenziali cifrate con una chiave del Keystore. */
class Store(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("usageai", Context.MODE_PRIVATE)

    init {
        // Versione 1 (solo Claude): il sessionKey era salvato in "session_key_enc".
        prefs.getString("session_key_enc", null)?.let { old ->
            if (!prefs.contains("cred_claude")) prefs.edit().putString("cred_claude", old).apply()
            prefs.edit().remove("session_key_enc").remove("last_json").apply()
        }
    }

    var settings: AlertSettings
        get() = AlertSettings(
            shortEnabled = prefs.getBoolean("short_on", true),
            longEnabled = prefs.getBoolean("long_on", true),
            thresholdsShort = intList(prefs.getString("t_short", null) ?: prefs.getString("t5h", null)) ?: listOf(80),
            thresholdsLong = intList(prefs.getString("t_long", null) ?: prefs.getString("tweek", null)) ?: listOf(50, 75, 90),
            paceEnabled = prefs.getBoolean("pace", true),
            paceMargin = prefs.getInt("pace_margin", 10),
            dailyEnabled = prefs.getBoolean("daily", true),
        )
        set(v) {
            prefs.edit()
                .putBoolean("short_on", v.shortEnabled)
                .putBoolean("long_on", v.longEnabled)
                .putString("t_short", v.thresholdsShort.joinToString(","))
                .putString("t_long", v.thresholdsLong.joinToString(","))
                .putBoolean("pace", v.paceEnabled)
                .putInt("pace_margin", v.paceMargin)
                .putBoolean("daily", v.dailyEnabled)
                .apply()
        }

    var state: Map<String, WindowState>
        get() = stateFromJson(prefs.getString("state", null))
        set(v) { prefs.edit().putString("state", stateToJson(v)).apply() }

    /** Ultime finestre lette, di tutti i servizi (per schermata e widget). */
    var lastWindows: List<UsageWindow>
        get() = windowsFromJson(prefs.getString("last_windows", null))
        set(v) { prefs.edit().putString("last_windows", windowsToJson(v)).apply() }

    /** Ultimo errore per servizio. */
    var errors: Map<String, String>
        get() = mapFromJson(prefs.getString("errors", null))
        set(v) { prefs.edit().putString("errors", mapToJson(v)).apply() }

    var lastCheck: Long
        get() = prefs.getLong("last_check", 0L)
        set(v) { prefs.edit().putLong("last_check", v).apply() }

    /** Interruttore generale delle notifiche. */
    var notificationsEnabled: Boolean
        get() = prefs.getBoolean("notif_on", true)
        set(v) { prefs.edit().putBoolean("notif_on", v).apply() }

    /** Avviso quando l'accesso a un servizio scade. */
    var authAlertsEnabled: Boolean
        get() = prefs.getBoolean("auth_alerts_on", true)
        set(v) { prefs.edit().putBoolean("auth_alerts_on", v).apply() }

    /** Controllo automatico ogni 15 minuti in background. */
    var backgroundEnabled: Boolean
        get() = prefs.getBoolean("background_on", true)
        set(v) { prefs.edit().putBoolean("background_on", v).apply() }

    /** Servizi spenti: non vengono controllati, ma la credenziale resta. */
    var disabledServices: Set<String>
        get() = prefs.getStringSet("disabled_services", emptySet())?.toSet() ?: emptySet()
        set(v) { prefs.edit().putStringSet("disabled_services", v).apply() }

    fun isEnabled(service: String) = service !in disabledServices
    fun setEnabled(service: String, on: Boolean) {
        disabledServices = if (on) disabledServices - service else disabledServices + service
    }

    var authWarned: Set<String>
        get() = prefs.getStringSet("auth_warned_set", emptySet()) ?: emptySet()
        set(v) { prefs.edit().putStringSet("auth_warned_set", v).apply() }

    fun credential(provider: String): String? =
        prefs.getString("cred_$provider", null)?.let { runCatching { Crypto.decrypt(it) }.getOrNull() }

    fun setCredential(provider: String, value: String?) {
        val e = prefs.edit()
        if (value.isNullOrBlank()) e.remove("cred_$provider") else e.putString("cred_$provider", Crypto.encrypt(value.trim()))
        // credenziale cambiata: dimentico cache, errori e finestre di quel servizio
        prefs.all.keys.filter { it.startsWith("cache_${provider}_") }.forEach { e.remove(it) }
        e.apply()
        errors = errors - provider
        authWarned = authWarned - provider
        lastWindows = lastWindows.filter { it.provider != provider }
    }

    /** Sostituisce la credenziale (es. token rinnovato) senza dimenticare stato, errori e finestre. */
    fun updateCredential(provider: String, value: String) {
        prefs.edit().putString("cred_$provider", Crypto.encrypt(value.trim())).apply()
    }

    fun cache(provider: String, key: String): String? = prefs.getString("cache_${provider}_$key", null)
    fun setCache(provider: String, key: String, value: String?) {
        prefs.edit().apply { if (value == null) remove("cache_${provider}_$key") else putString("cache_${provider}_$key", value) }.apply()
    }

    private fun intList(s: String?): List<Int>? =
        if (s == null) null else s.split(',').mapNotNull { it.trim().toIntOrNull() }
}

private object Crypto {
    private const val ALIAS = "usageai_session_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val out = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    fun decrypt(encoded: String): String {
        val data = Base64.decode(encoded, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data, 0, 12))
        return String(cipher.doFinal(data, 12, data.size - 12), Charsets.UTF_8)
    }
}
