package io.github.yosefchai.mealie.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.yosefchai.mealie.net.MealieJson
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable

/** טוקן API ארוך-טווח שהאפליקציה יצרה בשרת (מופיע אצל המשתמש בפרופיל, תחת טוקנים). */
@Serializable
data class ApiToken(val token: String, val id: Int? = null)

/** קובץ נפרד שלא נכלל בגיבוי (data_extraction_rules מגבה רק את settings). */
private val Context.secureStore: DataStore<Preferences> by preferencesDataStore(name = "secure")

/**
 * טוקנים לווידג'טים ולייבוא ברקע, מוצפנים ב-AES-GCM עם מפתח מ-Android Keystore.
 * security-crypto הוצא משימוש, ולכן ההצפנה ישירה מול Keystore, כפי שהתיעוד ממליץ.
 */
class TokenStore(private val context: Context) {

    private fun key(serverId: String) = stringPreferencesKey("token_$serverId")

    suspend fun get(serverId: String): ApiToken? {
        val raw = context.secureStore.data.first()[key(serverId)] ?: return null
        return runCatching { MealieJson.decodeFromString<ApiToken>(decrypt(raw)) }.getOrNull()
    }

    suspend fun put(serverId: String, token: ApiToken) {
        val enc = encrypt(MealieJson.encodeToString(ApiToken.serializer(), token))
        context.secureStore.edit { it[key(serverId)] = enc }
    }

    suspend fun remove(serverId: String) {
        context.secureStore.edit { it.remove(key(serverId)) }
    }

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
        val out = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String {
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, bytes, 0, IV_BYTES))
        return String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "mealie_api_tokens"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
    }
}
