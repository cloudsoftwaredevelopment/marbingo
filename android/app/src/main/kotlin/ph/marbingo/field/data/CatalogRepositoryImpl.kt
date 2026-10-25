package ph.marbingo.field.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import ph.marbingo.field.data.db.ItemDao

class CatalogRepositoryImpl(private val dao: ItemDao) : CatalogRepository {
    override fun search(query: String): Flow<List<Item>> = dao.search(query.trim()).map { l -> l.map { it.toModel() } }
    override suspend fun findBySku(sku: String): Item? = dao.bySku(sku.trim())?.toModel()
    override suspend fun get(id: Int): Item? = dao.byId(id)?.toModel()
}
