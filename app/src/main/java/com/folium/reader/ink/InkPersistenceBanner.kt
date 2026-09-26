package com.folium.reader.ink

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.folium.reader.ui.FoliumRuleEdge
import com.folium.reader.ui.FoliumSpacing
import com.folium.reader.ui.foliumRule

/**
 * The non-dismissable banner shown once a drawing surface's writer has refused an edit, saying so in
 * [message]: a sheet pane and a page written on in the reader both draw it above their surface.
 */
@Composable
internal fun InkPersistenceBanner(message: String, testTag: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer)
            .foliumRule(FoliumRuleEdge.BOTTOM, 1.dp, MaterialTheme.colorScheme.error)
            .padding(horizontal = FoliumSpacing.m, vertical = FoliumSpacing.s)
            .testTag(testTag)
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer
        )
    }
}
