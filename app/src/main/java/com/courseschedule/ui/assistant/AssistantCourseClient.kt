package com.courseschedule.ui.assistant

import com.courseschedule.data.entity.Course
import com.courseschedule.data.entity.Semester
import com.courseschedule.data.entity.StudyTask
import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.time.LocalDate
import kotlinx.coroutines.CancellationException

/** Transport owns connection lifecycle and bounded recovery; it never writes application data. */
internal class AssistantCourseClient(private val openConnection: (String) -> HttpURLConnection = {
    URL(it).openConnection() as HttpURLConnection
}) {
    @Volatile private var activeConnection: HttpURLConnection? = null
    @Volatile private var cancelled = false

    fun cancel() { cancelled = true; activeConnection?.disconnect() }

    fun chat(config: AssistantApiConfig, request: String, totalWeeks: Int,
        existingCourses: List<Course> = emptyList(), semester: Semester? = null, displayedWeek: Int = 1,
        pending: AssistantCourseReply? = null, today: LocalDate = LocalDate.now(), selectedTarget: Course? = null,
        studyTasks: List<StudyTask> = emptyList(), targetChoice: AssistantTargetChoice? = null, onProgress: (String) -> Unit = {}): AssistantCourseReply {
        config.validate()
        var currentRequest = request
        val capabilities = JsonParser.parseString(request).asJsonObject.getAsJsonArray("tools")
            ?.map { it.asJsonObject.getAsJsonObject("function").get("name").asString }?.toSet()
        for (attempt in 0..1) {
            checkCancellation()
            try {
                onProgress(if (attempt == 0) "正在回复…" else "正在重新整理回复…")
                val response = post(config, currentRequest)
                checkCancellation()
                onProgress("正在核对回复…")
                val body = JsonParser.parseString(currentRequest).asJsonObject
                val names = body.getAsJsonArray("tools")?.map { it.asJsonObject.getAsJsonObject("function").get("name").asString }?.toSet()
                val reply = parseResponse(response, totalWeeks, existingCourses, semester, displayedWeek, pending, today,
                    selectedTarget, studyTasks, targetChoice, allowLegacyOperations = names == null, allowedTools = capabilities)
                checkCancellation()
                return reply
            } catch (failure: AssistantAgentFailure) {
                if (attempt == 0 && failure.reason == AssistantFailureKind.UNSUPPORTED_TOOLS && JsonParser.parseString(currentRequest).asJsonObject.has("tools")) {
                    currentRequest = AssistantAgentProtocol.legacyRequest(currentRequest)
                } else if (attempt == 0 && failure.canRepair) {
                    currentRequest = AssistantAgentProtocol.repairRequest(currentRequest, failure)
                } else throw failure
            }
        }
        error("Unreachable request state")
    }

    private fun checkCancellation() { if (cancelled) throw CancellationException("请求已停止") }

    private fun post(config: AssistantApiConfig, request: String): String {
        checkCancellation()
        val connection = openConnection(config.endpoint())
        activeConnection = connection
        try {
            checkCancellation()
            connection.requestMethod = "POST"; connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000; connection.readTimeout = 60_000; connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer ${config.apiKey}")
            connection.setRequestProperty("Content-Type", "application/json")
            val bytes = request.toByteArray(Charsets.UTF_8)
            require(bytes.size <= 1_500_000) { "当前课表与图片内容过多，请分批处理。" }
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }
            val status = connection.responseCode
            if (status !in 200..299) {
                val errorBody = runCatching { connection.errorStream?.use { input ->
                    val buffer = ByteArray(2048)
                    val errorOutput = ByteArrayOutputStream()
                    while (errorOutput.size() < 16_384) {
                        val count = input.read(buffer, 0, minOf(buffer.size, 16_384 - errorOutput.size()))
                        if (count < 0) break
                        errorOutput.write(buffer, 0, count)
                    }
                    errorOutput.toString("UTF-8")
                }.orEmpty() }.getOrDefault("")
                if (status in setOf(400, 422) && Regex("tools|tool_choice|function.call", RegexOption.IGNORE_CASE).containsMatchIn(errorBody))
                    throw AssistantAgentFailure(AssistantFailureKind.UNSUPPORTED_TOOLS, "当前服务不支持函数工具，正在改用兼容通道。")
                throw when (status) {
                    401, 403 -> AssistantAgentFailure(AssistantFailureKind.AUTHORIZATION, "API Key 无效或没有模型权限，请检查 API 配置。")
                    429 -> AssistantAgentFailure(AssistantFailureKind.QUOTA, "API 额度不足或请求过快，请稍后重试。")
                    400, 404, 422 -> AssistantAgentFailure(AssistantFailureKind.SERVICE, "API 地址、模型或请求格式不受支持，请检查配置。")
                    in 300..399 -> AssistantAgentFailure(AssistantFailureKind.SERVICE, "API 地址发生重定向，请填写最终的 HTTPS 地址。")
                    else -> AssistantAgentFailure(AssistantFailureKind.SERVICE, "API 请求失败（HTTP $status），请稍后重试。")
                }
            }
            val output = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    checkCancellation()
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > 256_000) throw AssistantAgentFailure(AssistantFailureKind.RESPONSE, "服务回复过长，本次未执行操作。请分批描述。")
                    output.write(buffer, 0, count)
                }
            }
            return output.toString("UTF-8")
        } catch (_: SocketTimeoutException) {
            checkCancellation()
            throw AssistantAgentFailure(AssistantFailureKind.TIMEOUT, "服务回复超时，本次未执行操作。请重试。")
        } catch (_: IOException) {
            checkCancellation()
            throw AssistantAgentFailure(AssistantFailureKind.NETWORK, "无法连接服务，请检查网络和 API 地址后重试。")
        } finally { connection.disconnect(); activeConnection = null }
    }

    companion object {
        fun createRequest(model: String, messages: List<AssistantMessage>, semester: Semester, displayedWeek: Int,
            sectionTimes: List<String>, sectionEndTimes: List<String>, existingCourses: List<Course> = emptyList(),
            canUndo: Boolean = false, defaultReminderMinutes: Int = -1, pending: AssistantCourseReply? = null,
            today: LocalDate = LocalDate.now(), selectedTarget: Course? = null, imageData: Map<String, String> = emptyMap(),
            studyTasks: List<StudyTask> = emptyList(), targetChoice: AssistantTargetChoice? = null) = AssistantAgentContext.createRequest(model, messages, semester, displayedWeek,
                sectionTimes, sectionEndTimes, existingCourses, canUndo, defaultReminderMinutes, pending, today, selectedTarget, imageData, studyTasks, targetChoice)

        internal fun contextMessages(messages: List<AssistantMessage>) = AssistantAgentContext.contextMessages(messages)

        fun parseResponse(response: String, totalWeeks: Int, existingCourses: List<Course> = emptyList(), semester: Semester? = null,
            displayedWeek: Int = 1, pending: AssistantCourseReply? = null, today: LocalDate = LocalDate.now(),
            selectedTarget: Course? = null, studyTasks: List<StudyTask> = emptyList(), targetChoice: AssistantTargetChoice? = null, allowLegacyOperations: Boolean = true,
            allowedTools: Set<String>? = null): AssistantCourseReply {
            val decoded = AssistantAgentProtocol.decode(response, allowLegacyOperations)
            decoded.text?.let { return AssistantCourseReply(it, emptyList()) }
            if (allowedTools != null && decoded.toolName != null && decoded.toolName !in allowedTools)
                throw AssistantAgentFailure(AssistantFailureKind.PROTOCOL, "当前状态不允许该操作，请先确认或取消现有方案。")
            val operation = requireNotNull(decoded.operation)
            val action = operation.get("action")?.asString
            if (allowedTools != null && decoded.toolName == null && action == null) {
                val emptyCourses = operation.get("courses")?.let { it.isJsonArray && it.asJsonArray.size() == 0 } ?: true
                if (!emptyCourses || operation.keySet().any { it !in setOf("reply", "courses") })
                    throw AssistantAgentFailure(AssistantFailureKind.PROTOCOL, "兼容操作缺少明确的类型，本次未执行。")
            }
            if (allowedTools != null && decoded.toolName == null && action != null &&
                (action !in setOf("chat", "clarify") || operation.has("targetQuery")) && allowedTools.none { AssistantAgentTools.action(it) == action })
                throw AssistantAgentFailure(AssistantFailureKind.PROTOCOL, "当前状态不允许该操作，请先确认或取消现有方案。")
            if (operation.get("action")?.asString == "cancel") {
                if ((pending == null && targetChoice == null) || operation.keySet() != setOf("version", "action", "reply") ||
                    operation.get("version").let { !it.isJsonPrimitive || !it.asJsonPrimitive.isNumber || it.asBigDecimal != java.math.BigDecimal.ONE } ||
                    operation.get("reply").let { !it.isJsonPrimitive || !it.asJsonPrimitive.isString || it.asString.isBlank() })
                    throw AssistantAgentFailure(AssistantFailureKind.OPERATION, "当前没有可取消的待确认方案。")
                return AssistantCourseReply("取消待确认方案。", emptyList(), cancelPending = true)
            }
            return AssistantCourseProtocol.parseOperation(operation, totalWeeks, existingCourses, semester, displayedWeek,
                pending, today, selectedTarget, studyTasks, nativeTool = decoded.toolName != null)
        }
    }
}
