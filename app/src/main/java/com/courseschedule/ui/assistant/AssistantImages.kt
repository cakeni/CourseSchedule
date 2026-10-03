package com.courseschedule.ui.assistant

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Base64
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.util.UUID

internal object AssistantImageMessage {
    private val reference = Regex("[a-zA-Z0-9_-]{1,80}/[a-f0-9-]{36}\\.jpg")
    fun valid(ref: String) = reference.matches(ref)
    fun encode(text: String, ref: String): String {
        require(valid(ref) && text.length <= 2000)
        return JsonObject().apply { addProperty("text", text); addProperty("image", ref) }.toString()
    }
    fun decode(content: String, createdAt: Long): AssistantMessage {
        val root = JsonParser.parseString(content).asJsonObject
        val ref = root.get("image").asString
        val text = root.get("text").asString
        require(valid(ref) && text.length <= 2000)
        return AssistantMessage("user", text, "image", createdAt, imageRef = ref)
    }
}

/** App-private copies survive URI grants, rotation and process restart. No image bytes enter Room. */
internal class AssistantImages(private val context: Context) {
    private val root get() = File(context.filesDir, "assistant_images")
    fun file(ref: String): File {
        require(AssistantImageMessage.valid(ref)) { "图片记录无效。" }
        return File(root, ref)
    }

    fun import(uri: Uri, conversationId: String): String {
        require(Regex("[a-zA-Z0-9_-]{1,80}").matches(conversationId))
        val dir = File(root, conversationId).apply { mkdirs() }
        val source = File(dir, "import-${UUID.randomUUID()}.tmp")
        var decoded: Bitmap? = null
        var transformed: Bitmap? = null
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                source.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var total = 0
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        require(total <= 24 * 1024 * 1024) { "图片超过24MB，请裁剪或压缩后再选择。" }
                        output.write(buffer, 0, read)
                    }
                }
            } ?: throw IllegalArgumentException("无法读取图片，请重新选择。")
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(source.path, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0) { "请选择有效的图片文件。" }
            val options = BitmapFactory.Options().apply {
                inSampleSize = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / inSampleSize > 3200) inSampleSize *= 2
            }
            decoded = BitmapFactory.decodeFile(source.path, options)
                ?: throw IllegalArgumentException("图片无法解码，请换一张图片。")
            val orientation = runCatching { ExifInterface(source.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }.getOrDefault(1)
            val matrix = Matrix().apply {
                when (orientation) {
                    2 -> postScale(-1f, 1f)
                    3 -> postRotate(180f)
                    4 -> postScale(1f, -1f)
                    5 -> { postRotate(90f); postScale(-1f, 1f) }
                    6 -> postRotate(90f)
                    7 -> { postRotate(270f); postScale(-1f, 1f) }
                    8 -> postRotate(270f)
                }
            }
            transformed = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            val destination = File(dir, "${UUID.randomUUID()}.jpg")
            try {
                destination.outputStream().use { require(transformed.compress(Bitmap.CompressFormat.JPEG, 92, it)) }
                require(destination.length() <= 12 * 1024 * 1024) { "处理后的图片仍过大，请裁剪后重试。" }
            }
            catch (error: Exception) { destination.delete(); throw error }
            return "$conversationId/${destination.name}"
        } finally {
            source.delete()
            if (transformed !== decoded) transformed?.recycle()
            decoded?.recycle()
        }
    }

    fun dataUrl(ref: String): String {
        val image = file(ref)
        require(image.isFile && image.length() in 1..12 * 1024 * 1024L) { "图片已丢失，请重新选择后发送。" }
        return "data:image/jpeg;base64," + Base64.encodeToString(image.readBytes(), Base64.NO_WRAP)
    }

    fun thumbnail(ref: String, size: Int = 400): Bitmap? = runCatching {
        val path = file(ref).path
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply {
            inSampleSize = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / inSampleSize > size) inSampleSize *= 2
        })
    }.getOrNull()

    fun deleteConversation(id: String) {
        require(Regex("[a-zA-Z0-9_-]{1,80}").matches(id))
        File(root, id).deleteRecursively()
    }

    fun removeOrphans(conversations: Set<String>) {
        root.listFiles()?.filter { it.isDirectory && it.name !in conversations }?.forEach {
            deleteConversation(it.name)
        }
    }
}
