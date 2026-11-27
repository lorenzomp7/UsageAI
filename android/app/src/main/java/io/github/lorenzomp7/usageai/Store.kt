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

/** Impostazioni e stato in SharedPreferences; il sessionKey cifrato con una chiave del Keystore. */
class Store(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("usageai", Context.MODE_PRIVATE)

    var settings: AlertSettings
        get() = AlertSettings(
            thresholds5h = intList(prefs.getString("t5h", null)) ?: listOf(80),
            thresholdsWeek = intList(prefs.getString("tweek", null)) ?: listOf(50, 75, 90),
            paceEnabled = prefs.getBoolean("pace", true),
            paceMargin = prefs.getInt("pace_margin", 10),
        )
        set(v) {
            prefs.edit()
                .putString("t5h", v.thresholds5h.joinToString(","))
                .putString("tweek", v.thresholdsWeek.joinToString(","))
                .putBoolean("pace", v.paceEnabled)
                .putInt("pace_margin", v.paceMargin)
                .apply()
        }

    var state: Map<String, WindowState>
        get() = stateFromJson(prefs.getString("state", null))
        set(v) { prefs.edit().putString("state", stateToJson(v)).apply() }

    var orgId: String?
        get() = prefs.getString("org_id", null)
        set(v) { prefs.edit().putString("org_id", v).apply() }

    var lastJson: String?
        get() = prefs.getString("last_json", null)
        set(v) { prefs.edit().putString("last_json", v).apply() }

    var lastCheck: Long
        get() = prefs.getLong("last_check", 0L)
        set(v) { prefs.edit().putLong("last_check", v).apply() }

    var lastError: String?
        get() = prefs.getString("last_error", null)
        set(v) { prefs.edit().putString("last_error", v).apply() }

    var authWarned: Boolean
        get() = prefs.getBoolean("auth_warned", false)
        set(v) { prefs.edit().putBoolean("auth_warned", v).apply() }

    var sessionKey: String?
        get() = prefs.getString("session_key_enc", null)?.let { runCatching { Crypto.decrypt(it) }.getOrNull() }
        set(v) {
            if (v.isNullOrBlank()) prefs.edit().remove("session_key_enc").apply()
            else prefs.edit().putString("session_key_enc", Crypto.encrypt(v.trim())).apply()
        }

    /** Dimentica la sessione e i dati legati all'account. */
    fun clearAccount() {
        prefs.edit().remove("session_key_enc").remove("org_id").remove("last_json")
            .remove("state").remove("auth_warned").putLong("last_check", 0L).apply()
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
