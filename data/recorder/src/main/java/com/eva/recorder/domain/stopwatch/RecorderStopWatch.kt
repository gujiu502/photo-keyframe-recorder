package com.eva.recorder.domain.stopwatch

import com.eva.recorder.domain.models.RecorderState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.datetime.LocalTime
import android.os.SystemClock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.DurationUnit
import kotlin.time.ExperimentalTime

class RecorderStopWatch(
	private val delayTime: Duration = 80.milliseconds,
) {

	private val scope = CoroutineScope(Dispatchers.Default)
	private val clock = SessionClock(SystemClock::elapsedRealtime)
	fun currentPositionMs(): Long = clock.positionMs()

	private val _state = MutableStateFlow(RecorderState.IDLE)
	val recorderState = _state.asStateFlow()

	private val _elapsedTime = MutableStateFlow(0)

	@OptIn(ExperimentalCoroutinesApi::class)
	val elapsedTime = _elapsedTime
		.mapLatest { current -> LocalTime.fromMillisecondOfDay(current) }
		.stateIn(
			scope = scope,
			started = SharingStarted.WhileSubscribed(5_000L),
			initialValue = LocalTime(0, 0, 0)
		)

	init { updateElapsedTime() }

	@OptIn(ExperimentalCoroutinesApi::class)
	private fun updateElapsedTime() = _state
		.flatMapLatest { state -> runStopWatch(isRunning = state == RecorderState.RECORDING) }
		.onEach { _elapsedTime.value = (clock.positionMs() % 86_400_000).toInt() }
		.launchIn(scope)


	@OptIn(ExperimentalTime::class)
	private fun runStopWatch(isRunning: Boolean): Flow<Int> = flow {
		while (isRunning) {
			emit(0)
			delay(delayTime)
		}
	}.flowOn(Dispatchers.Default)


	fun startOrResume() { clock.resume(); _state.value = RecorderState.RECORDING }

	fun pause() { clock.pause(); _state.value = RecorderState.PAUSED }

	fun prepare() { clock.start(); clock.pause(); _state.value = RecorderState.PREPARING }

	fun stop() {
		clock.pause()
		// completes the timer and reset the elapsed time
		_state.update { RecorderState.COMPLETED }
		_elapsedTime.update { 0 }
	}

	fun cancel() {
		clock.pause()
		// cancel the current run
		_state.update { RecorderState.CANCELLED }
		_elapsedTime.update { 0 }
	}

	fun reset() {
		//cancels the scope
		scope.cancel()
		// update the state
		_state.update { RecorderState.IDLE }
	}

}
