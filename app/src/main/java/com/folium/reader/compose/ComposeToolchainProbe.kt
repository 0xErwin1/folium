package com.folium.reader.compose

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

const val COMPOSE_TOOLCHAIN_PROBE_TAG = "compose-toolchain-probe"

@Composable
fun ComposeToolchainProbe(modifier: Modifier = Modifier) {
    MaterialTheme {
        Surface {
            Text(
                text = "Compose toolchain ready",
                modifier = modifier.semantics { contentDescription = COMPOSE_TOOLCHAIN_PROBE_TAG }
            )
        }
    }
}
