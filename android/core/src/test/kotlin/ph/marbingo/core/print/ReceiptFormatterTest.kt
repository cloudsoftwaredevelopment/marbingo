package ph.marbingo.core.print

import org.junit.Assert.*
import org.junit.Test
import ph.marbingo.core.model.PricePartDto

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

    @Test fun breakdownLayout() {
        val sack = PricePartDto(5, "Sack", 50, 1, 290000, 290000)
        val loose = PricePartDto(null, null, 1, 3, 6200, 18600)
        val dozen = PricePartDto(2, "Dozen", 12, 2, 9000, 18000)
        val d = data.copy(
            lines = listOf(
                ReceiptLineItem("Jasmine rice", 53, "kg", 6200, 308600, listOf(sack, loose)),
                ReceiptLineItem("Eggs", 24, "pc", 900, 18000, listOf(dozen)),
                ReceiptLineItem("Sugar", 5, "kg", 7500, 37500, listOf(PricePartDto(null, null, 1, 5, 7500, 37500))),
            ),
            total = 364100, notes = null,
        )
        val expected = listOf(
            "Jasmine rice",
            "  1 Sack(50kg)          2,900.00",
            "  3 kg x 62.00            186.00",
            "  Item total           ₱3,086.00",
            "Eggs",
            "  2 Dozen(12pc) x 90.00   180.00",
            "Sugar",
            "  5 kg x 75.00            375.00",
        )
        val out = ReceiptFormatter.format(d).map { it.text }
        val start = out.indexOf("Jasmine rice")
        assertEquals(expected, out.subList(start, start + expected.size))
        assertTrue(out.all { it.length <= 32 })
    }

    @Test fun wideSackRowMovesAmountDown() {
        val p = PricePartDto(5, "Sack", 50, 12, 290000, 3480000)
        assertEquals(listOf("  12 Sack(50kg) x 2,900.00", "                       34,800.00"),
            ReceiptFormatter.partRows(p, "kg", 32))
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
