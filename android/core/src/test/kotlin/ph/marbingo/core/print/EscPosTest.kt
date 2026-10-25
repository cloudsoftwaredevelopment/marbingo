package ph.marbingo.core.print

import org.junit.Assert.*
import org.junit.Test

class EscPosTest {
    @Test fun commandBytes() {
        val b = EscPos().init().align(EscPos.Align.CENTER).bold(true).doubleHeight(true).line("Hi").feed(3).cut().toByteArray()
        val expected = byteArrayOf(
            0x1B, 0x40, 0x1B, 0x61, 1, 0x1B, 0x45, 1, 0x1D, 0x21, 1, 'H'.code.toByte(), 'i'.code.toByte(), 0x0A,
            0x1B, 0x64, 3, 0x1D, 0x56, 66, 0,
        )
        assertArrayEquals(expected, b)
    }
    @Test fun transliterates() {
        assertEquals("P1,234.00", EscPos.transliterate("₱1,234.00"))
        assertEquals("\"a\" 'b' - x", EscPos.transliterate("“a” ‘b’ – ×"))
        assertEquals("Cafe", EscPos.transliterate("Café"))
        assertEquals("?", EscPos.transliterate("日"))
    }
    @Test fun textIsAsciiBytes() {
        val b = EscPos().text("₱5").toByteArray()
        assertArrayEquals(byteArrayOf('P'.code.toByte(), '5'.code.toByte()), b)
    }
}
