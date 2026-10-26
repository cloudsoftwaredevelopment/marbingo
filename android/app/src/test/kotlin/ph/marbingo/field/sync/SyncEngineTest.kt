package ph.marbingo.field.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import ph.marbingo.core.model.UserDto
import ph.marbingo.core.net.ApiClient
import ph.marbingo.core.net.InMemoryTokenStore
import ph.marbingo.field.data.*
import ph.marbingo.field.data.db.AppDatabase
import ph.marbingo.field.data.db.CustomerEntity
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

private class FakeTrigger : SyncTrigger {
    var syncs = 0; var cancelled = false
    override fun start() {}
    override fun syncNow() { syncs++ }
    override fun cancelAll() { cancelled = true }
    override val running: Flow<Boolean> = flowOf(false)
}

@RunWith(RobolectricTestRunner::class)
class SyncEngineTest {
    private lateinit var server: MockWebServer
    private lateinit var db: AppDatabase
    private lateinit var tokens: InMemoryTokenStore
    private lateinit var engine: SyncEngine
    private lateinit var orders: OrderRepositoryImpl
    private lateinit var stores: StoreRepositoryImpl
    private val trigger = FakeTrigger()
    private val batchCalls = AtomicInteger()
    private val ordersByUuid = mutableMapOf<String, Int>() // fake server state: clientUuid -> id
    private val cancelReasons = mutableMapOf<String, String>() // cancelReason sent per order clientUuid
    @Volatile private var batchMode = "ok" // ok | offline | itemError
    @Volatile private var pullBody = pullJson("T1", emptyList(), emptyList())

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        tokens = InMemoryTokenStore().apply {
            accessToken = "a"; refreshToken = "r"
            user = UserDto(4, "Ramon", "Dela Cruz", "ramon@marbingo.test", "AGENT")
        }
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path!!
                return when {
                    path.startsWith("/api/sync/pull") -> MockResponse().setBody(pullBody)
                    path == "/api/orders/batch" -> {
                        batchCalls.incrementAndGet()
                        when (batchMode) {
                            "offline" -> MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_START)
                            else -> MockResponse().setBody(batchResponse(request.body.readUtf8()))
                        }
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        val api = ApiClient.create(server.url("/").toString(), tokens)
        engine = SyncEngine(api, db, tokens)
        orders = OrderRepositoryImpl(db, api, tokens, trigger)
        stores = StoreRepositoryImpl(db.customers(), trigger)
        runBlocking { db.items().upsertAll(listOf(itemDto(1, 5600).let { ph.marbingo.field.data.db.ItemEntity(1, "Rice", "480", "pc", null, null, 6200, 5600, 5600, 10, null, "x") })) }
    }

    @After fun tearDown() { server.shutdown(); db.close() }

    private fun itemDto(id: Int, price: Long) = id to price

    private fun pullJson(time: String, customers: List<String>, orders: List<String>) =
        """{"serverTime":"$time","items":[],"categories":[],"customers":[${customers.joinToString(",")}],"orders":[${orders.joinToString(",")}]}"""

    private fun custJson(uuid: String, id: Int, name: String) =
        """{"id":$id,"clientUuid":"$uuid","name":"$name","ownerName":null,"phone":null,"address":null,"notes":null,"active":true,"createdAt":null,"updatedAt":"2026-10-26T00:00:00.000Z"}"""

    private fun orderJson(uuid: String, id: Int, custUuid: String, total: Long, status: String = "pending") =
        """{"id":$id,"clientUuid":"$uuid","status":"$status","customer":{"id":3,"clientUuid":"$custUuid","name":"Nena"},
        "agent":{"id":4,"firstName":"Ramon","lastName":"Dela Cruz"},"orderedAt":"2026-10-26T01:00:00.000Z","syncedAt":null,
        "updatedAt":"2026-10-26T01:00:00.000Z","deliveredAt":null,"deliveredBy":null,"cancelledReason":null,"notes":null,"total":$total,"saleId":null,
        "lines":[{"itemId":1,"name":"Rice","unit":"pc","qty":2,"unitPrice":5600,"lineTotal":$total,"deliveredQty":null}]}"""

