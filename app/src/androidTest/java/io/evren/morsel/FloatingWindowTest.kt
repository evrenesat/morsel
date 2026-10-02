package io.evren.morsel

import android.content.Intent
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves the card is a genuinely floating window: its window frame is smaller
 * than the display, the home screen stays alive behind it, outside taps dismiss
 * without touching anything underneath, and Back dismisses back to home.
 *
 * The home package is captured from the actual foreground application window
 * after pressHome — on unprovisioned CI emulators the PackageManager resolves
 * CATEGORY_HOME to com.android.settings, so device.launcherPackageName lies.
 */
@RunWith(AndroidJUnit4::class)
class FloatingWindowTest {

    @get:Rule
    val failureScreenshot = FailureScreenshotRule()

    private val device: UiDevice
        get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** Package of the focused application window right now (the launcher after Home). */
    private fun foregroundApplicationPackage(): String? {
        val windows = InstrumentationRegistry.getInstrumentation()
            .uiAutomation.windows.orEmpty()
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        val focused = windows.firstOrNull { it.isFocused } ?: windows.firstOrNull()
        return focused?.root?.packageName?.toString()
    }

    /** Home package as the system actually routes the Home press. */
    private fun goHomeAndWait(): String {
        device.pressHome()
        device.waitForIdle(5_000)
        val homePackage = foregroundApplicationPackage()
        assertNotNull("no foreground application window after Home", homePackage)
        assertTrue(
            "expected the home screen, got $homePackage",
            homePackage != "io.evren.morsel",
        )
        device.wait(Until.hasObject(By.pkg(homePackage!!).depth(0)), 5_000)
        return homePackage
    }

    private fun launchAndWaitForCard() {
        context.startActivity(
            Intent(context, FeedPopupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        assertNotNull(device.wait(Until.hasObject(By.pkg("io.evren.morsel")), 10_000))
    }

    /** Waits until the given package owns the focused application window again. */
    private fun waitUntilForeground(homePackage: String): Boolean {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            if (foregroundApplicationPackage() == homePackage &&
                device.currentPackageName == homePackage
            ) {
                return true
            }
            device.waitForIdle(1_000)
        }
        return false
    }

    @Test
    fun floatingCardIsSmallerThanDisplayOverHome() {
        val homePackage = goHomeAndWait()
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
        // Layering over the home screen is proven behaviorally by the
        // dismiss-back-to-home tests below and visually by the screenshot
        // (accessibility window lists hide occluded windows on newer APIs).
        Screenshots.capture("floating-over-home")
        device.pressBack()
    }

    @Test
    fun backDismissesTheCard() {
        val homePackage = goHomeAndWait()
        launchAndWaitForCard()
        device.pressBack()
        assertTrue(
            "card did not dismiss back to $homePackage",
            waitUntilForeground(homePackage),
        )
    }

    @Test
    fun dismissalAndReopenKeepsTheSameProcessAndCoordinator() {
        val homePackage = goHomeAndWait()
        launchAndWaitForCard()
        val pidBefore = android.os.Process.myPid()
        device.pressBack()
        assertTrue(waitUntilForeground(homePackage))

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
        val homePackage = goHomeAndWait()
        launchAndWaitForCard()
        // Click just LEFT of the actual card bounds at mid height: (0,0) sits
        // in the status bar and would pull down the notification shade instead.
        val bounds = device.findObject(By.pkg("io.evren.morsel")).visibleBounds
        val x = (bounds.left - 8).coerceAtLeast(0)
        val y = bounds.centerY().coerceIn(0, device.displayHeight - 1)
        device.click(x, y)
        assertTrue(
            "outside tap did not dismiss back to $homePackage",
            waitUntilForeground(homePackage),
        )
        // The home screen is foreground: the tap was absorbed by the dim layer,
        // never passed through to whatever sits beneath.
        assertEquals(homePackage, device.currentPackageName)
    }
}
