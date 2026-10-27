package ph.marbingo.field.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import ph.marbingo.field.data.OrderStatus
import ph.marbingo.field.data.SyncStatus
import ph.marbingo.field.ui.neworder.*
import ph.marbingo.field.ui.preview.*
import ph.marbingo.field.ui.settings.SettingsViewModel
import ph.marbingo.field.ui.auth.SignInViewModel
import ph.marbingo.field.ui.orders.OrderFilter
import ph.marbingo.field.data.SyncState

@OptIn(ExperimentalCoroutinesApi::class)
class NewOrderViewModelTest {
    @Before fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private val rice = Sample.items[0]   // ₱62.00/kg, Sack 50 kg ₱2,900
    private val oil = Sample.items[1]    // ₱105.00, no packs

    @Test fun addMergesSameItem() {
        val vm = NewOrderViewModel(FakeDeps())
        vm.addItem(rice); vm.addItem(oil); vm.addItem(rice)
        val s = vm.state.value
        assertEquals(2, s.cart.size)
        assertEquals(2, s.cart.first { it.item.id == rice.id }.qty)
        assertEquals(3, s.count)
        assertEquals(6200L * 2 + 10500, s.total)
    }

    @Test fun qtyClampsToOneAndMax() {
        val vm = NewOrderViewModel(FakeDeps())
        vm.addItem(rice)
        vm.setQty(rice.id, 0)
        assertEquals(1, vm.state.value.cart[0].qty)
        vm.decrement(rice.id)
        assertEquals(1, vm.state.value.cart[0].qty)
        vm.setQty(rice.id, 5)
        vm.increment(rice.id)
        assertEquals(6, vm.state.value.cart[0].qty)
        vm.setQty(rice.id, 10_000_000)
        assertEquals(MAX_QTY, vm.state.value.cart[0].qty)
    }

    @Test fun removeAndReviewGuard() {
        val vm = NewOrderViewModel(FakeDeps())
        vm.review()
        assertEquals(OrderStep.STORE, vm.state.value.step)
        vm.addItem(rice)
        vm.review() // no store yet
        assertEquals(OrderStep.STORE, vm.state.value.step)
        vm.selectStore(Sample.store)
        assertEquals(OrderStep.ITEMS, vm.state.value.step)
        vm.review()
        assertEquals(OrderStep.REVIEW, vm.state.value.step)
        vm.remove(rice.id)
        assertTrue(vm.state.value.cart.isEmpty())
        assertEquals(OrderStep.ITEMS, vm.state.value.step)
        assertFalse(vm.state.value.dirty)
    }