    /** Minimal fake of /orders/batch: idempotent on clientUuid, rejects itemId 99. */
    private fun batchResponse(body: String): String {
        val j = org.json.JSONObject(body)
        val custs = j.getJSONArray("customers")
        val custOut = (0 until custs.length()).joinToString(",") {
            val u = custs.getJSONObject(it).getString("clientUuid"); """{"clientUuid":"$u","id":${100 + it}}"""
        }
        val os = j.getJSONArray("orders")
        val results = (0 until os.length()).joinToString(",") {
            val o = os.getJSONObject(it)
            val uuid = o.getString("clientUuid")
            if (o.has("cancelReason") && !o.isNull("cancelReason")) cancelReasons[uuid] = o.getString("cancelReason")
            val bad = (0 until o.getJSONArray("lines").length()).any { i -> o.getJSONArray("lines").getJSONObject(i).getInt("itemId") == 99 }
            if (bad) """{"clientUuid":"$uuid","ok":false,"error":"Unknown item 99."}"""
            else {
                val id = ordersByUuid.getOrPut(uuid) { 500 + ordersByUuid.size }
                val cu = o.optString("customerClientUuid", "c")
                """{"clientUuid":"$uuid","ok":true,"order":${orderJson(uuid, id, cu, 11200)}}"""
            }
        }
        return """{"customers":[$custOut],"results":[$results]}"""
    }

    private suspend fun newStoreAndOrder(itemId: Int = 1): Pair<String, String> {
        val s = stores.create("Aling Nena", null, null, null, null)
        val item = db.items().byId(1)!!.toModel().copy(id = itemId)
        val o = orders.place(s, listOf(CartLine(item, 2)), "note")
        return s to o
    }

    @Test fun offlineQueueThenRetrySucceeds() = runBlocking {
        val (storeUuid, orderUuid) = newStoreAndOrder()
        assertEquals(2, trigger.syncs) // store create + order place
        val placed = orders.observe(orderUuid).first()!!
        assertEquals(11200L, placed.total)
        assertEquals(SyncState.QUEUED, placed.syncState)

        batchMode = "offline"
        val r1 = engine.run()
        assertTrue(r1 is SyncEngine.Outcome.Retry)
        assertEquals(SyncState.QUEUED, orders.observe(orderUuid).first()!!.syncState)
        assertTrue(db.customers().get(storeUuid)!!.dirty)

        batchMode = "ok"
        assertTrue(engine.run() is SyncEngine.Outcome.Success)
        val synced = orders.observe(orderUuid).first()!!
        assertEquals(SyncState.SYNCED, synced.syncState)
        assertEquals(500, synced.serverId)
        assertEquals("Ramon Dela Cruz", synced.agentName)
        val st = db.customers().get(storeUuid)!!
        assertFalse(st.dirty); assertEquals(100, st.serverId)
        assertEquals("T1", db.syncMeta().get()!!.since)
        assertNotNull(db.syncMeta().get()!!.lastSyncedAt)
    }

    @Test fun idempotentReplayDoesNotDuplicate() = runBlocking {
        val (_, orderUuid) = newStoreAndOrder()
        engine.run()
        // Simulate the response being lost: the order is queued again locally, the server already has it.
        val e = db.orders().get(orderUuid)!!
        db.orders().upsert(e.copy(syncState = "QUEUED"))
        engine.run()
        assertEquals(1, orders.recent(100).first().size)
        assertEquals(1, ordersByUuid.size)
        assertEquals(500, orders.observe(orderUuid).first()!!.serverId)
        assertEquals(SyncState.SYNCED, orders.observe(orderUuid).first()!!.syncState)
    }

    @Test fun perOrderErrorIsSurfaced() = runBlocking {
        val (_, orderUuid) = newStoreAndOrder(itemId = 99)
        assertTrue(engine.run() is SyncEngine.Outcome.Success)
        val o = orders.observe(orderUuid).first()!!
        assertEquals(SyncState.ERROR, o.syncState)
        assertEquals("Unknown item 99.", o.syncError)
        assertEquals(1, db.orders().get(orderUuid)!!.attempts)
        engine.run()
        assertEquals(2, db.orders().get(orderUuid)!!.attempts)
    }

