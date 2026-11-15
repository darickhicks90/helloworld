/*
 * FoxBox low-latency output: an AAudio stream (MMAP / exclusive where the
 * device supports it) whose real-time callback asks Kotlin's
 * Audio.render() for each burst. Kotlin falls back to AudioTrack if this
 * library or the stream is unavailable.
 */
#include <jni.h>
#include <string.h>
#include <aaudio/AAudio.h>
#include <android/log.h>

#define TAG "FoxBoxAudio"
#define MAX_FRAMES 4096

static JavaVM *g_vm = NULL;
static jclass g_cls = NULL;
static jmethodID g_render = NULL;
static jfloatArray g_buf = NULL;
static AAudioStream *g_stream = NULL;
static volatile int g_disconnected = 0;

static aaudio_data_callback_result_t on_audio(AAudioStream *stream, void *user, void *data, int32_t frames) {
    (void) stream; (void) user;
    JNIEnv *env = NULL;
    if ((*g_vm)->GetEnv(g_vm, (void **) &env, JNI_VERSION_1_6) != JNI_OK) {
        if ((*g_vm)->AttachCurrentThreadAsDaemon(g_vm, &env, NULL) != JNI_OK) {
            memset(data, 0, (size_t) frames * 2 * sizeof(float));
            return AAUDIO_CALLBACK_RESULT_CONTINUE;
        }
    }
    if (frames > MAX_FRAMES || g_buf == NULL) {
        memset(data, 0, (size_t) frames * 2 * sizeof(float));
        return AAUDIO_CALLBACK_RESULT_CONTINUE;
    }
    (*env)->CallStaticVoidMethod(env, g_cls, g_render, g_buf, frames);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        memset(data, 0, (size_t) frames * 2 * sizeof(float));
    } else {
        (*env)->GetFloatArrayRegion(env, g_buf, 0, frames * 2, (jfloat *) data);
    }
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

static void on_error(AAudioStream *stream, void *user, aaudio_result_t error) {
    (void) stream; (void) user;
    __android_log_print(ANDROID_LOG_WARN, TAG, "stream error %d", error);
    g_disconnected = 1;
}

/* Opens and starts the stream. Returns the actual sample rate, or a negative error. */
JNIEXPORT jint JNICALL
Java_app_foxbox_audio_NativeAudio_nOpen(JNIEnv *env, jclass cls, jint sample_rate, jint device_id, jint buffer_frames) {
    if (g_stream != NULL) return AAudioStream_getSampleRate(g_stream);
    (*env)->GetJavaVM(env, &g_vm);
    if (g_cls == NULL) g_cls = (jclass) (*env)->NewGlobalRef(env, cls);
    g_render = (*env)->GetStaticMethodID(env, cls, "render", "([FI)V");
    if (g_render == NULL) return -100;
    if (g_buf == NULL) {
        jfloatArray local = (*env)->NewFloatArray(env, MAX_FRAMES * 2);
        g_buf = (jfloatArray) (*env)->NewGlobalRef(env, local);
        (*env)->DeleteLocalRef(env, local);
    }

    AAudioStreamBuilder *b = NULL;
    aaudio_result_t r = AAudio_createStreamBuilder(&b);
    if (r != AAUDIO_OK) return r;
    AAudioStreamBuilder_setDirection(b, AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setPerformanceMode(b, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setSharingMode(b, AAUDIO_SHARING_MODE_EXCLUSIVE);
    AAudioStreamBuilder_setFormat(b, AAUDIO_FORMAT_PCM_FLOAT);
    AAudioStreamBuilder_setChannelCount(b, 2);
    AAudioStreamBuilder_setSampleRate(b, sample_rate);
    if (device_id > 0) AAudioStreamBuilder_setDeviceId(b, device_id);
    AAudioStreamBuilder_setUsage(b, AAUDIO_USAGE_GAME);
    AAudioStreamBuilder_setContentType(b, AAUDIO_CONTENT_TYPE_MUSIC);
    AAudioStreamBuilder_setDataCallback(b, on_audio, NULL);
    AAudioStreamBuilder_setErrorCallback(b, on_error, NULL);
    r = AAudioStreamBuilder_openStream(b, &g_stream);
    AAudioStreamBuilder_delete(b);
    if (r != AAUDIO_OK) { g_stream = NULL; return r; }

    int32_t burst = AAudioStream_getFramesPerBurst(g_stream);
    int32_t want = buffer_frames > 0 ? buffer_frames : burst * 2;
    if (burst > 0 && want < burst) want = burst;
    AAudioStream_setBufferSizeInFrames(g_stream, want);
    g_disconnected = 0;
    r = AAudioStream_requestStart(g_stream);
    if (r != AAUDIO_OK) { AAudioStream_close(g_stream); g_stream = NULL; return r; }
    return AAudioStream_getSampleRate(g_stream);
}

JNIEXPORT void JNICALL
Java_app_foxbox_audio_NativeAudio_nClose(JNIEnv *env, jclass cls) {
    (void) env; (void) cls;
    if (g_stream == NULL) return;
    AAudioStream_requestStop(g_stream);
    AAudioStream_close(g_stream);
    g_stream = NULL;
}

JNIEXPORT jint JNICALL
Java_app_foxbox_audio_NativeAudio_nBurst(JNIEnv *env, jclass cls) {
    (void) env; (void) cls;
    return g_stream ? AAudioStream_getFramesPerBurst(g_stream) : 0;
}

JNIEXPORT jint JNICALL
Java_app_foxbox_audio_NativeAudio_nBufferSize(JNIEnv *env, jclass cls) {
    (void) env; (void) cls;
    return g_stream ? AAudioStream_getBufferSizeInFrames(g_stream) : 0;
}

JNIEXPORT jint JNICALL
Java_app_foxbox_audio_NativeAudio_nSetBufferSize(JNIEnv *env, jclass cls, jint frames) {
    (void) env; (void) cls;
    return g_stream ? AAudioStream_setBufferSizeInFrames(g_stream, frames) : 0;
}

JNIEXPORT jint JNICALL
Java_app_foxbox_audio_NativeAudio_nXRuns(JNIEnv *env, jclass cls) {
    (void) env; (void) cls;
    return g_stream ? AAudioStream_getXRunCount(g_stream) : 0;
}

JNIEXPORT jboolean JNICALL
Java_app_foxbox_audio_NativeAudio_nDisconnected(JNIEnv *env, jclass cls) {
    (void) env; (void) cls;
    return g_disconnected ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_app_foxbox_audio_NativeAudio_nCapacity(JNIEnv *env, jclass cls) {
    (void) env; (void) cls;
    return g_stream ? AAudioStream_getBufferCapacityInFrames(g_stream) : 0;
}

JNIEXPORT jboolean JNICALL
Java_app_foxbox_audio_NativeAudio_nExclusive(JNIEnv *env, jclass cls) {
    (void) env; (void) cls;
    return (g_stream && AAudioStream_getSharingMode(g_stream) == AAUDIO_SHARING_MODE_EXCLUSIVE) ? JNI_TRUE : JNI_FALSE;
}
