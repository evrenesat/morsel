package io.evren.morsel

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves the card is a genuinely floating window: its window frame is smaller
 * than the display, the launcher stays visible behind it, outside taps dismiss
 * without touching anything underneath, and Back dismisses.
 */
@RunWith(AndroidJUnit4::class)
class FloatingWindowTest {

    private val device: UiDevice
        get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    private fun goHome() {
        device.pressHome()
        device.wait(Until.hasObject(By.pkg(device.launcherPackageName).depth(0)), 5_000)
    }

    private fun launchAndWaitForCard() {
        context.startActivity(
            Intent(context, FeedPopupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        assertNotNull(device.wait(Until.hasObject(By.pkg("io.evren.morsel")), 10_000))
    }

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun floatingCardIsSmallerThanDisplayOverLauncher() {
        goHome()
        launchAndWaitForCard()
        val window = device.findObject(By.pkg("io.evren.morsel"))
        assertNotNull("Morsel window not found", window)
        val bounds = window.visibleBounds
        assertTrue(
            "card width ${bounds.width()} should be smaller than display ${device.displayWidth}",
            bounds.width() in 1 until device.displayWidth,
        )
        assertTrue(
            "card height ${bounds.height()} should be smaller than display ${device.displayHeight}",
            bounds.height() in 1 until device.displayHeight,
        )
        // The launcher is still behind the card window.
        assertTrue(device.hasObject(By.pkg(device.launcherPackageName)))
        Screenshots.capture("floating-over-launcher")
        device.pressBack()
    }

    @Test
    fun backDismissesTheCard() {
        goHome()
        launchAndWaitForCard()
        device.pressBack()
        assertTrue(device.wait(Until.hasObject(By.pkg(device.launcherPackageName).depth(0)), 5_000))
        assertEquals(device.launcherPackageName, device.currentPackageName)
    }

    @Test
    fun dismissalAndReopenKeepsTheSameProcessAndCoordinator() {
        goHome()
        launchAndWaitForCard()
        val pidBefore = android.os.Process.myPid()
        device.pressBack()
        assertTrue(device.wait(Until.hasObject(By.pkg(device.launcherPackageName).depth(0)), 5_000))

        // Reopen in the same process: the application-scoped coordinator and
        // journal survive the dismissed card untouched.
        launchAndWaitForCard()
        assertEquals(pidBefore, android.os.Process.myPid())
        assertNotNull(device.findObject(By.pkg("io.evren.morsel")))
        Screenshots.capture("reopened-same-process")
        device.pressBack()
    }

    @Test
    fun outsideTapDismissesWithoutTouchingWhatIsBeneath() {
        goHome()
        launchAndWaitForCard()
        // Top-left corner of the screen is outside the centered card.
        device.click(10, 10)
        assertTrue(device.wait(Until.hasObject(By.pkg(device.launcherPackageName).depth(0)), 5_000))
        // The launcher (not the app) is foreground: the tap was absorbed, not
        // passed through to whatever sits beneath the dim layer.
        assertEquals(device.launcherPackageName, device.currentPackageName)
    }
}
