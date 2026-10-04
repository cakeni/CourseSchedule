package com.courseschedule.ui.importdata

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Device-local encrypted credentials; noBackupFilesDir excludes backup and device transfer. */
internal class AiWebCredentials(context: Context) {
    private val folder = context.noBackupFilesDir
    private val preferences = context.getSharedPreferences("academic_ai_provider", Context.MODE_PRIVATE)

    fun lastProvider(): AiWebProvider = runCatching {
        AiWebProvider.valueOf(preferences.getString("provider", AiWebProvider.DEEPSEEK.name).orEmpty())
    }.getOrDefault(AiWebProvider.DEEPSEEK)

    private fun file(provider: AiWebProvider) = AtomicFile(File(folder, "academic_ai_${provider.name}.json"))

    fun load(provider: AiWebProvider): String = synchronized(LOCK) {
        val file = file(provider)
        val stored = try {
            JSONObject(file.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() })
        } catch (_: FileNotFoundException) { return@synchronized "" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val key = keyStore().getKey(KEY_ALIAS, null) as? SecretKey
            ?: error("Saved credential encryption key is unavailable")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, Base64.decode(stored.getString("iv"), Base64.NO_WRAP)))
        cipher.updateAAD(provider.name.toByteArray(Charsets.UTF_8))
        AiWebScheduleRecognizer.normalizeKey(
            String(cipher.doFinal(Base64.decode(stored.getString("secret"), Base64.NO_WRAP)), Charsets.UTF_8), provider
        )
    }

    fun save(provider: AiWebProvider, apiKey: String) = synchronized(LOCK) {
        val key = AiWebScheduleRecognizer.normalizeKey(apiKey, provider)
        val store = keyStore()
        val encryptionKey = store.getKey(KEY_ALIAS, null) as? SecretKey ?: KeyGenerator
            .getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256).build())
            }.generateKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey)
        cipher.updateAAD(provider.name.toByteArray(Charsets.UTF_8))
        val secret = cipher.doFinal(key.toByteArray(Charsets.UTF_8))
        val stored = JSONObject().put("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .put("secret", Base64.encodeToString(secret, Base64.NO_WRAP)).toString()
        val file = file(provider)
        val stream = file.startWrite()
        try {
            stream.write(stored.toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
        preferences.edit().putString("provider", provider.name).apply()
    }

    fun remove(provider: AiWebProvider) = synchronized(LOCK) {
        val file = file(provider)
        file.delete()
        check(listOf("", ".bak", ".new").none { File(file.baseFile.path + it).exists() })
    }

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private companion object {
        const val KEY_ALIAS = "academic_ai_credentials_v1"
        val LOCK = Any()
    }
}
