package ph.marbingo.field.tracking

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ph.marbingo.core.model.TrackingPointDto
import ph.marbingo.core.model.TrackingPointsRequest
import ph.marbingo.core.net.ApiErrors
import ph.marbingo.core.net.MarbingoApi
import ph.marbingo.field.data.Iso
import ph.marbingo.field.data.db.AppDatabase
import ph.marbingo.field.data.db.LocationPointEntity
import retrofit2.HttpException
import java.io.IOException

/** Sends the GPS outbox to POST /api/tracking/points in batches of at most [BATCH]. Plain class, tested against MockWebServer. */
class LocationUploader(
    private val api: MarbingoApi,
    private val db: AppDatabase,
    private val now: () -> Long = System::currentTimeMillis,
) {
    sealed class Outcome {
        /** Everything pending was sent (possibly nothing). */
        data class Uploaded(val sent: Int) : Outcome()
        /** Offline / 5xx / throttled: the points stay queued. */
        data class Retry(val message: String) : Outcome()
        /** 401 after the token refresh failed: the session is gone. */
        data class AuthFailed(val message: String) : Outcome()
        /** Permanent rejection that retrying won't fix (e.g. 403). The points stay queued. */
        data class Failed(val message: String) : Outcome()
    }

    private val lock = Mutex()

    suspend fun uploadPending(): Outcome = lock.withLock {
        val dao = db.locationPoints()
        var sent = 0
        try {
            while (true) {
                val batch = dao.pending(BATCH)
                if (batch.isEmpty()) break
                try {
                    api.postTrackingPoints(TrackingPointsRequest(batch.map { it.toDto() }))
                } catch (e: HttpException) {
                    // The server judged this batch itself invalid: drop it, or it would block every later upload.
                    if (e.code() in DROP_CODES) {
                        dao.markUploaded(batch.map { it.id })
                        db.trackingMeta().saveError(ApiErrors.message(e))
                        continue
                    }
                    throw e
                }
                // accepted, duplicate and rejected points are all "handled": the server won't take them again.
                dao.markUploaded(batch.map { it.id })
                sent += batch.size
                db.trackingMeta().saveLastUpload(now())
                if (batch.size < BATCH) break
            }
            dao.pruneUploaded(now() - KEEP_UPLOADED_MS)
            Outcome.Uploaded(sent)
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpException) {
            val msg = ApiErrors.message(e)
            db.trackingMeta().saveError(msg)
            when {
                e.code() == 401 -> Outcome.AuthFailed(msg)
                ApiErrors.isRetryable(e) || e.code() == 408 || e.code() == 429 -> Outcome.Retry(msg)
                else -> Outcome.Failed(msg)
            }
        } catch (e: IOException) {
            db.trackingMeta().saveError(ApiErrors.OFFLINE)
            Outcome.Retry(ApiErrors.OFFLINE)
        } catch (e: Exception) {
            val msg = e.message ?: "Upload failed."
            db.trackingMeta().saveError(msg)
            Outcome.Retry(msg)
        }
    }

    private fun LocationPointEntity.toDto() = TrackingPointDto(
        recordedAt = Iso.format(recordedAt), lat = lat, lng = lng,
        accuracy = accuracy, speed = speed, bearing = bearing, battery = battery,
    )

    companion object {
        const val BATCH = 500
        const val KEEP_UPLOADED_MS = 3L * 24 * 60 * 60 * 1000
        private val DROP_CODES = setOf(400, 413, 422)
    }
}
