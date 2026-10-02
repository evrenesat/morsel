package io.evren.morsel

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.evren.morsel.demo.DemoScenario
import io.evren.morsel.ui.Tags
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Full user flow against the production coordinator + demo repository (real
 * code, scripted transport). Per the plan: success with explicit correlation,
 * accepted unconfirmed, rejected, timeout/unknown, mismatch, offline.
 *
 * The application-scoped demo coordinator, repository and settings persist
 * across test classes in one process, so [prepareDemoState] resets leftover
 * unresolved state, awaits the scenario switch, and (via the ViewModel's
 * settings-driven screen) lands on the demo Feed card before asserting.
 */
@RunWith(AndroidJUnit4::class)
abstract class DemoFlowBase(private val scenario: DemoScenario, private val label: String) {

    @get:Rule
    val compose = createAndroidComposeRule<FeedPopupActivity>()

    @get:Rule
    val failureScreenshot = FailureScreenshotRule()

    private val graph: AppGraph
        get() = (
            InstrumentationRegistry.getInstrumentation()
                .targetContext.applicationContext as MorselApplication
            ).graph

    @Before
    fun prepareDemoState() {
        runBlocking {
            // A previous class may have left an unresolved demo operation
            // (its polling stops once nothing blocks anymore).
            repeat(3) {
                if (!graph.demoCoordinator.state.value.blocksNewSubmissions) return@repeat
                graph.demoCoordinator.acknowledgeUnresolved()
            }
            graph.settingsStore.setBoundSerial(null)
            graph.settingsStore.setCatName(null)
            graph.settingsStore.setOnboardingComplete(false)
            graph.settingsStore.setDemoMode(false)
            graph.settingsStore.setDemoScenario(scenario)
            graph.settingsStore.setDemoMode(true)
            graph.settingsStore.setOnboardingComplete(true)

            // The demo repository reads the scenario through the graph's
            // settings collector; wait for the switch to land so this test's
            // dispatch never races the previous scenario. A stale scenario
            // would let a poll tick confirm the wrong history, so timing out
            // here is a FAILURE, never a silent skip.
            val deadline = System.currentTimeMillis() + 5_000
            while (graph.demoScenario != scenario) {
                check(System.currentTimeMillis() < deadline) {
                    "demo scenario did not switch to $scenario (still ${graph.demoScenario})"
                }
                Thread.sleep(25)
            }
        }
    }

    @Test
    fun demoFlow() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        // Demo is clearly labelled and starts at zero with an empty cup.
        compose.onNodeWithTag(Tags.DEMO_BANNER).assertExists()
        compose.onNodeWithTag(Tags.FEED).assertIsNotEnabled()

        if (scenario == DemoScenario.OFFLINE) {
            compose.onNodeWithText(context.getString(R.string.demo_offline_button)).assertExists()
            Screenshots.capture("demo-$label-offline")
            return
        }

        compose.onNodeWithTag(Tags.PLUS).performClick()
        compose.onNodeWithTag(Tags.PLUS).performClick()
        compose.onNodeWithTag(Tags.FEED).assertIsEnabled().performClick()

        when (scenario) {
            DemoScenario.SUCCESS_CORRELATED -> {
                // Wait for the CORRELATED confirmation text itself: the
                // unresolved state also shows a Done button, so a Done-tag
                // wait would pass before the poll has ever confirmed.
                compose.waitUntil(15_000) {
                    compose.onAllNodesWithText(
                        context.getString(R.string.success_title),
                    ).fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithText(context.getString(R.string.success_title)).assertExists()
                compose.onNodeWithTag(Tags.FEED).assertDoesNotExist()
                Screenshots.capture("demo-$label-success")
            }
            DemoScenario.ACCEPTED_UNCONFIRMED -> {
                compose.waitUntil(10_000) {
                    compose.onAllNodesWithTag(Tags.CHECK_STATUS).fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithText(context.getString(R.string.accepted_unconfirmed_body)).assertExists()
                Screenshots.capture("demo-$label-unconfirmed")
                // Explicit acknowledgement explains duplicate risk before a fresh feed.
                compose.onNodeWithTag(Tags.ACK).performClick()
                compose.onNodeWithText(context.getString(R.string.ack_dialog_confirm)).performClick()
                compose.waitUntil(5_000) {
                    compose.onAllNodesWithTag(Tags.FEED).fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithTag(Tags.FEED).assertIsEnabled()
            }
            DemoScenario.REJECTED -> {
                compose.waitUntil(10_000) {
                    compose.onAllNodesWithText(context.getString(R.string.rejected_body))
                        .fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithTag(Tags.FEED).assertIsEnabled()
                Screenshots.capture("demo-$label-rejected")
            }
            DemoScenario.TIMEOUT_UNKNOWN -> {
                compose.waitUntil(10_000) {
                    compose.onAllNodesWithTag(Tags.CHECK_STATUS).fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithText(context.getString(R.string.unknown_body)).assertExists()
                compose.onNodeWithTag(Tags.ACK).assertExists()
                Screenshots.capture("demo-$label-unknown")
            }
            DemoScenario.MISMATCH -> {
                compose.waitUntil(15_000) {
                    compose.onAllNodesWithText(
                        context.getString(R.string.mismatch_body, 2),
                    ).fetchSemanticsNodes().isNotEmpty()
                }
                // No automatic top-up: the Feed button returns for deliberate use only.
                compose.onNodeWithTag(Tags.FEED).assertIsEnabled()
                Screenshots.capture("demo-$label-mismatch")
            }
            DemoScenario.OFFLINE -> Unit // handled above
        }
    }
}

class DemoSuccessFlowTest : DemoFlowBase(DemoScenario.SUCCESS_CORRELATED, "success")

class DemoUnconfirmedFlowTest : DemoFlowBase(DemoScenario.ACCEPTED_UNCONFIRMED, "unconfirmed")

class DemoRejectedFlowTest : DemoFlowBase(DemoScenario.REJECTED, "rejected")

class DemoUnknownFlowTest : DemoFlowBase(DemoScenario.TIMEOUT_UNKNOWN, "unknown")

class DemoMismatchFlowTest : DemoFlowBase(DemoScenario.MISMATCH, "mismatch")

class DemoOfflineFlowTest : DemoFlowBase(DemoScenario.OFFLINE, "offline")
