package com.courseschedule.ui.importdata

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class XlsxImporterTest {

    @Test
    fun parsesSharedStringsInlineStringsAndNumericCells() {
        val sharedStrings = listOf(
            "课程导入表", "课程名称", "教师", "教室", "星期",
            "开始节次", "结束节次", "开始周", "结束周", "单双周",
            "数据结构", "张老师", "A101", "周三", "单周"
        )
        val sheet = """
            <?xml version="1.0" encoding="UTF-8"?>
            <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
              <sheetData>
                <row r="1"><c r="A1" t="s"><v>0</v></c></row>
                <row r="3">
                  <c r="A3" t="s"><v>1</v></c><c r="B3" t="s"><v>2</v></c>
                  <c r="C3" t="s"><v>3</v></c><c r="D3" t="s"><v>4</v></c>
                  <c r="E3" t="s"><v>5</v></c><c r="F3" t="s"><v>6</v></c>
                  <c r="G3" t="s"><v>7</v></c><c r="H3" t="s"><v>8</v></c>
                  <c r="I3" t="s"><v>9</v></c>
                </row>
                <row r="4">
                  <c r="A4" t="s"><v>10</v></c><c r="B4" t="s"><v>11</v></c>
                  <c r="C4" t="s"><v>12</v></c><c r="D4" t="s"><v>13</v></c>
                  <c r="E4"><v>3.0</v></c><c r="F4"><v>4</v></c>
                  <c r="G4"><v>1</v></c><c r="H4"><v>16</v></c>
                  <c r="I4" t="s"><v>14</v></c>
                </row>
                <row r="5">
                  <c r="A5" t="inlineStr"><is><t>高等数学</t></is></c>
                  <c r="D5"><v>2</v></c><c r="E5"><v>1</v></c><c r="F5"><v>2</v></c>
                  <c r="G5"><v>1</v></c><c r="H5"><v>18</v></c>
                </row>
              </sheetData>
            </worksheet>
        """.trimIndent()

        val parsed = XlsxImporter(totalWeeks = 20).parse(
            ByteArrayInputStream(createWorkbook(sharedStrings, sheet))
        )

        assertEquals("Excel", parsed.sourceLabel)
        assertEquals(2, parsed.courses.size)
        assertEquals("数据结构", parsed.courses[0].courseName)
        assertEquals(3, parsed.courses[0].dayOfWeek)
        assertEquals(1, parsed.courses[0].weekType)
        assertEquals("高等数学", parsed.courses[1].courseName)
        assertEquals(2, parsed.courses[1].dayOfWeek)
        assertEquals(18, parsed.courses[1].endWeek)
    }

    private fun createWorkbook(sharedStrings: List<String>, sheet: String): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            addEntry(
                zip,
                "xl/sharedStrings.xml",
                """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                      ${sharedStrings.joinToString("") { "<si><t>$it</t></si>" }}
                    </sst>
                """.trimIndent()
            )
            addEntry(zip, "xl/worksheets/sheet1.xml", sheet)
        }
        return output.toByteArray()
    }

    private fun addEntry(zip: ZipOutputStream, name: String, content: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(content.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }
}
