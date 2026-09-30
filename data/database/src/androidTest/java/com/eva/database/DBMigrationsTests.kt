@file:OptIn(ExperimentalTime::class)

package com.eva.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.datetime.TimeZone
import kotlinx.datetime.offsetAt
import org.junit.Rule
import org.junit.runner.RunWith
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

private const val TEST_DB_NAME = "migration-test-db"

@RunWith(AndroidJUnit4::class)
class DBMigrationsTests {

	@Test fun migrate_6_to_7_preserves_bookmarks_and_recordings() {
		val db = testHelper.createDatabase("keyframe-migration", 6)
		db.execSQL("INSERT INTO recording_meta_data (RECORDING_ID, IS_FAVOURITE) VALUES (99, 1)")
		db.execSQL("INSERT INTO recording_bookmark_table (BOOKMARK_ID, BOOKMARK_TEXT, RECORDING_ID, BOOKMARK_TIMESTAMP) VALUES (99, 'legacy', 99, 871420)")
		db.close()
		val migrated = testHelper.runMigrationsAndValidate("keyframe-migration", 7, true, DBMigrations.MIGRATE_6_7)
		migrated.query("SELECT BOOKMARK_TIMESTAMP FROM recording_bookmark_table WHERE BOOKMARK_ID=99").use {
			assertTrue(it.moveToFirst()); assertEquals(871420L, it.getLong(0))
		}
		migrated.close()
	}

	@get:Rule
	val testHelper = MigrationTestHelper(
		instrumentation = InstrumentationRegistry.getInstrumentation(),
		databaseClass = RecorderDataBase::class.java,
	)

	private val offsetMs: Long
		get() {
			val timeZone = TimeZone.currentSystemDefault()
			val instant = Clock.System.now()

			val utcOffset = timeZone.offsetAt(instant)
			// Convert the UtcOffset to milliseconds
			return utcOffset.totalSeconds * 1000L
		}

	@Test
	fun migrate_5_to_6_correcting_timestamp_in_bookmarks_and_category() {

		var db = testHelper.createDatabase(TEST_DB_NAME, 5).apply {
			execSQL("INSERT INTO recording_meta_data (RECORDING_ID,IS_FAVOURITE) VALUES (1, 0)")
			execSQL("INSERT INTO recording_bookmark_table (BOOKMARK_ID,BOOKMARK_TEXT,RECORDING_ID, BOOKMARK_TIMESTAMP) VALUES (1,\"RANDOM TEXT\",1, 1000)")
			execSQL("INSERT INTO recordings_category VALUES (1, \"SOME NAME\", 1000, null, null)")
		}
		db.close()

		db = testHelper.runMigrationsAndValidate(
			TEST_DB_NAME,
			6,
			true,
			DBMigrations.MIGRATE_5_6
		)

		db.query("SELECT BOOKMARK_TIMESTAMP FROM recording_bookmark_table").use { cursor ->
			assertTrue(cursor.moveToFirst())
			val colIdx = cursor.getColumnIndex("BOOKMARK_TIMESTAMP")
			val updated = cursor.getLong(colIdx)
			assertEquals(1000 + offsetMs, updated)
		}

		db.query("SELECT CREATED_AT FROM recordings_category").use { cursor ->
			assertTrue(cursor.moveToFirst())
			val colIdx = cursor.getColumnIndex("CREATED_AT")
			val updated = cursor.getLong(colIdx)
			assertEquals(1000 + offsetMs, updated)
		}

		db.close()
	}

	@Test
	fun migrate_5_to_6_correcting_trash_file_entity()  {
		var db = testHelper.createDatabase(TEST_DB_NAME, 5).apply {
			execSQL("INSERT INTO trash_files_data_table  VALUES (1, \"TRASH_ENTRY\", \"DISPLAY NAME\", \"audio/*\", 2000, 5000, \"some_location\")")
		}
		db.close()

		db = testHelper.runMigrationsAndValidate(
			TEST_DB_NAME,
			6,
			true,
			DBMigrations.MIGRATE_5_6
		)

		db.query("SELECT DATE_ADDED, DATE_EXPIRES FROM trash_files_data_table").use { cursor ->
			assertTrue(cursor.moveToFirst())
			val colIdx1 = cursor.getColumnIndex("DATE_ADDED")
			val colIdx2 = cursor.getColumnIndex("DATE_EXPIRES")
			val newDateAdded = cursor.getLong(colIdx1)
			val newDateExpires = cursor.getLong(colIdx2)


			assertEquals(2000 + offsetMs, newDateAdded)
			assertEquals(5000 + offsetMs, newDateExpires)
		}
		db.close()
	}
}
