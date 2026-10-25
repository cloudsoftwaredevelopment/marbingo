package ph.marbingo.core.money

import org.junit.Assert.*
import org.junit.Test

class MoneyTest {
    @Test fun formats() {
        assertEquals("₱0.00", Money.format(0))
        assertEquals("₱0.05", Money.format(5))
        assertEquals("₱1,234.00", Money.format(123400))
        assertEquals("₱1,234,567.89", Money.format(123456789))
        assertEquals("₱999.99", Money.format(99999))
        assertEquals("-₱12.50", Money.format(-1250))
        assertEquals("₱92,233,720,368,547,758.07", Money.format(Long.MAX_VALUE))
    }
    @Test fun parses() {
        assertEquals(123450L, Money.parse("₱1,234.5"))
        assertEquals(1200L, Money.parse("12"))
        assertNull(Money.parse("1.234"))
        assertNull(Money.parse("abc"))
    }
}
