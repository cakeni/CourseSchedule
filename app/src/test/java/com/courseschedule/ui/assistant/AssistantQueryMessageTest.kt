package com.courseschedule.ui.assistant

import org.junit.Assert.*
import org.junit.Test

class AssistantQueryMessageTest {
    @Test fun queryIdentitiesPersistIndependentlyOfCourseText() {
        val text = "查询范围：全学期\n课程 #42\n课程名称包含引号\"及换行\n课程 #987\n"
        val record = AssistantQueryMessage.decode(AssistantQueryMessage.encode(text, listOf(42L, 43L)), 123)
        assertEquals(text, record.content)
        assertEquals(listOf(42L, 43L), record.courseIds)
        assertEquals("result", record.kind)
        assertEquals("assistant", record.role)
        assertEquals(123L, record.createdAt)
    }

    @Test fun malformedRecordsCannotIntroduceNavigationTargets() {
        for (json in listOf("null", "{}",
            """{"version":2,"text":"查询","courseIds":[1]}""",
            """{"version":1,"text":"查询","courseIds":[1,1]}""",
            """{"version":1,"text":"查询","courseIds":[0]}""",
            """{"version":1,"text":"查询","courseIds":[1.5]}""",
            """{"version":1,"text":"查询","courseIds":["1"]}""",
            """{"version":1,"text":"查询","courseIds":[9223372036854775808]}""",
            """{"version":1,"text":"查询","courseIds":[1],"unknown":true}""")) {
            assertThrows(Exception::class.java) { AssistantQueryMessage.decode(json, 123) }
        }
    }

    @Test fun emptyAndOversizedNavigationListsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { AssistantQueryMessage.encode("查询", emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { AssistantQueryMessage.encode("查询", (1L..201L).toList()) }
        val ids = (1L..200L).toList()
        assertEquals(ids, AssistantQueryMessage.decode(AssistantQueryMessage.encode("查询", ids), 123).courseIds)
    }
}
