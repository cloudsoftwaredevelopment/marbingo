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
 * version 3 adds pack pricing; version 4 adds the truck tables; version 5 adds the device-log outbox.
 */
@Database(
    entities = [ItemEntity::class, CustomerEntity::class, OrderEntity::class, OrderLineEntity::class, SyncMetaEntity::class,
        LocationPointEntity::class, TrackingMetaEntity::class, ItemPackEntity::class,
        TruckMetaEntity::class, TruckStockEntity::class, TruckSaleEntity::class, TruckSaleLineEntity::class,
        DeviceEventEntity::class,
    ],
    version = 5,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun items(): ItemDao
    abstract fun customers(): CustomerDao
    abstract fun orders(): OrderDao
    abstract fun syncMeta(): SyncMetaDao
    abstract fun locationPoints(): LocationPointDao
    abstract fun trackingMeta(): TrackingMetaDao
    abstract fun truck(): TruckDao
    abstract fun deviceEvents(): DeviceEventDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "marbingo_field.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
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

/** v3 -> v4 (trucks): four new tables only (truck_meta, truck_stock, truck_sales, truck_sale_lines). Nothing existing is touched. */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `truck_meta` (`id` INTEGER NOT NULL, `truckId` INTEGER NOT NULL, `name` TEXT NOT NULL, `plate` TEXT, " +
                "`driverName` TEXT, `periodId` INTEGER, `periodMonth` TEXT, `periodStatus` TEXT, PRIMARY KEY(`id`))"
        )
        db.execSQL("CREATE TABLE IF NOT EXISTS `truck_stock` (`itemId` INTEGER NOT NULL, `qty` INTEGER NOT NULL, PRIMARY KEY(`itemId`))")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `truck_sales` (`clientUuid` TEXT NOT NULL, `serverId` INTEGER, `customerClientUuid` TEXT, `storeName` TEXT, " +
                "`syncState` TEXT NOT NULL, `syncError` TEXT, `attempts` INTEGER NOT NULL, `soldAt` INTEGER NOT NULL, `total` INTEGER NOT NULL, " +
                "`oversold` INTEGER NOT NULL, `late` INTEGER NOT NULL, `agentName` TEXT, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`clientUuid`))"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_truck_sales_syncState` ON `truck_sales` (`syncState`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_truck_sales_soldAt` ON `truck_sales` (`soldAt`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `truck_sale_lines` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `saleClientUuid` TEXT NOT NULL, " +
                "`itemId` INTEGER NOT NULL, `name` TEXT NOT NULL, `unit` TEXT, `qty` INTEGER NOT NULL, `unitPrice` INTEGER NOT NULL, " +
                "`lineTotal` INTEGER NOT NULL, `breakdown` TEXT, FOREIGN KEY(`saleClientUuid`) REFERENCES `truck_sales`(`clientUuid`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE )"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_truck_sale_lines_saleClientUuid` ON `truck_sale_lines` (`saleClientUuid`)")
    }
}

/** v4 -> v5 (device log): the device_events outbox only. Nothing existing is touched. */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `device_events` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `type` TEXT NOT NULL, " +
                "`at` INTEGER NOT NULL, `battery` INTEGER, `estimated` INTEGER NOT NULL, `shiftMs` INTEGER, `uploaded` INTEGER NOT NULL)"
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_device_events_type_at` ON `device_events` (`type`, `at`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_device_events_uploaded` ON `device_events` (`uploaded`)")
    }
}
