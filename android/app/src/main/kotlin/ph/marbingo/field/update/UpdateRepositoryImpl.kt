package ph.marbingo.field.update

import android.content.pm.PackageInstaller
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import ph.marbingo.core.net.ApiErrors
import ph.marbingo.core.net.MarbingoApi
import ph.marbingo.field.data.UpdateRepository
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** The Android bits, so the repository is testable on the JVM. */
interface UpdatePlatform {
    fun canInstall(): Boolean
    /** Starts a PackageInstaller session for [apk]; the result arrives at [UpdateRepositoryImpl.onInstallResult]. Throws on failure. */
    fun install(apk: File)
    fun openInstallSettings()
}

class UpdateRepositoryImpl(
    private val api: MarbingoApi,
    private val http: OkHttpClient,
    private val baseUrl: String,
    private val dir: File,
    private val currentVersionCode: Int,
    private val platform: UpdatePlatform,
    private val now: () -> Long = System::currentTimeMillis,
) : UpdateRepository {

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    override val state: StateFlow<UpdateState> = _state.asStateFlow()
    private val _offer = MutableStateFlow<UpdateState.Available?>(null)
    override val offer: StateFlow<UpdateState.Available?> = _offer.asStateFlow()

    private val busy = Mutex() // one check / download at a time
    private var lastCheckAt = 0L

    override suspend fun check(force: Boolean) {
        if (!force && lastCheckAt != 0L && now() - lastCheckAt < THROTTLE_MS) return
        if (!busy.tryLock()) return
        try {
            if (_state.value.let { it is UpdateState.Downloading || it is UpdateState.ReadyToInstall }) return
            val info = try {
                val r = api.latestApp()
                if (!r.isSuccessful) throw IOException("HTTP ${r.code()}")
                r.body()?.toInfo() // 204: nothing published
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Only a manual check reports the problem; an offer we already have stays.
                if (force && _offer.value == null) _state.value = UpdateState.Failed(checkFailure(e))
                return
            }
            lastCheckAt = now()
            val decided = decideUpdate(info, currentVersionCode)
            _offer.value = decided as? UpdateState.Available
            // Don't bounce the user out of the permission prompt they are looking at.
            if (!(decided is UpdateState.Available && _state.value == UpdateState.NeedsPermission)) _state.value = decided
        } finally {
            busy.unlock()
        }
    }

    override suspend fun downloadAndInstall() {
        val offer = _offer.value ?: run { check(force = true); _offer.value ?: return }
        if (!busy.tryLock()) return
        try {
            val info = offer.info
            val file = fileFor(info)
            if (!isVerified(file, info)) {
                file.delete()
                try {
                    download(info, file)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: VerifyException) {
                    file.delete()
                    _state.value = UpdateState.Failed(e.message ?: "The download was damaged. Try again.")
                    return
                } catch (e: Exception) {
                    file.delete()
                    _state.value = UpdateState.Failed(downloadFailure(e))
                    return
                }
            }
            if (!platform.canInstall()) {
                _state.value = UpdateState.NeedsPermission
                return
            }
            _state.value = UpdateState.ReadyToInstall
            withContext(Dispatchers.IO) { platform.install(file) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value = UpdateState.Failed(e.message ?: "Could not start the install.")
        } finally {
            busy.unlock()
        }
    }

    override fun openInstallSettings() = platform.openInstallSettings()

    override suspend fun recheckPermission() {
        if (_state.value == UpdateState.NeedsPermission && platform.canInstall()) downloadAndInstall()
    }

    /** From [InstallResultReceiver]. SUCCESS never really arrives (the process is replaced), but is handled anyway. */
    fun onInstallResult(status: Int, message: String?) {
        when (status) {
            PackageInstaller.STATUS_SUCCESS -> {}
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {} // the receiver launches the confirmation; stay in ReadyToInstall
            else -> _state.value = UpdateState.Failed(installFailure(status, message))
        }
    }

    /** Deletes downloaded APKs of this or older versions (and half-finished downloads). Call on startup. */
    fun cleanStale() {
        dir.listFiles()?.forEach { f ->
            val code = f.name.removeSuffix(".apk").toIntOrNull()
            if (code == null || code <= currentVersionCode) f.delete()
        }
    }

    // ---- download ----

    internal fun fileFor(info: UpdateInfo) = File(dir, "${info.versionCode}.apk")

    internal fun resolveUrl(url: String): String = when {
        url.startsWith("http://") || url.startsWith("https://") -> url
        else -> baseUrl.trimEnd('/') + "/" + url.trimStart('/')
    }

    private fun isVerified(file: File, info: UpdateInfo) =
        file.isFile && file.length() == info.size && sha256(file) == info.sha256

    private suspend fun download(info: UpdateInfo, target: File) = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val part = File(dir, "${info.versionCode}.apk.part")
        _state.value = UpdateState.Downloading(0f)
        http.newCall(Request.Builder().url(resolveUrl(info.url)).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("Empty response")
            val total = if (info.size > 0) info.size else body.contentLength()
            val digest = MessageDigest.getInstance("SHA-256")
            var read = 0L
            var lastPct = -1
            body.byteStream().use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        read += n
                        val pct = if (total > 0) (read * 100 / total).toInt().coerceIn(0, 100) else 0
                        if (pct != lastPct) { lastPct = pct; _state.value = UpdateState.Downloading(pct / 100f) }
                    }
                }
            }
            val hex = digest.digest().joinToString("") { "%02x".format(it) }
            if (read != info.size || hex != info.sha256) {
                part.delete()
                throw VerifyException("The download was damaged (checksum mismatch). Try again.")
            }
        }
        if (!part.renameTo(target)) { part.delete(); throw IOException("Could not save the update.") }
    }

    private class VerifyException(message: String) : Exception(message)

    companion object {
        const val THROTTLE_MS = 30 * 60 * 1000L

        fun sha256(file: File): String {
            val d = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) { val n = input.read(buf); if (n < 0) break; d.update(buf, 0, n) }
            }
            return d.digest().joinToString("") { "%02x".format(it) }
        }

        private fun checkFailure(e: Exception) =
            if (e is IOException && e.message?.startsWith("HTTP") != true) "No connection. Check for updates when you're online."
            else "Couldn't check for updates right now."

        private fun downloadFailure(e: Exception) =
            if (e is IOException && e.message?.startsWith("HTTP") != true) "The download was interrupted. Check your connection and try again."
            else "Couldn't download the update (${e.message ?: "error"}). Try again."

        fun installFailure(status: Int, message: String?): String = when (status) {
            PackageInstaller.STATUS_FAILURE_ABORTED -> "The install was cancelled."
            PackageInstaller.STATUS_FAILURE_BLOCKED -> "Android blocked the install. Check Play Protect or device policy."
            PackageInstaller.STATUS_FAILURE_CONFLICT -> "The update conflicts with the installed app. Ask your manager for help."
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "This update can't run on this tablet."
            PackageInstaller.STATUS_FAILURE_INVALID -> "The update file is not valid. Try again."
            PackageInstaller.STATUS_FAILURE_STORAGE -> "Not enough free storage to install the update."
            else -> message?.takeIf { it.isNotBlank() } ?: "The install failed."
        }
    }
}
