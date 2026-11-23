package com.eva.ui.navigation

import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.painterResource
import com.eva.ui.R

@Composable
fun RecordingNavigation(showingRecordings: Boolean, onRecorder: () -> Unit, onRecordings: () -> Unit) {
    val colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.primaryContainer)
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
        NavigationBarItem(
            selected = !showingRecordings,
            onClick = onRecorder,
            icon = { Icon(painterResource(R.drawable.ic_microphone), null) },
            label = { Text("錄音") },
            colors = colors,
        )
        NavigationBarItem(
            selected = showingRecordings,
            onClick = onRecordings,
            icon = { Icon(painterResource(R.drawable.ic_list), null) },
            label = { Text("錄音列表") },
            colors = colors,
        )
    }
}
