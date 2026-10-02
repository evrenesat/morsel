package io.evren.morsel

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.evren.morsel.ui.MorselTheme

/**
 * Launcher entry point. Renders a genuinely floating window (see Theme.Morsel.Popup:
 * windowIsFloating) sized to its content, bounded to the visible display area. Back and
 * outside taps finish the activity; the dimmed area absorbs outside touches so the
 * launcher beneath never receives them.
 */
class FeedPopupActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Outside-tap behavior comes from windowCloseOnTouchOutside in Theme.Morsel.Popup:
        // with windowIsFloating, a tap outside the card finishes the activity and is
        // absorbed by the dim layer, so the launcher beneath never receives it.
        window.setBackgroundDrawableResource(android.R.color.transparent)
        setContent {
            MorselTheme {
                PrototypeCard(onClose = { finish() })
            }
        }
    }
}

@Composable
private fun PrototypeCard(onClose: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.systemBars),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 336.dp),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    text = stringResource(R.string.prototype_subtitle),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = stringResource(R.string.prototype_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(onClick = onClose) {
                    Text(stringResource(R.string.prototype_close))
                }
            }
        }
    }
}
