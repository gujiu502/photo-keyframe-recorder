#include <jni.h>
#include <whisper.h>
#include <atomic>
#include <string>

// One inference job per process; cancellation never dereferences a freed context.
static std::atomic<bool> stopped{false};
extern "C" JNIEXPORT void JNICALL Java_com_eva_transcription_WhisperNative_resetStop(JNIEnv *, jobject) { stopped.store(false); }
extern "C" JNIEXPORT void JNICALL Java_com_eva_transcription_WhisperNative_stop(JNIEnv *, jobject) { stopped.store(true); }
extern "C" JNIEXPORT jlong JNICALL Java_com_eva_transcription_WhisperNative_open(JNIEnv *env, jobject, jstring path) {
    const char *chars = env->GetStringUTFChars(path, nullptr);
    if (!chars) return 0;
    auto params = whisper_context_default_params();
    params.use_gpu = false;
    auto ctx = whisper_init_from_file_with_params(chars, params);
    env->ReleaseStringUTFChars(path, chars);
    return reinterpret_cast<jlong>(ctx);
}
extern "C" JNIEXPORT void JNICALL Java_com_eva_transcription_WhisperNative_close(JNIEnv *, jobject, jlong handle) {
    whisper_free(reinterpret_cast<whisper_context *>(handle));
}
static jstring utf8(JNIEnv *env, const std::string &text) {
    auto bytes = env->NewByteArray(static_cast<jsize>(text.size()));
    if (!bytes) return nullptr;
    env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(text.size()), reinterpret_cast<const jbyte *>(text.data()));
    auto cls = env->FindClass("java/lang/String");
    auto ctor = env->GetMethodID(cls, "<init>", "([BLjava/lang/String;)V");
    auto encoding = env->NewStringUTF("UTF-8");
    auto value = static_cast<jstring>(env->NewObject(cls, ctor, bytes, encoding));
    env->DeleteLocalRef(bytes); env->DeleteLocalRef(encoding); env->DeleteLocalRef(cls);
    return value;
}
extern "C" JNIEXPORT jobjectArray JNICALL Java_com_eva_transcription_WhisperNative_transcribe(
    JNIEnv *env, jobject, jlong handle, jfloatArray audio, jint length, jstring language) {
    auto ctx = reinterpret_cast<whisper_context *>(handle);
    if (!ctx || length < 1 || length > env->GetArrayLength(audio)) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), "Invalid Whisper audio buffer");
        return nullptr;
    }
    const char *lang = env->GetStringUTFChars(language, nullptr);
    if (!lang) return nullptr;
    auto params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = 2;
    params.language = lang;
    params.translate = false;
    params.no_context = true;
    params.print_realtime = params.print_progress = params.print_timestamps = params.print_special = false;
    params.abort_callback = [](void *) { return stopped.load(); };
    params.encoder_begin_callback = [](whisper_context *, whisper_state *, void *) { return !stopped.load(); };
    auto samples = env->GetFloatArrayElements(audio, nullptr);
    if (!samples) { env->ReleaseStringUTFChars(language, lang); return nullptr; }
    int status = stopped.load() ? 1 : whisper_full(ctx, params, samples, length);
    env->ReleaseFloatArrayElements(audio, samples, JNI_ABORT);
    env->ReleaseStringUTFChars(language, lang);
    if (status != 0 && !stopped.load()) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "Whisper inference failed");
        return nullptr;
    }
    int count = stopped.load() ? 0 : whisper_full_n_segments(ctx);
    auto result = env->NewObjectArray(count, env->FindClass("java/lang/String"), nullptr);
    if (!result) return nullptr;
    for (int i = 0; i < count; ++i) {
        auto text = std::to_string(whisper_full_get_segment_t0(ctx, i) * 10) + "|" +
            std::to_string(whisper_full_get_segment_t1(ctx, i) * 10) + "|" + whisper_full_get_segment_text(ctx, i);
        auto value = utf8(env, text);
        if (!value || env->ExceptionCheck()) return nullptr;
        env->SetObjectArrayElement(result, i, value);
        env->DeleteLocalRef(value);
    }
    return result;
}
