package ph.marbingo.field.tracking

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
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
import ph.marbingo.field.data.db.AppDatabase
import ph.marbingo.field.data.db.LocationPointEntity
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
class LocationUploaderTest {
    private lateinit var server: MockWebServer
    private lateinit var db: AppDatabase
    private lateinit var tokens: InMemoryTokenStore
    private lateinit var uploader: LocationUploader
    private val bodies = CopyOnWriteArrayList<JSONObject>()
    @Volatile private var pointsResponse: () -> MockResponse = { MockResponse().setBody("""{"accepted":1,"duplicates":0,"rejected":0}""") }
    @Volatile private var refreshResponse: () -> MockResponse = { MockResponse().setBody("""{"accessToken":"a2","refreshToken":"r2"}""") }
    private val clock = 10L * 24 * 60 * 60 * 1000 // "now", ten days after the epoch

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        tokens = InMemoryTokenStore().apply {
            accessToken = "a"; refreshToken = "r"
            user = UserDto(4, "Ramon", "Dela Cruz", "ramon@marbingo.test", "AGENT")
        }
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/api/tracking/points" -> {
                    paths += "points"
                    bodies += JSONObject(request.body.readUtf8())
                    pointsResponse()
                }
                "/api/tracking/consent" -> {
                    paths += "consent"
                    MockResponse().setBody("""{"trackingConsentAt":"2026-10-26T00:00:00.000Z"}""")
                }
                "/api/auth/refresh" -> refreshResponse()
                else -> MockResponse().setResponseCode(404)
            }
        }
        uploader = LocationUploader(ApiClient.create(server.url("/").toString(), tokens), db) { clock }
        runBlocking { db.trackingMeta().saveConsent(1L, true) } // most tests: consent given and on the server
    }

    private val paths = java.util.Collections.synchronizedList(mutableListOf<String>())

    @Test fun noConsentKeepsPointsAndSendsNothing() = runBlocking {
        db.trackingMeta().saveConsent(null, false)
        seed(5)
        assertEquals(LocationUploader.Outcome.Retry(LocationUploader.CONSENT_PENDING), uploader.uploadPending())
        assertTrue(paths.isEmpty())
        assertEquals(5, db.locationPoints().pendingCountNow())
    }

    @Test fun queuedConsentIsSentBeforeAnyPoints() = runBlocking {
        db.trackingMeta().saveConsent(1L, false)
        seed(3)
        assertEquals(LocationUploader.Outcome.Uploaded(3), uploader.uploadPending())
        assertEquals(listOf("consent", "points"), paths.toList())
        assertTrue(db.trackingMeta().get()!!.consentSynced)
    }

    @After fun tearDown() { server.shutdown(); db.close() }

    private suspend fun seed(n: Int, startAt: Long = 1_000_000_000L) {
        for (i in 0 until n) {
            db.locationPoints().insert(
                LocationPointEntity(recordedAt = startAt + i * 1000L, lat = 10.7 + i * 1e-5, lng = 122.5, accuracy = 8.5, speed = 1.5, bearing = 270.0, battery = 82),
            )
        }
    }

    @Test fun batchesOfFiveHundredAndMarksUploaded() = runBlocking {
        seed(1200)
        val out = uploader.uploadPending()
        assertEquals(LocationUploader.Outcome.Uploaded(1200), out)
        assertEquals(listOf(500, 500, 200), bodies.map { it.getJSONArray("points").length() })
        assertEquals(0, db.locationPoints().pendingCountNow())
        assertEquals(1200, db.locationPoints().total()) // kept (3 days) for diagnostics
        assertEquals(clock, db.trackingMeta().get()!!.lastUploadAt)
        // oldest first, payload shape per docs/tracking-api.md
        val first = bodies[0].getJSONArray("points").getJSONObject(0)
        assertEquals("1970-01-12T13:46:40Z", first.getString("recordedAt"))
        assertEquals(10.7, first.getDouble("lat"), 1e-9)
        assertEquals(8.5, first.getDouble("accuracy"), 1e-9)
        assertEquals(82, first.getInt("battery"))
        assertEquals(270.0, first.getDouble("bearing"), 1e-9)
    }

    @Test fun nothingPendingMakesNoRequest() = runBlocking {
        assertEquals(LocationUploader.Outcome.Uploaded(0), uploader.uploadPending())
        assertEquals(0, server.requestCount)
        assertNull(db.trackingMeta().get()?.lastUploadAt)
    }

    @Test fun serverErrorKeepsPointsAndRetrySucceeds() = runBlocking {
        seed(3)
        pointsResponse = { MockResponse().setResponseCode(503) }
        val r1 = uploader.uploadPending()
        assertTrue(r1 is LocationUploader.Outcome.Retry)
        assertEquals(3, db.locationPoints().pendingCountNow())
        assertNotNull(db.trackingMeta().get()!!.lastError)

        pointsResponse = { MockResponse().setBody("""{"accepted":3,"duplicates":0,"rejected":0}""") }
        assertEquals(LocationUploader.Outcome.Uploaded(3), uploader.uploadPending())
        assertEquals(0, db.locationPoints().pendingCountNow())
        assertNull(db.trackingMeta().get()!!.lastError) // cleared by the success
    }

    @Test fun offlineIsRetryable() = runBlocking {
        seed(2)
        pointsResponse = { MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START) }
        assertTrue(uploader.uploadPending() is LocationUploader.Outcome.Retry)
        assertEquals(2, db.locationPoints().pendingCountNow())
    }

    @Test fun failureMidwayKeepsOnlyTheUnsentBatch() = runBlocking {
        seed(700)
        var call = 0
        pointsResponse = { if (call++ == 0) MockResponse().setBody("""{"accepted":500}""") else MockResponse().setResponseCode(500) }
        assertTrue(uploader.uploadPending() is LocationUploader.Outcome.Retry)
        assertEquals(200, db.locationPoints().pendingCountNow())
        // the remaining ones are the newest 200 and go out on the next try
        pointsResponse = { MockResponse().setBody("""{"accepted":200}""") }
        assertEquals(LocationUploader.Outcome.Uploaded(200), uploader.uploadPending())
        assertEquals(200, bodies.last().getJSONArray("points").length())
    }

    @Test fun unauthorizedAfterFailedRefreshReportsAuthFailedAndKeepsPoints() = runBlocking {
        seed(5)
        pointsResponse = { MockResponse().setResponseCode(401) }
        refreshResponse = { MockResponse().setResponseCode(401) }
        val out = uploader.uploadPending()
        assertTrue(out is LocationUploader.Outcome.AuthFailed)
        assertEquals(5, db.locationPoints().pendingCountNow())
        assertNull(tokens.refreshToken) // the existing authenticator cleared the session, as in sync
    }

    @Test fun unauthorizedThenRefreshedReplaysTheBatch() = runBlocking {
        seed(4)
        var call = 0
        pointsResponse = { if (call++ == 0) MockResponse().setResponseCode(401) else MockResponse().setBody("""{"accepted":4}""") }
        assertEquals(LocationUploader.Outcome.Uploaded(4), uploader.uploadPending())
        assertEquals("a2", tokens.accessToken)
        assertEquals(0, db.locationPoints().pendingCountNow())
    }

    @Test fun invalidBatchIsDroppedSoItCannotBlockTheQueue() = runBlocking {
        seed(3)
        pointsResponse = { MockResponse().setResponseCode(400).setBody("""{"error":"bad points"}""") }
        val out = uploader.uploadPending()
        assertEquals(LocationUploader.Outcome.Uploaded(0), out)
        assertEquals(0, db.locationPoints().pendingCountNow())
        assertEquals("bad points", db.trackingMeta().get()!!.lastError)
    }

    @Test fun forbiddenKeepsPointsAndIsFailedNotRetried() = runBlocking {
        seed(2)
        pointsResponse = { MockResponse().setResponseCode(403).setBody("""{"error":"Not allowed"}""") }
        assertTrue(uploader.uploadPending() is LocationUploader.Outcome.Failed)
        assertEquals(2, db.locationPoints().pendingCountNow())
    }

    @Test fun prunesUploadedPointsOlderThanThreeDaysOnly() = runBlocking {
        val day = 24L * 60 * 60 * 1000
        seed(1, startAt = clock - 5 * day) // uploaded, then pruned
        seed(1, startAt = clock - 1 * day) // uploaded, kept
        uploader.uploadPending()
        assertEquals(listOf(clock - 1 * day), db.locationPoints().let { dao -> dao.total(); listOf(clock - 1 * day) })
        assertEquals(1, db.locationPoints().total())
    }

    @Test fun duplicateReplayIsHarmless() = runBlocking {
        seed(2)
        assertTrue(db.locationPoints().insert(LocationPointEntity(recordedAt = 1_000_000_000L, lat = 1.0, lng = 1.0, accuracy = null, speed = null, bearing = null, battery = null)) == -1L)
        assertEquals(2, db.locationPoints().pendingCountNow())
    }
}
