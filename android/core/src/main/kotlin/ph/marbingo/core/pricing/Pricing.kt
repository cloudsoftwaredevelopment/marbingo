package ph.marbingo.core.pricing

import com.squareup.moshi.Types
import ph.marbingo.core.model.PricePartDto
import ph.marbingo.core.net.ApiClient

/**
 * Pack pricing (docs/pricing.md). A faithful port of server/src/lib/pricing.js: the cheapest mix of whole
 * packs plus loose units. All three implementations must pass docs/pricing-vectors.json. Money is centavos.
 */

/** A wholesale bundle of [size] base units sold at [price] for the whole pack. */
data class PackDef(val packTypeId: Int?, val name: String, val size: Int, val price: Long)

data class PriceResult(val total: Long, val parts: List<PricePartDto>)

/** Buying [addQty] more would cost [total] (<= the current price) so the buyer saves [saving]. */
data class Upsell(val addQty: Int, val total: Long, val saving: Long)

object Pricing {
    const val DP_LIMIT = 100_000

    private fun usable(packs: List<PackDef>): List<PackDef> =
        packs.filter { it.size >= 2 && it.price > 0 }.sortedWith(compareBy<PackDef>({ -it.size }, { it.price }))

    fun priceQty(qty: Int, retail: Long, packs: List<PackDef> = emptyList()): PriceResult {
        val n = maxOf(0, qty)
        val u = usable(packs)
        val counts = if (n <= DP_LIMIT) solveExact(n, retail, u) else solveGreedy(n, retail, u)
        return build(counts, retail, u)
    }

    private class Counts(val loose: Int, val packs: IntArray)

    // Minimises (cost, loose units, pack count) lexicographically; options are tried loose-first, then packs by
    // size descending, replacing only on a strictly better key (same tie-breaking as the reference).
    private fun solveExact(n: Int, retail: Long, packs: List<PackDef>): Counts {
        val cost = LongArray(n + 1)
        val loose = IntArray(n + 1)
        val cnt = IntArray(n + 1)
        val pick = IntArray(n + 1) // -1 = loose unit, else index into packs
        for (q in 1..n) {
            cost[q] = cost[q - 1] + retail; loose[q] = loose[q - 1] + 1; cnt[q] = cnt[q - 1]; pick[q] = -1
            for (i in packs.indices) {
                val s = packs[i].size
                if (s > q) continue
                val c = cost[q - s] + packs[i].price
                val l = loose[q - s]
                val k = cnt[q - s] + 1
                if (c < cost[q] || (c == cost[q] && (l < loose[q] || (l == loose[q] && k < cnt[q])))) {
                    cost[q] = c; loose[q] = l; cnt[q] = k; pick[q] = i
                }
            }
        }
        var looseCount = 0
        val pc = IntArray(packs.size)
        var q = n
        while (q > 0) {
            val i = pick[q]
            if (i == -1) { looseCount++; q-- } else { pc[i]++; q -= packs[i].size }
        }
        return Counts(looseCount, pc)
    }

    private fun solveGreedy(n: Int, retail: Long, packs: List<PackDef>): Counts {
        val pc = IntArray(packs.size)
        var left = n
        packs.forEachIndexed { i, p ->
            if (p.price < p.size.toLong() * retail) {
                pc[i] = left / p.size
                left -= pc[i] * p.size
            }
        }
        return Counts(left, pc)
    }

    private fun build(c: Counts, retail: Long, packs: List<PackDef>): PriceResult {
        val parts = mutableListOf<PricePartDto>()
        packs.forEachIndexed { i, p ->
            val k = c.packs[i]
            if (k > 0) parts += PricePartDto(p.packTypeId, p.name, p.size, k, p.price, k.toLong() * p.price)
        }
        if (c.loose > 0) parts += PricePartDto(null, null, 1, c.loose, retail, c.loose.toLong() * retail)
        return PriceResult(parts.sumOf { it.amount }, parts)
    }

    /** When a little more costs the same or less (49 kg loose vs one 50 kg sack), suggest it. */
    fun suggestUpsell(qty: Int, retail: Long, packs: List<PackDef> = emptyList()): Upsell? {
        val n = maxOf(0, qty)
        val sizes = packs.filter { it.size >= 2 && it.price > 0 }.map { it.size }
        if (n == 0 || sizes.isEmpty() || n > DP_LIMIT) return null
        val now = priceQty(n, retail, packs).total
        for (q in n + 1..n + sizes.max()) {
            val t = priceQty(q, retail, packs).total
            if (t <= now) return Upsell(q - n, t, now - t)
        }
        return null
    }

    /** "1 × Sack (50 kg) + 3 kg". */
    fun describe(parts: List<PricePartDto>, unit: String = "pc"): String =
        parts.joinToString(" + ") { if (it.name != null) "${it.count} × ${it.name} (${it.size} $unit)" else "${it.count} $unit" }
}

/** Order-line breakdowns are stored in Room as JSON text. */
object PartsJson {
    private val adapter = ApiClient.moshi.adapter<List<PricePartDto>>(
        Types.newParameterizedType(List::class.java, PricePartDto::class.java),
    )

    fun encode(parts: List<PricePartDto>?): String? = parts?.let { adapter.toJson(it) }
    fun decode(json: String?): List<PricePartDto>? =
        if (json.isNullOrBlank()) null else runCatching { adapter.fromJson(json) }.getOrNull()
}
