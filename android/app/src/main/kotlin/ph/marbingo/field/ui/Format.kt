package ph.marbingo.field.ui

import ph.marbingo.field.data.*
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Money and date formatting. Centavos in, display strings out. */
object Fmt {
    val Manila: ZoneId = ZoneId.of("Asia/Manila")
    private val dateTime = DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a", Locale.ENGLISH).withZone(Manila)
    private val dateOnly = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH).withZone(Manila)
    private val timeOnly = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH).withZone(Manila)

    /** 123400 -> "₱1,234.00" (en-PH grouping, always two decimals). */
    fun money(centavos: Long): String {
        val abs = Math.abs(centavos)
        val whole = DecimalFormat("#,##0", DecimalFormatSymbols(Locale.US)).format(abs / 100)
        return (if (centavos < 0) "-" else "") + "₱" + whole + "." + (abs % 100).toString().padStart(2, '0')
    }

    fun dateTime(epochMillis: Long): String = dateTime.format(Instant.ofEpochMilli(epochMillis))
    fun date(epochMillis: Long): String = dateOnly.format(Instant.ofEpochMilli(epochMillis))
    fun time(epochMillis: Long): String = timeOnly.format(Instant.ofEpochMilli(epochMillis))

    fun localDate(epochMillis: Long): LocalDate = Instant.ofEpochMilli(epochMillis).atZone(Manila).toLocalDate()
    fun isToday(epochMillis: Long, now: Long = System.currentTimeMillis()): Boolean =
        localDate(epochMillis) == localDate(now)

    /** "just now", "5 min ago", "2 h ago", "3 days ago". */
    fun relative(thenMillis: Long, now: Long = System.currentTimeMillis()): String {
        val mins = (now - thenMillis) / 60_000
        return when {
            mins < 1 -> "just now"
            mins < 60 -> "$mins min ago"
            mins < 60 * 24 -> "${mins / 60} h ago"
            else -> "${mins / (60 * 24)} day${if (mins / (60 * 24) == 1L) "" else "s"} ago"
        }
    }

    /** 12.4 -> "±12 m". */
    fun accuracy(metres: Double?): String? = metres?.let { "±${Math.round(it)} m" }

    fun plural(n: Int, one: String, many: String = one + "s") = if (n == 1) "$n $one" else "$n $many"
}

enum class Tone { Neutral, Good, Warn, Danger }

data class SyncSummary(val title: String, val detail: String?, val tone: Tone)

/** Text for the Home / Settings sync card. */
fun syncSummary(s: SyncStatus, now: Long = System.currentTimeMillis()): SyncSummary {
    val waiting = buildList {
        if (s.waitingOrders > 0) add("${Fmt.plural(s.waitingOrders, "order")} waiting to sync")
        if (s.waitingStores > 0) add("${Fmt.plural(s.waitingStores, "store")} waiting to sync")
    }.joinToString(" · ").ifEmpty { null }
    val title = when {
        s.running -> "Syncing…"
        s.lastSyncedAt != null -> "Synced ${Fmt.relative(s.lastSyncedAt, now)}"
        else -> "Not synced yet"
    }
    val detail = listOfNotNull(waiting, s.lastError?.let { "Last error: $it" }).joinToString("\n").ifEmpty { null }
    val tone = when {
        s.lastError != null -> Tone.Danger
        waiting != null -> Tone.Warn
        s.running -> Tone.Neutral
        else -> Tone.Good
    }
    return SyncSummary(title, detail, tone)
}

data class TimelineStep(val label: String, val detail: String?, val done: Boolean, val tone: Tone = Tone.Good)

fun orderTimeline(o: Order): List<TimelineStep> = buildList {
    add(TimelineStep("Ordered", Fmt.dateTime(o.orderedAt), true))
    when (o.syncState) {
        SyncState.SYNCED -> add(TimelineStep("Received by the shop", null, true))
        SyncState.QUEUED -> add(TimelineStep("Waiting to sync", "Saved on this tablet", false, Tone.Warn))
        SyncState.ERROR -> add(TimelineStep("Sync failed", o.syncError, false, Tone.Danger))
    }
    when (o.status) {
        OrderStatus.DELIVERED -> add(TimelineStep("Delivered", o.deliveredAt?.let { Fmt.dateTime(it) }, true))
        OrderStatus.CANCELLED -> add(TimelineStep("Cancelled", o.cancelledReason, true, Tone.Danger))
        OrderStatus.PENDING -> add(TimelineStep("Awaiting delivery", null, false, Tone.Neutral))
    }
}
