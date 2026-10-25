package ph.marbingo.field.ui

import org.junit.Assert.*
import org.junit.Test
import ph.marbingo.field.ui.scanner.StableCodeDetector

class StableCodeDetectorTest {
    @Test fun needsConsecutiveReads() {
        val d = StableCodeDetector(2)
        assertNull(d.accept("123"))
        assertNull(d.accept("456"))
        assertEquals("456", d.accept("456"))
        assertNull(d.accept(null))
        assertNull(d.accept("456"))
    }
}
