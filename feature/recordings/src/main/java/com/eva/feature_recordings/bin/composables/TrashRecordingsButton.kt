package com.eva.feature_recordings.bin.composables

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.FloatingActionButtonElevation
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.eva.ui.R

@Composable
internal fun TrashSelectedRecordingsButton(
	onDelete: () -> Unit,
	onDeleteCloud: () -> Unit = {},
	modifier: Modifier = Modifier,
	shape: Shape = MaterialTheme.shapes.medium,
	containerColor: Color = FloatingActionButtonDefaults.containerColor,
	contentColor: Color = contentColorFor(containerColor),
	elevation: FloatingActionButtonElevation = FloatingActionButtonDefaults.elevation(),
) {

	var showDialog by remember { mutableStateOf(false) }
	var confirmCloud by remember { mutableStateOf(false) }
	if (confirmCloud) AlertDialog(onDismissRequest = { confirmCloud = false },
		title = { Text("確認刪除 Google Drive 備份？") }, text = { Text("所選課程的雲端錄音、照片及時間軸都會刪除，無法復原。沒有網路時會排隊執行。") },
		confirmButton = { TextButton(onClick = { confirmCloud = false; onDeleteCloud() }) { Text("確認刪除本地與雲端") } },
		dismissButton = { TextButton(onClick = { confirmCloud = false }) { Text("取消") } })

	if (showDialog)
		AlertDialog(
			onDismissRequest = { showDialog = false },
			confirmButton = {
				TextButton(
					onClick = {
						onDelete()
						showDialog = false
					},
				) {
					Text("只刪本地")
				}
			},
			dismissButton = {
				TextButton(onClick = { showDialog = false }) {
					Text(text = stringResource(id = R.string.action_cancel))
				}
			},
			title = { Text(text = stringResource(id = R.string.recording_trash_dialog_title)) },
			text = { androidx.compose.foundation.layout.Column {
				Text(text = stringResource(id = R.string.recording_trash_dialog_text))
				TextButton(onClick = { showDialog = false; confirmCloud = true }) { Text("本地與 Google Drive 都刪除") }
			} },
			icon = {
				Icon(
					painter = painterResource(id = R.drawable.ic_eraser),
					contentDescription = stringResource(id = R.string.recording_action_trash)
				)
			},
		)

	ExtendedFloatingActionButton(
		onClick = { showDialog = true },
		modifier = modifier,
		shape = shape,
		contentColor = contentColor,
		containerColor = containerColor,
		elevation = elevation
	) {
		Icon(
			painter = painterResource(id = R.drawable.ic_eraser),
			contentDescription = stringResource(id = R.string.recording_action_trash)
		)
		Spacer(modifier = Modifier.width(6.dp))
		Text(text = stringResource(id = R.string.recording_action_trash))
	}
}
