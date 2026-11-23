package com.eva.transcription

import com.google.protobuf.ByteString
import io.grpc.CallOptions
import io.grpc.ClientCall
import io.grpc.ManagedChannel
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.okhttp.OkHttpChannelBuilder
import io.grpc.protobuf.lite.ProtoLiteUtils
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import nvidia.riva.RivaAudio.AudioEncoding
import nvidia.riva.asr.RivaAsr.RecognitionConfig
import nvidia.riva.asr.RivaAsr.StreamingRecognitionConfig
import nvidia.riva.asr.RivaAsr.StreamingRecognizeRequest
import nvidia.riva.asr.RivaAsr.StreamingRecognizeResponse

internal class NvidiaStreamingRecognizer(
    private val apiKey: String,
    private val channel: ManagedChannel = OkHttpChannelBuilder.forAddress("grpc.nvcf.nvidia.com", 443)
        .useTransportSecurity().build(),
) : AutoCloseable {
    suspend fun recognize(
        samples: FloatArray,
        count: Int,
        onText: suspend (text: String, finalized: Boolean, processedMs: Long) -> Unit,
    ) = coroutineScope {
        require(count in 1..samples.size)
        val call = channel.newCall(METHOD, CallOptions.DEFAULT.withDeadlineAfter(60, TimeUnit.SECONDS))
        val ready = Channel<Unit>(Channel.CONFLATED)
        val responses = Channel<StreamingRecognizeResponse>(1)
        val metadata = Metadata().apply {
            put(Metadata.Key.of("function-id", Metadata.ASCII_STRING_MARSHALLER), "8473f56d-51ef-473c-bb26-efd4f5def2bf")
            put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "Bearer $apiKey")
        }
        call.start(object : ClientCall.Listener<StreamingRecognizeResponse>() {
            override fun onReady() { ready.trySend(Unit) }
            override fun onMessage(message: StreamingRecognizeResponse) {
                if (responses.trySend(message).isFailure) call.cancel("Response consumer closed", null)
            }
            override fun onClose(status: Status, trailers: Metadata) {
                responses.close(if (status.isOk) null else status.asException())
            }
        }, metadata)
        call.request(1)
        val sender = launch {
            suspend fun send(message: StreamingRecognizeRequest) {
                while (!call.isReady) ready.receive()
                ensureActive()
                call.sendMessage(message)
            }
            val config = RecognitionConfig.newBuilder()
                .setLanguageCode("zh-TW")
                .setEncoding(AudioEncoding.LINEAR_PCM)
                .setSampleRateHertz(16000)
                .setAudioChannelCount(1)
                .setMaxAlternatives(1)
                .setEnableAutomaticPunctuation(true)
                .build()
            send(StreamingRecognizeRequest.newBuilder().setStreamingConfig(
                StreamingRecognitionConfig.newBuilder().setConfig(config).setInterimResults(true)
            ).build())
            val bytes = ByteArray(3200)
            var offset = 0
            while (offset < count) {
                val length = minOf(bytes.size / 2, count - offset)
                for (index in 0 until length) {
                    val pcm = (samples[offset + index] * 32768f).roundToInt().coerceIn(-32768, 32767)
                    bytes[2 * index] = pcm.toByte()
                    bytes[2 * index + 1] = (pcm shr 8).toByte()
                }
                send(StreamingRecognizeRequest.newBuilder().setAudioContent(ByteString.copyFrom(bytes, 0, length * 2)).build())
                offset += length
            }
            call.halfClose()
        }
        try {
            for (response in responses) {
                for (result in response.resultsList) {
                    val text = result.alternativesList.firstOrNull()?.transcript?.trim().orEmpty()
                    val elapsed = (result.audioProcessed * 1000).toLong().coerceIn(0L, count.toLong() / 16)
                    onText(text, result.isFinal, elapsed)
                }
                call.request(1)
            }
        } catch (e: StatusException) {
            // Remote errors can include request diagnostics; never show or log raw trailers.
            error(when (e.status.code) {
                Status.Code.UNAUTHENTICATED, Status.Code.PERMISSION_DENIED -> "NVIDIA 金鑰無效或無辨識權限，請重新設定"
                Status.Code.RESOURCE_EXHAUSTED -> "NVIDIA 雲端額度或速率已達上限，請稍後再試"
                Status.Code.DEADLINE_EXCEEDED, Status.Code.UNAVAILABLE -> "NVIDIA 雲端連線逾時或無法連線，請確認網路後重試"
                else -> "NVIDIA 雲端辨識失敗，已儲存的文字仍保留"
            })
        } finally {
            sender.cancel()
            call.cancel("Recognition stream closed", null)
            responses.cancel()
            ready.cancel()
        }
    }

    override fun close() { channel.shutdownNow() }

    internal companion object {
        val METHOD: MethodDescriptor<StreamingRecognizeRequest, StreamingRecognizeResponse> =
            MethodDescriptor.newBuilder<StreamingRecognizeRequest, StreamingRecognizeResponse>()
                .setType(MethodDescriptor.MethodType.BIDI_STREAMING)
                .setFullMethodName("nvidia.riva.asr.RivaSpeechRecognition/StreamingRecognize")
                .setRequestMarshaller(ProtoLiteUtils.marshaller(StreamingRecognizeRequest.getDefaultInstance()))
                .setResponseMarshaller(ProtoLiteUtils.marshaller(StreamingRecognizeResponse.getDefaultInstance()))
                .build()
    }
}
