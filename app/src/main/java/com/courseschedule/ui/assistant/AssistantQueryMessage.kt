package com.courseschedule.ui.assistant

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Store local query identities separately from displayed, potentially user-supplied course text. */
internal object AssistantQueryMessage {
    fun encode(text: String, courseIds: List<Long>): String {
        require(text.isNotBlank() && courseIds.size in 1..200 && courseIds.all { it > 0 } && courseIds.distinct() == courseIds)
        return JsonObject().apply {
            addProperty("version", 1)
            addProperty("text", text)
            add("courseIds", JsonArray().apply { courseIds.forEach { add(it) } })
        }.toString()
    }

    fun decode(json: String, createdAt: Long): AssistantMessage {
        val row = JsonParser.parseString(json).asJsonObject
        require(row.keySet() == setOf("version", "text", "courseIds"))
        val version = row.get("version")
        require(version.isJsonPrimitive && version.asJsonPrimitive.isNumber && version.asBigDecimal.intValueExact() == 1)
        val text = row.get("text")
        require(text.isJsonPrimitive && text.asJsonPrimitive.isString && text.asString.isNotBlank())
        val ids = row.getAsJsonArray("courseIds").map {
            require(it.isJsonPrimitive && it.asJsonPrimitive.isNumber)
            it.asBigDecimal.longValueExact()
        }
        require(ids.size in 1..200 && ids.all { it > 0 } && ids.distinct() == ids)
        return AssistantMessage("assistant", text.asString, "result", createdAt, ids)
    }
}
