package io.evren.morsel.ui

import android.text.format.DateUtils
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.evren.morsel.R
import io.evren.morsel.domain.FeedState

/** Tag names used by instrumented tests. */
object Tags {
    const val CARD = "morsel.card"
    const val PLUS = "morsel.plus"
    const val MINUS = "morsel.minus"
    const val COUNT = "morsel.count"
    const val FEED = "morsel.feed"
    const val DONE = "morsel.done"
    const val CHECK_STATUS = "morsel.check_status"
    const val ACK = "morsel.ack"
    const val GEAR = "morsel.gear"
    const val STATUS = "morsel.status"
    const val NOTICE = "morsel.notice"
    const val DEMO_BANNER = "morsel.demo_banner"
}

@Composable
fun morselFur(): androidx.compose.ui.graphics.Color = if (isSystemDark()) MorselArt.DarkFur else MorselArt.FurLight

@Composable
fun morselFurSoft(): androidx.compose.ui.graphics.Color = if (isSystemDark()) MorselArt.DarkFurSoft else MorselArt.FurLightSoft

@Composable
private fun isSystemDark(): Boolean = androidx.compose.foundation.isSystemInDarkTheme()

/**
 * The main feeding card: header, art, counter, the one deliberate Feed button,
 * honest status area and a quiet footer. Bounded to ~336dp wide; the scene
 * shrinks first on small windows, then the card scrolls.
 */
@Composable
fun FeedCard(
    state: FeedUiState,
    onPlus: () -> Unit,
    onMinus: () -> Unit,
    onFeed: () -> Unit,
    onCheckStatus: () -> Unit,
    onAcknowledge: () -> Unit,
    onDone: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val view = LocalView.current
    var showAckDialog by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier
            .widthIn(max = 336.dp)
            .testTag(Tags.CARD),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            HeaderRow(state, onOpenSettings)
            Spacer(Modifier.size(8.dp))
            SceneArea(state)
            Spacer(Modifier.size(12.dp))
            CounterRow(state, onPlus, onMinus, haptics::performHapticFeedback)
            Spacer(Modifier.size(12.dp))
            StatusArea(
                state = state,
                onFeed = {
                    if (state.settings.hapticsEnabled) {
                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    }
                    onFeed()
                },
                onCheckStatus = onCheckStatus,
                onAcknowledge = { showAckDialog = true },
                onDone = onDone,
            )
            Spacer(Modifier.size(8.dp))
            Footer(state)
        }
    }

    if (showAckDialog) {
        AlertDialog(
            onDismissRequest = { showAckDialog = false },
            title = { Text(stringResource(R.string.ack_dialog_title)) },
            text = { Text(stringResource(R.string.ack_dialog_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showAckDialog = false
                    onAcknowledge()
                }) { Text(stringResource(R.string.ack_dialog_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showAckDialog = false }) {
                    Text(stringResource(R.string.ack_dialog_dismiss))
                }
            },
        )
    }
}

@Composable
private fun HeaderRow(state: FeedUiState, onOpenSettings: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = state.settings.catName?.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.food_time),
                style = MaterialTheme.typography.titleLarge,
            )
            if (state.demoMode) {
                Text(
                    text = stringResource(R.string.demo_badge),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        IconButton(
            onClick = onOpenSettings,
            modifier = Modifier
                .size(48.dp)
                .testTag(Tags.GEAR),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_gear),
                contentDescription = stringResource(R.string.a11y_settings),
            )
        }
    }
}

