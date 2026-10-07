package com.eva.transcription

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.nio.ByteOrder

/** Decode in bounded 30-second windows; a two-hour file is never held in memory. */
internal suspend fun decodeAudio(context: Context, uri: Uri, consume: suspend (FloatArray, Int, Long) -> Unit) {
    val extractor = MediaExtractor()
    var codec: MediaCodec? = null
    try {
        extractor.setDataSource(context, uri, null)
        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: error("找不到音訊軌道")
        extractor.selectTrack(track)
        val input = extractor.getTrackFormat(track)
        val decoder = MediaCodec.createDecoderByType(requireNotNull(input.getString(MediaFormat.KEY_MIME)))
        codec = decoder
        decoder.configure(input, null, null, 0)
        decoder.start()
        var rate = input.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = input.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var encoding = AudioFormat.ENCODING_PCM_16BIT
        val chunk = FloatArray(16000 * 30)
        var used = 0
        var offset = 0L
        var pending = false
        var resampler = Pcm16kResampler(rate) { sample -> chunk[used++] = sample }
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        var lastOutput = android.os.SystemClock.elapsedRealtime()
        suspend fun flush() {
            if (used > 0) { consume(chunk, used, offset); offset += used; used = 0 }
        }
        while (!outputDone) {
            currentCoroutineContext().ensureActive()
            if (!inputDone) {
                val index = decoder.dequeueInputBuffer(10000)
                if (index >= 0) {
                    val buffer = requireNotNull(decoder.getInputBuffer(index))
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) {
                        decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        decoder.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val index = decoder.dequeueOutputBuffer(info, 10000)
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val format = decoder.outputFormat
                val newRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                val newChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                if (rate != newRate || channels != newChannels) {
                    check(!pending) { "音訊中途改變格式，已保留已識別內容" }
                    rate = newRate; channels = newChannels
                    resampler = Pcm16kResampler(rate) { sample -> chunk[used++] = sample }
                }
                encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) format.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                check(encoding == AudioFormat.ENCODING_PCM_16BIT || encoding == AudioFormat.ENCODING_PCM_FLOAT) { "不支援此音訊的 PCM 格式" }
                check(channels in 1..8)
            } else if (index >= 0) {
                try {
                    if (info.size > 0) {
                        val buffer = requireNotNull(decoder.getOutputBuffer(index)).order(ByteOrder.LITTLE_ENDIAN)
                        buffer.position(info.offset); buffer.limit(info.offset + info.size)
                        val bytes = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
                        check(info.size % (bytes * channels) == 0) { "音訊解碼資料不完整" }
                        while (buffer.remaining() >= bytes * channels) {
                            currentCoroutineContext().ensureActive()
                            var sample = 0f
                            repeat(channels) { sample += if (bytes == 4) buffer.float else buffer.short / 32768f }
                            // At most two outputs per input at the minimum supported rate.
                            if (used > chunk.size - 2) flush()
                            resampler.push(sample / channels)
                            pending = true
                        }
                        lastOutput = android.os.SystemClock.elapsedRealtime()
                    }
                    outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                } finally { decoder.releaseOutputBuffer(index, false) }
            }
            check(android.os.SystemClock.elapsedRealtime() - lastOutput < 60000) { "音訊解碼逾時，原始檔案已保留" }
        }
        flush()
        check(offset > 0) { "音訊沒有可識別的內容" }
    } finally {
        codec?.let { runCatching { it.stop() }; it.release() }
        extractor.release()
    }
}
