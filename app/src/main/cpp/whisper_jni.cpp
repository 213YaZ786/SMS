// The bridge between the app and whisper.cpp: open a model, write out a
// voice message's sound (16 kHz mono floats), close. Nothing is logged.
#include <jni.h>
#include <string>
#include "whisper.h"
#include "ggml-backend.h"

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_yaz_sms_core_voice_Whisper_open(JNIEnv *env, jclass, jstring lib_dir, jstring model) {
    const char *dir = env->GetStringUTFChars(lib_dir, nullptr);
    // The CPU variants live with the app's own libraries; the best one for this phone is taken.
    static bool loaded = false;
    if (!loaded) {
        ggml_backend_load_all_from_path(dir);
        loaded = true;
    }
    env->ReleaseStringUTFChars(lib_dir, dir);
    const char *path = env->GetStringUTFChars(model, nullptr);
    whisper_context_params params = whisper_context_default_params();
    params.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(path, params);
    env->ReleaseStringUTFChars(model, path);
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT jbyteArray JNICALL
Java_com_yaz_sms_core_voice_Whisper_transcribe(JNIEnv *env, jclass, jlong handle, jfloatArray pcm, jint threads) {
    auto *ctx = reinterpret_cast<whisper_context *>(handle);
    if (ctx == nullptr) return nullptr;
    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    // Any language, found from the sound itself, never translated.
    params.language = "auto";
    params.translate = false;
    params.no_timestamps = true;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.suppress_blank = true;
    params.n_threads = threads;
    jsize n = env->GetArrayLength(pcm);
    jfloat *samples = env->GetFloatArrayElements(pcm, nullptr);
    int failed = whisper_full(ctx, params, samples, n);
    env->ReleaseFloatArrayElements(pcm, samples, JNI_ABORT);
    if (failed != 0) return nullptr;
    std::string text;
    const int segments = whisper_full_n_segments(ctx);
    for (int i = 0; i < segments; ++i) text += whisper_full_get_segment_text(ctx, i);
    // Plain UTF-8 bytes: Java's own string maker refuses some of it (emoji).
    jbyteArray out = env->NewByteArray(static_cast<jsize>(text.size()));
    env->SetByteArrayRegion(out, 0, static_cast<jsize>(text.size()), reinterpret_cast<const jbyte *>(text.data()));
    return out;
}

JNIEXPORT void JNICALL
Java_com_yaz_sms_core_voice_Whisper_close(JNIEnv *, jclass, jlong handle) {
    auto *ctx = reinterpret_cast<whisper_context *>(handle);
    if (ctx != nullptr) whisper_free(ctx);
}

}
