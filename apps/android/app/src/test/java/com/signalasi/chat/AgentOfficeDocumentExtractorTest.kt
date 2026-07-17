package com.signalasi.chat

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class AgentOfficeDocumentExtractorTest {
    @Test
    fun xlsxExtractsSharedInlineFormulaAndNumericCells() {
        val archive = zip(
            "xl/sharedStrings.xml" to """
                <sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                  <si><t>Name</t></si><si><t>SignalASI</t></si>
                </sst>
            """.trimIndent(),
            "xl/worksheets/sheet1.xml" to """
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>
                  <row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="inlineStr"><is><t>Status</t></is></c></row>
                  <row r="2"><c r="A2" t="s"><v>1</v></c><c r="B2"><v>42</v></c><c r="C2"><f>SUM(B2:B2)</f></c></row>
                </sheetData></worksheet>
            """.trimIndent()
        )

        val text = AgentOfficeDocumentExtractor.extractXlsx(archive)

        assertTrue(text.contains("A1=Name"))
        assertTrue(text.contains("B1=Status"))
        assertTrue(text.contains("A2=SignalASI"))
        assertTrue(text.contains("B2=42"))
        assertTrue(text.contains("C2==SUM(B2:B2)"))
    }

    @Test
    fun pptxExtractsTextPerSlideInNaturalOrder() {
        val archive = zip(
            "ppt/slides/slide10.xml" to slide("Last slide"),
            "ppt/slides/slide2.xml" to slide("Second slide")
        )

        val text = AgentOfficeDocumentExtractor.extractPptx(archive)

        assertTrue(text.indexOf("Second slide") < text.indexOf("Last slide"))
        assertTrue(text.contains("[Slide 1]"))
        assertTrue(text.contains("[Slide 2]"))
    }

    private fun slide(value: String) = """
        <p:sld xmlns:p="urn:p" xmlns:a="urn:a"><p:cSld><a:p><a:r><a:t>$value</a:t></a:r></a:p></p:cSld></p:sld>
    """.trimIndent()

    private fun zip(vararg entries: Pair<String, String>): ByteArray = ByteArrayOutputStream().use { output ->
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, value) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(value.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        output.toByteArray()
    }
}
