package ph.marbingo.field.data

import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import ph.marbingo.core.model.LoginRequest
import ph.marbingo.core.net.ApiErrors
import ph.marbingo.core.net.MarbingoApi
import ph.marbingo.field.data.db.AppDatabase
import ph.marbingo.field.sync.SyncEngine
import ph.marbingo.field.sync.SyncTrigger
import retrofit2.HttpException
import java.io.IOException

class AuthRepositoryImpl(
    private val api: MarbingoApi,
    private val tokens: PrefsTokenStore,
    private val db: AppDatabase,
    private val engine: SyncEngine,
    private val trigger: SyncTrigger,
) : AuthRepository {

    override val session: Flow<Session?> =
        tokens.changes.map { currentSession() }.distinctUntilChanged()

    private fun currentSession(): Session? {
        if (tokens.accessToken.isNullOrBlank()) return null
        val u = tokens.user ?: return null
        val shop = tokens.shopName ?: "Marbingo"
        return Session(u.id, "${u.firstName} ${u.lastName}".trim(), u.role, shop, tokens.shopMark ?: shop.take(2).uppercase())
    }

    override suspend fun signIn(email: String, password: String): Session {
        val resp = try {
            api.login(LoginRequest(email.trim(), password))
        } catch (e: HttpException) {
            throw UserFacingException(ApiErrors.message(e, "Couldn't sign in. Check your email and password."))
        } catch (e: IOException) {
            throw UserFacingException("You need a connection to sign in the first time.")
        }
        val role = resp.user.role.uppercase()
        if (role != "AGENT" && role != "MANAGER") {
            throw UserFacingException("This app is for field agents. Use the web dashboard instead.")
        }

        // Another account's data is on this device: keep its unsent work safe, otherwise start clean.
        val owner = tokens.dataOwnerId
        if (owner != null && owner != resp.user.id) {
            val waiting = withContext(Dispatchers.IO) { db.orders().waitingCountNow() + db.customers().dirtyCountNow() }
            if (waiting > 0) {
                throw UserFacingException("Another account has orders on this device that haven't synced yet. Sign in with that account first.")
            }
            withContext(Dispatchers.IO) { db.clearAllTables() }
        }

        tokens.accessToken = resp.accessToken
        tokens.refreshToken = resp.refreshToken
        tokens.user = resp.user
        tokens.dataOwnerId = resp.user.id
        try {
            val s = api.settings()
            tokens.shopName = s.shopName
            tokens.shopMark = s.shopMark
        } catch (_: Exception) {
            tokens.shopName = tokens.shopName ?: "Marbingo"
        }

        trigger.start()
        // First full sync inline so the catalog is ready; failures are fine, the worker retries.
        try { engine.run() } catch (_: Exception) {}
        return currentSession() ?: throw UserFacingException("Couldn't sign in. Try again.")
    }

    override suspend fun signOut(force: Boolean): Boolean {
        if (!force) {
            val waiting = withContext(Dispatchers.IO) { db.orders().waitingCountNow() + db.customers().dirtyCountNow() }
            if (waiting > 0) return false
        }
        trigger.cancelAll()
        withContext(Dispatchers.IO) { db.clearAllTables() }
        tokens.dataOwnerId = null
        tokens.clear()
        return true
    }
}
