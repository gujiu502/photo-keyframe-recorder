package com.eva.recorder.data.reader

import com.eva.recorder.domain.models.RecordedPoint
import kotlin.math.abs

internal fun Sequence<RecordedPoint>.normalize(max: Int, min: Int): Sequence<RecordedPoint> {
	val range = (max - min).let { diff -> if (diff <= 0) 1 else diff }
	return map { point ->
		point.copy(
			rmsValue = (abs(point.rmsValue - min) / range)
				.coerceIn(0f..1f)
		)
	}
}

internal fun Sequence<RecordedPoint>.smoothen(factor: Float = 0.3f): Sequence<RecordedPoint> {
	var prev = 0f
	return map { point ->
		prev = lerp(prev, point.rmsValue, factor)
		point.copy(rmsValue = prev)
	}
}

private fun lerp(v0: Float, v1: Float, t: Float): Float {
	return (1 - t) * v1 + t * v0
}