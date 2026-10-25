package ph.marbingo.core.net

import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.Test
import ph.marbingo.core.model.*
import java.util.UUID

/** Talks to the real server. Skips itself when it's unreachable or lacks /api/sync/pull. */
class LiveServerTest {
    companion object {
        const val BASE = "http://marbingo.62-84-181-147.sslip.io/"
        lateinit var api: MarbingoApi
        val store = InMemoryTokenStore()
        var ready = false

        @BeforeClass @JvmStatic fun connect() {
            try {
                api = ApiClient.create(BASE, store)
                runBlocking {
                    val r = api.login(LoginRequest("ramon@marbingo.test", "marbingo123"))
                    store.accessToken = r.accessToken; store.refreshToken = r.refreshToken; store.user = r.user
                    api.pull(null)
                }
                ready = true
            } catch (e: Throwable) { println("LiveServerTest skipped: $e"); ready = false }
        }
    }

    @Test fun pullPushAndIdempotentReplay() = runBlocking {
        assumeTrue("server unreachable or sync not deployed", ready)
        val pull = api.pull(null)
        assertTrue(pull.items.isNotEmpty())
        val item = pull.items.first()

        val custUuid = UUID.randomUUID().toString()
        val orderUuid = UUID.randomUUID().toString()
        val req = OrderBatchRequest(
            customers = listOf(CustomerInput(custUuid, "Live Test Store ${custUuid.take(4)}")),
            orders = listOf(OrderInput(orderUuid, customerClientUuid = custUuid, orderedAt = java.time.Instant.now().toString(),
                notes = "live test", lines = listOf(OrderLineInput(item.id, 2)))),
        )
        val first = api.pushOrders(req)
        val r1 = first.results.single()
        assertTrue(r1.error, r1.ok)
        assertEquals(2 * item.fieldPrice, r1.order!!.total)
        val custId = first.customers.single().id

        val second = api.pushOrders(req)
        val r2 = second.results.single()
        assertTrue(r2.ok)
        assertEquals(r1.order!!.id, r2.order!!.id)
        assertEquals(custId, second.customers.single().id)

        val again = api.pull(pull.serverTime)
        assertTrue(again.orders.any { it.clientUuid == orderUuid })
    }
}
