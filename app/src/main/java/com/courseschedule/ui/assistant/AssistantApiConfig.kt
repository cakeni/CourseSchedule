package com.courseschedule.ui.assistant

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.google.gson.Gson
import java.io.File
import java.net.URI
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal data class AssistantApiConfig(
    val baseUrl: String = "https://api.openai.com/v1",
    val model: String = "gpt-4o-mini",
    val apiKey: String = "",
    val jsonMode: Boolean = false
) {
    fun endpoint(): String {
        val uri = runCatching { URI(baseUrl.trim().trimEnd('/')) }.getOrNull()
        require(uri != null && uri.scheme == "https" && !uri.host.isNullOrBlank() &&
            uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null) {
            "请输入有效的 HTTPS API 地址，不要包含账号、查询参数或片段"
        }
        val url = uri.toASCIIString()
        return if (uri.path.orEmpty().endsWith("/chat/completions")) url else "$url/chat/completions"
    }

    fun validate() {
        endpoint()
        require(model.isNotBlank() && model.length <= 120 && model.none { it.isISOControl() }) {
            "请填写有效的模型名称"
        }
        require(apiKey.isNotBlank() && apiKey.length <= 4096 && apiKey.none { it.isISOControl() }) {
            "请填写有效的 API Key"
        }
    }
}

/** Device-bound encryption; noBackupFilesDir also excludes credentials from device migration. */
internal class AssistantConfigStore(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "course-assistant-api"))

    fun load(): AssistantApiConfig {
        if (!file.baseFile.exists()) return AssistantApiConfig()
        val bytes = file.readFully()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return Gson().fromJson(
            cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8),
            AssistantApiConfig::class.java
        ).also { it.endpoint() }
    }

    fun save(config: AssistantApiConfig, rememberKey: Boolean) {
        config.validate()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val stored = if (rememberKey) config else config.copy(apiKey = "")
        val bytes = cipher.iv + cipher.doFinal(Gson().toJson(stored).toByteArray(Charsets.UTF_8))
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }

    fun clear() = file.delete()

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
        }.generateKey()
    }

    companion object { private const val KEY_ALIAS = "course_assistant_api" }
}
