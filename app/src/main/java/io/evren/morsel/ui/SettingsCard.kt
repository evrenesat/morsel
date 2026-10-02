package io.evren.morsel.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.evren.morsel.R
import io.evren.morsel.demo.DemoScenario

/** Settings tags for instrumented tests. */
object SettingsTags {
    const val CAT_NAME = "morsel.settings.cat_name"
    const val CAP_SLIDER = "morsel.settings.cap"
    const val HAPTICS = "morsel.settings.haptics"
    const val REDUCE_MOTION = "morsel.settings.reduce_motion"
    const val SIGN_OUT = "morsel.settings.sign_out"
    const val CLOSE = "morsel.settings.close"
    const val SCENARIO = "morsel.settings.scenario"
    const val EXIT_DEMO = "morsel.settings.exit_demo"
}

/**
 * Small settings card: local cat name, lower-only portion cap, haptics,
 * reduce motion, demo controls (when in demo), sign-out. Close returns to the
 * feeding card.
 */
@Composable
fun SettingsCard(
    state: FeedUiState,
    onSetCatName: (String?) -> Unit,
    onSetCap: (Int) -> Unit,
    onSetHaptics: (Boolean) -> Unit,
    onSetReduceMotion: (Boolean) -> Unit,
    onSetDemoScenario: (DemoScenario) -> Unit,
    onExitDemo: () -> Unit,
    onSignOut: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.widthIn(max = 336.dp),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                stringResource(R.string.settings_title),
                style = MaterialTheme.typography.titleLarge,
            )

            var catName by remember(state.settings.catName) {
                mutableStateOf(state.settings.catName.orEmpty())
            }
            OutlinedTextField(
                value = catName,
                onValueChange = {
                    catName = it
                    onSetCatName(it.ifBlank { null })
                },
                label = { Text(stringResource(R.string.settings_cat_name)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(SettingsTags.CAT_NAME),
            )

            CapRow(state.settings.portionCap, onSetCap)

            SwitchRow(
                label = stringResource(R.string.settings_haptics),
                checked = state.settings.hapticsEnabled,
                onChange = onSetHaptics,
                tag = SettingsTags.HAPTICS,
            )
            SwitchRow(
                label = stringResource(R.string.settings_reduce_motion),
                checked = state.settings.reduceMotion,
                onChange = onSetReduceMotion,
                tag = SettingsTags.REDUCE_MOTION,
            )

            if (state.demoMode) {
                DemoSection(state, onSetDemoScenario, onExitDemo)
            } else {
                TextButton(
                    onClick = onSignOut,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag(SettingsTags.SIGN_OUT),
                ) {
                    Text(stringResource(R.string.settings_sign_out))
                }
            }

            Button(
                onClick = onClose,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag(SettingsTags.CLOSE),
            ) {
                Text(stringResource(R.string.settings_close))
            }
            Text(
                stringResource(R.string.settings_version, "0.1.0"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun CapRow(cap: Int, onSetCap: (Int) -> Unit) {
    Column {
        Text(
            stringResource(R.string.settings_cap_label, cap),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            stringResource(R.string.settings_cap_help),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        androidx.compose.material3.Slider(
            value = cap.toFloat(),
            onValueChange = { onSetCap(it.toInt().coerceIn(1, 16)) },
            valueRange = 1f..16f,
            steps = 14,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(SettingsTags.CAP_SLIDER),
        )
    }
}

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    tag: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            modifier = Modifier
                .size(52.dp)
                .testTag(tag),
        )
    }
}

@Composable
private fun DemoSection(
    state: FeedUiState,
    onSetDemoScenario: (DemoScenario) -> Unit,
    onExitDemo: () -> Unit,
) {
    Column {
        Text(
            stringResource(R.string.settings_demo_title),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            stringResource(R.string.settings_demo_help),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(4.dp))
        DemoScenario.entries.forEach { scenario ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 40.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    scenarioLabel(scenario),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                androidx.compose.material3.RadioButton(
                    selected = state.settings.demoScenario == scenario.name,
                    onClick = { onSetDemoScenario(scenario) },
                    modifier = Modifier.testTag(SettingsTags.SCENARIO),
                )
            }
        }
        TextButton(
            onClick = onExitDemo,
            modifier = Modifier
                .heightIn(min = 48.dp)
                .testTag(SettingsTags.EXIT_DEMO),
        ) {
            Text(stringResource(R.string.settings_exit_demo))
        }
    }
}

@Composable
private fun scenarioLabel(scenario: DemoScenario): String = stringResource(
    when (scenario) {
        DemoScenario.SUCCESS_CORRELATED -> R.string.demo_scenario_success
        DemoScenario.ACCEPTED_UNCONFIRMED -> R.string.demo_scenario_unconfirmed
        DemoScenario.REJECTED -> R.string.demo_scenario_rejected
        DemoScenario.TIMEOUT_UNKNOWN -> R.string.demo_scenario_timeout
        DemoScenario.MISMATCH -> R.string.demo_scenario_mismatch
        DemoScenario.OFFLINE -> R.string.demo_scenario_offline
    },
)
