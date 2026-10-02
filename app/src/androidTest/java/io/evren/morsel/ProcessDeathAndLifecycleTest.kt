package io.evren.morsel

import android.content.Intent
import android.content.pm.ActivityInfo
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import io.evren.morsel.demo.DemoFeederRepository
import io.evren.morsel.demo.DemoScenario
import io.evren.morsel.domain.FeedOperation
import io.evren.morsel.domain.FeedState
import io.evren.morsel.ui.SetupTags
import io.evren.morsel.ui.Tags
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Shared demo-mode preparation for the lifecycle tests below. */
internal fun prepareDemo(graph: AppGraph, scenario: DemoScenario) {
    runBlocking {
        repeat(3) {
            if (!graph.demoCoordinator.state.value.blocksNewSubmissions) return@repeat
            graph.demoCoordinator.acknowledgeUnresolved()
        }
        graph.settingsStore.setOnboardingComplete(false)
        graph.settingsStore.setDemoMode(false)
        graph.settingsStore.setDemoScenario(scenario)
        graph.settingsStore.setDemoMode(true)
        graph.settingsStore.setOnboardingComplete(true)
        // A stale scenario would let a poll tick reconcile the wrong history,
        // so timing out here is a FAILURE, never a silent skip.
        val deadline = System.currentTimeMillis() + 5_000
        while (graph.demoScenario != scenario) {
            check(System.currentTimeMillis() < deadline) {
                "demo scenario did not switch to $scenario (still ${graph.demoScenario})"
            }
            Thread.sleep(25)
        }
    }
}

/**
 * Seeds demo mode for the adb-driven visual evidence block (run via
 * `adb shell am instrument` by scripts/ci-emulator.sh; not part of the
 * gradle suite).
 */
@RunWith(AndroidJUnit4::class)
class VisualSetupTest {

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun seedDemoMode() = prepareDemo(
        (context.applicationContext as MorselApplication).graph,
        DemoScenario.SUCCESS_CORRELATED,
    )
}

/**
 * Checkpoint review gate: a request left PENDING when the card is dismissed
 * stays visible and blocking after reopening IN THE SAME PROCESS — and is
 * never replayed. The demo TIMEOUT_UNKNOWN scenario drives the production
 * coordinator; a test-only gate holds the single write mid-flight so dismissal
 * happens BEFORE any outcome exists (transport is scripted; no real feeder and
 * no network).
 */
@RunWith(AndroidJUnit4::class)
class InFlightDismissReopenTest {

    @get:Rule
    val compose = createAndroidComposeRule<FeedPopupActivity>()

    @get:Rule
    val failureScreenshot = FailureScreenshotRule()

    private val device: UiDevice
        get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val graph: AppGraph
        get() = (context.applicationContext as MorselApplication).graph

    @Before
    fun prepare() {
        // Fresh gate for this test; leftover unresolved state from earlier
        // classes is retired exactly like the demo flows do.
        graph.demoRepository.sendGate = null
        prepareDemo(graph, DemoScenario.TIMEOUT_UNKNOWN)
    }

    /** Package of the focused application window right now. */
    private fun foregroundApplicationPackage(): String? {
        val windows = InstrumentationRegistry.getInstrumentation()
            .uiAutomation.windows.orEmpty()
            .filter { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION }
        val focused = windows.firstOrNull { it.isFocused } ?: windows.firstOrNull()
        return focused?.root?.packageName?.toString()
    }

