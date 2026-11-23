package com.eva.transcription

import io.grpc.ServerServiceDefinition
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import io.grpc.stub.ServerCalls
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.runBlocking
import nvidia.riva.asr.RivaAsr.SpeechRecognitionAlternative
import nvidia.riva.asr.RivaAsr.StreamingRecognitionResult
import nvidia.riva.asr.RivaAsr.StreamingRecognizeRequest
import nvidia.riva.asr.RivaAsr.StreamingRecognizeResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NvidiaStreamingRecognizerTest {
    @Test fun sendsPcmAndDeliversInterimBeforeFinal() = runBlocking {
        val requests = mutableListOf<StreamingRecognizeRequest>()
        val method = NvidiaStreamingRecognizer.METHOD
        val service = ServerServiceDefinition.builder(method.serviceName)
            .addMethod(method, ServerCalls.asyncBidiStreamingCall { downstream ->
                object : StreamObserver<StreamingRecognizeRequest> {
                    override fun onNext(request: StreamingRecognizeRequest) {
                        requests += request
                        if (requests.size == 2) downstream.onNext(response("台灣", false, .1f))
                    }
                    override fun onError(error: Throwable) {}
                    override fun onCompleted() {
                        downstream.onNext(response("台灣華語", true, .2f))
                        downstream.onCompleted()
                    }
                }
            }).build()
        val name = InProcessServerBuilder.generateName()
        val server = InProcessServerBuilder.forName(name).directExecutor().addService(service).build().start()
        val results = mutableListOf<Triple<String, Boolean, Long>>()
        try {
            NvidiaStreamingRecognizer("fixture-only", InProcessChannelBuilder.forName(name).directExecutor().build())
                .use { recognizer ->
                    recognizer.recognize(FloatArray(3200) { .5f }, 3200) { text, final, elapsed ->
                        results += Triple(text, final, elapsed)
                    }
                }
        } finally {
            server.shutdownNow()
        }
        val config = requests.first().streamingConfig
        assertEquals("zh-TW", config.config.languageCode)
        assertEquals(16000, config.config.sampleRateHertz)
        assertTrue(config.interimResults)
        assertEquals(listOf(3200, 3200), requests.drop(1).map { it.audioContent.size() })
        assertEquals(64, requests[1].audioContent.byteAt(1).toInt())
        assertEquals(listOf(Triple("台灣", false, 100L), Triple("台灣華語", true, 200L)), results)
    }

    private fun response(text: String, final: Boolean, seconds: Float) =
        StreamingRecognizeResponse.newBuilder().addResults(
            StreamingRecognitionResult.newBuilder().setIsFinal(final).setAudioProcessed(seconds)
                .addAlternatives(SpeechRecognitionAlternative.newBuilder().setTranscript(text))
        ).build()
}
