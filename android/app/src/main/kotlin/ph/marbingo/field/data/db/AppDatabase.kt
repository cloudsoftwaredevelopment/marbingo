package ph.marbingo.field.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * The DB holds queued orders, so destructive migration is never allowed: every schema change
 * ships a real Migration (see [MIGRATION_1_2], [MIGRATION_2_3]). Version 2 adds store locations and the GPS outbox;
 * version 3 adds pack pricing.
 */
@Database(
    entities = [ItemEntity::class, CustomerEntity::class, OrderEntity::class, OrderLineEntity::class, SyncMetaEntity::class,
        LocationPointEntity::class, TrackingMetaEntity::class, ItemPackEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun items(): ItemDao
    abstract fun customers(): CustomerDao
    abstract fun orders(): OrderDao
    abstract fun syncMeta(): SyncMetaDao
    abstract fun locationPoints(): LocationPointDao
    abstract fun trackingMeta(): TrackingMetaDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "marbingo_field.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
    }
}

/** v1 -> v2: store pin columns on customers, the GPS outbox and the tracking meta row. Pure additions: nothing is dropped. */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `customers` ADD COLUMN `lat` REAL")
        db.execSQL("ALTER TABLE `customers` ADD COLUMN `lng` REAL")
        db.execSQL("ALTER TABLE `customers` ADD COLUMN `locationAccuracy` REAL")
        db.execSQL("ALTER TABLE `customers` ADD COLUMN `locationSource` TEXT")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `location_points` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`recordedAt` INTEGER NOT NULL, `lat` REAL NOT NULL, `lng` REAL NOT NULL, `accuracy` REAL, " +
                "`speed` REAL, `bearing` REAL, `battery` INTEGER, `uploaded` INTEGER NOT NULL)"
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_location_points_recordedAt` ON `location_points` (`recordedAt`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_location_points_uploaded` ON `location_points` (`uploaded`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `tracking_meta` (`id` INTEGER NOT NULL, `scheduleDays` TEXT, `scheduleStart` TEXT, " +
                "`scheduleEnd` TEXT, `consentAt` INTEGER, `consentSynced` INTEGER NOT NULL, `lastFixAt` INTEGER, " +
                "`lastUploadAt` INTEGER, `lastError` TEXT, PRIMARY KEY(`id`))"
        )
    }
}

/**
 * v2 -> v3 (pack pricing): item_packs cache table, breakdown columns on order lines, and the items cache loses
 * wholesalePrice/fieldPrice (table rebuild: create, copy, drop, rename). Orders and their lines are untouched,
 * so queued orders survive. The items cache is reset to a full re-pull (sync `since` cleared) so packs arrive.
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `item_packs` (`itemId` INTEGER NOT NULL, `packTypeId` INTEGER NOT NULL, `name` TEXT NOT NULL, " +
                "`size` INTEGER NOT NULL, `price` INTEGER NOT NULL, PRIMARY KEY(`itemId`, `packTypeId`))"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_item_packs_itemId` ON `item_packs` (`itemId`)")
        db.execSQL("ALTER TABLE `order_lines` ADD COLUMN `breakdown` TEXT")
        db.execSQL("ALTER TABLE `order_lines` ADD COLUMN `deliveredBreakdown` TEXT")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `items_new` (`id` INTEGER NOT NULL, `name` TEXT NOT NULL, `sku` TEXT, `unit` TEXT NOT NULL, " +
                "`categoryId` INTEGER, `category` TEXT, `sellingPrice` INTEGER NOT NULL, `qtyOnHand` INTEGER NOT NULL, " +
                "`imageUrl` TEXT, `updatedAt` TEXT NOT NULL, PRIMARY KEY(`id`))"
        )
        db.execSQL(
            "INSERT INTO `items_new` (`id`, `name`, `sku`, `unit`, `categoryId`, `category`, `sellingPrice`, `qtyOnHand`, `imageUrl`, `updatedAt`) " +
                "SELECT `id`, `name`, `sku`, `unit`, `categoryId`, `category`, `sellingPrice`, `qtyOnHand`, `imageUrl`, `updatedAt` FROM `items`"
        )
        db.execSQL("DROP TABLE `items`")
        db.execSQL("ALTER TABLE `items_new` RENAME TO `items`")
        // Cached items have no packs yet: force the next pull to be a full one.
        db.execSQL("UPDATE `sync_meta` SET `since` = NULL")
    }
}