    /** Waits until the given package owns the focused application window. */
    private fun awaitForeground(pkg: String, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (foregroundApplicationPackage() == pkg) return true
            Thread.sleep(200)
        }
        return foregroundApplicationPackage() == pkg
    }

    @Test
    fun dismissedInFlightRequestIsStillBlockingAfterReopen() {
        val attemptsBefore = graph.demoRepository.sendAttempts.get()

        compose.onNodeWithTag(Tags.PLUS).performClick()
        compose.onNodeWithTag(Tags.PLUS).performClick()

        // Hold the single write mid-flight, before any scripted outcome.
        graph.demoRepository.sendGate =
            DemoFeederRepository.SendGate { _, _, _ -> awaitCancellation() }
        compose.onNodeWithTag(Tags.FEED).assertIsEnabled().performClick()

        // The request reached the transport and is pending: exactly one attempt.
        val reachedDeadline = System.currentTimeMillis() + 10_000
        while (graph.demoRepository.sendAttempts.get() == attemptsBefore &&
            System.currentTimeMillis() < reachedDeadline
        ) {
            Thread.sleep(25)
        }
        assertEquals(attemptsBefore + 1, graph.demoRepository.sendAttempts.get())

        // Dismiss the card WHILE the request is still pending. The submitting
        // scope dies with the card; the coordinator records UNKNOWN durably.
        device.pressBack()
        // The dismissed card must actually leave before the reopen, or the
        // relaunch races the finishing window (a stale first window must not
        // decide this test's outcome).
        assertTrue(
            "dismissed card never left the foreground",
            awaitForeground("com.google.android.apps.nexuslauncher", 10_000),
        )

        // Wait for the durable UNKNOWN to actually land before reopening, so
        // the reopened card observes steady state.
        val unknownDeadline = System.currentTimeMillis() + 10_000
        while (graph.demoCoordinator.state.value.unresolvedOperation?.state != FeedState.UNKNOWN &&
            System.currentTimeMillis() < unknownDeadline
        ) {
            Thread.sleep(25)
        }
        assertEquals(
            FeedState.UNKNOWN,
            graph.demoCoordinator.state.value.unresolvedOperation?.state,
        )

        // Reopen in the SAME process: the application-scoped coordinator still
        // holds the conservative UNKNOWN and nothing was resent.
        val pidBefore = android.os.Process.myPid()
        // The card must reopen in demo mode; a flipped mode would silently
        // show the real (empty) card and fail confusingly.
        check(runBlocking { graph.settingsStore.settings.first().demoMode }) {
            "demo mode was disabled mid-test"
        }
        context.startActivity(
            Intent(context, FeedPopupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        assertTrue(
            "reopened card never reached the foreground",
            awaitForeground("io.evren.morsel", 10_000),
        )
        val unknown = context.getString(R.string.unknown_body)
        // The reopened window must be polled through the compose rule: the
        // rule overrides the PROCESS-WIDE window recomposer factory for the
        // whole test (WindowRecomposerPolicy.withFactory), so the raw
        // startActivity relaunch composes under the rule's
        // TestMonotonicFrameClock — which only advances inside compose test
        // APIs. A plain device.wait loop advances nothing, so the reopened
        // card would show its one initial frame (the stateIn default) forever
        // while the ViewModel provably emitted (runs 36981516152/36979628029).
        // compose.waitUntil advances the clock per iteration; the observation
        // itself stays UiAutomator, and a timeout still fails the test.
        compose.waitUntil("reopened card shows the UNKNOWN body", 10_000) {
            device.hasObject(By.text(unknown))
        }
        assertEquals(pidBefore, android.os.Process.myPid())
        // Check status AND acknowledgement stay available for this outcome.
        val ackAvailable = context.getString(R.string.i_checked_the_feeder)
        compose.waitUntil("acknowledgement control visible after reopen", 5_000) {
            device.hasObject(By.text(ackAvailable))
        }

        // A user status check reconciles read-only; no second request may
        // appear while it runs or after it.
        val checkStatus = context.getString(R.string.check_status)
        val checkButton = device.wait(Until.findObject(By.text(checkStatus)), 5_000)
        assertNotNull("check-status button not found after reopen", checkButton)
        checkButton!!.click()
        val quietDeadline = System.currentTimeMillis() + 2_000
        while (System.currentTimeMillis() < quietDeadline) {
            assertEquals(attemptsBefore + 1, graph.demoRepository.sendAttempts.get())
            Thread.sleep(50)
        }
        // Same clock-pumping requirement as the first UNKNOWN wait: the
        // notice recomposition after the read-only check also needs a
        // rule-driven frame; a timeout here fails the test loudly.
        compose.waitUntil("UNKNOWN panel persists after status check", 5_000) {
            device.hasObject(By.text(unknown))
        }
        assertEquals(attemptsBefore + 1, graph.demoRepository.sendAttempts.get())
        Screenshots.capture("inflight-dismiss-reopen")
    }
}

/**
 * Visual evidence: a nonzero selection must be visible as kibble in the cup
 * (review follow-up: selection evidence at the default font scale).
 * Runs only in the API 36 visual phase (see scripts/ci-emulator.sh).
 */
@RunWith(AndroidJUnit4::class)
class SelectionVisualTest {

    @get:Rule
    val compose = createAndroidComposeRule<FeedPopupActivity>()

    @get:Rule
    val failureScreenshot = FailureScreenshotRule()

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun selectionShowsKibbleInCup() {
        prepareDemo(
            (context.applicationContext as MorselApplication).graph,
            DemoScenario.SUCCESS_CORRELATED,
        )
        compose.onNodeWithTag(Tags.PLUS).performClick()
        compose.onNodeWithTag(Tags.PLUS).performClick()
        compose.onNodeWithTag(Tags.COUNT).assertExists()
        Screenshots.capture("demo-selection")
    }
}

/**
 * English-only proof: under a Dutch per-app locale (API 33+) the card still
 * renders ENGLISH strings — the app ships no Dutch resources by owner
 * decision — asserted BEFORE the capture. A localized-Dutch card would fail
 * this test (owner correction, plans/owner-english-only.md).
 * Runs only in the API 36 visual phase.
 */
@RunWith(AndroidJUnit4::class)
class DutchLocaleEnglishUiTest {

    @get:Rule
    val compose = createAndroidComposeRule<FeedPopupActivity>()

    @get:Rule
    val failureScreenshot = FailureScreenshotRule()

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun dutchLocaleStillShowsEnglishStrings() {
        org.junit.Assume.assumeTrue(android.os.Build.VERSION.SDK_INT >= 33)
        prepareDemo(
            (context.applicationContext as MorselApplication).graph,
            DemoScenario.SUCCESS_CORRELATED,
        )
        val localeManager = context.getSystemService(android.app.LocaleManager::class.java)
        localeManager.applicationLocales = android.os.LocaleList.forLanguageTags("nl-NL")
        try {
            // English strings must be on screen even under the Dutch locale.
            compose.waitUntil(15_000) {
                compose.onAllNodesWithText(context.getString(R.string.food_time))
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            compose.onNodeWithText(context.getString(R.string.food_time)).assertExists()
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText(
                    context.resources.getQuantityString(R.plurals.portions, 0, 0),
                ).fetchSemanticsNodes().isNotEmpty()
            }
            Screenshots.capture("dutch-locale-english-card")
        } finally {
            localeManager.applicationLocales = android.os.LocaleList.getEmptyLocaleList()
        }
    }
}

/**
 * Visual evidence: the setup sign-in form raises a real IME over the floating
 * card, Back dismisses the keyboard (not the card), and the form stays usable.
 * Runs only in the API 36 visual phase (see scripts/ci-emulator.sh).
 */
@RunWith(AndroidJUnit4::class)
class ImeVisualTest {

    @get:Rule
    val compose = createAndroidComposeRule<FeedPopupActivity>()

    @get:Rule
    val failureScreenshot = FailureScreenshotRule()

    private val device: UiDevice
        get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** True while the system reports the IME as shown. */
    private fun imeShown(): Boolean = device.executeShellCommand("dumpsys input_method").contains("mInputShown=true")

    private fun awaitIme(visible: Boolean, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (imeShown() == visible) return true
            Thread.sleep(250)
        }
        return imeShown() == visible
    }

    @Test
    fun setupEmailFieldRaisesUsableIme() {
        // Leave demo mode: the setup welcome card holds the sign-in form.
        runBlocking {
            (context.applicationContext as MorselApplication).graph.settingsStore.let {
                it.setOnboardingComplete(false)
                it.setDemoMode(false)
            }
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag(SetupTags.SIGN_IN).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(SetupTags.SIGN_IN).performClick()
        compose.onNodeWithTag(SetupTags.EMAIL).performClick()

        assertTrue("IME did not become visible over the setup card", awaitIme(true, 10_000))
        // The IMMS reports "shown" before the keyboard surface has drawn its
        // first frame; give it a moment so the capture is visual evidence.
        Thread.sleep(1_500)
        Screenshots.capture("ime-setup-card")

        // Back dismisses the keyboard first; the form itself stays on screen.
        device.pressBack()
        assertTrue("IME did not dismiss on Back", awaitIme(false, 10_000))
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag(SetupTags.EMAIL).fetchSemanticsNodes().isNotEmpty()
        }
        Screenshots.capture("ime-dismissed-form")
    }
}

