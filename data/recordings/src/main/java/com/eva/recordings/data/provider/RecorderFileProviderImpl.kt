package com.eva.recordings.data.provider

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.core.os.bundleOf
import androidx.room.withTransaction
import com.eva.database.dao.RecordingsMetadataDao
import com.eva.database.entity.RecordingsMetaDataEntity
import com.eva.datastore.domain.enums.AudioFileNamingFormat
import com.eva.datastore.domain.repository.RecorderFileSettingsRepo
import com.eva.recordings.data.wrapper.RecordingsConstants
import com.eva.recordings.domain.exceptions.MediastoreOperationException
import com.eva.recordings.domain.provider.RecorderFileProvider
import com.eva.utils.LocalTimeFormats
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.datetime.format
import kotlinx.datetime.toKotlinLocalDateTime
import java.io.File
import java.io.IOException
import java.time.LocalDateTime

private const val LOGGER_TAG = "RECORDER_FILE_PROVIDE"

internal class RecorderFileProviderImpl(
	private val context: Context,
	private val recordingDao: RecordingsMetadataDao,
	private val settings: RecorderFileSettingsRepo,
	private val sessions: com.eva.database.SessionStore,
) : RecorderFileProvider {

	private val _tempRecordingDir by lazy {
		File(context.filesDir, "temp_recordings")
			.apply(File::mkdirs)
	}

	override suspend fun createFileForRecording(extension: String?): File {
		return withContext(Dispatchers.IO) {
			val ext = extension.takeIf { it?.startsWith(".") ?: false } ?: ".tmp"
			val file = File.createTempFile("some_recordings", ext, _tempRecordingDir)
			val active = sessions.dao.unfinishedList().lastOrNull { it.status == "ACTIVE" && it.audioPath == null }
			active?.let { sessions.dao.audio(it.sessionId, file.absolutePath, when (extension) {
				".opus", ".ogg" -> "audio/ogg"; ".amr" -> "audio/amr"; ".3gp" -> "audio/3gpp"; else -> "audio/mp4"
			}) }
			Log.d(LOGGER_TAG, "FILE CREATED FOR RECORDING NAME: ${file.name}")
			file
		}
	}

	override suspend fun transferFileDataToStorage(file: File, mimeType: String): Result<Long> {
		return withContext(Dispatchers.IO) {
			try {
				// file don't exist
				if (!file.exists()) {
					Log.d(LOGGER_TAG, "FILE DON'T EXISTS")
					return@withContext Result.failure(Exception("File missing exception"))
				}
				// content uri cannot be created
				val session = sessions.dao.unfinishedList().firstOrNull { it.audioPath == file.absolutePath }
				val contentUri = createContentUriAndCopy(file, mimeType, session?.sessionId, session?.exportUri)
					?: return@withContext Result.failure(MediastoreOperationException())

				val uriId = ContentUris.parseId(contentUri)
				sessions.database.withTransaction {
					recordingDao.updateOrInsertRecordingMetadata(RecordingsMetaDataEntity(recordingId = uriId))
					session?.let { sessions.dao.complete(it.sessionId, uriId) }
				}
				deleteCreatedFile(file)
				return@withContext Result.success(uriId)
			} catch (e: CancellationException) {
				throw e
			} catch (e: IOException) {
				e.printStackTrace()
				Result.failure(e)
			}
		}
	}

	override suspend fun deleteCreatedFile(file: File): Boolean {
		return withContext(Dispatchers.IO) {
			try {
				// ensure the file exits  and starts with the tempPrefix
				if (!file.exists()) return@withContext false
				if (file.parentFile != _tempRecordingDir) return@withContext false
				val result = file.delete()
				Log.d(LOGGER_TAG, "TEMP FILE DELETED : $result")
				result
			} catch (_: SecurityException) {
				false
			}
		}
	}

	private suspend fun createContentUriAndCopy(file: File, mimeType: String, sessionId: String?, existingUri: String?): Uri? {
		return withContext(Dispatchers.IO) {
			val contentUri = existingUri?.let(Uri::parse) ?: createUriForRecording(mimeType, sessionId) ?: return@withContext null
			sessionId?.let { sessions.dao.exportUri(it, contentUri.toString()) }
			Log.d(LOGGER_TAG, "CONTENT URI CREATED")
			try {
				requireNotNull(context.contentResolver.openOutputStream(contentUri, "wt")).use { outStream ->
					file.inputStream().use { inStream -> inStream.copyTo(outStream) }
				}
				Log.d(LOGGER_TAG, "CONTENT COPIED")
				val newMetaData = ContentValues().apply {
					put(MediaStore.Audio.AudioColumns.IS_PENDING, 0)
				}
				val result = context.contentResolver.update(contentUri, newMetaData, null, null)
				Log.d(LOGGER_TAG, "UPDATED URI AFTER COPY :${result == 1}")
				contentUri
			} catch (_: Exception) {
				// Keep the reserved MediaStore URI and durable audio for an idempotent retry.
				null
			}
		}
	}

	private suspend fun createUriForRecording(mimeType: String, sessionId: String?): Uri? {
		val fileSettings = settings.fileSettings()

		val namingStrategy = when (fileSettings.format) {
			AudioFileNamingFormat.DATE_TIME -> {
				LocalDateTime.now().toKotlinLocalDateTime()
					.format(LocalTimeFormats.RECORDING_RECORD_TIME_FORMAT)
					.trim()
			}

			AudioFileNamingFormat.COUNT -> {
				val currentCount = getItemNumber()
				"${currentCount + 1}".padStart(3, '0').trim()
			}
		}

		// file name
		val fileName = if (sessionId != null) "Lecture-$sessionId.${android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType) ?: "m4a"}" else buildString {
			append(fileSettings.name)
			append("-")
			append(namingStrategy)
		}
		// metadata
		val metaData = ContentValues().apply {
			put(
				MediaStore.Audio.AudioColumns.RELATIVE_PATH,
				RecordingsConstants.RECORDINGS_MUSIC_PATH
			)
			put(MediaStore.Audio.AudioColumns.DISPLAY_NAME, fileName)
			put(MediaStore.Audio.AudioColumns.MIME_TYPE, mimeType)
			put(MediaStore.Audio.AudioColumns.IS_PENDING, 1)
		}

		// insert the metadata on IO thread
		return withContext(Dispatchers.IO) {
			if (sessionId != null) {
				// Find a URI reserved before a crash, even if the DB write had not finished.
				val args = bundleOf(ContentResolver.QUERY_ARG_SQL_SELECTION to "${MediaStore.Audio.Media.DISPLAY_NAME} = ? AND ${MediaStore.Audio.Media.OWNER_PACKAGE_NAME} = ?",
					ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS to arrayOf(fileName, context.packageName))
				if (Build.VERSION.SDK_INT >= 30) args.putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
				context.contentResolver.query(RecordingsConstants.AUDIO_VOLUME_URI, arrayOf(MediaStore.Audio.Media._ID), args, null)?.use { cursor ->
					if (cursor.moveToFirst()) return@withContext ContentUris.withAppendedId(RecordingsConstants.AUDIO_VOLUME_URI, cursor.getLong(0))
				}
			}
			Log.d(LOGGER_TAG, "CREATING FILE WITH METADATA :$metaData")
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
				context.contentResolver.insert(RecordingsConstants.AUDIO_VOLUME_URI, metaData, null)
			} else {
				context.contentResolver.insert(RecordingsConstants.AUDIO_VOLUME_URI, metaData)
			}
		}
	}


	private suspend fun getItemNumber(): Int {
		val projection = arrayOf(MediaStore.Audio.AudioColumns._ID)
		val selection = "${MediaStore.Audio.AudioColumns.OWNER_PACKAGE_NAME} = ?"
		val selectionArgs = arrayOf(context.packageName)

		val bundle = bundleOf(
			ContentResolver.QUERY_ARG_SQL_SELECTION to selection,
			ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS to selectionArgs
		)

		return withContext(Dispatchers.IO) {
			context.contentResolver.query(
				RecordingsConstants.AUDIO_VOLUME_URI,
				projection,
				bundle,
				null
			)
				?.use { cursor -> cursor.count }
				?: 0
		}
	}
}