    @Test fun pullUpdatesCleanCustomers() = runBlocking {
        db.customers().upsert(CustomerEntity("u-clean", 7, "Old", null, null, null, null, true, false, 1))
        pullBody = pullJson("T2", listOf(custJson("u-clean", 7, "New")), emptyList())
        assertTrue(engine.run() is SyncEngine.Outcome.Success)
        assertEquals("New", db.customers().get("u-clean")!!.name)
    }

    @Test fun pullKeepsDirtyCustomerWhenPushDidNotCoverIt() = runBlocking {
        // Dirty customer created while the pull is in flight: pull must skip it.
        val api = ApiClient.create(server.url("/").toString(), tokens)
        val racing = SyncEngine(object : ph.marbingo.core.net.MarbingoApi by api {
            override suspend fun pull(since: String?): ph.marbingo.core.model.SyncPullResponse {
                db.customers().upsert(CustomerEntity("u-new", null, "Local only", null, null, null, null, true, true, 1))
                return api.pull(since)
            }
        }, db, tokens)
        pullBody = pullJson("T3", listOf(custJson("u-new", 9, "Server version")), emptyList())
        assertTrue(racing.run() is SyncEngine.Outcome.Success)
        val c = db.customers().get("u-new")!!
        assertEquals("Local only", c.name); assertTrue(c.dirty); assertNull(c.serverId)
    }

    @Test fun pullUpsertsServerOrdersAndServerWins() = runBlocking {
        pullBody = pullJson("T4", emptyList(), listOf(orderJson("o-1", 12, "c-1", 11200, "delivered")))
        engine.run()
        val o = orders.observe("o-1").first()!!
        assertEquals(OrderStatus.DELIVERED, o.status)
        assertEquals("#12", o.reference)
        assertEquals(1, o.lines.size)
    }

    @Test fun signOutRefusedWithQueuedOrders() = runBlocking {
        newStoreAndOrder()
        val auth = AuthRepositoryImpl(ApiClient.create(server.url("/").toString(), tokens), prefsStore(), db, engine, trigger)
        assertFalse(auth.signOut())
        assertFalse(trigger.cancelled)
        assertEquals(1, db.orders().waitingCountNow())
        assertTrue(auth.signOut(force = true))
        assertTrue(trigger.cancelled)
        assertEquals(0, db.orders().waitingCountNow())
    }

    @Test fun signOutAllowedWhenNothingWaiting() = runBlocking {
        val auth = AuthRepositoryImpl(ApiClient.create(server.url("/").toString(), tokens), prefsStore(), db, engine, trigger)
        assertTrue(auth.signOut())
    }

    @Test fun cancelUnsyncedOrderIsQueuedAndSentAsCancelled() = runBlocking {
        val (_, orderUuid) = newStoreAndOrder()
        orders.cancel(orderUuid, "changed mind")
        val local = orders.observe(orderUuid).first()!!
        assertEquals(OrderStatus.CANCELLED, local.status)
        assertEquals(SyncState.QUEUED, local.syncState) // not deleted: the server may already have it
        engine.run()
        assertEquals("changed mind", cancelReasons[orderUuid])
    }

    @Test fun cancelAfterLostBatchReplyStillReachesServer() = runBlocking {
        val (_, orderUuid) = newStoreAndOrder()
        batchMode = "offline" // first upload: no reply, order stays QUEUED
        engine.run()
        orders.cancel(orderUuid, "store closed")
        batchMode = "ok"
        engine.run()
        assertEquals("store closed", cancelReasons[orderUuid])
    }

    @Test fun cancelSyncedOrderOfflineGivesClearError() = runBlocking {
        val (_, orderUuid) = newStoreAndOrder()
        engine.run()
        server.shutdown()
        try { orders.cancel(orderUuid, "x"); fail() } catch (e: UserFacingException) {
            assertTrue(e.message!!.contains("connection"))
        }
    }

    private fun prefsStore() = PrefsTokenStore(ApplicationProvider.getApplicationContext())
}
