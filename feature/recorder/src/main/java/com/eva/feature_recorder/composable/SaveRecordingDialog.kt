package com.eva.feature_recorder.composable

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import com.eva.recorder.domain.models.isValidRecordingName
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.window.DialogProperties
import com.eva.ui.R
import com.eva.ui.theme.RecorderAppTheme

@Composable
internal fun SaveRecordingDialog(
	showDialog: Boolean,
	onDismiss: () -> Unit,
	onSave: (String, String) -> Unit,
	modifier: Modifier = Modifier,
	properties: DialogProperties = DialogProperties(dismissOnClickOutside = false),
) {
	if (!showDialog) return
	var name by rememberSaveable { mutableStateOf("") }
	var course by rememberSaveable { mutableStateOf("") }
	val valid = isValidRecordingName(name) && (course.isBlank() || isValidRecordingName(course))

	AlertDialog(
		onDismissRequest = onDismiss,
		confirmButton = {
			TextButton(
				onClick = {
					onDismiss()
					onSave(name.trim(), course.trim().ifEmpty { "未分類" })
				},
				enabled = valid,
			) {
				Text(text = stringResource(id = R.string.dialog_action_done))
			}
		},
		dismissButton = {
			TextButton(onClick = onDismiss) {
				Text(text = stringResource(id = R.string.action_cancel))
			}
		},
		title = { Text(text = stringResource(id = R.string.save_recording_dialog_title)) },
		text = {
			Column {
				OutlinedTextField(value = course, onValueChange = { course = it }, singleLine = true,
					label = { Text("課程名稱（可留空）") }, isError = course.isNotBlank() && !isValidRecordingName(course),
					supportingText = { Text(if (course.isNotBlank() && !isValidRecordingName(course)) "最多 50 字，不可包含檔名禁用符號" else "留空時備份到「未分類」課程") })
				OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true,
					label = { Text("檔名（不含副檔名）") }, isError = name.isNotEmpty() && !valid,
					supportingText = { Text(if (name.isNotEmpty() && !valid) "最多 50 字，不可包含 / \\ : * ? \" < > |" else "自動附加日期時間：yyyy-MM-dd_HH-mm-ss") })
			}
		},
		modifier = modifier,
		shape = MaterialTheme.shapes.extraLarge,
		properties = properties,
	)
}

@PreviewLightDark
@Composable
private fun SaveRecordingsDialogPreview() = RecorderAppTheme {
	SaveRecordingDialog(
		showDialog = true,
		onDismiss = {},
		onSave = { _, _ -> }
	)
}
