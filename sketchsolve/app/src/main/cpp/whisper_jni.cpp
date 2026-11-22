// JNI bridge between the app and whisper.cpp.
#include <jni.h>
#include <string>
#include <thread>
#include <android/log.h>
#include "whisper.h"

#define TAG "SketchSolveWhisper"

extern "C" JNIEXPORT jlong JNICALL
Java_app_sketchsolve_llm_WhisperSpeech_nativeInit(JNIEnv *env, jclass, jstring path) {
    const char *p = env->GetStringUTFChars(path, nullptr);
    whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(p, cp);
    env->ReleaseStringUTFChars(path, p);
    if (!ctx) __android_log_print(ANDROID_LOG_ERROR, TAG, "could not load the speech model");
    return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT jstring JNICALL
Java_app_sketchsolve_llm_WhisperSpeech_nativeTranscribe(JNIEnv *env, jclass, jlong handle, jfloatArray audio, jstring prompt, jint threads) {
    auto *ctx = reinterpret_cast<whisper_context *>(handle);
    if (!ctx) return env->NewStringUTF("");
    jsize n = env->GetArrayLength(audio);
    jfloat *samples = env->GetFloatArrayElements(audio, nullptr);
    const char *pr = env->GetStringUTFChars(prompt, nullptr);

    whisper_full_params wp = whisper_full_default_params(WHISPER_SAMPLING_BEAM_SEARCH);
    wp.beam_search.beam_size = 3;
    wp.n_threads = threads;
    wp.language = "en";
    wp.translate = false;
    wp.no_context = true;
    wp.single_segment = false;
    wp.print_progress = false;
    wp.print_realtime = false;
    wp.print_special = false;
    wp.print_timestamps = false;
    wp.suppress_blank = true;
    wp.initial_prompt = pr;   // maths vocabulary hint

    std::string text;
    if (whisper_full(ctx, wp, samples, n) == 0) {
        int segs = whisper_full_n_segments(ctx);
        for (int i = 0; i < segs; i++) text += whisper_full_get_segment_text(ctx, i);
    } else {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "transcription failed");
    }
    env->ReleaseStringUTFChars(prompt, pr);
    env->ReleaseFloatArrayElements(audio, samples, JNI_ABORT);
    return env->NewStringUTF(text.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_app_sketchsolve_llm_WhisperSpeech_nativeFree(JNIEnv *, jclass, jlong handle) {
    if (handle) whisper_free(reinterpret_cast<whisper_context *>(handle));
}
