package io.evren.morsel

import android.content.Intent
import android.content.pm.ActivityInfo
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import io.evren.morsel.demo.DemoScenario
import io.evren.morsel.domain.FeedOperation
import io.evren.morsel.domain.FeedState
import io.evren.morsel.ui.Tags
import kotlinx.coroutines.runBlocking
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
        val deadline = System.currentTimeMillis() + 5_000
        while (graph.demoScenario != scenario && System.currentTimeMillis() < deadline) {
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
 * Checkpoint review gate: a request left in flight when the card is dismissed
 * stays visible and blocking after reopening IN THE SAME PROCESS. The demo
 * TIMEOUT_UNKNOWN scenario drives the production coordinator (transport is
 * scripted; no real feeder and no network).
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

    @Before
    fun prepare() = prepareDemo(
        (context.applicationContext as MorselApplication).graph,
        DemoScenario.TIMEOUT_UNKNOWN,
    )

    @Test
    fun dismissedInFlightRequestIsStillBlockingAfterReopen() {
        val graph = context.applicationContext as MorselApplication
        compose.onNodeWithTag(Tags.PLUS).performClick()
        compose.onNodeWithTag(Tags.PLUS).performClick()
        compose.onNodeWithTag(Tags.FEED).assertIsEnabled().performClick()

        // The scripted timeout resolves to UNKNOWN: unresolved actions appear.
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag(Tags.CHECK_STATUS).fetchSemanticsNodes().isNotEmpty()
        }
        val unknown = context.getString(R.string.unknown_body)

        // Card dismissal while the outcome is unresolved.
        device.pressBack()

        // Reopen in the SAME process: the application-scoped coordinator still
        // holds the unresolved operation; nothing was resent.
        val pidBefore = android.os.Process.myPid()
        context.startActivity(
            Intent(context, FeedPopupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        org.junit.Assert.assertNotNull(device.wait(Until.hasObject(By.text(unknown)), 10_000))
        org.junit.Assert.assertEquals(pidBefore, android.os.Process.myPid())
        Screenshots.capture("inflight-dismiss-reopen")
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
