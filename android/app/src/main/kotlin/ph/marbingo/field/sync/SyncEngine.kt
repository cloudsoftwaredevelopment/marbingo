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
import ph.marbingo.field.data.OrderStatus
import ph.marbingo.field.data.toEntities
import ph.marbingo.field.data.toEntity
import ph.marbingo.field.data.db.AppDatabase
import ph.marbingo.field.data.db.CustomerEntity
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
        pushConsent()
        pushStoreLocations()
        val pending = db.orders().pendingForPush()
        if (pending.isEmpty() && db.customers().dirty().none { it.serverId == null }) return
        val chunks = pending.chunked(MAX_BATCH).ifEmpty { listOf(emptyList()) }
        for (chunk in chunks) pushChunk(chunk)
    }

    /** Consent given while offline: tell the server now. A failure just leaves it queued for the next sync. */
    private suspend fun pushConsent() {
        val meta = db.trackingMeta().get() ?: return
        if (meta.consentAt == null || meta.consentSynced) return
        try {
            api.giveTrackingConsent()
            db.trackingMeta().markConsentSynced()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) { }
    }

    /**
     * Stores that already exist on the server are updated with PUT (the batch upsert returns an existing row untouched).
     * Only a pin the agent set on this device is sent, so a server-side manual pin is never overwritten with nothing.
     */
    private suspend fun pushStoreLocations() {
        for (c in db.customers().dirty()) {
            val id = c.serverId ?: continue
            if (!c.hasDeviceLocation) { db.customers().markSynced(c.clientUuid, id); continue }
            try {
                api.updateCustomerLocation(id, CustomerLocationInput(c.lat!!, c.lng!!, c.locationAccuracy))
                db.customers().markSynced(c.clientUuid, id)
            } catch (e: HttpException) {
                // Not allowed (someone else's store) or rejected: retrying won't help, the next pull restores the server copy.
                if (e.code() in 400..499 && e.code() != 401 && e.code() != 408 && e.code() != 429) {
                    db.customers().markSynced(c.clientUuid, id)
                } else throw e
            }
        }
    }

    private suspend fun pushChunk(chunk: List<OrderWithLines>) {
        val dirty = db.customers().dirty().filter { it.serverId == null }
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
                cancelReason = if (o.order.status.equals(OrderStatus.CANCELLED.name, ignoreCase = true)) o.order.cancelledReason ?: "Cancelled by agent" else null,
                lines = o.lines.sortedBy { it.id }.map { OrderLineInput(it.itemId, it.qty) },
            )
            sendable += o
        }
        if (inputs.isEmpty() && dirty.isEmpty()) return

        val req = OrderBatchRequest(
            customers = dirty.map {
                val pin = it.hasDeviceLocation
                CustomerInput(
                    it.clientUuid, it.name, it.ownerName, it.phone, it.address, it.notes,
                    lat = if (pin) it.lat else null, lng = if (pin) it.lng else null,
                    locationAccuracy = if (pin) it.locationAccuracy else null,
                )
            },
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
                var fresh = c.toEntity(t)
                // Keep a pin set on this tablet until it has been pushed; otherwise the server wins, including a
                // cleared pin (lat == null) after a manager removed a wrong location.
                if (existing?.locationSource == CustomerEntity.SOURCE_DEVICE && existing.lat != null) {
                    fresh = fresh.copy(lat = existing.lat, lng = existing.lng, locationAccuracy = existing.locationAccuracy, locationSource = existing.locationSource)
                }
                db.customers().upsert(fresh)
            }
            for (o in resp.orders) {
                val (e, rows) = o.toEntities(db.orders().get(o.clientUuid), t)
                db.orders().replace(e, rows)
            }
            resp.trackingSchedule?.let { sch ->
                if (sch.days.isNotEmpty()) db.trackingMeta().saveSchedule(sch.days.joinToString(","), sch.start, sch.end)
            }
            Iso.parse(resp.trackingConsentAt)?.let { db.trackingMeta().saveConsent(it, true) }
            db.syncMeta().put(SyncMetaEntity(since = resp.serverTime, lastSyncedAt = t, lastError = null))
        }
    }

    companion object { const val MAX_BATCH = 100 }
}
