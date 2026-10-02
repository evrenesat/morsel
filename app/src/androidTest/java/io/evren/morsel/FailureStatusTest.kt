package io.evren.morsel

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.evren.morsel.data.MorselSettings
import io.evren.morsel.domain.SubmissionResult
import io.evren.morsel.ui.FeedCard
import io.evren.morsel.ui.FeedViewModel
import io.evren.morsel.ui.MorselTheme
import io.evren.morsel.ui.Tags
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Checkpoint-3 acceptance: a blocked attempt (here: feeder offline) shows a
 * visible, localized failure status through the production ViewModel and
 * FeedCard on a device, and says nothing was sent.
 */
@RunWith(AndroidJUnit4::class)
class FailureStatusTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun blockedOfflineAttemptShowsVisibleFailureText() {
        val coordinator = ScriptableCoordinator().apply {
            nextSubmitResult = SubmissionResult.Blocked(SubmissionResult.Blocked.Reason.OFFLINE)
        }
        val settings = MutableStateFlow(
            MorselSettings(onboardingComplete = true, boundSerial = "SN"),
        )
        val viewModel = FeedViewModel(InstrumentedGraph(settings, coordinator, ScriptableCoordinator()))

        compose.setContent {
            MorselTheme {
                val state by viewModel.uiState.collectAsState()
                FeedCard(
                    state = state,
                    onPlus = viewModel::selectPlus,
                    onMinus = viewModel::selectMinus,
                    onFeed = viewModel::feed,
                    onCheckStatus = viewModel::checkStatus,
                    onAcknowledge = viewModel::acknowledgeUnresolved,
                    onDone = { },
                    onOpenSettings = { },
                )
            }
        }

        compose.onNodeWithTag(Tags.PLUS).performClick()
        compose.onNodeWithTag(Tags.FEED).performClick()

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val failure = context.getString(R.string.blocked_offline)
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(failure).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(failure).assertExists()
    }
}
