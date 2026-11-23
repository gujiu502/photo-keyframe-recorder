package com.eva.feature_recorder.screen

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.dimensionResource
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.eva.feature_recorder.composable.MicPermissionWrapper
import com.eva.feature_recorder.composable.RecorderContent
import com.eva.feature_recorder.composable.RecorderTopBar
import com.eva.feature_recorder.keyframe.KeyframeTools
import com.eva.feature_recorder.keyframe.KeyframeViewModel
import com.eva.feature_recorder.util.showTopBarActions
import com.eva.recorder.domain.models.RecorderAction
import com.eva.recorder.domain.models.RecorderState
import com.eva.recorder.utils.DeferredDurationList
import com.eva.recorder.utils.DeferredRecordedPointList
import com.eva.ui.R
import com.eva.ui.navigation.RecordingNavigation
import com.eva.ui.utils.LocalSnackBarProvider
import kotlinx.datetime.LocalTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VoiceRecorderScreen(
	isRecorderReady: Boolean,
	recorderState: RecorderState,
	recorderTimer:()-> LocalTime,
	deferredRecordingPoints: DeferredRecordedPointList,
	bookMarksSetDeferred: DeferredDurationList,
	onRecorderAction: (RecorderAction) -> Unit,
	onScreenEvent: (RecorderScreenEvent) -> Unit,
	onShowRecordings: () -> Unit,
	onNavigateToSettings: () -> Unit,
	onNavigateToBin: () -> Unit,
	modifier: Modifier = Modifier,
	navigation: @Composable () -> Unit = {},
) {

	val snackBarHostState = LocalSnackBarProvider.current
	val lifecycleOwner = LocalLifecycleOwner.current
	val keyframes: KeyframeViewModel = hiltViewModel()
	val activeId by keyframes.sessions.activeId.collectAsStateWithLifecycle()
	val items by keyframes.items.collectAsStateWithLifecycle()
	val exporting by keyframes.exporting.collectAsStateWithLifecycle()
	var pendingVideo by rememberSaveable { mutableStateOf<String?>(null) }
	val recorderAction: (RecorderAction) -> Unit = { action ->
		if (action is RecorderAction.SaveRecorderAction) pendingVideo = activeId
		onRecorderAction(action)
	}

	val currentOnScreenEvent by rememberUpdatedState(onScreenEvent)

	LifecycleStartEffect(key1 = lifecycleOwner) {
		// bind the service on start
		currentOnScreenEvent(RecorderScreenEvent.BindRecorderService)
		onStopOrDispose {
			// unbind the service on stop
			currentOnScreenEvent(RecorderScreenEvent.UnBindRecorderService)
		}
	}

	Scaffold(
		topBar = {
			RecorderTopBar(
				showActions = recorderState.showTopBarActions,
				onNavigateToSettings = onNavigateToSettings,
				onNavigateToBin = onNavigateToBin,
				onAddBookMark = { recorderAction(RecorderAction.AddBookMarkAction) },
				navigation = navigation
			)
		},
		bottomBar = {
			if (recorderState.showTopBarActions && !exporting) {
				RecordingNavigation(false, {}, onShowRecordings)
			}
		},
		snackbarHost = { SnackbarHost(snackBarHostState) },
		modifier = modifier,
	) { scPadding ->
		MicPermissionWrapper(
			modifier = Modifier.padding(
				start = dimensionResource(id = R.dimen.sc_padding),
				end = dimensionResource(R.dimen.sc_padding),
				top = dimensionResource(id = R.dimen.sc_padding_secondary) + scPadding.calculateTopPadding(),
				bottom = dimensionResource(id = R.dimen.sc_padding_secondary) + scPadding.calculateBottomPadding()
			)
		) {
			RecorderContent(
				timer = recorderTimer,
				recorderState = recorderState,
				recordingPointsCallback = deferredRecordingPoints,
				bookMarksDeferred = bookMarksSetDeferred,
				onRecorderAction = recorderAction,
				isStartRecordingEnabled = isRecorderReady && !exporting,
				photoCount = items.count { it.type == "PHOTO" },
				modifier = Modifier.fillMaxSize(),
			) {
				KeyframeTools(recorderState, { recorderTimer().toString().substringBefore('.') },
					recorderAction, pendingVideo, { pendingVideo = null }, keyframes)
			}
		}
	}
}
