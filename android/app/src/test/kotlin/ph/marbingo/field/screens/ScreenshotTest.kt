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
import ph.marbingo.field.ui.preview.FakeDeps
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
        rule.onAllNodesWithText("Rice 5kg").onFirst().performClick()
        rule.onAllNodesWithText("Cooking oil 1L").onFirst().performClick()
        rule.onAllNodesWithText("Rice 5kg").onFirst().performClick()
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
}
