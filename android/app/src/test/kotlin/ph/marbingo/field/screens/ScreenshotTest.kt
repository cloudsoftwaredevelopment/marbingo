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
import ph.marbingo.field.ui.preview.FakeTracking
import ph.marbingo.field.ui.theme.MarbingoTheme
import ph.marbingo.field.ui.tracking.OnboardingViewModel
import ph.marbingo.field.ui.tracking.TrackingSetupScreen
import java.time.ZonedDateTime
import java.io.File

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
}
