package ph.marbingo.field.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import ph.marbingo.field.data.*

class FormatTest {
    @Test fun money() {
        assertEquals("₱0.00", Fmt.money(0))
        assertEquals("₱1,234.00", Fmt.money(123400))
        assertEquals("₱12,345,678.05", Fmt.money(1234567805))
        assertEquals("₱0.99", Fmt.money(99))
        assertEquals("-₱1.50", Fmt.money(-150))
    }

    @Test fun manilaTime() {
        // 2024-01-01T16:30:00Z is 2024-01-02 00:30 in Manila (UTC+8)
        val t = java.time.Instant.parse("2024-01-01T16:30:00Z").toEpochMilli()
        assertEquals("2 Jan 2024, 12:30 AM", Fmt.dateTime(t))
        assertEquals(java.time.LocalDate.of(2024, 1, 2), Fmt.localDate(t))
        assertEquals(true, Fmt.isToday(t, t + 3600_000))
        assertEquals(false, Fmt.isToday(t, t + 24 * 3600_000))
    }

    @Test fun relative() {
        val now = 10_000_000_000L
        assertEquals("just now", Fmt.relative(now - 10_000, now))
        assertEquals("5 min ago", Fmt.relative(now - 5 * 60_000, now))
        assertEquals("2 h ago", Fmt.relative(now - 2 * 3600_000, now))
        assertEquals("1 day ago", Fmt.relative(now - 24 * 3600_000, now))
        assertEquals("3 days ago", Fmt.relative(now - 72 * 3600_000, now))
    }

    @Test fun syncSummaryTexts() {
        val now = 10_000_000_000L
        assertEquals("Syncing…", syncSummary(SyncStatus(null, 0, 0, true, null), now).title)
        assertEquals("Synced 5 min ago", syncSummary(SyncStatus(now - 300_000, 0, 0, false, null), now).title)
        assertEquals("Not synced yet", syncSummary(SyncStatus(null, 0, 0, false, null), now).title)
        val w = syncSummary(SyncStatus(now, 2, 0, false, null), now)
        assertEquals("2 orders waiting to sync", w.detail)
        assertEquals(Tone.Warn, w.tone)
        val e = syncSummary(SyncStatus(now, 0, 0, false, "No signal"), now)
        assertEquals(Tone.Danger, e.tone)
        assertEquals("Last error: No signal", e.detail)
    }
}
