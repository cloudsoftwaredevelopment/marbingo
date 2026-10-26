package ph.marbingo.field.tracking

import ph.marbingo.core.model.TrackingScheduleDto
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.util.Locale

/**
 * The work-hours window, evaluated in the shop's timezone (Asia/Manila) no matter where the tablet thinks it is.
 * Pure (no Android) so the window math is unit-tested. [days] are ISO weekdays, 1 = Mon … 7 = Sun.
 * The window is [start, end): a fix at exactly the end minute is outside.
 */
data class TrackingSchedule(val days: Set<Int>, val startMinute: Int, val endMinute: Int) {

    init { require(startMinute in 0 until endMinute && endMinute <= 24 * 60) { "start must be before end" } }

    private fun zoned(millis: Long): ZonedDateTime = Instant.ofEpochMilli(millis).atZone(ZONE)
    private fun isWorkDay(d: LocalDate) = d.dayOfWeek.value in days
    private fun at(d: LocalDate, minute: Int): Long =
        d.atStartOfDay(ZONE).plusMinutes(minute.toLong()).toInstant().toEpochMilli()

    fun isInHours(now: Long): Boolean {
        val z = zoned(now)
        if (!isWorkDay(z.toLocalDate())) return false
        // Compare on the instant so seconds count: 17:59:30 is still in hours, 18:00:00 is not.
        return now >= at(z.toLocalDate(), startMinute) && now < at(z.toLocalDate(), endMinute)
    }

    /** When today's window ends, or null when [now] is outside hours. */
    fun windowEnd(now: Long): Long? = if (isInHours(now)) at(zoned(now).toLocalDate(), endMinute) else null

    /** The first window start strictly after [now] (looks a week ahead); null when no day is selected. */
    fun nextStart(now: Long): Long? {
        val today = zoned(now).toLocalDate()
        for (offset in 0..7) {
            val d = today.plusDays(offset.toLong())
            if (!isWorkDay(d)) continue
            val start = at(d, startMinute)
            if (start > now) return start
        }
        return null
    }

    /** The next moment the on/off state flips: the end of the current window, else the next start. */
    fun nextTransition(now: Long): Long? = windowEnd(now) ?: nextStart(now)

    /** "7:00–18:00" */
    fun hoursLabel(): String = "${clock(startMinute)}–${clock(endMinute)}"

    /** "Mon–Sat", "Mon, Wed, Fri" or "Every day". */
    fun daysLabel(): String {
        val sorted = days.filter { it in 1..7 }.sorted()
        if (sorted.size == 7) return "Every day"
        if (sorted.isEmpty()) return "No days"
        val contiguous = sorted.size > 2 && sorted.last() - sorted.first() == sorted.size - 1
        return if (contiguous) "${dayName(sorted.first())}–${dayName(sorted.last())}" else sorted.joinToString(", ") { dayName(it) }
    }

    /** "Mon–Sat, 7:00–18:00" */
    fun fullLabel(): String = "${daysLabel()}, ${hoursLabel()}"

    /** "today 7:00" / "Mon 7:00" for the next window start, or null. */
    fun nextStartLabel(now: Long): String? {
        val start = nextStart(now) ?: return null
        val z = zoned(start)
        val day = if (z.toLocalDate() == zoned(now).toLocalDate()) "today" else dayName(z.dayOfWeek.value)
        return "$day ${clock(z.hour * 60 + z.minute)}"
    }

    fun toCsv(): String = days.sorted().joinToString(",")

    companion object {
        val ZONE: ZoneId = ZoneId.of("Asia/Manila")

        /** Mon–Sat 07:00–18:00, used until the first sync delivers the server's schedule. */
        val DEFAULT = TrackingSchedule(setOf(1, 2, 3, 4, 5, 6), 7 * 60, 18 * 60)

        fun clock(minute: Int): String = "${minute / 60}:${(minute % 60).toString().padStart(2, '0')}"

        fun dayName(isoDay: Int): String = DayOfWeek.of(isoDay).getDisplayName(TextStyle.SHORT, Locale.ENGLISH)

        fun parseMinute(hhmm: String?): Int? {
            val parts = hhmm?.trim()?.split(":") ?: return null
            if (parts.size != 2) return null
            val h = parts[0].toIntOrNull() ?: return null
            val m = parts[1].toIntOrNull() ?: return null
            return if (h in 0..24 && m in 0..59) h * 60 + m else null
        }

        /** Null when the data is unusable (the caller then keeps the default / previous schedule). */
        fun from(days: Collection<Int>?, start: String?, end: String?): TrackingSchedule? {
            val s = parseMinute(start) ?: return null
            val e = parseMinute(end) ?: return null
            val d = days?.filter { it in 1..7 }?.toSet() ?: return null
            if (d.isEmpty() || s >= e || e > 24 * 60) return null
            return TrackingSchedule(d, s, e)
        }

        fun from(dto: TrackingScheduleDto): TrackingSchedule? = from(dto.days, dto.start, dto.end)

        fun fromCsv(days: String?, start: String?, end: String?): TrackingSchedule? =
            from(days?.split(",")?.mapNotNull { it.trim().toIntOrNull() }, start, end)
    }
}
