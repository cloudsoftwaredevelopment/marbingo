package ph.marbingo.core.print

import ph.marbingo.core.model.PricePartDto
import ph.marbingo.core.money.Money

data class ReceiptLineItem(
    val name: String,
    val qty: Int,
    val unit: String?,
    val unitPrice: Long,
    val lineTotal: Long,
    /** Price breakdown (packs + loose units). Empty for legacy lines, which print as "qty x unit price". */
    val parts: List<PricePartDto> = emptyList(),
)

data class ReceiptData(
    val shopName: String,
    val ref: String,
    val storeName: String,
    val storeAddress: String? = null,
    val agentName: String,
    val orderedAt: String,
    val lines: List<ReceiptLineItem>,
    val total: Long,
    val notes: String? = null,
    val footer: String = "Order only — not yet delivered",
    /** Heading under the shop name. */
    val title: String = "ORDER SLIP",
    /** Label of the [ref] row ("Order: #12", "Receipt: S-3F9A"). */
    val refLabel: String = "Order",
) {
    companion object {
        const val SALES_TITLE = "SALES RECEIPT"
        const val SALES_FOOTER = "Paid — thank you"
    }
}

data class ReceiptLine(
    val text: String,
    val align: EscPos.Align = EscPos.Align.LEFT,
    val bold: Boolean = false,
    val doubleHeight: Boolean = false,
)

/** Pure layout for a 58mm printer (32 columns). */
object ReceiptFormatter {
    const val COLUMNS = 32

    fun format(r: ReceiptData, columns: Int = COLUMNS): List<ReceiptLine> {
        val out = mutableListOf<ReceiptLine>()
        val c = EscPos.Align.CENTER
        val rule = "-".repeat(columns)
        wrap(r.shopName, columns).forEach { out += ReceiptLine(it, c, bold = true, doubleHeight = true) }
        out += ReceiptLine(r.title, c, bold = true)
        out += ReceiptLine(rule)
        out += ReceiptLine("${r.refLabel}: ${r.ref}")
        out += ReceiptLine("Date: ${r.orderedAt}")
        out += ReceiptLine("Agent: ${r.agentName}")
        wrap("Store: ${r.storeName}", columns).forEach { out += ReceiptLine(it) }
        r.storeAddress?.takeIf { it.isNotBlank() }?.let { a -> wrap(a, columns).forEach { out += ReceiptLine(it) } }
        out += ReceiptLine(rule)
        for (l in r.lines) {
            wrap(l.name, columns).forEach { out += ReceiptLine(it) }
            if (l.parts.isEmpty()) {
                val left = "${l.qty} x ${Money.format(l.unitPrice)}"
                out += ReceiptLine(twoCol(left, Money.format(l.lineTotal), columns))
            } else {
                l.parts.forEach { partRows(it, l.unit ?: "pc", columns).forEach { r -> out += ReceiptLine(r) } }
                if (l.parts.size > 1) out += ReceiptLine(twoCol("  Item total", Money.format(l.lineTotal), columns))
            }
        }
        out += ReceiptLine(rule)
        out += ReceiptLine(twoCol("TOTAL", Money.format(r.total), columns), bold = true)
        r.notes?.takeIf { it.isNotBlank() }?.let { n ->
            out += ReceiptLine("")
            wrap("Notes: $n", columns).forEach { out += ReceiptLine(it) }
        }
        out += ReceiptLine("")
        wrap(r.footer, columns).forEach { out += ReceiptLine(it, c) }
        return out
    }

    /**
     * One indented row per breakdown part: "  1 Sack(50kg) x 2,900.00   2,900.00" or "  3 kg x 62.00      186.00".
     * When a pack row is too wide for the paper, a single pack drops the redundant "x price"; otherwise the
     * amount moves to its own right-aligned row.
     */
    internal fun partRows(p: PricePartDto, unit: String, columns: Int): List<String> {
        val label = if (p.name != null) "${p.name}(${p.size}$unit)" else unit
        val amount = Money.format(p.amount, "")
        val full = "  ${p.count} $label x ${Money.format(p.price, "")}"
        if (full.length + 1 + amount.length <= columns) return listOf(twoCol(full, amount, columns))
        val short = "  ${p.count} $label"
        if (p.name != null && p.count == 1 && short.length + 1 + amount.length <= columns) return listOf(twoCol(short, amount, columns))
        return listOf(full.take(columns), amount.padStart(columns))
    }

    fun toPlainText(r: ReceiptData, columns: Int = COLUMNS): String =
        format(r, columns).joinToString("\n") { align(it.text, it.align, columns) }.let { EscPos.transliterate(it) }

    fun toEscPos(r: ReceiptData, columns: Int = COLUMNS): ByteArray {
        val p = EscPos().init()
        for (l in format(r, columns)) {
            p.align(l.align).bold(l.bold).doubleHeight(l.doubleHeight).line(l.text)
        }
        return p.align(EscPos.Align.LEFT).bold(false).doubleHeight(false).feed(4).cut().toByteArray()
    }

    private fun align(s: String, a: EscPos.Align, columns: Int): String = when (a) {
        EscPos.Align.CENTER -> " ".repeat(((columns - s.length) / 2).coerceAtLeast(0)) + s
        EscPos.Align.RIGHT -> s.padStart(columns)
        else -> s
    }

    /** Left text and right-aligned amount on one row; if too long the left is trimmed. */
    internal fun twoCol(left: String, right: String, columns: Int): String {
        val room = columns - right.length - 1
        val l = if (left.length > room) left.take(room.coerceAtLeast(0)) else left
        return l + " ".repeat((columns - l.length - right.length).coerceAtLeast(1)) + right
    }

    /** Word wrap; words longer than a row are hard-split. */
    internal fun wrap(text: String, width: Int): List<String> {
        val lines = mutableListOf<String>()
        for (para in text.split('\n')) {
            var cur = ""
            for (word in para.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }) {
                var w = word
                while (w.length > width) {
                    if (cur.isNotEmpty()) { lines += cur; cur = "" }
                    lines += w.take(width); w = w.drop(width)
                }
                cur = when {
                    cur.isEmpty() -> w
                    cur.length + 1 + w.length <= width -> "$cur $w"
                    else -> { lines += cur; w }
                }
            }
            lines += cur
        }
        return lines
    }
}

fun ReceiptData.toLines(columns: Int = ReceiptFormatter.COLUMNS): List<ReceiptLine> = ReceiptFormatter.format(this, columns)
fun ReceiptData.toEscPos(columns: Int = ReceiptFormatter.COLUMNS): ByteArray = ReceiptFormatter.toEscPos(this, columns)
fun ReceiptData.toPlainText(columns: Int = ReceiptFormatter.COLUMNS): String = ReceiptFormatter.toPlainText(this, columns)
