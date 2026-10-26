package ph.marbingo.core.pricing

import com.squareup.moshi.Types
import org.junit.Assert.*
import org.junit.Test
import ph.marbingo.core.model.PricePartDto
import ph.marbingo.core.net.ApiClient

/** Runs every case of docs/pricing-vectors.json (path from the Gradle system property). */
class PricingVectorsTest {
    data class VPack(val packTypeId: Int?, val name: String, val size: Int, val price: Long)
    data class VExpect(val total: Long, val parts: List<PricePartDto>)
    data class VUpsell(val addQty: Int, val total: Long, val saving: Long)
    data class VCase(val name: String, val qty: Double, val retail: Long, val packs: List<VPack>, val expect: VExpect, val upsell: VUpsell?)
    data class VFile(val version: Int, val cases: List<VCase>)

    private val file: VFile by lazy {
        val text = java.io.File(System.getProperty("pricing.vectors")).readText()
        ApiClient.moshi.adapter(VFile::class.java).fromJson(text)!!
    }

    @Test fun everyVector() {
        assertTrue(file.cases.size >= 20)
        for (c in file.cases) {
            val packs = c.packs.map { PackDef(it.packTypeId, it.name, it.size, it.price) }
            val qty = Math.floor(c.qty).toInt()
            val r = Pricing.priceQty(qty, c.retail, packs)
            assertEquals("${c.name}: total", c.expect.total, r.total)
            assertEquals("${c.name}: parts", c.expect.parts, r.parts)
            val u = Pricing.suggestUpsell(qty, c.retail, packs)
            if (c.upsell == null) assertNull("${c.name}: upsell", u)
            else assertEquals("${c.name}: upsell", Upsell(c.upsell.addQty, c.upsell.total, c.upsell.saving), u)
        }
    }

    @Test fun describeMatchesContract() {
        val r = Pricing.priceQty(53, 6200, listOf(PackDef(5, "Sack", 50, 290000)))
        assertEquals("1 × Sack (50 kg) + 3 kg", Pricing.describe(r.parts, "kg"))
        assertEquals(290000L + 3 * 6200, r.total)
    }

    @Test fun partsJsonRoundTrip() {
        val r = Pricing.priceQty(53, 6200, listOf(PackDef(5, "Sack", 50, 290000)))
        assertEquals(r.parts, PartsJson.decode(PartsJson.encode(r.parts)))
        assertNull(PartsJson.decode(null)); assertNull(PartsJson.decode("garbage"))
    }
}
