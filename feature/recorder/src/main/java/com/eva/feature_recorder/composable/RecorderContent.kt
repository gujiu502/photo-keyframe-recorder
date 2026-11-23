package com.eva.feature_recorder.composable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.datasource.CollectionPreviewParameterProvider
import androidx.compose.ui.unit.dp
import com.eva.feature_recorder.util.RecorderPreviewFakes
import com.eva.recorder.domain.models.RecorderAction
import com.eva.recorder.domain.models.RecorderState
import com.eva.recorder.utils.DeferredDurationList
import com.eva.recorder.utils.DeferredRecordedPointList
import com.eva.ui.R
import com.eva.ui.theme.DownloadableFonts
import com.eva.ui.theme.RecorderAppTheme
import kotlinx.datetime.LocalTime

@Composable
internal fun RecorderContent(
	timer: () -> LocalTime,
	recordingPointsCallback: DeferredRecordedPointList,
	bookMarksDeferred: DeferredDurationList,
	recorderState: RecorderState,
	onRecorderAction: (RecorderAction) -> Unit,
	modifier: Modifier = Modifier,
	isStartRecordingEnabled: Boolean = true,
	photoCount: Int = 0,
	keyframeControls: @Composable () -> Unit = {},
) {
	val console = Color(0xFF251F26)
	val accent = Color(0xFFF38255)
	val light = Color(0xFFFAF7F0)
	val status = when (recorderState) {
		RecorderState.RECORDING -> "錄音中"
		RecorderState.PAUSED -> "已暫停"
		RecorderState.PREPARING -> "正在處理"
		else -> "待機"
	}
	Column(
		modifier = modifier,
		verticalArrangement = Arrangement.spacedBy(8.dp),
	) {
		Column(
			modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
			verticalArrangement = Arrangement.spacedBy(8.dp),
		) {
			Text("錄音工作台", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
			Surface(color = console, contentColor = light, shape = RoundedCornerShape(22.dp)) {
				Column(
					verticalArrangement = Arrangement.spacedBy(6.dp),
					modifier = Modifier.fillMaxWidth().padding(14.dp),
				) {
					Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
						androidx.compose.foundation.layout.Spacer(Modifier.size(10.dp).background(accent, CircleShape))
						Text(status, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
						RecorderTimerText(
							time = timer,
							color = light,
							style = MaterialTheme.typography.headlineSmall,
							fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
						)
					}
					RecorderAmplitudeGraph(
						recoderPointsCallback = recordingPointsCallback,
						bookMarksDeferred = bookMarksDeferred,
						timelineFontFamily = DownloadableFonts.PLUS_CODE_LATIN_FONT_FAMILY,
						containerColor = console,
						chartColor = accent,
						bookMarkColor = accent,
						timelineColor = Color(0xFFB8A8A8),
						timelineColorVariant = Color(0xFF544951),
						timelineTextColor = light,
						contentPadding = PaddingValues(horizontal = 6.dp),
						modifier = Modifier.fillMaxWidth(),
					)
					HorizontalDivider(color = Color(0xFF544951))
					Text("$photoCount 張照片", style = MaterialTheme.typography.labelLarge)
				}
			}
			keyframeControls()
		}
		AnimatedRecorderActionTray(
			recorderState = recorderState,
			onRecorderAction = onRecorderAction,
			isStartRecordingEnabled = isStartRecordingEnabled,
			modifier = Modifier.fillMaxWidth(),
		)
	}
}

private class RecorderContentRecorderStatePreviewParams :
	CollectionPreviewParameterProvider<RecorderState>(
		listOf(
			RecorderState.RECORDING,
			RecorderState.COMPLETED,
			RecorderState.PAUSED
		)
	)

@PreviewLightDark
@Composable
private fun RecorderContentPreview(
	@PreviewParameter(RecorderContentRecorderStatePreviewParams::class)
	recorderState: RecorderState,
) = RecorderAppTheme {
	Surface {
		RecorderContent(
			timer = { LocalTime(0, 10, 56, 0) },
			recorderState = recorderState,
			bookMarksDeferred = { setOf() },
			recordingPointsCallback = { RecorderPreviewFakes.PREVIEW_RECORDER_AMPLITUDE_FLOAT_ARRAY_LARGE },
			onRecorderAction = {},
			modifier = Modifier
				.padding(horizontal = dimensionResource(R.dimen.sc_padding_secondary))
				.fillMaxSize()
		)
	}
}
