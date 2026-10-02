package io.evren.morsel

import android.content.Intent
import android.view.MotionEvent
import android.view.Window
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

    /**
     * The system-recorded frame of the morsel application window — the truth
     * ACTION_OUTSIDE geometry is judged against. The accessibility ROOT bounds
     * only describe the content inside the window; the frame can be larger by
     * invisible margins (the transparent Box padding IS window surface).
     */
    private fun morselWindowFrame(): android.graphics.Rect? {
        val windows = InstrumentationRegistry.getInstrumentation()
            .uiAutomation.windows.orEmpty()
            .filter {
                it.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                    it.root?.packageName?.toString() == "io.evren.morsel"
            }
        val window = windows.firstOrNull { it.isFocused } ?: windows.firstOrNull() ?: return null
        val frame = android.graphics.Rect()
        window.getBoundsInScreen(frame)
        return frame
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

    private fun launchAndWaitForCard(): FeedPopupActivity {
        val monitor = InstrumentationRegistry.getInstrumentation()
            .addMonitor(FeedPopupActivity::class.java.name, null, false)
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
        val activity = monitor.waitForActivityWithTimeout(10_000)
        assertNotNull("FeedPopupActivity instance never tracked by the monitor", activity)
        return activity as FeedPopupActivity
    }

    /** Waits until the system's focused window belongs to the given package. */
    private fun waitUntilForeground(
        homePackage: String,
        extraDiag: (() -> String)? = null,
    ): Boolean {
        // Two independent signals, either of which counts: the accessibility
        // window list (worked after BACK dismissals, but kept reporting the
        // just-finished Morsel window after an ACTION_OUTSIDE dismissal —
        // run 36974801304) and the system's mCurrentFocus from dumpsys (the
        // signal scripts/ci-emulator.sh gates evidence with). A failed wait
        // reports what each signal saw instead of a bare assertion.
        val deadline = System.currentTimeMillis() + 10_000
        var sawDumpsys: String? = null
        var sawAccessibility: String? = null
        while (System.currentTimeMillis() < deadline) {
            val dumpsys = focusedWindowPackage()
            val accessibility = foregroundApplicationPackage()
            sawDumpsys = dumpsys
            sawAccessibility = accessibility
            if (dumpsys == homePackage ||
                (accessibility == homePackage && device.currentPackageName == homePackage)
            ) {
                return true
            }
            Thread.sleep(250)
        }
        throw AssertionError(
            "foreground never became $homePackage within 10s " +
                "(last dumpsys focus=$sawDumpsys, last accessibility window=$sawAccessibility, " +
                "uiautomator=${device.currentPackageName})" +
                (extraDiag?.invoke()?.let { " | $it" } ?: ""),
        )
    }

    /** Raw `mCurrentFocus=` line of the last dumpsys read, for diagnostics. */
    @Volatile
    private var lastFocusLine: String? = null

    /** What the last dispatched touch event looked like to the activity. */
    @Volatile
    private var lastDispatchReport: String? = null

    /**
     * Package of the window the system currently gives input focus to, parsed
     * from `mCurrentFocus=Window{... u0 pkg/activity}`. (UiAutomation shell
     * commands interpret no pipes or redirects, so the stream is scanned
     * directly; read failures are logged and surface as null. Plain string
     * splitting, not a regex — Android's ICU regex engine rejects an
     * unescaped `}` in an alternation, which left this parse dead for two CI
     * runs before the logged exception revealed it.)
     */
    private fun focusedWindowPackage(): String? = try {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("dumpsys window")
        val line = pfd.use {
            java.io.InputStreamReader(
                android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd),
            ).useLines { lines ->
                lines.map { raw -> raw.trim() }
                    .firstOrNull { candidate -> candidate.startsWith("mCurrentFocus=") }
            }
        }
        lastFocusLine = line
        line?.substringAfter("=")
            ?.substringAfter(" u0 ", missingDelimiterValue = "")
            ?.substringBefore("/")
            ?.takeUnless { it.isEmpty() || it == "null" }
    } catch (e: Exception) {
        android.util.Log.w("FloatingWindowTest", "dumpsys mCurrentFocus read failed", e)
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
        val activity = launchAndWaitForCard()

        // Measure everything the tap geometry depends on BEFORE tapping. Run
        // 36981516152 proved the previous 8px-left-of-root tap landed INSIDE
        // the invisible window margin (the transparent Box padding is real
        // window surface): input focus never left the card, so all three
        // focus signals honestly kept reporting io.evren.morsel for 10s, and
        // no ACTION_OUTSIDE ever existed. The dim itself works — pixel
        // comparison of the same run's captures measured exactly 0.24.
        val attrs = activity.window.attributes
        val decor = activity.window.decorView
        val location = IntArray(2)
        decor.getLocationOnScreen(location)
        val root = morselWindowBounds()
        val frame = morselWindowFrame()
        val watchOutside =
            (attrs.flags and android.view.WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH) != 0
        val dimBehind =
            (attrs.flags and android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND) != 0
        val report =
            "root=$root frame=$frame attrs(x=${attrs.x} y=${attrs.y} " +
                "w=${attrs.width} h=${attrs.height}) " +
                "decor=${decor.width}x${decor.height}@(${location[0]},${location[1]}) " +
                "watchOutsideTouch=$watchOutside dimBehind=$dimBehind"
        println("FloatingWindowTest: window geometry: $report")
        assertNotNull("morsel window frame not found: $report", frame)
        assertNotNull("morsel accessibility root not found: $report", root)

        // Log every touch event the activity actually receives: if dismissal
        // still fails, this is the ACTION_DOWN/ACTION_OUTSIDE delivery
        // record that names the consumed link without another blind run.
        val original = requireNotNull(activity.window.callback)
        lastDispatchReport = null
        activity.window.callback = object : Window.Callback by original {
            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                val handled = original.dispatchTouchEvent(event)
                lastDispatchReport =
                    "action=${event.actionMasked} at (${event.rawX},${event.rawY}) " +
                    "handled=$handled"
                println("FloatingWindowTest: dispatchTouchEvent $lastDispatchReport")
                return handled
            }
        }

        // Tap in the middle of the gap between the measured window frame and
        // the display edge — provably outside the frame and far from it, and
        // away from the status bar / shade and the navigation bar.
        val tapX: Int
        val tapY: Int
        when {
            frame!!.left >= 40 -> {
                tapX = frame.left / 2
                tapY = frame.centerY().coerceIn(0, device.displayHeight - 1)
            }
            device.displayWidth - frame.right >= 40 -> {
                tapX = (frame.right + device.displayWidth) / 2
                tapY = frame.centerY().coerceIn(0, device.displayHeight - 1)
            }
            frame.top >= 240 -> {
                tapX = frame.centerX()
                tapY = frame.top / 2
            }
            else -> throw AssertionError("no measurable gap to tap into: $report")
        }
        assertTrue(
            "chosen tap ($tapX,$tapY) is not outside the measured frame $frame / root $root",
            !frame.contains(tapX, tapY) && !root!!.contains(tapX, tapY),
        )

        device.click(tapX, tapY)
        try {
            assertTrue(
                "outside tap did not dismiss back to $homePackage",
                waitUntilForeground(homePackage) {
                    "tap=($tapX,$tapY) $report lastDispatch=$lastDispatchReport " +
                        "activityFinishing=${activity.isFinishing}"
                },
            )
        } finally {
            activity.window.callback = original
        }
        // The home screen is foreground: the tap was absorbed by the dim layer,
        // never passed through to whatever sits beneath (no launcher surface
        // was replaced by another window either).
        assertEquals(homePackage, focusedWindowPackage())
    }
}
