package dev.corn.flashing.core

import org.junit.Assert.*
import org.junit.Test

class BatchCsvTest {
    @Test fun bomAndHeaderArePresent() {
        val text = BatchCsv.render(emptyList()) { "" }
        assertTrue(text.startsWith("\uFEFF序号,"))
    }

    @Test fun formulaInjectionIsNeutralizedAndCrLfQuoted() {
        assertEquals("'=1+1", BatchCsv.escape("=1+1"))
        assertEquals("'+cmd", BatchCsv.escape("+cmd"))
        assertEquals("'-1", BatchCsv.escape("-1"))
        assertEquals("'@SUM(A1)", BatchCsv.escape("@SUM(A1)"))
        assertEquals("'\tTAB", BatchCsv.escape("\tTAB"))
        assertEquals("\"a\r\nb\"", BatchCsv.escape("a\r\nb"))
        assertEquals("\"say \"\"hi\"\"\"", BatchCsv.escape("say \"hi\""))
        assertEquals("plain", BatchCsv.escape("plain"))
    }

    @Test fun renderNeutralizesUidAndMessage() {
        val text = BatchCsv.render(listOf(
            BatchCsvRow(1, "工位 1", "=CMD", null, "成功", 10, "完成", "+alert()", 1),
        )) { "t" }
        assertTrue(text.contains("'=CMD"))
        assertTrue(text.contains("'+alert()"))
    }
}
