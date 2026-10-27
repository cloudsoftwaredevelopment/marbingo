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
    private val truckSalesByUuid = mutableMapOf<String, Int>()
    private val truckBatches = mutableListOf<String>()
    private lateinit var truck: TruckRepositoryImpl
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
                    path == "/api/truck-sales/batch" -> {
                        val body = request.body.readUtf8()
                        truckBatches += body
                        when (batchMode) {
                            "offline" -> MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_START)
                            else -> MockResponse().setBody(truckBatchResponse(body))
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
        truck = TruckRepositoryImpl(db, tokens, trigger)
        runBlocking { db.items().upsertAll(listOf(itemDto(1, 5600).let { ph.marbingo.field.data.db.ItemEntity(1, "Rice", "480", "pc", null, null, 5600, 10, null, "x") })) }
    }

    @After fun tearDown() { server.shutdown(); db.close() }

    private fun itemDto(id: Int, price: Long) = id to price

    private fun pullJson(time: String, customers: List<String>, orders: List<String>, items: List<String> = emptyList()) =
        """{"serverTime":"$time","items":[${items.joinToString(",")}],"packTypes":[{"id":5,"name":"Sack","defaultSize":null,"sort":5,"active":true}],"categories":[],"customers":[${customers.joinToString(",")}],"orders":[${orders.joinToString(",")}]}"""

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

    /** Minimal fake of /truck-sales/batch: idempotent on clientUuid, rejects itemId 99, flags oversold when qty > 100. */
    private fun truckBatchResponse(body: String): String {
        val j = org.json.JSONObject(body)
        val custs = j.getJSONArray("customers")
        val custOut = (0 until custs.length()).joinToString(",") {
            """{"clientUuid":"${custs.getJSONObject(it).getString("clientUuid")}","id":${200 + it}}"""
        }
        val ss = j.getJSONArray("sales")
        val results = (0 until ss.length()).joinToString(",") {
            val sale = ss.getJSONObject(it)
            val uuid = sale.getString("clientUuid")
            val ls = sale.getJSONArray("lines")
            if ((0 until ls.length()).any { i -> ls.getJSONObject(i).getInt("itemId") == 99 }) {
                """{"clientUuid":"$uuid","ok":false,"error":"Unknown item 99."}"""
            } else {
                val id = truckSalesByUuid.getOrPut(uuid) { 900 + truckSalesByUuid.size }
                val lines = (0 until ls.length()).joinToString(",") { i ->
                    val l = ls.getJSONObject(i); val q = l.getInt("qty")
                    """{"itemId":${l.getInt("itemId")},"qty":$q,"unitPrice":5000,"lineTotal":${q * 5000},"breakdown":[{"packTypeId":null,"name":null,"size":1,"count":$q,"price":5000,"amount":${q * 5000}}]}"""
                }
                val total = (0 until ls.length()).sumOf { i -> ls.getJSONObject(i).getInt("qty") * 5000 }
                """{"clientUuid":"$uuid","ok":true,"sale":{"id":$id,"clientUuid":"$uuid","truckId":2,"periodId":2,"soldAt":"2026-10-27T05:00:00.000Z","customerId":null,"total":$total,"oversold":false,"late":true,"lines":[$lines]}}"""
            }
        }
        return """{"customers":[$custOut],"results":[$results]}"""
    }

    private fun truckPull(stock: String, period: String? = """{"id":2,"month":"2026-10","status":"open"}""") =
        pullJson("T2", emptyList(), emptyList()).dropLast(1) +
            ""","truck":{"id":2,"name":"Truck 2","plate":"ABC 123","driverName":"Lito","period":${period ?: "null"},"stock":[$stock]}}"""

    private suspend fun seedTruck(qty: Int = 10) {
        pullBody = truckPull("""{"itemId":1,"qty":$qty}""")
        assertTrue(engine.run() is SyncEngine.Outcome.Success)
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

    private fun itemJson(id: Int, retail: Long, packs: String) =
        """{"id":$id,"name":"Rice","sku":"480","unit":"kg","categoryId":null,"category":null,"sellingPrice":$retail,"qtyOnHand":10,"imageUrl":null,"updatedAt":"2026-10-26T00:00:00.000Z","packs":[$packs]}"""

    @Test fun pullStoresPacksReplacesThemAndKeepsBreakdowns() = runBlocking {
        val sack = """{"id":3,"packTypeId":5,"name":"Sack","size":50,"price":290000}"""
        val box = """{"id":4,"packTypeId":2,"name":"Box","size":10,"price":59000}"""
        val bd = """[{"packTypeId":5,"name":"Sack","size":50,"count":1,"price":290000,"amount":290000},{"packTypeId":null,"name":null,"size":1,"count":3,"price":6200,"amount":18600}]"""
        val dbd = """[{"packTypeId":null,"name":null,"size":1,"count":30,"price":6200,"amount":186000}]"""
        val order = orderJson("o-9", 9, "c-9", 308600, "delivered")
            .replace(""""deliveredQty":null}""", """"deliveredQty":30,"breakdown":$bd,"deliveredBreakdown":$dbd}""")
        pullBody = pullJson("T2", listOf(custJson("c-9", 3, "Nena")), listOf(order), listOf(itemJson(1, 6200, "$sack,$box")))
        assertTrue(engine.run() is SyncEngine.Outcome.Success)

        val item = db.items().byId(1)!!.toModel()
        assertEquals(6200L, item.sellingPrice)
        assertEquals(listOf("Sack" to 50, "Box" to 10), item.packs.map { it.name to it.size }) // size descending
        assertEquals(290000L, item.packs[0].price)

        val line = orders.observe("o-9").first()!!.lines.single()
        assertEquals(2, line.breakdown!!.size)
        assertEquals("Sack", line.breakdown!![0].name); assertEquals(18600L, line.breakdown!![1].amount)
        assertEquals(186000L, line.deliveredBreakdown!!.single().amount)

        // The next pull replaces the item's packs (the Box is gone, the Sack price changed).
        pullBody = pullJson("T3", emptyList(), emptyList(), listOf(itemJson(1, 6200, sack.replace("290000", "280000"))))
        assertTrue(engine.run() is SyncEngine.Outcome.Success)
        val again = db.items().byId(1)!!.toModel()
        assertEquals(listOf("Sack"), again.packs.map { it.name }); assertEquals(280000L, again.packs[0].price)
    }

    @Test fun placePricesLocallyWithPacksAndStoresTheBreakdown() = runBlocking {
        val s = stores.create("Aling Nena", null, null, null, null)
        db.items().upsertWithPacks(
            listOf(ph.marbingo.field.data.db.ItemEntity(7, "Jasmine rice", "481", "kg", null, null, 6200, 400, null, "x")),
            listOf(ph.marbingo.field.data.db.ItemPackEntity(7, 5, "Sack", 50, 290000)),
        )
        val item = db.items().byId(7)!!.toModel()
        // Two cart lines for the same item are merged before pricing: 20 + 33 = 53 kg.
        val id = orders.place(s, listOf(CartLine(item, 20), CartLine(item, 33)), null)
        val o = orders.observe(id).first()!!
        val line = o.lines.single()
        assertEquals(53, line.qty); assertEquals(6200L, line.unitPrice)
        assertEquals(290000L + 3 * 6200, line.lineTotal); assertEquals(line.lineTotal, o.total)
        assertEquals(listOf("Sack", null), line.breakdown!!.map { it.name })
        assertEquals(listOf(1, 3), line.breakdown!!.map { it.count })
        assertNull(line.deliveredBreakdown)
    }

    // ---- truck sales ----

    @Test fun pullReplacesTruckMetaAndStockAndNullClearsIt() = runBlocking {
        seedTruck(10)
        assertEquals("Truck 2", db.truck().meta()!!.name)
        assertEquals("open", db.truck().meta()!!.periodStatus)
        assertEquals(10, db.truck().stockFor(1)!!.available)
        // The next pull replaces (not merges) the stock: item 1 disappears, item 3 arrives; period goes to counting.
        pullBody = truckPull("""{"itemId":3,"qty":-2}""", """{"id":2,"month":"2026-10","status":"counting"}""")
        engine.run()
        assertNull(db.truck().stockFor(1)); assertEquals(-2, db.truck().stockFor(3)!!.qty)
        assertEquals("counting", db.truck().meta()!!.periodStatus)
        // No truck block (older server / unassigned): everything is cleared.
        pullBody = pullJson("T3", emptyList(), emptyList())
        engine.run()
        assertNull(db.truck().meta()); assertNull(db.truck().stockFor(3))
        assertNull(truck.observeTruck().first())
    }

    @Test fun truckSaleQueuesAvailableArithmeticAndSyncs() = runBlocking {
        seedTruck(10)
        val item = db.items().byId(1)!!.toModel()
        val id = truck.placeSale(null, listOf(CartLine(item, 4)))
        assertEquals(1, trigger.syncs)
        val queued = truck.observe(id).first()!!
        assertTrue(queued.isSale); assertEquals(SyncState.QUEUED, queued.syncState); assertEquals(22400L, queued.total)
        // Never mutates truck_stock: server qty stays 10, available drops to 6.
        assertEquals(10, db.truck().stockFor(1)!!.qty); assertEquals(6, db.truck().stockFor(1)!!.available)
        assertEquals(6, truck.observeAvailable().first()[1]); assertEquals(6, truck.observeTruck().first()!!.unitsOnBoard)
        // Can't sell more than what is left.
        try { truck.placeSale(null, listOf(CartLine(item, 7))); fail() } catch (e: UserFacingException) { assertTrue(e.message!!.contains("6")) }

        // Offline: stays queued, nothing lost.
        batchMode = "offline"
        assertTrue(engine.run() is SyncEngine.Outcome.Retry)
        assertEquals(SyncState.QUEUED, truck.observe(id).first()!!.syncState)
        assertEquals(6, db.truck().stockFor(1)!!.available)

        // Online: pushed, server price/flags win, and the pull (stock now 6 on the server) leaves available at 6, not 2.
        batchMode = "ok"
        pullBody = truckPull("""{"itemId":1,"qty":6}""")
        assertTrue(engine.run() is SyncEngine.Outcome.Success)
        val synced = truck.observe(id).first()!!
        assertEquals(SyncState.SYNCED, synced.syncState); assertTrue(synced.reference.startsWith("S#"))
        assertEquals(20000L, synced.total) // server price 5000 x 4
        assertEquals("Rice", synced.lines.single().name)
        assertEquals(6, db.truck().stockFor(1)!!.available)
        assertEquals(0, db.truck().waitingCountNow())
    }

    @Test fun truckSaleReplayIsIdempotentAndErrorsAreRecorded() = runBlocking {
        seedTruck(10)
        val item = db.items().byId(1)!!.toModel()
        val id = truck.placeSale(null, listOf(CartLine(item, 1)))
        engine.run()
        val serverId = truck.observe(id).first()!!.serverId
        // Simulate a lost reply: put the same sale back to QUEUED and push again; the server returns the same row.
        db.truck().upsert(db.truck().get(id)!!.copy(syncState = SyncState.QUEUED.name, serverId = null))
        engine.run()
        assertEquals(serverId, truck.observe(id).first()!!.serverId)
        assertEquals(1, truckSalesByUuid.size)

        // A sale the server rejects is marked ERROR (and still counts against available until fixed).
        val bad = db.items().byId(1)!!.toModel().copy(id = 99)
        db.items().upsertAll(listOf(ph.marbingo.field.data.db.ItemEntity(99, "Ghost", null, "pc", null, null, 100, 0, null, "x")))
        db.truck().insertStock(listOf(ph.marbingo.field.data.db.TruckStockEntity(99, 5)))
        val badId = truck.placeSale(null, listOf(CartLine(bad, 1)))
        engine.run()
        val e = truck.observe(badId).first()!!
        assertEquals(SyncState.ERROR, e.syncState); assertEquals("Unknown item 99.", e.syncError)
    }

    @Test fun truckSaleToNewStoreSendsTheStoreInTheSameBatchAndWalkInSendsNone() = runBlocking {
        seedTruck(10)
        val item = db.items().byId(1)!!.toModel()
        val storeUuid = stores.create("Aling Rosa", null, null, "Brgy. Daro", null)
        val walkIn = truck.placeSale(null, listOf(CartLine(item, 1)))
        val named = truck.placeSale(storeUuid, listOf(CartLine(item, 2)))
        assertEquals("Aling Rosa", truck.observe(named).first()!!.storeName)
        engine.run()
        val body = org.json.JSONObject(truckBatches.last())
        val sales = body.getJSONArray("sales")
        val byUuid = (0 until sales.length()).associate { sales.getJSONObject(it).getString("clientUuid") to sales.getJSONObject(it) }
        assertFalse(byUuid.getValue(walkIn).has("customerClientUuid")); assertFalse(byUuid.getValue(walkIn).has("customerId"))
        // The order push (no orders) already carried the new store, so the sale references it by server id.
        val n = byUuid.getValue(named)
        assertTrue(n.has("customerId") || n.has("customerClientUuid"))
        assertEquals(SyncState.SYNCED, truck.observe(walkIn).first()!!.syncState)
        assertEquals(SyncState.SYNCED, truck.observe(named).first()!!.syncState)
        assertFalse(db.customers().get(storeUuid)!!.dirty)
    }

    @Test fun placeSaleBlockedUntilThePeriodIsOpenAndSignOutCountsTruckSales() = runBlocking {
        pullBody = truckPull("""{"itemId":1,"qty":10}""", """{"id":2,"month":"2026-10","status":"draft"}""")
        engine.run()
        val item = db.items().byId(1)!!.toModel()
        try { truck.placeSale(null, listOf(CartLine(item, 1))); fail() } catch (e: UserFacingException) { assertTrue(e.message!!.contains("approval")) }
        assertTrue(truck.observeTruck().first()!!.waitingForApproval)

        seedTruck(10)
        truck.placeSale(null, listOf(CartLine(item, 1)))
        val auth = AuthRepositoryImpl(ApiClient.create(server.url("/").toString(), tokens), PrefsTokenStore(ApplicationProvider.getApplicationContext()), db, engine, trigger)
        assertFalse(auth.signOut(false)) // a truck sale is still unsynced
    }
}
