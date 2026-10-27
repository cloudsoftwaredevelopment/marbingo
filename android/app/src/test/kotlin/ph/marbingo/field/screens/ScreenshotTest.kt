package ph.marbingo.field.screens

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import ph.marbingo.field.ui.FieldApp
import androidx.compose.runtime.CompositionLocalProvider
import androidx.navigation.compose.rememberNavController
import ph.marbingo.field.tracking.PermissionState
import ph.marbingo.field.tracking.TrackingSchedule
import ph.marbingo.field.tracking.TrackingState
import ph.marbingo.field.ui.LocalDeps
import ph.marbingo.field.ui.preview.FakeDeps
import ph.marbingo.field.ui.preview.FakeTruck
import ph.marbingo.field.data.TruckInfo
import ph.marbingo.field.data.TruckStockLine
import ph.marbingo.field.ui.preview.FakeTracking
import ph.marbingo.field.ui.theme.MarbingoTheme
import ph.marbingo.field.ui.tracking.OnboardingViewModel
import ph.marbingo.field.ui.tracking.TrackingSetupScreen
import java.time.ZonedDateTime
import java.io.File
import org.junit.Assert.assertEquals
import ph.marbingo.field.ui.preview.FakeAuth
import ph.marbingo.field.ui.preview.FakeUpdate
import ph.marbingo.field.update.UpdateInfo
import ph.marbingo.field.update.UpdateState

