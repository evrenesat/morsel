package io.evren.morsel

import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.evren.morsel.ui.CardScreen
import io.evren.morsel.ui.FeedCard
import io.evren.morsel.ui.FeedViewModel
import io.evren.morsel.ui.MorselTheme
import io.evren.morsel.ui.SettingsCard
import io.evren.morsel.ui.SetupCard

/**
 * Launcher entry point. Renders a genuinely floating window (see
 * Theme.Morsel.Popup: windowIsFloating) sized to its content and bounded to
 * the visible display area. Back and outside taps finish the activity; the
 * dimmed area absorbs outside touches so the launcher beneath never receives
 * them.
 */
class FeedPopupActivity : ComponentActivity() {

    private val viewModel: FeedViewModel by viewModels {
        FeedViewModel.Factory((application as MorselApplication).graph)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Outside-tap dismissal, as measured on CI emulators (run
        // 36986559378): the floating window's INPUT surface extends beyond the
        // visible card frame, so a tap beside the card is delivered to THIS
        // window as a normal DOWN/UP (ACTION_OUTSIDE never fires — the window
        // is its own touch target; FLAG_WATCH_OUTSIDE_TOUCH is set but inert).
        // Dismissal is the platform's Window.shouldCloseOnTouch: on
        // ACTION_OUTSIDE, or on ACTION_UP landing beyond the window-touch slop
        // outside the decor bounds (the branch that actually fires here), it
        // finishes the activity. The input surface covers the display, so the
        // launcher beneath never receives the touch — the dimmed area absorbs
        // outside taps, with a small within-slop dead margin at the card edge
        // (same as dialog windows with a shadow margin).
        setFinishOnTouchOutside(true)
        window.setFlags(
            android.view.WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            android.view.WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
        )
        window.setBackgroundDrawableResource(android.R.color.transparent)
        setContent {
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            val animatorScale = remember {
                Settings.Global.getFloat(
                    contentResolver,
                    Settings.Global.ANIMATOR_DURATION_SCALE,
                    1f,
                )
            }
            val motionEnabled = !state.settings.reduceMotion && animatorScale > 0f
            MorselTheme(motionEnabled = motionEnabled) {
                // Wrap content (never fillMaxSize): the floating window then
                // wraps the card itself, so the window frame IS the card.
                Box(
                    modifier = Modifier
                        .windowInsetsPadding(WindowInsets.systemBars)
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .widthIn(max = 368.dp),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    when (state.screen) {
                        CardScreen.SETUP -> SetupCard(
                            setup = state.setup,
                            onDemo = viewModel::startDemo,
                            onSignIn = viewModel::signIn,
                            onDiscover = viewModel::discover,
                            onBind = viewModel::bindDevice,
                        )
                        CardScreen.FEED -> FeedCard(
                            state = state,
                            onPlus = viewModel::selectPlus,
                            onMinus = viewModel::selectMinus,
                            onFeed = viewModel::feed,
                            onCheckStatus = viewModel::checkStatus,
                            onAcknowledge = viewModel::acknowledgeUnresolved,
                            onDone = { finish() },
                            onOpenSettings = viewModel::openSettings,
                        )
                        CardScreen.SETTINGS -> SettingsCard(
                            state = state,
                            onSetCatName = viewModel::setCatName,
                            onSetCap = viewModel::setPortionCap,
                            onSetHaptics = viewModel::setHapticsEnabled,
                            onSetReduceMotion = viewModel::setReduceMotion,
                            onSetDemoScenario = viewModel::setDemoScenario,
                            onExitDemo = viewModel::exitDemo,
                            onSignOut = viewModel::signOut,
                            onClose = viewModel::closeSettings,
                        )
                    }
                }
            }
        }
    }
}