@Composable
private fun SceneArea(state: FeedUiState) {
    val mood = sceneMood(state)
    val cupState = when {
        state.coordinator.dispatching -> CupState.STILL
        state.unresolved?.state == FeedState.ACCEPTED_UNCONFIRMED -> CupState.POURING
        state.lastResolved?.state == FeedState.REPORTED_SUCCESS -> CupState.POURING
        else -> CupState.PORTIONS
    }
    // Canvas scenes have no intrinsic height: give the row a definite,
    // bounded height (reduced on short/constrained layouts, where the card
    // scrolls) so both scenes actually have room to draw.
    val sceneHeight = if (LocalConfiguration.current.screenHeightDp < 480) 84.dp else 120.dp
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(sceneHeight),
        verticalAlignment = Alignment.Bottom,
    ) {
        CatScene(
            mood = mood,
            fur = morselFur(),
            furSoft = morselFurSoft(),
            accent = MaterialTheme.colorScheme.primary,
            onFur = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .weight(1.1f)
                .fillMaxHeight(),
        )
        CupScene(
            state = cupState,
            portions = state.selection,
            cup = MaterialTheme.colorScheme.surfaceVariant,
            cupRim = MaterialTheme.colorScheme.primaryContainer,
            kibble = MorselArt.Kibble,
            kibbleDark = MorselArt.KibbleDark,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
        )
    }
}

private fun sceneMood(state: FeedUiState): CatMood = when {
    state.coordinator.storageError -> CatMood.UNSURE
    state.coordinator.dispatching -> CatMood.SENDING
    state.unresolved?.state == FeedState.ACCEPTED_UNCONFIRMED -> CatMood.WAITING
    state.unresolved != null -> CatMood.UNSURE
    state.lastResolved?.state == FeedState.REPORTED_SUCCESS -> CatMood.HAPPY
    else -> CatMood.IDLE
}