    @Test fun scanAddsKnownAndMessagesUnknown() = runTest {
        val vm = NewOrderViewModel(FakeDeps())
        val msgs = mutableListOf<String>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.messages.collect { msgs += it } }
        vm.onScanned("4800002")
        vm.onScanned("999")
        assertEquals(1, vm.state.value.cart.size)
        assertEquals(oil.id, vm.state.value.cart[0].item.id)
        assertEquals("No item with barcode 999", msgs.last())
        job.cancel()
    }

    @Test fun saveRoundTripsToOrders() = runTest {
        val deps = FakeDeps()
        val vm = NewOrderViewModel(deps)
        vm.selectStore(Sample.store)
        vm.addItem(rice); vm.addItem(rice)
        vm.setNotes("  leave at back door ")
        vm.save()
        val id = vm.state.value.savedUuid
        assertNotNull(id)
        val o = deps.orders.observe(id!!).first()!!
        assertEquals(6200L * 2, o.total)
        assertEquals("leave at back door", o.notes)
        assertEquals(OrderStatus.PENDING, o.status)
        assertEquals(SyncState.QUEUED, o.syncState)
    }

    @Test fun totalsUsePacksAndUpsellAddsToTheCart() {
        val vm = NewOrderViewModel(FakeDeps())
        vm.addItem(rice); vm.addItem(oil)
        vm.setQty(rice.id, 53)
        val line = vm.state.value.cart.first { it.item.id == rice.id }
        assertEquals(290000L + 3 * 6200, line.total)
        assertEquals(listOf("Sack", null), line.priced.parts.map { it.name })
        assertNull(line.upsell) // 53 kg: nothing is cheaper by adding up to a sack's worth more
        assertEquals(290000L + 3 * 6200 + 10500, vm.state.value.total)

        vm.setQty(rice.id, 49) // 49 kg loose = 303,800; one sack = 290,000
        val l49 = vm.state.value.cart.first { it.item.id == rice.id }
        assertEquals(49 * 6200L, l49.total)
        val up = l49.upsell!!
        assertEquals(1, up.addQty); assertEquals(290000L, up.total); assertEquals(13800L, up.saving)
        assertNull(vm.state.value.cart.first { it.item.id == oil.id }.upsell)

        vm.applyUpsell(rice.id)
        val after = vm.state.value.cart.first { it.item.id == rice.id }
        assertEquals(50, after.qty); assertEquals(290000L, after.total)
        assertEquals(listOf("Sack"), after.priced.parts.map { it.name })
        assertEquals(290000L + 10500, vm.state.value.total)
    }

    @Test fun startWithStoreSkipsToItems() {
        val vm = NewOrderViewModel(FakeDeps())
        vm.start(Sample.store.clientUuid)
        assertEquals(OrderStep.ITEMS, vm.state.value.step)
        assertEquals(Sample.store.name, vm.state.value.store?.name)
    }

    @Test fun signOutRefusalShowsDialogThenForces() = runTest {
        val auth = FakeAuth(refuseUnlessForced = true)
        val deps = FakeDeps(auth = auth, sync = FakeSync(SyncStatus(null, 2, 0, false, null)))
        val vm = SettingsViewModel(deps)
        vm.signOut()
        assertEquals("2 orders haven't synced yet. Sign out anyway and lose them?", vm.forceSignOutText.value)
        assertNotNull(auth.sessionState.value)
        vm.forceSignOut()
        assertNull(vm.forceSignOutText.value)
        assertTrue(auth.forced == true)
        assertNull(auth.sessionState.value)
    }

    @Test fun signOutWithoutPendingGoesStraightThrough() = runTest {
        val auth = FakeAuth()
        val vm = SettingsViewModel(FakeDeps(auth = auth))
        vm.signOut()
        assertNull(vm.forceSignOutText.value)
        assertNull(auth.sessionState.value)
    }

    @Test fun signInShowsExceptionMessage() = runTest {
        val auth = FakeAuth(initial = null).also { it.signInError = IllegalStateException("Wrong email or password") }
        val vm = SignInViewModel(FakeDeps(auth = auth))
        vm.setEmail("a@b.c"); vm.setPassword("x")
        vm.submit()
        assertEquals("Wrong email or password", vm.state.value.error)
        assertFalse(vm.state.value.loading)
    }

    @Test fun orderFilters() {
        val base = Sample.let {
            ph.marbingo.field.data.Order("u", null, "s", "S", null, 0, OrderStatus.PENDING, SyncState.QUEUED, null, null, 0, emptyList(), null, null)
        }
        assertTrue(OrderFilter.WAITING.matches(base))
        assertTrue(OrderFilter.PENDING.matches(base))
        assertFalse(OrderFilter.DELIVERED.matches(base))
        val done = base.copy(status = OrderStatus.DELIVERED, syncState = SyncState.SYNCED)
        assertFalse(OrderFilter.WAITING.matches(done))
        assertTrue(OrderFilter.DELIVERED.matches(done))
        assertTrue(OrderFilter.ALL.matches(done))
    }

    // ---- truck sale mode ----

    private fun truckDeps(rice: Int = 3, oil: Int = 0): FakeDeps = FakeDeps(
        truck = FakeTruck(
            ph.marbingo.field.data.TruckInfo(1, "Truck 1", "ABC 123", null, "open", "2026-10", rice + oil),
            listOf(ph.marbingo.field.data.TruckStockLine(1, "Jasmine rice", "kg", rice, rice), ph.marbingo.field.data.TruckStockLine(2, "Cooking oil 1L", "btl", oil, oil)),
        ),
    )

    @Test fun truckSaleStartsOnItemsWithoutAStoreAndOnlyListsTruckStock() {
        val vm = NewOrderViewModel(truckDeps(), OrderMode.TRUCK_SALE)
        assertEquals(OrderStep.ITEMS, vm.state.value.step)
        val items = kotlinx.coroutines.runBlocking { vm.items.first { it.isNotEmpty() } }
        assertEquals(listOf(1), items.map { it.id }) // oil has 0 on the truck
    }

    @Test fun truckSaleCapsQuantityAtAvailable() {
        val vm = NewOrderViewModel(truckDeps(rice = 3), OrderMode.TRUCK_SALE)
        vm.addItem(rice); vm.addItem(rice); vm.addItem(rice)
        assertEquals(3, vm.state.value.cart.single().qty)
        vm.addItem(rice) // would be the 4th
        assertEquals(3, vm.state.value.cart.single().qty)
        vm.increment(rice.id)
        assertEquals(3, vm.state.value.cart.single().qty)
        vm.setQty(rice.id, 50)
        assertEquals(3, vm.state.value.cart.single().qty)
        vm.setQty(rice.id, 2)
        assertEquals(2, vm.state.value.cart.single().qty)
    }

    @Test fun truckSaleRejectsItemsNotOnTheTruck() {
        val vm = NewOrderViewModel(truckDeps(rice = 3, oil = 0), OrderMode.TRUCK_SALE)
        vm.addItem(oil)
        assertTrue(vm.state.value.cart.isEmpty())
    }

    @Test fun truckSaleReviewsWithoutStoreAndSavesAsWalkIn() {
        val deps = truckDeps()
        val vm = NewOrderViewModel(deps, OrderMode.TRUCK_SALE)
        vm.addItem(rice); vm.review()
        assertEquals(OrderStep.REVIEW, vm.state.value.step) // no store needed
        vm.save()
        val id = vm.state.value.savedUuid!!
        val sale = deps.truck.sales.value.single()
        assertEquals(id, sale.clientUuid)
        assertTrue(sale.isSale)
        assertEquals(6200L, sale.total)
        assertTrue(deps.orders.all.value.isEmpty()) // never an order
    }

    @Test fun truckSaleTrimsCartWhenStockShrinks() {
        val deps = truckDeps(rice = 3)
        val vm = NewOrderViewModel(deps, OrderMode.TRUCK_SALE)
        vm.setQty(rice.id, 3); vm.addItem(rice); vm.addItem(rice)
        deps.truck.stockLines.value = listOf(ph.marbingo.field.data.TruckStockLine(1, "Jasmine rice", "kg", 3, 1))
        assertEquals(1, vm.state.value.cart.single().qty)
        deps.truck.stockLines.value = emptyList()
        assertTrue(vm.state.value.cart.isEmpty())
    }

    @Test fun orderFilterTruckSalesMatchesOnlySales() {
        val sale = ph.marbingo.field.data.Order("s", null, "", "Walk-in customer", null, 1, OrderStatus.DELIVERED, SyncState.QUEUED, null, null, 100, emptyList(), null, null, isSale = true)
        val order = sale.copy(isSale = false)
        assertTrue(OrderFilter.TRUCK.matches(sale)); assertFalse(OrderFilter.TRUCK.matches(order))
        assertFalse(OrderFilter.DELIVERED.matches(sale))
    }
}
