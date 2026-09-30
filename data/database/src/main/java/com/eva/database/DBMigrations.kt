@file:OptIn(ExperimentalTime::class)

package com.eva.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.datetime.TimeZone
import kotlinx.datetime.offsetAt
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

object DBMigrations {

	val MIGRATE_7_8 = object : Migration(7, 8) {
		override fun migrate(db: SupportSQLiteDatabase) {
			db.execSQL("ALTER TABLE recording_sessions ADD COLUMN fileName TEXT")
		}
	}

	val MIGRATE_6_7 = object : Migration(6, 7) {
		override fun migrate(db: SupportSQLiteDatabase) {
			db.execSQL("CREATE TABLE IF NOT EXISTS recording_sessions (sessionId TEXT NOT NULL PRIMARY KEY, startedAt INTEGER NOT NULL, status TEXT NOT NULL, audioPath TEXT, recordingId INTEGER, positionMs INTEGER NOT NULL, mimeType TEXT NOT NULL, exportUri TEXT)")
			db.execSQL("CREATE TABLE IF NOT EXISTS timeline_items (id TEXT NOT NULL PRIMARY KEY, sessionId TEXT NOT NULL, recordingId INTEGER, positionMs INTEGER NOT NULL, type TEXT NOT NULL, mediaPath TEXT, text TEXT NOT NULL, state TEXT NOT NULL, createdAt INTEGER NOT NULL, FOREIGN KEY(sessionId) REFERENCES recording_sessions(sessionId) ON UPDATE NO ACTION ON DELETE CASCADE)")
			db.execSQL("CREATE INDEX IF NOT EXISTS index_timeline_items_sessionId ON timeline_items(sessionId)")
			db.execSQL("CREATE INDEX IF NOT EXISTS index_timeline_items_recordingId ON timeline_items(recordingId)")
		}
	}

	// Updated the timezone issue in localtime
	val MIGRATE_5_6 = object : Migration(5, 6) {

		private val offset: Long
			get() {
				val timeZone = TimeZone.currentSystemDefault()
				val instant = Clock.System.now()

				val utcOffset = timeZone.offsetAt(instant)
				// Convert the UtcOffset to milliseconds
				return utcOffset.totalSeconds * 1000L
			}

		override fun migrate(db: SupportSQLiteDatabase) {

			db.execSQL("UPDATE recording_bookmark_table set BOOKMARK_TIMESTAMP = BOOKMARK_TIMESTAMP + $offset")
			db.execSQL("UPDATE recordings_category set CREATED_AT = CREATED_AT + $offset")
			db.execSQL("UPDATE trash_files_data_table set DATE_ADDED = DATE_ADDED + $offset , DATE_EXPIRES = DATE_EXPIRES + $offset")
		}
	}
}
