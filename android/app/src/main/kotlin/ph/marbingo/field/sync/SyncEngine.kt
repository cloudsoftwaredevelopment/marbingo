package ph.marbingo.field.sync

import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ph.marbingo.core.model.*
import ph.marbingo.core.net.ApiErrors
import ph.marbingo.core.net.MarbingoApi
import ph.marbingo.core.net.TokenStore
import ph.marbingo.field.data.Iso
import ph.marbingo.field.data.toEntities
import ph.marbingo.field.data.toEntity
import ph.marbingo.field.data.db.AppDatabase
import ph.marbingo.field.data.db.OrderWithLines
import ph.marbingo.field.data.db.SyncMetaEntity
import retrofit2.HttpException
import java.io.IOException

/** Push queued work, then pull changes. Plain class so it is testable without WorkManager. */
class SyncEngine(
    private val api: MarbingoApi,
    private val db: AppDatabase,
    private val tokens: TokenStore,
    private val now: () -> Long = System::currentTimeMillis,
) {
    sealed class Outcome {
        object Success : Outcome()
        /** Transient (offline, 5xx): try again later. */
        data class Retry(val message: String) : Outcome()
        /** Session is gone: the user must sign in again. */
        data class AuthFailed(val message: String) : Outcome()
        /** Permanent rejection (other 4xx): retrying won't help. */
        data class Failed(val message: String) : Outcome()
    }

    private val lock = Mutex()

    suspend fun run(): Outcome = lock.withLock {
        if (tokens.accessToken == null && tokens.refreshToken == null) {
            return@withLock Outcome.AuthFailed("Not signed in.")
        }
        try {
            push()
            pull()
            Outcome.Success
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpException) {
            val msg = ApiErrors.message(e)
            recordError(msg)
            when {
                e.code() == 401 -> Outcome.AuthFailed(msg)
                ApiErrors.isRetryable(e) || e.code() == 408 || e.code() == 429 -> Outcome.Retry(msg)
                else -> Outcome.Failed(msg)
            }
        } catch (e: IOException) {
            recordError(ApiErrors.OFFLINE)
            Outcome.Retry(ApiErrors.OFFLINE)
        } catch (e: Exception) {
            val msg = e.message ?: "Sync failed."
            recordError(msg)
            Outcome.Retry(msg)
        }
    }

    private suspend fun recordError(msg: String) {
        val m = db.syncMeta().get() ?: SyncMetaEntity()
        db.syncMeta().put(m.copy(lastError = msg))
    }

    // ---- push ----

    private suspend fun push() {
        val pending = db.orders().pendingForPush()
        if (pending.isEmpty() && db.customers().dirty().isEmpty()) return
        val chunks = pending.chunked(MAX_BATCH).ifEmpty { listOf(emptyList()) }
        for (chunk in chunks) pushChunk(chunk)
    }

    private suspend fun pushChunk(chunk: List<OrderWithLines>) {
        val dirty = db.customers().dirty()
        val dirtyByUuid = dirty.associateBy { it.clientUuid }
        val inputs = mutableListOf<OrderInput>()
        val sendable = mutableListOf<OrderWithLines>()
        for (o in chunk) {
            val cust = dirtyByUuid[o.order.customerClientUuid] ?: db.customers().get(o.order.customerClientUuid)
            if (cust == null) {
                db.orders().markError(o.order.clientUuid, "This store is missing on the device.")
                continue
            }
            inputs += OrderInput(
                clientUuid = o.order.clientUuid,
                customerClientUuid = if (cust.serverId == null || cust.dirty) cust.clientUuid else null,
                customerId = if (cust.serverId != null && !cust.dirty) cust.serverId else null,
                orderedAt = Iso.format(o.order.orderedAt),
                notes = o.order.notes,
                lines = o.lines.sortedBy { it.id }.map { OrderLineInput(it.itemId, it.qty) },
            )
            sendable += o
        }
        if (inputs.isEmpty() && dirty.isEmpty()) return

        val req = OrderBatchRequest(
            customers = dirty.map { CustomerInput(it.clientUuid, it.name, it.ownerName, it.phone, it.address, it.notes) },
            orders = inputs,
        )
        val resp = try {
            api.pushOrders(req)
        } catch (e: HttpException) {
            // A 4xx other than auth/throttling means the server rejected the batch itself.
            if (e.code() in 400..499 && e.code() != 401 && e.code() != 408 && e.code() != 429) {
                val msg = ApiErrors.message(e)
                sendable.forEach { db.orders().markError(it.order.clientUuid, msg) }
                return
            }
            throw e
        }

        db.withTransaction {
            resp.customers.forEach { db.customers().markSynced(it.clientUuid, it.id) }
            val byUuid = resp.results.associateBy { it.clientUuid }
            for (o in sendable) {
                val r = byUuid[o.order.clientUuid] ?: continue
                val dto = r.order
                if (r.ok && dto != null) {
                    // The user may have deleted it locally while the request was in flight.
                    val existing = db.orders().get(o.order.clientUuid) ?: continue
                    val (e, rows) = dto.toEntities(existing, now())
                    db.orders().replace(e, rows)
                } else {
                    db.orders().markError(o.order.clientUuid, r.error ?: "The server rejected this order.")
                }
            }
        }
    }

    // ---- pull ----

    private suspend fun pull() {
        val meta = db.syncMeta().get() ?: SyncMetaEntity()
        val resp = api.pull(meta.since)
        val t = now()
        db.withTransaction {
            db.items().upsertAll(resp.items.map { it.toEntity() })
            for (c in resp.customers) {
                val existing = db.customers().get(c.clientUuid)
                if (existing?.dirty == true) continue // never clobber unsent local changes
                db.customers().upsert(c.toEntity(t))
            }
            for (o in resp.orders) {
                val (e, rows) = o.toEntities(db.orders().get(o.clientUuid), t)
                db.orders().replace(e, rows)
            }
            db.syncMeta().put(SyncMetaEntity(since = resp.serverTime, lastSyncedAt = t, lastError = null))
        }
    }

    companion object { const val MAX_BATCH = 100 }
}
