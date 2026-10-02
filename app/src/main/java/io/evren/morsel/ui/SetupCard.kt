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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.evren.morsel.R
import io.evren.morsel.domain.DeviceIdentity

/** Setup tags for instrumented tests. */
object SetupTags {
    const val DEMO_BUTTON = "morsel.setup.demo"
    const val SIGN_IN = "morsel.setup.sign_in"
    const val EMAIL = "morsel.setup.email"
    const val PASSWORD = "morsel.setup.password"
    const val DEVICE_OPTION = "morsel.setup.device_option"
    const val BIND = "morsel.setup.bind"
}

/**
 * First-run setup: clearly-labelled demo route (no credentials, no food) or a
 * real sign-in with explicit discovery of exactly one PLAF108.
 */
@Composable
fun SetupCard(
    setup: SetupState,
    onDemo: () -> Unit,
    onSignIn: (email: String, password: String) -> Unit,
    onDiscover: () -> Unit,
    onBind: (serial: String) -> Unit,
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
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CatScene(
                mood = CatMood.IDLE,
                fur = morselFur(),
                furSoft = morselFurSoft(),
                accent = MaterialTheme.colorScheme.primary,
                onFur = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 72.dp, max = 110.dp),
            )
            Spacer(Modifier.size(8.dp))
            Text(
                stringResource(R.string.setup_welcome_title),
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.size(6.dp))
            Text(
                stringResource(R.string.setup_welcome_body),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(16.dp))
            when (setup) {
                SetupState.Welcome -> WelcomeActions(onDemo = onDemo, onSignIn = onSignIn)
                SetupState.SigningIn -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(Modifier.size(28.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.setup_signing_in))
                }
                SetupState.Discovering -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(Modifier.size(28.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.setup_discovering))
                }
                is SetupState.FoundOne -> FoundOneContent(setup.device, onBind, onDiscover)
                is SetupState.ChooseDevice -> ChooseDeviceContent(setup.devices, onBind)
                SetupState.NoneFound -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        stringResource(R.string.setup_none_found),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.size(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onDiscover) {
                            Text(stringResource(R.string.setup_retry))
                        }
                        TextButton(onClick = onDemo) { Text(stringResource(R.string.setup_demo_button)) }
                    }
                }
                is SetupState.Failed -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(
                            when (setup.reason) {
                                SetupFailure.AUTH -> R.string.setup_failed_auth
                                SetupFailure.NETWORK -> R.string.setup_failed_network
                                SetupFailure.UNEXPECTED -> R.string.setup_failed_unexpected
                            },
                        ),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.size(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onDiscover) {
                            Text(stringResource(R.string.setup_retry))
                        }
                        TextButton(onClick = onDemo) { Text(stringResource(R.string.setup_demo_button)) }
                    }
                }
            }
            Spacer(Modifier.size(8.dp))
            Text(
                stringResource(R.string.setup_demo_note),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun WelcomeActions(onDemo: () -> Unit, onSignIn: (String, String) -> Unit) {
    var showSignIn by remember { mutableStateOf(false) }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        if (!showSignIn) {
            Button(
                onClick = { showSignIn = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag(SetupTags.SIGN_IN),
            ) {
                Text(stringResource(R.string.setup_sign_in_button))
            }
            TextButton(
                onClick = onDemo,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag(SetupTags.DEMO_BUTTON),
            ) {
                Text(stringResource(R.string.setup_demo_button))
            }
        } else {
            SignInForm(
                onSubmit = {
                    showSignIn = false
                    onSignIn(it.first, it.second)
                },
            )
        }
    }
}

@Composable
private fun SignInForm(onSubmit: (Pair<String, String>) -> Unit) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text(stringResource(R.string.setup_email)) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(SetupTags.EMAIL),
        )
        Spacer(Modifier.size(8.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text(stringResource(R.string.setup_password)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier
                .fillMaxWidth()
                .testTag(SetupTags.PASSWORD),
        )
        Spacer(Modifier.size(6.dp))
        Text(
            stringResource(R.string.setup_session_note),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(10.dp))
        Button(
            onClick = { onSubmit(email to password) },
            enabled = email.isNotBlank() && password.isNotEmpty(),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
        ) {
            Text(stringResource(R.string.setup_sign_in_continue))
        }
    }
}

@Composable
private fun FoundOneContent(device: DeviceIdentity, onBind: (String) -> Unit, onDiscover: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.setup_found_one_title),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.size(4.dp))
        Text(
            device.name ?: device.productName ?: device.serial,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            device.serial,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(12.dp))
        Button(
            onClick = { onBind(device.serial) },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .testTag(SetupTags.BIND),
        ) {
            Text(stringResource(R.string.setup_use_this_feeder))
        }
        TextButton(onClick = onDiscover) { Text(stringResource(R.string.setup_refresh)) }
    }
}

@Composable
private fun ChooseDeviceContent(devices: List<DeviceIdentity>, onBind: (String) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.setup_choose_title),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.size(8.dp))
        devices.forEach { device ->
            OutlinedButton(
                onClick = { onBind(device.serial) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag(SetupTags.DEVICE_OPTION),
            ) {
                Text("${device.name ?: device.productName ?: "-"} · ${device.serial.takeLast(6)}")
            }
            Spacer(Modifier.size(4.dp))
        }
    }
}
