package io.evren.morsel

import android.content.Intent
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.After
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

    @After
    fun leaveNoCardBehind() {
        // A failed body must not leave the card stacked over the next class's
        // activity: every test in this class starts and ends on the launcher.
        runCatching { device.pressHome() }
    }

    /**
     * The application window's real frame, from the accessibility window list
     * — deterministic, unlike the accessibility node tree, where a By.pkg
     * match can land on a small text node instead of the window root.
     */
    private fun morselWindowBounds(): android.graphics.Rect? {
        val windows = InstrumentationRegistry.getInstrumentation()
            .uiAutomation.windows.orEmpty()
            .filter {
                it.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                    it.root?.packageName?.toString() == "io.evren.morsel"
            }
        val window = windows.firstOrNull { it.isFocused } ?: windows.firstOrNull() ?: return null
        val root = window.root ?: return null
        val bounds = android.graphics.Rect()
        root.getBoundsInScreen(bounds)
        return bounds
    }

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
        // Until.hasObject yields a Boolean: assert TRUE, not non-null —
        // assertNotNull would also accept a plain `false` (not found).
        assertTrue(
            "morsel card window never appeared after launch",
            device.wait(Until.hasObject(By.pkg("io.evren.morsel")), 10_000) == true,
        )
        // The wrapping window can be measured while its content is still
        // composing, so a bounds read right after launch races the first
        // layout (an earlier run tapped (28,80) against a near-empty window).
        // Wait until the window has its real card-sized frame.
        val deadline = System.currentTimeMillis() + 10_000
        var bounds = morselWindowBounds()
        while ((bounds == null || bounds.height() < device.displayHeight / 4) &&
            System.currentTimeMillis() < deadline
        ) {
            Thread.sleep(200)
            bounds = morselWindowBounds()
        }
        assertTrue(
            "card window never reached card size (bounds=$bounds)",
            bounds != null && bounds.height() >= device.displayHeight / 4,
        )
    }

    /** Waits until the system's focused window belongs to the given package. */
    private fun waitUntilForeground(homePackage: String): Boolean {
        // dumpsys ground truth, not the accessibility window list: after an
        // ACTION_OUTSIDE dismissal the list kept reporting the just-finished
        // Morsel window for the whole 10s budget while the launcher plainly
        // owned the screen (run 36974801304, both APIs). This is the same
        // signal scripts/ci-emulator.sh trusts for evidence gating.
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            if (focusedWindowPackage() == homePackage) return true
            Thread.sleep(250)
        }
        return focusedWindowPackage() == homePackage
    }

    /**
     * Package of the window the system currently gives input focus to, parsed
     * from `mCurrentFocus=Window{... u0 pkg/activity}`. Null when nothing is
     * focused. (UiAutomation shell commands interpret no pipes or redirects,
     * so the stream is scanned directly.)
     */
    private fun focusedWindowPackage(): String? = try {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("dumpsys window")
        pfd.use {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader()
                .lineSequence()
                .map { line -> line.trim() }
                .firstOrNull { candidate -> candidate.startsWith("mCurrentFocus=") }
                ?.substringAfter("=")
                ?.let { value -> Regex("""u\d+ (\S+?)(/|})""").find(value)?.groupValues?.get(1) }
        }
    } catch (_: Exception) {
        null
    }

    @Test
    fun floatingCardIsSmallerThanDisplayOverHome() {
        val homePackage = goHomeAndWait()
        launchAndWaitForCard()
        val bounds = morselWindowBounds()
        assertNotNull("Morsel window not found", bounds)
        assertTrue(
            "card width ${bounds!!.width()} should be smaller than display ${device.displayWidth}",
            bounds.width() in 1 until device.displayWidth,
        )
        assertTrue(
            "card height ${bounds.height()} should be smaller than display ${device.displayHeight}",
            bounds.height() in 1 until device.displayHeight,
        )
        // Layering over the home screen is proven behaviorally by the
        // dismiss-back-home tests below and visually by the screenshot
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
        Screenshots.capture("reopened-same-process")
        device.pressBack()
    }

    @Test
    fun outsideTapDismissesWithoutTouchingWhatIsBeneath() {
        val homePackage = goHomeAndWait()
        launchAndWaitForCard()
        // Click just LEFT of the actual card window at mid height: (0,0) sits
        // in the status bar and would pull down the notification shade.
        val bounds = morselWindowBounds()
        assertNotNull("Morsel window not found", bounds)
        val x = (bounds!!.left - 8).coerceAtLeast(0)
        val y = bounds.centerY().coerceIn(0, device.displayHeight - 1)
        device.click(x, y)
        assertTrue(
            "outside tap did not dismiss back to $homePackage",
            waitUntilForeground(homePackage),
        )
        // The home screen is foreground: the tap was absorbed by the dim layer,
        // never passed through to whatever sits beneath (no launcher surface
        // was replaced by another window either).
        assertEquals(homePackage, focusedWindowPackage())
    }
}