/** Checkpoint review gate: controls remain reachable after a landscape recreation. */
@RunWith(AndroidJUnit4::class)
class LandscapeUsabilityTest {

    @get:Rule
    val compose = createAndroidComposeRule<FeedPopupActivity>()

    @get:Rule
    val failureScreenshot = FailureScreenshotRule()

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun prepare() = prepareDemo(
        (context.applicationContext as MorselApplication).graph,
        DemoScenario.SUCCESS_CORRELATED,
    )

    @Test
    fun controlsStayUsableInLandscape() {
        compose.activityRule.scenario.onActivity { activity ->
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag(Tags.PLUS).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(Tags.PLUS).assertExists()
        compose.onNodeWithTag(Tags.COUNT).assertExists()
        compose.onNodeWithTag(Tags.GEAR).assertExists()
        Screenshots.capture("landscape-card")

        compose.activityRule.scenario.onActivity { activity ->
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag(Tags.PLUS).fetchSemanticsNodes().isNotEmpty()
        }
    }
}

/**
 * Seeds a persisted DISPATCHING operation in the REAL production journal for
 * the process-death phase (run by scripts/ci-emulator.sh). The shell then
 * force-stops the app and relaunches it; [ProcessDeathVerifyTest] asserts the
 * restored UNKNOWN state blocks resending.
 */
@RunWith(AndroidJUnit4::class)
class ProcessDeathSetupTest {

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun seedPersistedPendingOperation() {
        val graph = (context.applicationContext as MorselApplication).graph
        runBlocking {
            graph.settingsStore.setDemoMode(false)
            graph.settingsStore.setOnboardingComplete(true)
            graph.realJournal.upsert(
                FeedOperation(
                    id = "process-death-op",
                    serial = "SN-SEEDED",
                    portions = 2,
                    requestId = "req-process-death",
                    createdAtEpochMs = System.currentTimeMillis(),
                    state = FeedState.DISPATCHING,
                ),
            )
        }
    }
}

/** Runs in a NEW process (shell force-stopped the app): the seeded pending op must restore as blocking UNKNOWN. */
@RunWith(AndroidJUnit4::class)
class ProcessDeathVerifyTest {

    @get:Rule
    val compose = createAndroidComposeRule<FeedPopupActivity>()

    @get:Rule
    val failureScreenshot = FailureScreenshotRule()

    @Test
    fun restoredOperationIsUnknownAndBlocking() {
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag(Tags.CHECK_STATUS).fetchSemanticsNodes().isNotEmpty()
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.onNodeWithText(context.getString(R.string.unknown_body)).assertExists()
        // The restored operation blocks: no Feed button until acknowledged.
        compose.onNodeWithTag(Tags.FEED).assertDoesNotExist()
        Screenshots.capture("process-death-restored")
    }
}
