package ph.marbingo.field.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Version 1 and it stays: the DB holds queued orders, so destructive migration is never allowed.
 * Future schema changes must ship real Migrations.
 */
@Database(
    entities = [ItemEntity::class, CustomerEntity::class, OrderEntity::class, OrderLineEntity::class, SyncMetaEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun items(): ItemDao
    abstract fun customers(): CustomerDao
    abstract fun orders(): OrderDao
    abstract fun syncMeta(): SyncMetaDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "marbingo_field.db").build()
    }
}
