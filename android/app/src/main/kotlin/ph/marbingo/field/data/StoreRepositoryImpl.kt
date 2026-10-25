package ph.marbingo.field.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import ph.marbingo.field.data.db.CustomerDao
import ph.marbingo.field.data.db.CustomerEntity
import ph.marbingo.field.sync.SyncTrigger
import java.util.UUID

class StoreRepositoryImpl(private val dao: CustomerDao, private val trigger: SyncTrigger) : StoreRepository {
    override fun search(query: String): Flow<List<Store>> = dao.search(query.trim()).map { l -> l.map { it.toModel() } }
    override fun observe(clientUuid: String): Flow<Store?> = dao.observe(clientUuid).map { it?.toModel() }

    override suspend fun create(name: String, ownerName: String?, phone: String?, address: String?, notes: String?): String {
        val n = name.trim()
        if (n.isEmpty()) throw UserFacingException("Enter the store name.")
        val uuid = UUID.randomUUID().toString()
        dao.upsert(
            CustomerEntity(
                clientUuid = uuid, serverId = null, name = n,
                ownerName = ownerName.clean(), phone = phone.clean(), address = address.clean(), notes = notes.clean(),
                active = true, dirty = true, updatedAt = System.currentTimeMillis(),
            )
        )
        trigger.syncNow()
        return uuid
    }

    private fun String?.clean(): String? = this?.trim()?.ifEmpty { null }
}
