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

    private val rice = Sample.items[0]   // 25000
    private val oil = Sample.items[1]    // 9500

    @Test fun addMergesSameItem() {
        val vm = NewOrderViewModel(FakeDeps())
        vm.addItem(rice); vm.addItem(oil); vm.addItem(rice)
        val s = vm.state.value
        assertEquals(2, s.cart.size)
        assertEquals(2, s.cart.first { it.item.id == rice.id }.qty)
        assertEquals(3, s.count)
        assertEquals(25000L * 2 + 9500, s.total)
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
        assertEquals(50000L, o.total)
        assertEquals("leave at back door", o.notes)
        assertEquals(OrderStatus.PENDING, o.status)
        assertEquals(SyncState.QUEUED, o.syncState)
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
}
