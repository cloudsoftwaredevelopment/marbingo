package ph.marbingo.core.money

object Money {
    /** 123400 -> "₱1,234.00". Integer math only; en-PH grouping (thousands commas). */
    fun format(centavos: Long, symbol: String = "₱"): String {
        val neg = centavos < 0
        val abs = if (neg) -centavos else centavos
        val whole = (abs / 100).toString()
        val cents = (abs % 100).toString().padStart(2, '0')
        val grouped = StringBuilder()
        whole.forEachIndexed { i, c ->
            if (i > 0 && (whole.length - i) % 3 == 0) grouped.append(',')
            grouped.append(c)
        }
        return (if (neg) "-" else "") + symbol + grouped + "." + cents
    }

    /** Parses "1,234.50" / "₱12" into centavos, or null when malformed. */
    fun parse(text: String): Long? {
        val t = text.replace("₱", "").replace(",", "").trim()
        if (!Regex("""\d+(\.\d{1,2})?""").matches(t)) return null
        val parts = t.split('.')
        val cents = if (parts.size > 1) parts[1].padEnd(2, '0').toLong() else 0L
        return parts[0].toLong() * 100 + cents
    }
}
