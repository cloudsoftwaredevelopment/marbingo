package ph.marbingo.core.print

import org.junit.Assert.*
import org.junit.Test

class ReceiptFormatterTest {
    private val data = ReceiptData(
        shopName = "Marbingo", ref = "#12", storeName = "Aling Nena Store", storeAddress = "Brgy. San Jose, Jaro",
        agentName = "Ramon Dela Cruz", orderedAt = "Oct 26, 2026 9:30 AM",
        lines = listOf(
            ReceiptLineItem("Jasmine rice, 1 kg", 20, "pc", 5600, 112000),
            ReceiptLineItem("Extra long product name that wraps over rows", 3, "pc", 1250, 3750),
        ),
        total = 115750, notes = "Deliver Thursday",
    )

    @Test fun exactLayout() {
        val expected = listOf(
            "Marbingo",
            "ORDER SLIP",
            "--------------------------------",
            "Order: #12",
            "Date: Oct 26, 2026 9:30 AM",
            "Agent: Ramon Dela Cruz",
            "Store: Aling Nena Store",
            "Brgy. San Jose, Jaro",
            "--------------------------------",
            "Jasmine rice, 1 kg",
            "20 x ₱56.00            ₱1,120.00",
            "Extra long product name that",
            "wraps over rows",
            "3 x ₱12.50                ₱37.50",
            "--------------------------------",
            "TOTAL                  ₱1,157.50",
            "",
            "Notes: Deliver Thursday",
            "",
            "Order only — not yet delivered",
        )
        assertEquals(expected, ReceiptFormatter.format(data).map { it.text })
        assertTrue(ReceiptFormatter.format(data).all { it.text.length <= 32 })
    }

    @Test fun plainTextCentersAndTransliterates() {
        val t = data.toPlainText()
        val lines = t.lines()
        assertEquals("            Marbingo", lines[0])
        assertTrue(lines.contains("20 x P56.00            P1,120.00"))
        assertFalse(t.contains("₱"))
    }

    @Test fun escPosStartsWithInitAndEndsWithCut() {
        val b = data.toEscPos()
        assertEquals(0x1B.toByte(), b[0]); assertEquals(0x40.toByte(), b[1])
        assertArrayEquals(byteArrayOf(0x1D, 0x56, 66, 0), b.copyOfRange(b.size - 4, b.size))
        assertTrue(b.all { it >= 0 })
    }

    @Test fun longWordHardSplits() {
        assertEquals(listOf("abcdefghij", "klmno"), ReceiptFormatter.wrap("abcdefghijklmno", 10))
    }
}