/**
 * Renders the real UI against the in-memory fakes and writes PNGs, so screens can be eyeballed
 * on this headless server. Only runs when SCREENSHOT_DIR is set:
 *   SCREENSHOT_DIR=/tmp/shots ./gradlew :app:testDebugUnitTest --tests '*ScreenshotTest*'
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-land-xhdpi")
class ScreenshotTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val dir = System.getenv("SCREENSHOT_DIR")

    private fun shot(name: String) {
        rule.waitForIdle()
        // captureToImage() needs PixelCopy, which Robolectric lacks; draw the root view instead.
        val view = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bmp))
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun tabletFlow() {
        assumeTrue(dir != null)
        rule.setContent { FieldApp(depsOverride = FakeDeps()) }
        shot("01-home")
        rule.onAllNodesWithText("New order").onFirst().performClick()
        shot("02-pick-store")
        rule.onNodeWithText("Aling Nena Sari-Sari").performClick()
        shot("03-items")
        rule.onAllNodesWithText("Jasmine rice").onFirst().performClick()
        rule.onAllNodesWithText("Cooking oil 1L").onFirst().performClick()
        // 49 kg of rice is dearer than one 50 kg sack: the cart shows the loose price and the upsell hint.
        rule.onAllNodes(hasSetTextAction())[1].performTextReplacement("49")
        shot("04a-cart-upsell-hint")
        rule.onNodeWithText("Add 1 kg → pay ₱138.00 less").assertExists()
        rule.onNodeWithText("Add 1 kg → pay ₱138.00 less").performClick()
        rule.onAllNodes(hasSetTextAction())[1].performTextReplacement("53")
        shot("04-cart")
        rule.onNodeWithText("Review order").performClick()
        shot("05-review")
        rule.onNodeWithText("Save order").performClick()
        shot("06-receipt")
    }

    @Test @Config(qualifiers = "w400dp-h860dp-xxhdpi") fun phoneHome() {
        assumeTrue(dir != null)
        rule.setContent { FieldApp(depsOverride = FakeDeps()) }
        shot("07-phone-home")
    }

    private fun manila(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0) =
        ZonedDateTime.of(y, mo, d, h, mi, 0, 0, TrackingSchedule.ZONE).toInstant().toEpochMilli()
    private val mondayTen = manila(2026, 10, 26, 10)
    private val ready = TrackingState.Ready.copy(now = mondayTen)

    @Test fun homeTrackingCardStates() {
        assumeTrue(dir != null)
        val tracking = FakeTracking(ready.copy(serviceRunning = true, lastFixAt = mondayTen - 60_000, pendingPoints = 34))
        rule.setContent { FieldApp(depsOverride = FakeDeps(tracking = tracking)) }
        shot("08-home-sharing")
        tracking.current.value = ready.copy(permissions = ready.permissions.copy(gpsEnabled = false))
        shot("09-home-banner-gps-off")
        tracking.current.value = ready.copy(now = manila(2026, 11, 1, 12), pendingPoints = 3) // Sunday
        shot("10-home-off-duty")
    }

    @Test fun homeBannerOnPhone() {
        assumeTrue(dir != null)
        val tracking = FakeTracking(ready.copy(permissions = ready.permissions.copy(gpsEnabled = false)))
        rule.setContent { FieldApp(depsOverride = FakeDeps(tracking = tracking)) }
        shot("11-phone-home-banner")
        rule.onNodeWithText("Location sharing is off. Tap to fix.").performClick()
        shot("12-phone-setup-from-banner")
    }

    @Test fun onboardingSteps() {
        assumeTrue(dir != null)
        val tracking = FakeTracking(TrackingState(now = mondayTen))
        val deps = FakeDeps(tracking = tracking)
        rule.setContent {
            MarbingoTheme {
                CompositionLocalProvider(LocalDeps provides deps) {
                    TrackingSetupScreen(OnboardingViewModel(deps, sdkInt = 34, manufacturer = "Xiaomi"), rememberNavController(), forced = true)
                }
            }
        }
        shot("13-setup-1-consent")
        tracking.current.value = TrackingState(now = mondayTen, consentGiven = true, permissions = PermissionState(fine = true))
        shot("14-setup-3-background")
        tracking.current.value = TrackingState(now = mondayTen, consentGiven = true, permissions = PermissionState(fine = true, background = true))
        shot("15-setup-continue-allowed")
        tracking.current.value = TrackingState.Ready.copy(now = mondayTen)
        shot("16-setup-all-done")
    }

    @Test fun incompleteSetupForcesAgentsIntoTheFlow() {
        assumeTrue(dir != null)
        rule.setContent { FieldApp(depsOverride = FakeDeps(tracking = FakeTracking(TrackingState(now = mondayTen)))) }
        rule.onNodeWithText("Set up location sharing").assertExists()
        shot("17-forced-setup-after-signin")
    }

    @Test fun storeLocationUi() {
        assumeTrue(dir != null)
        org.robolectric.Shadows.shadowOf(rule.activity.application).grantPermissions(android.Manifest.permission.ACCESS_FINE_LOCATION)
        rule.setContent { FieldApp(depsOverride = FakeDeps()) }
        rule.onAllNodesWithText("Stores").onFirst().performClick()
        shot("18-stores-no-location-chip")
        rule.onAllNodesWithText("Add store", useUnmergedTree = true).onFirst().performClick(); rule.waitForIdle()
        rule.onNodeWithText("Store name *").assertExists()
        shot("19-add-store-with-location")
        rule.onNodeWithText("Cancel").performClick()
        rule.onNodeWithText("Aling Nena Sari-Sari").performClick()
        shot("20-store-detail-no-location")
        rule.onNodeWithText("Set location here").performClick()
        rule.onNodeWithText("Set location here?").assertExists()
        shot("21-confirm-location")
        rule.onNodeWithText("Set location").performClick()
        rule.onNodeWithText("Update location here").assertExists()
        shot("22-store-detail-located")
    }

    private fun truckDeps(status: String? = "open") = FakeDeps(
        truck = FakeTruck(
            TruckInfo(2, "Truck 2", "NEG 4821", "Pedro Alvarez", status, "2026-10", 527),
            listOf(TruckStockLine(1, "Jasmine rice", "kg", 400, 380), TruckStockLine(2, "Cooking oil 1L", "btl", 12, 12), TruckStockLine(4, "Instant noodles", "pc", 120, 120), TruckStockLine(3, "Sugar 1kg", "pack", -2, -2)),
        ),
    )

    @Test fun truckHomeCard() {
        assumeTrue(dir != null)
        rule.setContent { FieldApp(depsOverride = truckDeps()) }
        rule.onNodeWithText("Sell from truck").assertExists()
        shot("23-home-truck-card")
    }

    @Test fun truckHomeWaitingForApproval() {
        assumeTrue(dir != null)
        rule.setContent { FieldApp(depsOverride = truckDeps("draft")) }
        rule.onNodeWithText("Waiting for manager approval").assertExists()
        shot("24-home-truck-waiting")
    }

    @Test fun truckSaleReview() {
        assumeTrue(dir != null)
        rule.setContent { FieldApp(depsOverride = truckDeps()) }
        rule.onNodeWithText("Sell from truck").performClick()
        rule.onAllNodesWithText("Jasmine rice").onFirst().performClick()
        rule.onAllNodesWithText("Cooking oil 1L").onFirst().performClick()
        shot("25-truck-items")
        rule.onNodeWithText("Review sale").performClick()
        rule.onAllNodesWithText("Walk-in customer").onFirst().assertExists()
        shot("26-truck-sale-review")
        rule.onNodeWithText("Complete sale").performClick()
        rule.onNodeWithText("SALES RECEIPT").assertExists()
        shot("27-truck-sales-receipt")
    }

    @Test fun bookingAgentSeesNoTruck() {
        assumeTrue(dir != null)
        rule.setContent { FieldApp(depsOverride = FakeDeps()) }
        rule.onNodeWithText("Sell from truck").assertDoesNotExist()
    }

    private val offer = UpdateState.Available(
        UpdateInfo(7, "0.7", "Faster sync and a fix for printing receipts.", "/downloads/marbingo-field-v0.7.apk", "ab", 25_000_000, 0),
        required = false,
    )
    private val mandatory = offer.copy(info = offer.info.copy(minVersionCode = 7), required = true)

    @Test fun homeUpdateBanner() {
        assumeTrue(dir != null)
        val update = FakeUpdate(offer)
        rule.setContent { FieldApp(depsOverride = FakeDeps(update = update)) }
        rule.onNodeWithText("Update available — v0.7").assertExists()
        shot("28-home-update-banner")
        update.current.value = UpdateState.Downloading(0.42f)
        shot("29-home-update-downloading")
        update.current.value = UpdateState.NeedsPermission
        shot("30-home-update-needs-permission")
        update.current.value = UpdateState.Failed("The download was damaged (checksum mismatch). Try again.")
        shot("31-home-update-failed")
    }

    @Test fun updateRequiredScreen() {
        assumeTrue(dir != null)
        val update = FakeUpdate(mandatory)
        rule.setContent { FieldApp(depsOverride = FakeDeps(update = update)) }
        rule.onNodeWithText("Update required").assertExists()
        shot("32-update-required")
        rule.onNodeWithText("Update").performClick()
        assertEquals(1, update.installs)
        update.current.value = UpdateState.Downloading(0.6f)
        shot("33-update-required-downloading")
    }

    @Test fun signInUpdateBannerAndSettingsCard() {
        assumeTrue(dir != null)
        val signedOut = FakeDeps(auth = FakeAuth(initial = null), update = FakeUpdate(offer))
        rule.setContent { FieldApp(depsOverride = signedOut) }
        rule.onNodeWithText("Update available — v0.7").assertExists()
        shot("34-signin-update-banner")
    }

    @Test fun settingsVersionCard() {
        assumeTrue(dir != null)
        val update = FakeUpdate(UpdateState.UpToDate)
        rule.setContent { FieldApp(depsOverride = FakeDeps(update = update)) }
        rule.onAllNodesWithText("Settings").onFirst().performClick()
        rule.onNodeWithText("Check for updates").assertExists()
        shot("35-settings-version-up-to-date")
        rule.onNodeWithText("Check for updates").performClick()
        rule.waitForIdle()
        assertEquals(1, update.checks)
    }
}
