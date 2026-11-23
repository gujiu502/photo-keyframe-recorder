package com.eva.recordings.domain.models

import kotlinx.datetime.LocalDateTime
import kotlin.time.Duration

data class AudioFileModel(
	val id: Long,
	val displayName: String,
	val size: Long,
	val duration: Duration,
	val lastModified: LocalDateTime,
	val fileUri: String,
	val mimeType: String,
	val title: String = displayName,
	val path: String? = null,
	val isFavourite: Boolean = false,
	val metaData: MediaMetaDataInfo? = null,
)
