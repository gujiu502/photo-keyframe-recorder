package com.eva.feature_player.composable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.eva.bookmarks.domain.AudioBookmarkModel
import com.eva.feature_player.bookmarks.state.BookMarkEvents
import com.eva.feature_player.bookmarks.state.CreateBookmarkState
import com.eva.feature_player.state.PlayerEvents
import com.eva.player.domain.model.PlayerMetaData
import com.eva.player.domain.model.PlayerTrackData
import com.eva.player_shared.composables.PlayerDurationText
import com.eva.player_shared.util.PlayerGraphData
import com.eva.recordings.domain.models.AudioFileModel
import com.eva.ui.theme.DownloadableFonts
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AudioPlayerScreenContent(
	fileModel: AudioFileModel,
	waveforms: PlayerGraphData,
	bookMarkState: CreateBookmarkState,
	trackData: () -> PlayerTrackData,
	playerMetaData: PlayerMetaData,
	bookmarks: ImmutableList<AudioBookmarkModel>,
	onPlayerEvents: (PlayerEvents) -> Unit,
	modifier: Modifier = Modifier,
	onBookmarkEvent: (BookMarkEvents) -> Unit = {},
	isControllerReady: Boolean = false,
	isPlayerPlaying: Boolean = true,
) {
	val bookMarkTimeStamps by remember(bookmarks) {
		derivedStateOf {
			bookmarks.map(AudioBookmarkModel::timeStamp)
				.toImmutableList()
		}
	}

	BoxWithConstraints(modifier.fillMaxSize()) {
	val compact = maxHeight < 520.dp
	Column(
		modifier = Modifier.fillMaxSize(),
		verticalArrangement = Arrangement.spacedBy(8.dp)
	) {
		if (!compact) PlayerDurationText(
			track = trackData,
			fontFamily = DownloadableFonts.SPLINE_SANS_MONO_FONT_FAMILY,
			modifier = Modifier.align(Alignment.CenterHorizontally),
		)
		Column(
			modifier = Modifier
				.fillMaxWidth(),
			horizontalAlignment = Alignment.CenterHorizontally,
			verticalArrangement = Arrangement.spacedBy(4.dp),
		) {
			if (!compact) PlayerAmplitudeGraph2(
				trackData = trackData,
				bookMarksTimeStamps = bookMarkTimeStamps,
				graphData = waveforms,
				isSwipeToScrollEnabled = true,
				onSeek = { amount -> onPlayerEvents(PlayerEvents.OnSeekingPlayer(amount)) },
				onSeekEnd = { onPlayerEvents(PlayerEvents.OnSeekEndPlayer) },
				timelineFontFamily = DownloadableFonts.PLUS_CODE_LATIN_FONT_FAMILY,
				modifier = Modifier.fillMaxWidth().height(150.dp)
			)
			PlayerBookMarks(
				trackData = trackData,
				bookmarks = bookmarks,
				bookMarkState = bookMarkState,
				onBookmarkEvent = onBookmarkEvent,
				modifier = Modifier.fillMaxWidth()
			)
		}
		com.eva.feature_player.keyframe.PlayerKeyframes(
            id = fileModel.id, audioUri = fileModel.fileUri, title = fileModel.title,
            currentPosition = { trackData().current.inWholeMilliseconds },
            onSeek = { onPlayerEvents(PlayerEvents.PlayFromPosition(it)) },
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
		PlayerActionsAndSlider(
			metaData = playerMetaData,
			trackData = trackData,
			isPlayerPlaying = isPlayerPlaying,
			isControllerSet = isControllerReady,
			onPlayerAction = onPlayerEvents,
			modifier = Modifier
				.fillMaxWidth(),
		)
	}
	}
}
