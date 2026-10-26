package ph.marbingo.field.tracking

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZonedDateTime

/** All instants are written in Manila time: that is the only zone the schedule cares about. 2026-10-26 is a Monday. */
class TrackingScheduleTest {
    private val s = TrackingSchedule.DEFAULT // Mon-Sat 07:00-18:00
    private fun manila(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0, sec: Int = 0): Long =
        ZonedDateTime.of(y, mo, d, h, mi, sec, 0, TrackingSchedule.ZONE).toInstant().toEpochMilli()
    private fun mon(h: Int, mi: Int = 0, sec: Int = 0) = manila(2026, 10, 26, h, mi, sec)

    @Test fun windowIsStartInclusiveEndExclusive() {
        assertFalse(s.isInHours(mon(6, 59, 59)))
        assertTrue(s.isInHours(mon(7)))
        assertTrue(s.isInHours(mon(12)))
        assertTrue(s.isInHours(mon(17, 59, 59)))
        assertFalse(s.isInHours(mon(18)))
        assertFalse(s.isInHours(mon(23, 30)))
    }

    @Test fun sundayIsOff() {
        val sunday = { h: Int -> manila(2026, 11, 1, h) }
        for (h in listOf(0, 7, 12, 17)) assertFalse("hour $h", s.isInHours(sunday(h)))
        assertTrue(s.isInHours(manila(2026, 10, 31, 12))) // Saturday
    }

    @Test fun nextStartBeforeHoursIsToday() {
        assertEquals(mon(7), s.nextStart(mon(5, 0)))
        assertEquals(mon(7), s.nextStart(mon(6, 59, 59)))
    }

    @Test fun nextStartDuringOrAfterHoursIsTomorrow() {
        val tue7 = manila(2026, 10, 27, 7)
        assertEquals(tue7, s.nextStart(mon(7)))   // strictly after: 07:00 itself has started
        assertEquals(tue7, s.nextStart(mon(12)))
        assertEquals(tue7, s.nextStart(mon(19)))
    }

    @Test fun endOfWeekRollsOverSundayToMonday() {
        val mon7 = manila(2026, 11, 2, 7)
        assertEquals(mon7, s.nextStart(manila(2026, 10, 31, 19))) // Saturday evening
        assertEquals(mon7, s.nextStart(manila(2026, 11, 1, 12)))  // Sunday noon
        assertEquals(mon7, s.nextStart(manila(2026, 10, 31, 23, 59, 59)))
    }

    @Test fun rolloverAcrossMonthAndYear() {
        // Thu 2026-12-31 is a work day; Fri Jan 1 too. Sat 2026-12-26 evening -> Mon Dec 28.
        assertEquals(manila(2026, 12, 28, 7), s.nextStart(manila(2026, 12, 26, 20)))
        assertEquals(manila(2027, 1, 1, 7), s.nextStart(manila(2026, 12, 31, 18, 30)))
    }

    @Test fun windowEndAndNextTransition() {
        assertEquals(mon(18), s.windowEnd(mon(10)))
        assertNull(s.windowEnd(mon(19)))
        assertEquals(mon(18), s.nextTransition(mon(10)))
        assertEquals(manila(2026, 10, 27, 7), s.nextTransition(mon(19)))
        assertEquals(mon(7), s.nextTransition(mon(3)))
    }

    @Test fun usesManilaNotTheDeviceZone() {
        // 2026-10-26T22:59Z is Tue 06:59 Manila (off), one minute later it is 07:00 (on).
        assertFalse(s.isInHours(Instant.parse("2026-10-26T22:59:00Z").toEpochMilli()))
        assertTrue(s.isInHours(Instant.parse("2026-10-26T23:00:00Z").toEpochMilli()))
        // 2026-10-26T10:00Z = 18:00 Manila: closed.
        assertFalse(s.isInHours(Instant.parse("2026-10-26T10:00:00Z").toEpochMilli()))
    }

    @Test fun customScheduleAndEmptyDays() {
        val tueThu = TrackingSchedule(setOf(2, 4), 9 * 60, 17 * 60 + 30)
        assertFalse(tueThu.isInHours(mon(10)))
        assertTrue(tueThu.isInHours(manila(2026, 10, 27, 17, 29)))
        assertFalse(tueThu.isInHours(manila(2026, 10, 27, 17, 30)))
        assertEquals(manila(2026, 10, 27, 9), tueThu.nextStart(mon(10)))
        assertEquals(manila(2026, 10, 29, 9), tueThu.nextStart(manila(2026, 10, 27, 12)))
        assertNull(TrackingSchedule(emptySet(), 420, 1080).nextStart(mon(10)))
    }

    @Test fun labels() {
        assertEquals("7:00–18:00", s.hoursLabel())
        assertEquals("Mon–Sat", s.daysLabel())
        assertEquals("Mon–Sat, 7:00–18:00", s.fullLabel())
        assertEquals("Mon, Wed, Fri", TrackingSchedule(setOf(1, 3, 5), 0, 60).daysLabel())
        assertEquals("Every day", TrackingSchedule((1..7).toSet(), 0, 60).daysLabel())
        assertEquals("Mon 7:00", s.nextStartLabel(manila(2026, 11, 1, 12)))
        assertEquals("today 7:00", s.nextStartLabel(mon(5)))
        assertEquals("Tue 7:00", s.nextStartLabel(mon(19)))
        assertEquals("9:30", TrackingSchedule.clock(9 * 60 + 30))
    }

    @Test fun parsingRejectsBadData() {
        assertNotNull(TrackingSchedule.from(listOf(1, 2), "07:00", "18:00"))
        assertNull(TrackingSchedule.from(listOf(1, 2), "18:00", "07:00"))
        assertNull(TrackingSchedule.from(listOf(1, 2), "07:00", "07:00"))
        assertNull(TrackingSchedule.from(emptyList(), "07:00", "18:00"))
        assertNull(TrackingSchedule.from(listOf(1), "7am", "18:00"))
        assertEquals(setOf(1, 2, 3), TrackingSchedule.fromCsv("1,2,3", "07:00", "18:00")!!.days)
        assertEquals("1,2,3,4,5,6", s.toCsv())
    }
}
