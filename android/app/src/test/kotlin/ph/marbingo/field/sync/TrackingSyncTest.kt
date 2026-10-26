package ph.marbingo.field.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import ph.marbingo.core.model.UserDto
import ph.marbingo.core.net.ApiClient
import ph.marbingo.core.net.InMemoryTokenStore
import ph.marbingo.field.data.GeoFix
import ph.marbingo.field.data.StoreRepositoryImpl
import ph.marbingo.field.data.db.AppDatabase
import ph.marbingo.field.data.db.CustomerEntity
import java.util.concurrent.CopyOnWriteArrayList

/** Store pins, work schedule and consent riding on the existing sync. */
@RunWith(RobolectricTestRunner::class)
class TrackingSyncTest {
    private lateinit var server: MockWebServer
    private lateinit var db: AppDatabase
    private lateinit var engine: SyncEngine
    private lateinit var stores: StoreRepositoryImpl
    private val requests = CopyOnWriteArrayList<Pair<String, String>>() // "METHOD path" to body
    @Volatile private var pullBody = pull()
    @Volatile private var consentStatus = 200

    private fun pull(extra: String = "", customers: String = "") =
        """{"serverTime":"T1","items":[],"categories":[],"customers":[$customers],"orders":[]$extra}"""

    private fun cust(uuid: String, id: Int, loc: String = "") =
        """{"id":$id,"clientUuid":"$uuid","name":"S","ownerName":null,"phone":null,"address":null,"notes":null,"active":true,"createdAt":null,"updatedAt":"2026-10-26T00:00:00.000Z"$loc}"""

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        val tokens = InMemoryTokenStore().apply { accessToken = "a"; refreshToken = "r"; user = UserDto(4, "R", "D", "r@x", "AGENT") }
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += "${request.method} ${request.path}" to request.body.readUtf8()
                return when {
                    request.path!!.startsWith("/api/sync/pull") -> MockResponse().setBody(pullBody)
                    request.path == "/api/orders/batch" -> {
                        val cs = JSONObject(requests.last().second).getJSONArray("customers")
                        val out = (0 until cs.length()).joinToString(",") { """{"clientUuid":"${cs.getJSONObject(it).getString("clientUuid")}","id":${50 + it}}""" }
                        MockResponse().setBody("""{"customers":[$out],"results":[]}""")
                    }
                    request.path == "/api/customers/7" -> MockResponse().setResponseCode(if (request.method == "PUT") 200 else 405)
                        .setBody("""{"customer":${cust("c-7", 7)}}""")
                    request.path == "/api/customers/8" -> MockResponse().setResponseCode(403).setBody("""{"error":"Not yours"}""")
                    request.path == "/api/tracking/consent" -> MockResponse().setResponseCode(consentStatus).setBody("""{"trackingConsentAt":"2026-10-26T02:00:00.000Z"}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        engine = SyncEngine(ApiClient.create(server.url("/").toString(), tokens), db, tokens)
        stores = StoreRepositoryImpl(db.customers(), object : SyncTrigger {
            override fun start() {}; override fun syncNow() {}; override fun cancelAll() {}
            override val running = kotlinx.coroutines.flow.flowOf(false)
        })
    }

    @After fun tearDown() { server.shutdown(); db.close() }

    private fun customer(uuid: String, serverId: Int?, dirty: Boolean, lat: Double? = null, src: String? = null) =
        CustomerEntity(uuid, serverId, "S", null, null, null, null, true, dirty, 1, lat = lat, lng = lat?.let { 122.0 }, locationAccuracy = lat?.let { 9.0 }, locationSource = src)

    @Test fun pullStoresScheduleAndConsent() = runBlocking {
        pullBody = pull(""","trackingSchedule":{"days":[1,2,3,4,5],"start":"08:00","end":"17:00"},"trackingConsentAt":"2026-10-20T01:00:00.000Z" """)
        assertTrue(engine.run() is SyncEngine.Outcome.Success)
        val m = db.trackingMeta().get()!!
        assertEquals("1,2,3,4,5", m.scheduleDays); assertEquals("08:00", m.scheduleStart); assertEquals("17:00", m.scheduleEnd)
        assertEquals(java.time.Instant.parse("2026-10-20T01:00:00Z").toEpochMilli(), m.consentAt)
        assertTrue(m.consentSynced)
    }

    @Test fun olderServerWithoutTrackingFieldsStillParses() = runBlocking {
        assertTrue(engine.run() is SyncEngine.Outcome.Success)
        assertNull(db.trackingMeta().get())
    }

    @Test fun offlineConsentIsPushedOnNextSync() = runBlocking {
        db.trackingMeta().saveConsent(5L, false)
        engine.run()
        assertTrue(requests.any { it.first == "POST /api/tracking/consent" })
        assertTrue(db.trackingMeta().get()!!.consentSynced)
        requests.clear()
        engine.run()
        assertFalse(requests.any { it.first == "POST /api/tracking/consent" }) // only once
    }

    @Test fun newStoreSendsItsPinInTheBatch() = runBlocking {
        stores.create("Nena", null, null, null, null, lat = 10.72, lng = 122.56, locationAccuracy = 12.0)
        engine.run()
        val body = JSONObject(requests.first { it.first == "POST /api/orders/batch" }.second).getJSONArray("customers").getJSONObject(0)
        assertEquals(10.72, body.getDouble("lat"), 1e-9); assertEquals(122.56, body.getDouble("lng"), 1e-9)
        assertEquals(12.0, body.getDouble("locationAccuracy"), 1e-9)
    }

    @Test fun newStoreWithoutPinSendsNoCoordinates() = runBlocking {
        stores.create("Nena", null, null, null, null)
        engine.run()
        val body = JSONObject(requests.first { it.first == "POST /api/orders/batch" }.second).getJSONArray("customers").getJSONObject(0)
        assertFalse(body.has("lat")); assertFalse(body.has("lng")); assertFalse(body.has("locationAccuracy"))
    }

    @Test fun pinningAnExistingStoreUsesPutAndClearsDirty() = runBlocking {
        db.customers().upsert(customer("c-7", 7, dirty = false))
        stores.setLocation("c-7", GeoFix(10.5, 122.5, 8.0))
        assertTrue(db.customers().get("c-7")!!.dirty)
        engine.run()
        val put = requests.first { it.first == "PUT /api/customers/7" }
        val j = JSONObject(put.second)
        assertEquals(10.5, j.getDouble("lat"), 1e-9); assertEquals(8.0, j.getDouble("locationAccuracy"), 1e-9)
        assertFalse(db.customers().get("c-7")!!.dirty)
        assertFalse(requests.any { it.first == "POST /api/orders/batch" })
    }

    @Test fun neverSendsAServerPinBackOrAnEmptyOne() = runBlocking {
        // A dirty store whose pin came from the server (manual) must not be pushed with coordinates at all.
        db.customers().upsert(customer("c-7", 7, dirty = true, lat = 10.0, src = "manual"))
        engine.run()
        assertFalse(requests.any { it.first.startsWith("PUT") })
        assertFalse(db.customers().get("c-7")!!.dirty)
        assertEquals(10.0, db.customers().get("c-7")!!.lat!!, 0.0)
    }

    @Test fun rejectedPinUpdateDoesNotBlockSyncForever() = runBlocking {
        db.customers().upsert(customer("c-8", 8, dirty = true, lat = 10.0, src = "device"))
        assertTrue(engine.run() is SyncEngine.Outcome.Success)
        assertFalse(db.customers().get("c-8")!!.dirty)
    }

    @Test fun pullBringsServerPinsAndKeepsLocalOneAgainstOlderServers() = runBlocking {
        pullBody = pull(customers = cust("c-1", 1, ""","lat":10.1,"lng":122.1,"locationSource":"manual","locationAccuracy":null,"locatedAt":"2026-10-26T00:00:00.000Z" """))
        engine.run()
        val c = db.customers().get("c-1")!!
        assertEquals(10.1, c.lat!!, 0.0); assertEquals("manual", c.locationSource)

        // server without location support returns the store with no pin: ours stays
        db.customers().upsert(customer("c-2", 2, dirty = false, lat = 9.0, src = "device"))
        pullBody = pull(customers = cust("c-2", 2))
        engine.run()
        assertEquals(9.0, db.customers().get("c-2")!!.lat!!, 0.0)
    }
}
