package ph.marbingo.core.net

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import java.util.concurrent.atomic.AtomicInteger

class ApiClientTest {
    private lateinit var server: MockWebServer
    private lateinit var store: InMemoryTokenStore
    private lateinit var api: MarbingoApi

    @Before fun setUp() {
        server = MockWebServer()
        store = InMemoryTokenStore().apply { accessToken = "old"; refreshToken = "r1" }
        api = ApiClient.create(server.url("/").toString(), store)
    }
    @After fun tearDown() { server.shutdown() }

    private val settingsJson = """{"shopName":"Marbingo"}"""

    @Test fun refreshesOn401AndReplays() = runBlocking {
        val refreshes = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path!!.endsWith("auth/refresh") -> { refreshes.incrementAndGet(); MockResponse().setBody("""{"accessToken":"new","refreshToken":"r2"}""") }
                request.getHeader("Authorization") == "Bearer new" -> MockResponse().setBody(settingsJson)
                else -> MockResponse().setResponseCode(401).setBody("""{"error":"expired"}""")
            }
        }
        assertEquals("Marbingo", api.settings().shopName)
        assertEquals(1, refreshes.get())
        assertEquals("new", store.accessToken)
        assertEquals("r2", store.refreshToken)
    }

    @Test fun parallel401sRefreshOnce() = runBlocking {
        val refreshes = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path!!.endsWith("auth/refresh") -> { refreshes.incrementAndGet(); Thread.sleep(150); MockResponse().setBody("""{"accessToken":"new","refreshToken":"r2"}""") }
                request.getHeader("Authorization") == "Bearer new" -> MockResponse().setBody(settingsJson)
                else -> MockResponse().setResponseCode(401)
            }
        }
        val results = (1..4).map { async(Dispatchers.IO) { api.settings().shopName } }.awaitAll()
        assertEquals(List(4) { "Marbingo" }, results)
        assertEquals(1, refreshes.get())
    }

    @Test fun refreshRejectedClearsStore() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = when {
                request.path!!.endsWith("auth/refresh") -> MockResponse().setResponseCode(401).setBody("""{"error":"Session expired"}""")
                else -> MockResponse().setResponseCode(401)
            }
        }
        try { api.settings(); fail() } catch (e: HttpException) { assertEquals(401, e.code()) }
        assertNull(store.accessToken); assertNull(store.refreshToken)
    }

    @Test fun refreshServerErrorKeepsSession() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = when {
                request.path!!.endsWith("auth/refresh") -> MockResponse().setResponseCode(500)
                else -> MockResponse().setResponseCode(401)
            }
        }
        try { api.settings(); fail() } catch (e: HttpException) { assertEquals(401, e.code()) }
        assertEquals("r1", store.refreshToken)
    }

    @Test fun errorMessages() {
        val body = okhttp3.ResponseBody.create(null, """{"error":"Unknown item 99."}""")
        val e = HttpException(retrofit2.Response.error<Any>(400, body))
        assertEquals("Unknown item 99.", ApiErrors.message(e))
        assertEquals(ApiErrors.OFFLINE, ApiErrors.message(java.io.IOException()))
        assertTrue(ApiErrors.isRetryable(java.io.IOException()))
        assertFalse(ApiErrors.isRetryable(e))
    }

    @Test fun latestAppParses200() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"versionCode":7,"versionName":"0.7","url":"/downloads/marbingo-field-v0.7.apk","sha256":"ab12","size":123,"minVersionCode":6,"notes":"Fixes","publishedAt":"2026-10-27T00:00:00.000Z"}"""))
        val r = api.latestApp()
        val u = r.body()!!
        assertEquals(7, u.versionCode)
        assertEquals("/downloads/marbingo-field-v0.7.apk", u.url)
        assertEquals(123L, u.size)
        assertEquals(6, u.minVersionCode)
        assertEquals("Fixes", u.notes)
        assertEquals("/api/app/latest", server.takeRequest().path)
    }

    @Test fun latestAppNothingPublishedIs204() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))
        val r = api.latestApp()
        assertTrue(r.isSuccessful)
        assertEquals(204, r.code())
        assertNull(r.body())
    }
}
