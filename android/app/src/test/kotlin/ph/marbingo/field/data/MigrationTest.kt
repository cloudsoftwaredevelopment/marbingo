package ph.marbingo.field.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import ph.marbingo.field.data.db.*

/**
 * Room schema 1 -> 2 -> 3 and 2 -> 3 with queued work on the device. A v1 database is built by hand with the exact SQL Room
 * generated for version 1 (exportSchema is off, so MigrationTestHelper has no JSON to read), filled, then opened by Room with MIGRATION_1_2.
 */
@RunWith(RobolectricTestRunner::class)
class MigrationTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val name = "migration-test.db"
    private var db: AppDatabase? = null

    @Before fun setUp() { ctx.deleteDatabase(name) }
    @After fun tearDown() { db?.close(); ctx.deleteDatabase(name) }

    private fun createV1() {
        val file = ctx.getDatabasePath(name).also { it.parentFile?.mkdirs() }
        val raw = SQLiteDatabase.openOrCreateDatabase(file, null)
        V1_SCHEMA.forEach { raw.execSQL(it) }
        raw.execSQL("INSERT INTO items VALUES (1,'Rice 5kg','4800001','bag',NULL,'Grains',27000,25000,25000,40,NULL,'2026-10-01')")
        raw.execSQL("INSERT INTO customers VALUES ('c-synced',7,'Aling Nena',NULL,'0917',NULL,NULL,1,0,1000)")
        raw.execSQL("INSERT INTO customers VALUES ('c-queued',NULL,'Queued Store','Owner',NULL,'Mabini St','note',1,1,2000)")
        raw.execSQL("INSERT INTO orders VALUES ('o-1',NULL,'c-queued','Queued Store','PENDING','QUEUED',NULL,0,3000,50000,NULL,NULL,NULL,'Ramon',3000)")
        raw.execSQL("INSERT INTO order_lines VALUES (1,'o-1',1,'Rice 5kg','bag',2,25000,50000,NULL)")
        raw.execSQL("INSERT INTO sync_meta VALUES (1,'2026-10-25T00:00:00Z',5000,NULL)")
        raw.version = 1
        raw.close()
    }

    private fun open(): AppDatabase =
        Room.databaseBuilder(ctx, AppDatabase::class.java, name)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            .allowMainThreadQueries()
            .build().also { db = it }

    @Test fun queuedWorkSurvivesAndNewStructureExists() = runBlocking {
        createV1()
        val d = open()

        // Old data is intact (Room also validates every table against the v2 entities while opening).
        val synced = d.customers().get("c-synced")!!
        assertEquals("Aling Nena", synced.name); assertEquals(7, synced.serverId); assertFalse(synced.dirty)
        val queued = d.customers().get("c-queued")!!
        assertEquals("Queued Store", queued.name); assertTrue(queued.dirty); assertEquals("Mabini St", queued.address)
        assertEquals(1, d.orders().waitingCountNow())
        val order = d.orders().pendingForPush().single()
        assertEquals("o-1", order.order.clientUuid); assertEquals(50000L, order.order.total)
        assertEquals(2, order.lines.single().qty)
        assertEquals(1, d.items().byId(1)!!.item.id)
        assertNull(d.syncMeta().get()!!.since) // v3 clears it so the next pull is a full one (packs)
        assertEquals(27000L, d.items().byId(1)!!.item.sellingPrice)
        assertNull(order.lines.single().breakdown)

        // New customer columns default to null...
        assertNull(synced.lat); assertNull(synced.lng); assertNull(synced.locationAccuracy); assertNull(synced.locationSource)
        assertFalse(synced.hasDeviceLocation)
        // ...and can be written.
        d.customers().setDeviceLocation("c-queued", 10.72, 122.56, 12.5, 9999)
        val pinned = d.customers().get("c-queued")!!
        assertEquals(10.72, pinned.lat!!, 1e-9); assertEquals(12.5, pinned.locationAccuracy!!, 1e-9)
        assertTrue(pinned.hasDeviceLocation)

        // The outbox table works and recordedAt is unique.
        val p = LocationPointEntity(recordedAt = 1_000, lat = 10.7, lng = 122.5, accuracy = 8.0, speed = null, bearing = null, battery = 80)
        assertTrue(d.locationPoints().insert(p) > 0)
        assertEquals(-1L, d.locationPoints().insert(p.copy(lat = 11.0)))
        assertEquals(1, d.locationPoints().pendingCountNow())
        assertEquals(1, d.locationPoints().pendingCount().first())

        // tracking_meta works.
        d.trackingMeta().saveSchedule("1,2,3", "08:00", "17:00")
        d.trackingMeta().saveConsent(123L, false)
        val meta = d.trackingMeta().get()!!
        assertEquals("1,2,3", meta.scheduleDays); assertEquals(123L, meta.consentAt); assertFalse(meta.consentSynced)
    }

    @Test fun columnsAndTablesAreThereAtSqlLevel() {
        createV1()
        val d = open()
        val sql = d.openHelper.writableDatabase
        fun columns(table: String): Set<String> = sql.query("PRAGMA table_info($table)").use { c ->
            buildSet { while (c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow("name"))) }
        }
        assertTrue(columns("customers").containsAll(listOf("lat", "lng", "locationAccuracy", "locationSource", "dirty")))
        assertEquals(
            setOf("id", "recordedAt", "lat", "lng", "accuracy", "speed", "bearing", "battery", "uploaded"),
            columns("location_points"),
        )
        assertTrue(columns("tracking_meta").containsAll(listOf("scheduleDays", "consentAt", "consentSynced", "lastFixAt", "lastUploadAt")))
        assertEquals(3, sql.version)
    }

    private fun createV2() {
        val file = ctx.getDatabasePath(name).also { it.parentFile?.mkdirs() }
        val raw = SQLiteDatabase.openOrCreateDatabase(file, null)
        V2_SCHEMA.forEach { raw.execSQL(it) }
        raw.execSQL("INSERT INTO items VALUES (1,'Rice 5kg','4800001','bag',NULL,'Grains',27000,25000,25000,40,NULL,'2026-10-01')")
        raw.execSQL("INSERT INTO items VALUES (2,'Oil','4800002','btl',3,'Pantry',10500,NULL,10500,12,'/uploads/x.jpg','2026-10-02')")
        raw.execSQL("INSERT INTO customers VALUES ('c-queued',NULL,'Queued Store','Owner',NULL,'Mabini St','note',1,1,2000,10.5,122.5,9.0,'device')")
        raw.execSQL("INSERT INTO orders VALUES ('o-1',NULL,'c-queued','Queued Store','PENDING','QUEUED',NULL,0,3000,50000,'hello',NULL,NULL,'Ramon',3000)")
        raw.execSQL("INSERT INTO orders VALUES ('o-2',77,'c-queued','Queued Store','DELIVERED','SYNCED',NULL,0,2000,9500,NULL,4000,NULL,'Ramon',4000)")
        raw.execSQL("INSERT INTO order_lines VALUES (1,'o-1',1,'Rice 5kg','bag',2,25000,50000,NULL)")
        raw.execSQL("INSERT INTO order_lines VALUES (2,'o-2',2,'Oil','btl',1,9500,9500,1)")
        raw.execSQL("INSERT INTO location_points VALUES (1,1000,10.7,122.5,8.0,NULL,NULL,80,0)")
        raw.execSQL("INSERT INTO sync_meta VALUES (1,'2026-10-25T00:00:00Z',5000,NULL)")
        raw.version = 2
        raw.close()
    }

    @Test fun v2ToV3KeepsQueuedOrdersAndRebuildsItems() = runBlocking {
        createV2()
        val d = open()
        val sql = d.openHelper.writableDatabase
        assertEquals(3, sql.version)

        // Queued and synced orders with their lines are untouched; breakdown columns start null.
        val queued = d.orders().pendingForPush().single()
        assertEquals("o-1", queued.order.clientUuid); assertEquals(50000L, queued.order.total); assertEquals("hello", queued.order.notes)
        val line = queued.lines.single()
        assertEquals(2, line.qty); assertEquals(25000L, line.unitPrice); assertEquals(50000L, line.lineTotal)
        assertNull(line.breakdown); assertNull(line.deliveredBreakdown)
        val delivered = d.orders().get("o-2")!!
        assertEquals(77, delivered.serverId); assertEquals(4000L, delivered.deliveredAt)
        assertEquals(1, d.orders().observe("o-2").first()!!.lines.single().deliveredQty)
        assertEquals(10.5, d.customers().get("c-queued")!!.lat!!, 1e-9)
        assertEquals(1, d.locationPoints().pendingCountNow())

        // Items were rebuilt without the old price columns; the data copied over.
        fun columns(table: String): Set<String> = sql.query("PRAGMA table_info($table)").use { c ->
            buildSet { while (c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow("name"))) }
        }
        assertEquals(setOf("id", "name", "sku", "unit", "categoryId", "category", "sellingPrice", "qtyOnHand", "imageUrl", "updatedAt"), columns("items"))
        assertEquals(setOf("itemId", "packTypeId", "name", "size", "price"), columns("item_packs"))
        assertTrue(columns("order_lines").containsAll(listOf("breakdown", "deliveredBreakdown")))
        val oil = d.items().byId(2)!!
        assertEquals("Oil", oil.item.name); assertEquals(10500L, oil.item.sellingPrice); assertEquals(12, oil.item.qtyOnHand)
        assertEquals(3, oil.item.categoryId); assertEquals("/uploads/x.jpg", oil.item.imageUrl)
        assertTrue(oil.packs.isEmpty())
        assertEquals(2, d.items().search("").first().size)
        assertNull(d.syncMeta().get()!!.since)
        assertEquals(5000L, d.syncMeta().get()!!.lastSyncedAt)

        // New structures work.
        d.items().upsertWithPacks(listOf(oil.item), listOf(ItemPackEntity(2, 5, "Case", 12, 110000)))
        assertEquals("Case", d.items().byId(2)!!.packs.single().name)
        val parts = ph.marbingo.core.pricing.PartsJson.encode(ph.marbingo.core.pricing.Pricing.priceQty(13, 10500, listOf(ph.marbingo.core.pricing.PackDef(5, "Case", 12, 110000))).parts)
        d.orders().replace(queued.order, listOf(line.copy(id = 0, breakdown = parts)))
        assertEquals(parts, d.orders().pendingForPush().single().lines.single().breakdown)
    }

    @Test fun freshInstallAtV2HasSameShape() = runBlocking {
        val d = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build().also { db = it }
        d.customers().upsert(CustomerEntity("x", null, "X", null, null, null, null, true, true, 1, lat = 1.0, lng = 2.0, locationSource = "device"))
        assertEquals(1.0, d.customers().get("x")!!.lat!!, 0.0)
    }

    companion object {
        /** Verbatim from Room's generated AppDatabase_Impl for schema version 1. */
        val V1_SCHEMA = listOf(
            "CREATE TABLE IF NOT EXISTS `items` (`id` INTEGER NOT NULL, `name` TEXT NOT NULL, `sku` TEXT, `unit` TEXT NOT NULL, `categoryId` INTEGER, `category` TEXT, `sellingPrice` INTEGER NOT NULL, `wholesalePrice` INTEGER, `fieldPrice` INTEGER NOT NULL, `qtyOnHand` INTEGER NOT NULL, `imageUrl` TEXT, `updatedAt` TEXT NOT NULL, PRIMARY KEY(`id`))",
            "CREATE TABLE IF NOT EXISTS `customers` (`clientUuid` TEXT NOT NULL, `serverId` INTEGER, `name` TEXT NOT NULL, `ownerName` TEXT, `phone` TEXT, `address` TEXT, `notes` TEXT, `active` INTEGER NOT NULL, `dirty` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`clientUuid`))",
            "CREATE INDEX IF NOT EXISTS `index_customers_serverId` ON `customers` (`serverId`)",
            "CREATE TABLE IF NOT EXISTS `orders` (`clientUuid` TEXT NOT NULL, `serverId` INTEGER, `customerClientUuid` TEXT NOT NULL, `storeName` TEXT NOT NULL, `status` TEXT NOT NULL, `syncState` TEXT NOT NULL, `syncError` TEXT, `attempts` INTEGER NOT NULL, `orderedAt` INTEGER NOT NULL, `total` INTEGER NOT NULL, `notes` TEXT, `deliveredAt` INTEGER, `cancelledReason` TEXT, `agentName` TEXT, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`clientUuid`))",
            "CREATE INDEX IF NOT EXISTS `index_orders_customerClientUuid` ON `orders` (`customerClientUuid`)",
            "CREATE INDEX IF NOT EXISTS `index_orders_syncState` ON `orders` (`syncState`)",
            "CREATE INDEX IF NOT EXISTS `index_orders_orderedAt` ON `orders` (`orderedAt`)",
            "CREATE TABLE IF NOT EXISTS `order_lines` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `orderClientUuid` TEXT NOT NULL, `itemId` INTEGER NOT NULL, `name` TEXT NOT NULL, `unit` TEXT, `qty` INTEGER NOT NULL, `unitPrice` INTEGER NOT NULL, `lineTotal` INTEGER NOT NULL, `deliveredQty` INTEGER, FOREIGN KEY(`orderClientUuid`) REFERENCES `orders`(`clientUuid`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `index_order_lines_orderClientUuid` ON `order_lines` (`orderClientUuid`)",
            "CREATE TABLE IF NOT EXISTS `sync_meta` (`id` INTEGER NOT NULL, `since` TEXT, `lastSyncedAt` INTEGER, `lastError` TEXT, PRIMARY KEY(`id`))",
        )

        /** Schema version 2: v1 plus store pin columns, the GPS outbox and tracking meta. */
        val V2_SCHEMA = V1_SCHEMA.map {
            if (it.startsWith("CREATE TABLE IF NOT EXISTS `customers`"))
                "CREATE TABLE IF NOT EXISTS `customers` (`clientUuid` TEXT NOT NULL, `serverId` INTEGER, `name` TEXT NOT NULL, `ownerName` TEXT, `phone` TEXT, `address` TEXT, `notes` TEXT, `active` INTEGER NOT NULL, `dirty` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `lat` REAL, `lng` REAL, `locationAccuracy` REAL, `locationSource` TEXT, PRIMARY KEY(`clientUuid`))"
            else it
        } + listOf(
            "CREATE TABLE IF NOT EXISTS `location_points` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `recordedAt` INTEGER NOT NULL, `lat` REAL NOT NULL, `lng` REAL NOT NULL, `accuracy` REAL, `speed` REAL, `bearing` REAL, `battery` INTEGER, `uploaded` INTEGER NOT NULL)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_location_points_recordedAt` ON `location_points` (`recordedAt`)",
            "CREATE INDEX IF NOT EXISTS `index_location_points_uploaded` ON `location_points` (`uploaded`)",
            "CREATE TABLE IF NOT EXISTS `tracking_meta` (`id` INTEGER NOT NULL, `scheduleDays` TEXT, `scheduleStart` TEXT, `scheduleEnd` TEXT, `consentAt` INTEGER, `consentSynced` INTEGER NOT NULL, `lastFixAt` INTEGER, `lastUploadAt` INTEGER, `lastError` TEXT, PRIMARY KEY(`id`))",
        )
    }
}