package ph.marbingo.field.ui

import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import ph.marbingo.field.tracking.PermissionState
import ph.marbingo.field.tracking.TrackingState
import ph.marbingo.field.ui.preview.FakeDeps
import ph.marbingo.field.ui.preview.FakeTracking
import ph.marbingo.field.ui.tracking.*

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {
    private val R = Build.VERSION_CODES.R         // 30: background goes through settings
    private val T = Build.VERSION_CODES.TIRAMISU  // 33: notifications exist
    private val P = Build.VERSION_CODES.P         // 28: no background / notification permissions

    private fun state(
        consent: Boolean = false, fine: Boolean = false, bg: Boolean = false, notif: Boolean = false, battery: Boolean = false,
    ) = TrackingState(consentGiven = consent, permissions = PermissionState(fine, bg, notif, battery))

    private fun build(s: TrackingState, sdk: Int = 34, brand: String = "Google") = OnboardingViewModel.build(s, sdk, brand)
    private fun OnboardingUi.step(s: SetupStep) = steps.first { it.step == s }

    @Before fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun freshInstallStartsAtConsentAndOnlyConsentIsActionable() {
        val ui = build(state())
        assertEquals(listOf(SetupStep.CONSENT, SetupStep.PRECISE, SetupStep.BACKGROUND, SetupStep.NOTIFICATIONS, SetupStep.BATTERY), ui.steps.map { it.step })
        assertEquals(listOf(1, 2, 3, 4, 5), ui.steps.map { it.number })
        assertEquals(SetupStep.CONSENT, ui.current)
        assertTrue(ui.step(SetupStep.CONSENT).enabled)
        assertFalse(ui.step(SetupStep.PRECISE).enabled)
        assertFalse(ui.step(SetupStep.BACKGROUND).enabled)
        assertFalse(ui.canContinue)
        assertFalse(ui.allDone)
    }

    @Test fun stepsUnlockInOrderAndContinueNeedsOneToThree() {
        var ui = build(state(consent = true))
        assertEquals(SetupStep.PRECISE, ui.current)
        assertTrue(ui.step(SetupStep.PRECISE).enabled); assertFalse(ui.step(SetupStep.BACKGROUND).enabled)
        assertFalse(ui.canContinue)

        ui = build(state(consent = true, fine = true))
        assertEquals(SetupStep.BACKGROUND, ui.current)
        assertTrue(ui.step(SetupStep.BACKGROUND).enabled)
        assertFalse(ui.canContinue)

        ui = build(state(consent = true, fine = true, bg = true)) // 4 and 5 not done: still allowed to continue
        assertTrue(ui.canContinue)
        assertFalse(ui.allDone)
        assertEquals(SetupStep.NOTIFICATIONS, ui.current)
        assertTrue(ui.step(SetupStep.BATTERY).enabled)
        assertFalse(ui.step(SetupStep.NOTIFICATIONS).required)
        assertFalse(ui.step(SetupStep.BATTERY).required)

        ui = build(state(consent = true, fine = true, bg = true, notif = true, battery = true))
        assertTrue(ui.allDone); assertNull(ui.current); assertTrue(ui.canContinue)
    }

    @Test fun backgroundWithoutPreciseDoesNotCount() {
        // Order matters: "all the time" is unavailable until precise location is granted.
        val ui = build(state(consent = true, fine = false, bg = true))
        assertFalse(ui.canContinue)
        assertFalse(ui.step(SetupStep.BACKGROUND).enabled)
    }

    @Test fun stepsDependOnAndroidVersion() {
        val old = build(state(consent = true, fine = true), sdk = P)
        assertEquals(listOf(SetupStep.CONSENT, SetupStep.PRECISE, SetupStep.BATTERY), old.steps.map { it.step })
        assertTrue(old.canContinue) // no background step before Android 10

        val q = build(state(), sdk = Build.VERSION_CODES.Q)
        assertEquals(SetupStep.BACKGROUND, q.steps[2].step)
        assertFalse(q.backgroundViaSettings)
        assertNull(q.steps.firstOrNull { it.step == SetupStep.NOTIFICATIONS })

        assertTrue(build(state(), sdk = R).backgroundViaSettings)
        assertNotNull(build(state(), sdk = T).steps.firstOrNull { it.step == SetupStep.NOTIFICATIONS })
    }

    @Test fun brandTipOnlyForAggressiveBatteryManagers() {
        for (b in listOf("Xiaomi", "OPPO", "realme", "vivo", "HUAWEI", "samsung")) {
            val tip = build(state(), brand = b).brandTip
            assertNotNull(b, tip)
            assertTrue(tip!!.contains("No restrictions"))
            assertTrue(tip.contains("Marbingo Field"))
        }
        assertNull(build(state(), brand = "Google").brandTip)
        assertNull(build(state(), brand = "").brandTip)
    }

    @Test fun noticeUsesTheScheduleFromTheServer() {
        val txt = OnboardingViewModel.noticeText(TrackingState().schedule)
        assertTrue(txt.contains("Mon–Sat, 7:00–18:00"))
        assertTrue(txt.contains("not collected outside these hours"))
        assertTrue(txt.contains("12 months"))
    }

    @Test fun viewModelFollowsTheRepositoryAndConsentButtonCallsIt() = runTest {
        val tracking = FakeTracking(state())
        val vm = OnboardingViewModel(FakeDeps(tracking = tracking), sdkInt = 34, manufacturer = "Google")
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.ui.collect { } } // stateIn(WhileSubscribed) needs a collector
        assertEquals(SetupStep.CONSENT, vm.ui.value.current)

        vm.giveConsent()
        assertEquals(1, tracking.consentCalls)
        assertEquals(1, tracking.ensureCalls) // starts sharing right away when on duty
        assertEquals(SetupStep.PRECISE, vm.ui.value.current)

        tracking.current.value = state(consent = true, fine = true, bg = true)
        assertTrue(vm.ui.value.canContinue)
        vm.refresh()
        assertEquals(2, tracking.ensureCalls)
    }
}
