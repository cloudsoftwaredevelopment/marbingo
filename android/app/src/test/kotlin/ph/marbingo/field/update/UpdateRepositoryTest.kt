package ph.marbingo.field.update

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ph.marbingo.core.net.ApiClient
import ph.marbingo.core.net.InMemoryTokenStore
import java.io.File
import java.security.MessageDigest

class UpdateRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: MockWebServer
    private lateinit var dir: File
    private lateinit var platform: FakePlatform
    private var clock = 1_000_000L

    private class FakePlatform(var allowed: Boolean = true) : UpdatePlatform {
        val installed = mutableListOf<File>()
        var settingsOpened = 0
        override fun canInstall() = allowed
        override fun install(apk: File) { installed += apk }
        override fun openInstallSettings() { settingsOpened++ }
    }

    private val apk = ByteArray(200_000) { (it % 251).toByte() }
    private val apkSha = MessageDigest.getInstance("SHA-256").digest(apk).joinToString("") { "%02x".format(it) }

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        dir = File(tmp.newFolder(), "updates")
        platform = FakePlatform()
    }
    @After fun tearDown() { server.shutdown() }

    private fun repo(current: Int = 6): UpdateRepositoryImpl {
        val base = server.url("/").toString()
        return UpdateRepositoryImpl(ApiClient.create(base, InMemoryTokenStore()), OkHttpClient(), base, dir, current, platform, now = { clock })
    }

    private fun manifest(code: Int = 7, min: Int = 0, sha: String = apkSha, size: Long = apk.size.toLong()) =
        MockResponse().setBody("""{"versionCode":$code,"versionName":"0.$code","url":"/downloads/app.apk","sha256":"$sha","size":$size,"minVersionCode":$min,"notes":"Fixes"}""")

    private fun apkResponse() = MockResponse().setBody(Buffer().write(apk))

    private fun info(code: Int, min: Int = 0) = UpdateInfo(code, "0.$code", "", "/x.apk", "ab", 1, min)

    // ---- decision logic ----

    @Test fun decide() {
        assertEquals(UpdateState.UpToDate, decideUpdate(null, 6))
        assertEquals(UpdateState.UpToDate, decideUpdate(info(6), 6))
        assertEquals(UpdateState.UpToDate, decideUpdate(info(5), 6))
        assertEquals(UpdateState.Available(info(7), required = false), decideUpdate(info(7), 6))
        assertEquals(UpdateState.Available(info(7, min = 6), required = false), decideUpdate(info(7, min = 6), 6)) // min == current: optional
        assertEquals(UpdateState.Available(info(8, min = 7), required = true), decideUpdate(info(8, min = 7), 6))
    }

    @Test fun checkOffersNewerBuildAndKeepsRequiredFlag() = runBlocking {
        server.enqueue(manifest(code = 8, min = 7))
        val r = repo()
        r.check(force = true)
        val s = r.state.value as UpdateState.Available
        assertTrue(s.required)
        assertEquals("0.8", r.offer.value!!.info.versionName)
    }

    @Test fun nothingPublishedIsUpToDate() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))
        val r = repo()
        r.check(force = true)
        assertEquals(UpdateState.UpToDate, r.state.value)
        assertNull(r.offer.value)
    }

    @Test fun automaticChecksAreThrottledButManualOnesAreNot() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(MockResponse().setResponseCode(204))
        val r = repo()
        r.check(); r.check()
        assertEquals(1, server.requestCount)
        clock += UpdateRepositoryImpl.THROTTLE_MS + 1
        r.check()
        assertEquals(2, server.requestCount)
        server.enqueue(MockResponse().setResponseCode(204))
        r.check(force = true)
        assertEquals(3, server.requestCount)
    }

    @Test fun automaticCheckFailsQuietlyManualReports() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        val r = repo()
        r.check()
        assertEquals(UpdateState.Idle, r.state.value)
        server.enqueue(MockResponse().setResponseCode(500))
        r.check(force = true)
        assertTrue(r.state.value is UpdateState.Failed)
    }

    // ---- download + verify + install ----

    @Test fun downloadsVerifiesAndInstalls() = runBlocking {
        server.enqueue(manifest())
        server.enqueue(apkResponse())
        val r = repo()
        r.check(force = true)
        r.downloadAndInstall()
        assertEquals(UpdateState.ReadyToInstall, r.state.value)
        assertEquals(1, platform.installed.size)
        assertEquals(apkSha, UpdateRepositoryImpl.sha256(platform.installed[0]))
        assertEquals("/api/app/latest", server.takeRequest().path)
        assertEquals("/downloads/app.apk", server.takeRequest().path)
    }

    @Test fun checksumMismatchDeletesFileAndFails() = runBlocking {
        server.enqueue(manifest(sha = "0".repeat(64)))
        server.enqueue(apkResponse())
        val r = repo()
        r.check(force = true)
        r.downloadAndInstall()
        val s = r.state.value as UpdateState.Failed
        assertTrue(s.message, s.message.contains("damaged"))
        assertTrue(platform.installed.isEmpty())
        assertEquals(emptyList<String>(), dir.list()!!.toList()) // neither .apk nor .part left behind
        assertNotNull(r.offer.value) // the user can retry
    }

    @Test fun sizeMismatchFails() = runBlocking {
        server.enqueue(manifest(size = apk.size + 5L))
        server.enqueue(apkResponse())
        val r = repo()
        r.check(force = true)
        r.downloadAndInstall()
        assertTrue(r.state.value is UpdateState.Failed)
        assertTrue(dir.list()!!.isEmpty())
    }

    @Test fun missingInstallPermissionKeepsFileAndResumesWhenGranted() = runBlocking {
        platform.allowed = false
        server.enqueue(manifest())
        server.enqueue(apkResponse())
        val r = repo()
        r.check(force = true)
        r.downloadAndInstall()
        assertEquals(UpdateState.NeedsPermission, r.state.value)
        assertTrue(platform.installed.isEmpty())
        r.recheckPermission() // still not allowed: nothing happens
        assertEquals(UpdateState.NeedsPermission, r.state.value)

        platform.allowed = true
        r.recheckPermission() // reuses the verified download: no second request
        assertEquals(UpdateState.ReadyToInstall, r.state.value)
        assertEquals(1, platform.installed.size)
        assertEquals(2, server.requestCount)
    }

    @Test fun installFailureIsMapped() = runBlocking {
        server.enqueue(manifest())
        server.enqueue(apkResponse())
        val r = repo()
        r.check(force = true)
        r.downloadAndInstall()
        r.onInstallResult(android.content.pm.PackageInstaller.STATUS_FAILURE_STORAGE, null)
        assertEquals("Not enough free storage to install the update.", (r.state.value as UpdateState.Failed).message)
    }

    @Test fun cleanStaleRemovesOldAndPartialDownloads() {
        dir.mkdirs()
        File(dir, "5.apk").writeText("x"); File(dir, "6.apk").writeText("x"); File(dir, "7.apk").writeText("x"); File(dir, "7.apk.part").writeText("x")
        repo(current = 6).cleanStale()
        assertEquals(listOf("7.apk"), dir.list()!!.toList())
    }

    @Test fun resolvesRelativeUrlsAgainstBase() {
        val r = repo()
        val base = server.url("/").toString().trimEnd('/')
        assertEquals("$base/downloads/a.apk", r.resolveUrl("/downloads/a.apk"))
        assertEquals("https://cdn.example/a.apk", r.resolveUrl("https://cdn.example/a.apk"))
    }
}
