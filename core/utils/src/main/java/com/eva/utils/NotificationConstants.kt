package com.eva.utils

object NotificationConstants {

	const val RECORDER_NOTIFICATION_ID = 1
	const val RECORDER_NOTIFICATION_SECONDARY_ID = 2

	const val DELETE_WORKER_NOTIFICATION_ID = 3

	const val PLAYER_NOTIFICATION_ID = 4

	const val SAVE_EDITED_MEDIA_FOREGROUND_ID = 5

	// Recorder channel
	const val RECORDER_CHANNEL_ID = "recorder_channel"
	const val RECORDER_CHANNEL_NAME = "錄音狀態"
	const val RECORDER_CHANNEL_DESC =
		"顯示目前錄音的狀態與控制按鈕"

	// Player channel
	const val PLAYER_CHANNEL_ID = "player_channel"
	const val PLAYER_CHANNEL_NAME = "音訊播放"
	const val PLAYER_CHANNEL_DESC = "顯示音訊播放狀態"

	// show recording channel
	const val RECORDING_CHANNEL_ID = "recordings_channel"
	const val RECORDING_CHANNEL_NAME = "錄音結果"
	const val RECORDING_CHANNEL_DESC =
		"顯示錄音完成與取消的通知"
}
