package com.courseschedule.ui.assistant

import com.google.gson.JsonObject
import com.google.gson.JsonParser

internal enum class AssistantFailureKind { NETWORK, TIMEOUT, AUTHORIZATION, QUOTA, SERVICE, UNSUPPORTED_TOOLS, RESPONSE, PROTOCOL, OPERATION }
internal class AssistantAgentFailure(val reason: AssistantFailureKind, message: String) : IllegalArgumentException(message) {
    val canRepair get() = reason in setOf(AssistantFailureKind.RESPONSE, AssistantFailureKind.PROTOCOL, AssistantFailureKind.OPERATION)
}
internal data class AssistantAgentResponse(val text: String? = null, val operation: JsonObject? = null, val toolName: String? = null)

/** Assistant text has no authority to execute an operation. Only an explicit function call does. */
internal object AssistantAgentProtocol {
    fun decode(response: String, allowLegacyOperations: Boolean = true): AssistantAgentResponse {
        return try {
            val choice = JsonParser.parseString(response).asJsonObject.getAsJsonArray("choices").single().asJsonObject
            val finish = choice.get("finish_reason")?.asString
            if (finish == "length") throw AssistantAgentFailure(AssistantFailureKind.RESPONSE, "服务回复被截断，本次未执行操作。请缩小请求范围后重试。")
            if (finish == "content_filter") throw AssistantAgentFailure(AssistantFailureKind.RESPONSE, "服务未提供完整回复，请调整描述后重试。")
            require(finish in setOf("stop", "tool_calls"))
            val message = choice.getAsJsonObject("message")
            val calls = message.get("tool_calls")?.takeUnless { it.isJsonNull }?.asJsonArray
            if (calls != null && calls.size() > 0) {
                require(calls.size() == 1) { "请将同类操作合并成一次工具调用，不混合课程与事项。" }
                val call = calls.single().asJsonObject
                require(call.get("type")?.asString == "function")
                val function = call.getAsJsonObject("function")
                val name = function.get("name").asString
                val args = function.get("arguments")
                require(args.isJsonPrimitive && args.asJsonPrimitive.isString)
                AssistantAgentResponse(operation = AssistantAgentTools.operation(name, JsonParser.parseString(args.asString).asJsonObject), toolName = name)
            } else {
                require(finish == "stop")
                val value = message.get("content")
                require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString)
                val content = value.asString.trim()
                require(content.isNotEmpty() && content.length <= 16_000)
                // Code fences are examples, including on services using the legacy adapter.
                if (content.startsWith("```") && content.endsWith("```")) return AssistantAgentResponse(text = content)
                val unfenced = content.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
                val objectValue = runCatching { JsonParser.parseString(unfenced).takeIf { it.isJsonObject }?.asJsonObject }.getOrNull()
                val legacy = objectValue?.takeIf { root -> root.has("reply") ||
                    root.keySet().any { it in setOf("courses", "updates", "deleteIds", "queryIds", "occurrences", "revisions", "taskCreates", "taskUpdates", "taskDeletes", "taskRevisions", "taskQuery", "targetQuery") } ||
                    root.get("action")?.let { it.isJsonPrimitive && it.asJsonPrimitive.isString && it.asString in setOf("chat", "clarify", "query", "change", "undo", "revise", "task_query", "task_change", "task_revise", "cancel") } == true }
                when {
                    legacy != null && allowLegacyOperations -> AssistantAgentResponse(operation = legacy)
                    legacy != null && isChatEnvelope(legacy) -> AssistantAgentResponse(text = legacy.get("reply").asString)
                    legacy != null -> throw AssistantAgentFailure(AssistantFailureKind.PROTOCOL, "安排必须通过可用工具提出，文字中的操作 JSON 没有执行权限。")
                    objectValue == null && unfenced.startsWith("{") && Regex("\"(?:action|version|reply|courses|updates|taskCreates)\"\\s*:").containsMatchIn(unfenced) ->
                        throw AssistantAgentFailure(AssistantFailureKind.PROTOCOL, "服务回复格式不完整，请重新生成完整回复。")
                    else -> AssistantAgentResponse(text = content)
                }
            }
        } catch (error: AssistantAgentFailure) { throw error }
        catch (_: Exception) { throw AssistantAgentFailure(AssistantFailureKind.PROTOCOL, "服务回复未通过格式校验，本次未执行操作。") }
    }

    private fun isChatEnvelope(root: JsonObject): Boolean {
        val reply = root.get("reply") ?: return false
        if (!reply.isJsonPrimitive || !reply.asJsonPrimitive.isString || reply.asString.isBlank()) return false
        if (root.has("action") && root.get("action").asString !in setOf("chat", "clarify")) return false
        return root.entrySet().all { (key, value) -> key in setOf("version", "action", "reply") ||
            key in setOf("courses", "updates", "deleteIds", "queryIds", "occurrences", "revisions", "taskCreates", "taskUpdates", "taskDeletes", "taskRevisions") &&
                (value.isJsonNull || value.isJsonArray && value.asJsonArray.size() == 0) || key == "undo" && value.isJsonPrimitive && value.asJsonPrimitive.isBoolean && !value.asBoolean }
    }

    fun repairRequest(request: String, failure: AssistantAgentFailure): String = JsonParser.parseString(request).asJsonObject.apply {
        getAsJsonArray("messages").add(JsonObject().apply {
            addProperty("role", "system")
            addProperty("content", "上一轮回复未通过本地校验，未执行任何操作。原因：${failure.message}\n" +
                "只处理原用户请求，不改变目标或扩展范围。普通聊天直接回复自然文字；需要真实数据或提出安排时只调用当前可用的一项工具，合并同类操作。严格遵循工具参数格式，缺失信息就追问。")
        })
    }.toString()

    fun legacyRequest(request: String): String = JsonParser.parseString(request).asJsonObject.apply {
        val tools = getAsJsonArray("tools")
        getAsJsonArray("messages").add(JsonObject().apply {
            addProperty("role", "system")
            addProperty("content", "当前服务不支持函数工具，使用兼容通道。普通聊天直接回复文字。需要操作时只输出一个JSON对象，不使用代码块；示例代码仅供阅读，没有执行权限。version为1，reply为非空字符串；撤销还必须填写undo:true，取消使用action:cancel；action与字段仅使用以下映射：" +
                tools.joinToString("；") { entry -> val function = entry.asJsonObject.getAsJsonObject("function")
                    val name = function.get("name").asString
                    val action = AssistantAgentTools.action(name)
                    "$name 对应 $action，参数结构 ${function.get("parameters")}" })
        })
        remove("tools"); remove("tool_choice")
    }.toString()

}
