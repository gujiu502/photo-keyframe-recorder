package com.eva.feature_recorder.composable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.eva.ui.R

@Composable
internal fun RecorderPausePlayAction(
    showPausedAction: Boolean,
    onResume: () -> Unit,
    onPause: () -> Unit,
    onCancel: () -> Unit,
    onStop: (String, String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var showCancelDialog by rememberSaveable { mutableStateOf(false) }
    var showSaveDialog by rememberSaveable { mutableStateOf(false) }
    val shape = MaterialTheme.shapes.medium

    CancelRecordingDialog(showCancelDialog, { showCancelDialog = false }, onCancel)
    SaveRecordingDialog(showSaveDialog, { showSaveDialog = false }, onStop)

    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Button(
            onClick = { onPause(); showSaveDialog = true },
            enabled = enabled,
            shape = shape,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
        ) {
            Icon(painterResource(R.drawable.ic_stop), null, Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("結束並儲存")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(
                onClick = if (showPausedAction) onResume else onPause,
                enabled = enabled,
                shape = shape,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            ) { Text(stringResource(if (showPausedAction) R.string.recorder_action_resume else R.string.action_paused)) }
            TextButton(
                onClick = { onPause(); showCancelDialog = true },
                enabled = enabled,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.recorder_action_cancel)) }
        }
    }
}