@Composable
private fun CounterRow(
    state: FeedUiState,
    onPlus: () -> Unit,
    onMinus: () -> Unit,
    haptic: (HapticFeedbackType) -> Unit,
) {
    val enabled = state.canChangeSelection
    // The narrow counter row carries the bare number only: at large font
    // scales a "%d portions" label would break mid-word. The full localized
    // quantity sits on its own full-width line, and TalkBack still hears the
    // complete plural via the count's content description.
    val countQuantity = pluralStringResource(R.plurals.portions, state.selection, state.selection)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CounterButton(
                label = stringResource(R.string.a11y_minus),
                symbol = stringResource(R.string.minus_symbol),
                enabled = enabled && state.selection > 0,
                onClick = {
                    haptic(HapticFeedbackType.TextHandleMove)
                    onMinus()
                },
                tag = Tags.MINUS,
            )
            Text(
                text = "${state.selection}",
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .weight(1f)
                    .semantics { contentDescription = countQuantity }
                    .testTag(Tags.COUNT),
            )
            CounterButton(
                label = stringResource(R.string.a11y_plus),
                symbol = stringResource(R.string.plus_symbol),
                enabled = enabled && state.selection < state.cap,
                onClick = {
                    haptic(HapticFeedbackType.TextHandleMove)
                    onPlus()
                },
                tag = Tags.PLUS,
            )
        }
        Text(
            text = countQuantity,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun CounterButton(
    label: String,
    symbol: String,
    enabled: Boolean,
    onClick: () -> Unit,
    tag: String,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(56.dp)
            .semantics { contentDescription = label }
            .testTag(tag),
        contentPadding = PaddingValues(0.dp),
    ) {
        Text(symbol, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun StatusArea(
    state: FeedUiState,
    onFeed: () -> Unit,
    onCheckStatus: () -> Unit,
    onAcknowledge: () -> Unit,
    onDone: () -> Unit,
) {
    val unresolved = state.unresolved
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        // Actionable feedback for the last attempt or read (e.g. "feeder was
        // offline, nothing was sent"); cleared on a fresh deliberate attempt.
        state.noticeRes?.let { res ->
            StatusLine(stringResource(res), Tags.NOTICE)
            Spacer(Modifier.size(8.dp))
        }
        when {
            // Unreadable journal: sending is blocked, say so plainly. No feed
            // button is offered while earlier operation state is unknown.
            state.coordinator.storageError -> {
                StatusLine(stringResource(R.string.storage_error_body), Tags.STATUS)
            }
            state.coordinator.dispatching -> {
                CircularProgressIndicator(Modifier.size(28.dp))
                StatusLine(stringResource(R.string.sending), Tags.STATUS)
            }
            unresolved?.state == FeedState.ACCEPTED_UNCONFIRMED -> {
                UnresolvedActions(
                    message = stringResource(R.string.accepted_unconfirmed_body),
                    onCheckStatus = onCheckStatus,
                    onDone = onDone,
                    onAcknowledge = onAcknowledge,
                )
            }
            unresolved?.state == FeedState.UNKNOWN -> {
                UnresolvedActions(
                    message = if (unresolved.authExpiredDuringWrite) {
                        stringResource(R.string.unknown_auth_body)
                    } else {
                        stringResource(R.string.unknown_body)
                    },
                    onCheckStatus = onCheckStatus,
                    onDone = onDone,
                    onAcknowledge = onAcknowledge,
                )
            }
            state.successThisSession -> {
                StatusLine(stringResource(R.string.success_title), Tags.STATUS)
                Button(
                    onClick = onDone,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag(Tags.DONE),
                ) {
                    Text(stringResource(R.string.done))
                }
            }
            state.lastResolved?.state == FeedState.REPORTED_MISMATCH -> {
                StatusLine(
                    stringResource(R.string.mismatch_body, state.lastResolved?.portions ?: 0),
                    Tags.STATUS,
                )
                FeedButton(state, onFeed)
            }
            state.lastResolved?.state == FeedState.REJECTED -> {
                StatusLine(stringResource(R.string.rejected_body), Tags.STATUS)
                FeedButton(state, onFeed)
            }
            else -> FeedButton(state, onFeed)
        }
    }
}

@Composable
private fun UnresolvedActions(
    message: String,
    onCheckStatus: () -> Unit,
    onDone: () -> Unit,
    onAcknowledge: () -> Unit,
) {
    StatusLine(message, Tags.STATUS)
    Spacer(Modifier.size(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = onCheckStatus,
            modifier = Modifier
                .heightIn(min = 48.dp)
                .testTag(Tags.CHECK_STATUS),
        ) {
            Text(stringResource(R.string.check_status))
        }
        OutlinedButton(
            onClick = onDone,
            modifier = Modifier
                .heightIn(min = 48.dp)
                .testTag(Tags.DONE),
        ) {
            Text(stringResource(R.string.done))
        }
    }
    TextButton(
        onClick = onAcknowledge,
        modifier = Modifier
            .heightIn(min = 48.dp)
            .testTag(Tags.ACK),
    ) {
        Text(stringResource(R.string.i_checked_the_feeder))
    }
}

@Composable
private fun FeedButton(state: FeedUiState, onFeed: () -> Unit) {
    Button(
        onClick = onFeed,
        enabled = state.feedEnabled,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .testTag(Tags.FEED),
    ) {
        Text(
            when {
                state.demoOffline -> stringResource(R.string.demo_offline_button)
                state.selection == 0 -> stringResource(R.string.feed_button_idle)
                else -> pluralStringResource(R.plurals.feed_button, state.selection, state.selection)
            },
        )
    }
}

@Composable
private fun StatusLine(text: String, tag: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite }
            .testTag(tag),
    )
}

@Composable
private fun Footer(state: FeedUiState) {
    val last = state.coordinator.feederHistory.firstOrNull()
    val historyLine = last?.let {
        val relative = DateUtils.getRelativeTimeSpanString(
            it.recordTimeEpochMs,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS,
        ).toString()
        stringResource(R.string.feeder_history_line, it.actualGrainNum ?: 0, relative)
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = historyLine ?: stringResource(R.string.footer_idle),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (state.demoMode) {
            Text(
                text = stringResource(R.string.demo_banner),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier.testTag(Tags.DEMO_BANNER),
            )
        }
    }
}
