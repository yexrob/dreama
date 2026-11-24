/*
 * depth3d: JNI bridge between UxPlay's RAOP server and
 * com.limelight.local.airplay.AirPlayNative.
 *
 * Every raop callback is forwarded to a static method of AirPlayNative on
 * the thread UxPlay calls it on (its httpd connection threads, the mirror
 * TCP thread, the audio RTP thread). Threads are attached on first use and
 * detached when they exit. Picture and sound arrive as direct ByteBuffers
 * over UxPlay's own memory, valid only for the length of the call: the Java
 * side copies what it keeps before returning.
 *
 * Text that comes from the client (its name, model, the log lines that
 * quote them) crosses as UTF-8 bytes rather than through NewStringUTF,
 * which takes modified UTF-8 and would choke on an emoji in an iPad's name.
 *
 * GPLv3, as the rest of depth3d.
 */
#include <jni.h>
#include <pthread.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>

#include "airplay_server.h"
#include "dnssd_android.h"
#include "logger.h"
#include "raop.h"
#include "stream.h"

#define TAG "AirPlay"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static JavaVM *vm;
static pthread_key_t detach_key;
static jclass native_class;

static jmethodID m_conn_init;
static jmethodID m_conn_destroy;
static jmethodID m_conn_reset;
static jmethodID m_conn_feedback;
static jmethodID m_client_request;
static jmethodID m_mirror_running;
static jmethodID m_video_codec;
static jmethodID m_video_size;
static jmethodID m_video_frame;
static jmethodID m_video_reset;
static jmethodID m_video_pause;
static jmethodID m_video_resume;
static jmethodID m_video_flush;
static jmethodID m_audio_format;
static jmethodID m_audio_frame;
static jmethodID m_audio_flush;
static jmethodID m_audio_volume;
static jmethodID m_log;

static pthread_mutex_t server_lock = PTHREAD_MUTEX_INITIALIZER;
static raop_callbacks_t callbacks;

static void detach_thread(void *unused) {
    (void) unused;
    if (vm) {
        (*vm)->DetachCurrentThread(vm);
    }
}

/* The calling thread's JNIEnv, attaching it if UxPlay started it */
static JNIEnv *get_env(void) {
    JNIEnv *env = NULL;
    if (!vm) {
        return NULL;
    }
    jint got = (*vm)->GetEnv(vm, (void **) &env, JNI_VERSION_1_6);
    if (got == JNI_OK) {
        return env;
    }
    if (got != JNI_EDETACHED) {
        return NULL;
    }
    JavaVMAttachArgs args = { JNI_VERSION_1_6, "AirPlay native", NULL };
    if ((*vm)->AttachCurrentThread(vm, &env, &args) != JNI_OK) {
        return NULL;
    }
    /* Any non-NULL value, so the destructor runs when the thread exits */
    pthread_setspecific(detach_key, (void *) 1);
    return env;
}

static void clear_exception(JNIEnv *env, const char *where) {
    if ((*env)->ExceptionCheck(env)) {
        LOGE("Java threw in %s", where);
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
    }
}

/* UTF-8 bytes as a byte[], NULL for NULL */
static jbyteArray new_bytes(JNIEnv *env, const char *text) {
    if (!text) {
        return NULL;
    }
    jsize len = (jsize) strlen(text);
    jbyteArray bytes = (*env)->NewByteArray(env, len);
    if (!bytes) {
        (*env)->ExceptionClear(env);
        return NULL;
    }
    (*env)->SetByteArrayRegion(env, bytes, 0, len, (const jbyte *) text);
    return bytes;
}

static void call_void(jmethodID method, const char *where) {
    JNIEnv *env = get_env();
    if (!env) return;
    (*env)->CallStaticVoidMethod(env, native_class, method);
    clear_exception(env, where);
}

/* ---- raop callbacks ---- */

static void cb_conn_init(void *cls) {
    (void) cls;
    call_void(m_conn_init, "conn_init");
}

static void cb_conn_destroy(void *cls) {
    (void) cls;
    call_void(m_conn_destroy, "conn_destroy");
}

static void cb_conn_reset(void *cls, int reason) {
    (void) cls;
    JNIEnv *env = get_env();
    if (!env) return;
    (*env)->CallStaticVoidMethod(env, native_class, m_conn_reset, (jint) reason);
    clear_exception(env, "conn_reset");
}

static void cb_conn_feedback(void *cls) {
    (void) cls;
    call_void(m_conn_feedback, "conn_feedback");
}

static void cb_report_client_request(void *cls, char *deviceid, char *model, char *name, bool *admit) {
    (void) cls;
    JNIEnv *env = get_env();
    if (!env) {
        *admit = true;
        return;
    }
    jbyteArray jid = new_bytes(env, deviceid);
    jbyteArray jmodel = new_bytes(env, model);
    jbyteArray jname = new_bytes(env, name);
    jboolean ok = (*env)->CallStaticBooleanMethod(env, native_class, m_client_request, jid, jmodel, jname);
    if ((*env)->ExceptionCheck(env)) {
        clear_exception(env, "report_client_request");
        ok = JNI_TRUE;
    }
    *admit = ok ? true : false;
    if (jid) (*env)->DeleteLocalRef(env, jid);
    if (jmodel) (*env)->DeleteLocalRef(env, jmodel);
    if (jname) (*env)->DeleteLocalRef(env, jname);
}

static void cb_mirror_video_running(void *cls, bool is_running) {
    (void) cls;
    JNIEnv *env = get_env();
    if (!env) return;
    (*env)->CallStaticVoidMethod(env, native_class, m_mirror_running, (jboolean) (is_running ? JNI_TRUE : JNI_FALSE));
    clear_exception(env, "mirror_video_running");
}

static int cb_video_set_codec(void *cls, video_codec_t codec) {
    (void) cls;
    JNIEnv *env = get_env();
    if (!env) return -1;
    jint result = (*env)->CallStaticIntMethod(env, native_class, m_video_codec,
                                              (jboolean) (codec == VIDEO_CODEC_H265 ? JNI_TRUE : JNI_FALSE));
    if ((*env)->ExceptionCheck(env)) {
        clear_exception(env, "video_set_codec");
        return -1;
    }
    return (int) result;
}

static void cb_video_report_size(void *cls, float *width_source, float *height_source, float *width, float *height) {
    (void) cls;
    JNIEnv *env = get_env();
    if (!env) return;
    (*env)->CallStaticVoidMethod(env, native_class, m_video_size,
                                 (jfloat) *width_source, (jfloat) *height_source,
                                 (jfloat) *width, (jfloat) *height);
    clear_exception(env, "video_report_size");
}

static void cb_video_process(void *cls, raop_ntp_t *ntp, video_decode_struct *data) {
    (void) cls;
    (void) ntp;
    if (!data || !data->data || data->data_len <= 0) {
        return;
    }
    /* UxPlay sets the first byte to 1 when the frame did not decrypt or
     * parse; a good one starts with a 00 00 00 01 start code */
    if (data->data[0] != 0) {
        return;
    }
    JNIEnv *env = get_env();
    if (!env) return;
    jobject buffer = (*env)->NewDirectByteBuffer(env, data->data, (jlong) data->data_len);
    if (!buffer) {
        (*env)->ExceptionClear(env);
        return;
    }
    (*env)->CallStaticVoidMethod(env, native_class, m_video_frame, buffer,
                                 (jboolean) (data->is_h265 ? JNI_TRUE : JNI_FALSE),
                                 (jlong) data->ntp_time_local, (jlong) data->ntp_time_remote);
    clear_exception(env, "video_process");
    (*env)->DeleteLocalRef(env, buffer);
}

static void cb_video_reset(void *cls, reset_type_t type) {
    (void) cls;
    JNIEnv *env = get_env();
    if (!env) return;
    (*env)->CallStaticVoidMethod(env, native_class, m_video_reset, (jint) type);
    clear_exception(env, "video_reset");
}

static void cb_video_pause(void *cls) {
    (void) cls;
    call_void(m_video_pause, "video_pause");
}

static void cb_video_resume(void *cls) {
    (void) cls;
    call_void(m_video_resume, "video_resume");
}

static void cb_video_flush(void *cls) {
    (void) cls;
    call_void(m_video_flush, "video_flush");
}

static void cb_audio_get_format(void *cls, unsigned char *ct, unsigned short *spf, bool *usingScreen,
                                bool *isMedia, uint64_t *audioFormat) {
    (void) cls;
    (void) audioFormat;
    JNIEnv *env = get_env();
    if (!env) return;
    (*env)->CallStaticVoidMethod(env, native_class, m_audio_format, (jint) *ct, (jint) *spf,
                                 (jboolean) (*usingScreen ? JNI_TRUE : JNI_FALSE),
                                 (jboolean) (*isMedia ? JNI_TRUE : JNI_FALSE));
    clear_exception(env, "audio_get_format");
}

static void cb_audio_process(void *cls, raop_ntp_t *ntp, audio_decode_struct *data) {
    (void) cls;
    (void) ntp;
    if (!data || !data->data || data->data_len <= 0) {
        return;
    }
    JNIEnv *env = get_env();
    if (!env) return;
    jobject buffer = (*env)->NewDirectByteBuffer(env, data->data, (jlong) data->data_len);
    if (!buffer) {
        (*env)->ExceptionClear(env);
        return;
    }
    (*env)->CallStaticVoidMethod(env, native_class, m_audio_frame, buffer, (jint) data->ct,
                                 (jlong) data->ntp_time_local, (jlong) data->ntp_time_remote,
                                 (jint) data->seqnum);
    clear_exception(env, "audio_process");
    (*env)->DeleteLocalRef(env, buffer);
}

static void cb_audio_flush(void *cls) {
    (void) cls;
    call_void(m_audio_flush, "audio_flush");
}

static void cb_audio_set_volume(void *cls, float volume) {
    (void) cls;
    JNIEnv *env = get_env();
    if (!env) return;
    (*env)->CallStaticVoidMethod(env, native_class, m_audio_volume, (jfloat) volume);
    clear_exception(env, "audio_set_volume");
}

static double cb_audio_set_client_volume(void *cls) {
    (void) cls;
    /* Full volume to start with: the headset's own volume does the rest */
    return 0.0;
}

static void cb_log(void *cls, int level, const char *msg) {
    (void) cls;
    JNIEnv *env = get_env();
    if (!env || !msg) return;
    jbyteArray bytes = new_bytes(env, msg);
    if (!bytes) return;
    (*env)->CallStaticVoidMethod(env, native_class, m_log, (jint) level, bytes);
    clear_exception(env, "log");
    (*env)->DeleteLocalRef(env, bytes);
}

/* What depth3d does not use: cover art and track data (audio-only
 * AirPlay), remote control, PIN and client registration (no PIN, see
 * airplay_server.c) and the HLS video player (not advertised). UxPlay calls
 * several of them without a NULL check, so each is set. */
static void cb_audio_set_metadata(void *cls, const void *buffer, int buflen) {
    (void) cls; (void) buffer; (void) buflen;
}
static void cb_audio_set_coverart(void *cls, const void *buffer, int buflen) {
    (void) cls; (void) buffer; (void) buflen;
}
static void cb_audio_stop_coverart_rendering(void *cls) {
    (void) cls;
}
static void cb_audio_remote_control_id(void *cls, const char *dacp_id, const char *active_remote_header) {
    (void) cls; (void) dacp_id; (void) active_remote_header;
}
static void cb_audio_set_progress(void *cls, uint32_t *start, uint32_t *curr, uint32_t *end) {
    (void) cls; (void) start; (void) curr; (void) end;
}
static void cb_display_pin(void *cls, char *pin) {
    (void) cls; (void) pin;
}
static void cb_register_client(void *cls, const char *device_id, const char *pk_str, const char *name) {
    (void) cls; (void) device_id; (void) pk_str; (void) name;
}
static bool cb_check_register(void *cls, const char *pk_str) {
    (void) cls; (void) pk_str;
    return true;
}
static const char *cb_passwd(void *cls, int *len) {
    (void) cls;
    *len = 0;
    return NULL;
}
static void cb_export_dacp(void *cls, const char *active_remote, const char *dacp_id) {
    (void) cls; (void) active_remote; (void) dacp_id;
}
static void cb_on_video_play(void *cls, const char *location, const float start_position) {
    (void) cls; (void) location; (void) start_position;
}
static void cb_on_video_scrub(void *cls, const float position) {
    (void) cls; (void) position;
}
static void cb_on_video_rate(void *cls, const float rate) {
    (void) cls; (void) rate;
}
static void cb_on_video_stop(void *cls) {
    (void) cls;
}
static void cb_on_video_acquire_playback_info(void *cls, playback_info_t *info) {
    (void) cls;
    /* "Finished", as uxplay answers once its player has nothing */
    info->duration = -1.0;
    info->position = -1.0;
}
static float cb_on_video_playlist_remove(void *cls) {
    (void) cls;
    return 0.0f;
}

static void fill_callbacks(raop_callbacks_t *cbs) {
    memset(cbs, 0, sizeof(*cbs));
    cbs->cls = NULL;
    cbs->audio_process = cb_audio_process;
    cbs->video_process = cb_video_process;
    cbs->video_pause = cb_video_pause;
    cbs->video_resume = cb_video_resume;
    cbs->conn_feedback = cb_conn_feedback;
    cbs->conn_reset = cb_conn_reset;
    cbs->video_reset = cb_video_reset;
    cbs->conn_init = cb_conn_init;
    cbs->conn_destroy = cb_conn_destroy;
    cbs->audio_flush = cb_audio_flush;
    cbs->video_flush = cb_video_flush;
    cbs->audio_set_client_volume = cb_audio_set_client_volume;
    cbs->audio_set_volume = cb_audio_set_volume;
    cbs->audio_set_metadata = cb_audio_set_metadata;
    cbs->audio_set_coverart = cb_audio_set_coverart;
    cbs->audio_stop_coverart_rendering = cb_audio_stop_coverart_rendering;
    cbs->audio_remote_control_id = cb_audio_remote_control_id;
    cbs->audio_set_progress = cb_audio_set_progress;
    cbs->audio_get_format = cb_audio_get_format;
    cbs->video_report_size = cb_video_report_size;
    cbs->mirror_video_running = cb_mirror_video_running;
    cbs->report_client_request = cb_report_client_request;
    cbs->display_pin = cb_display_pin;
    cbs->register_client = cb_register_client;
    cbs->check_register = cb_check_register;
    cbs->passwd = cb_passwd;
    cbs->export_dacp = cb_export_dacp;
    cbs->video_set_codec = cb_video_set_codec;
    cbs->on_video_play = cb_on_video_play;
    cbs->on_video_scrub = cb_on_video_scrub;
    cbs->on_video_rate = cb_on_video_rate;
    cbs->on_video_stop = cb_on_video_stop;
    cbs->on_video_acquire_playback_info = cb_on_video_acquire_playback_info;
    cbs->on_video_playlist_remove = cb_on_video_playlist_remove;
}

/* ---- natives ---- */

#define NATIVE(name) Java_com_limelight_local_airplay_AirPlayNative_##name

JNIEXPORT jint JNICALL
NATIVE(nativeStart)(JNIEnv *env, jclass clazz, jbyteArray jname, jbyteArray jhw, jint width, jint height,
                    jint fps, jboolean h265, jstring jkeyfile, jint port, jint log_level) {
    (void) clazz;
    if (!jname || !jhw || !jkeyfile) {
        return -100;
    }
    jsize name_len = (*env)->GetArrayLength(env, jname);
    jsize hw_len = (*env)->GetArrayLength(env, jhw);
    if (name_len <= 0 || name_len > 200 || hw_len != 6) {
        return -101;
    }
    char name[201];
    (*env)->GetByteArrayRegion(env, jname, 0, name_len, (jbyte *) name);
    name[name_len] = '\0';
    unsigned char hw[6];
    (*env)->GetByteArrayRegion(env, jhw, 0, 6, (jbyte *) hw);
    const char *keyfile = (*env)->GetStringUTFChars(env, jkeyfile, NULL);
    if (!keyfile) {
        return -102;
    }

    airplay_config_t config;
    memset(&config, 0, sizeof(config));
    config.name = name;
    config.hw_addr = hw;
    config.hw_addr_len = 6;
    config.width = width;
    config.height = height;
    /* What /info offers the client, as UxPlay's defaults: a 60 Hz display
     * and a cap of fps frames a second for the stream itself */
    config.refresh_rate = 60;
    config.max_fps = fps;
    config.h265 = h265 ? 1 : 0;
    config.keyfile = keyfile;
    config.port = (unsigned short) port;
    config.log_level = log_level;
    config.log_callback = cb_log;
    config.log_cls = NULL;

    pthread_mutex_lock(&server_lock);
    fill_callbacks(&callbacks);
    int result = airplay_server_start(&config, &callbacks);
    pthread_mutex_unlock(&server_lock);

    (*env)->ReleaseStringUTFChars(env, jkeyfile, keyfile);
    return result;
}

JNIEXPORT jint JNICALL
NATIVE(nativeReset)(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    pthread_mutex_lock(&server_lock);
    int result = airplay_server_reset();
    pthread_mutex_unlock(&server_lock);
    return result;
}

JNIEXPORT void JNICALL
NATIVE(nativeStop)(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    pthread_mutex_lock(&server_lock);
    airplay_server_stop();
    pthread_mutex_unlock(&server_lock);
}

JNIEXPORT jbyteArray JNICALL
NATIVE(nativeServiceName)(JNIEnv *env, jclass clazz, jint which) {
    (void) clazz;
    jbyteArray result = NULL;
    pthread_mutex_lock(&server_lock);
    dnssd_t *dnssd = airplay_server_dnssd();
    if (dnssd && dnssd_android_is_registered(dnssd, which)) {
        result = new_bytes(env, dnssd_android_service_name(dnssd, which));
    }
    pthread_mutex_unlock(&server_lock);
    return result;
}

JNIEXPORT jint JNICALL
NATIVE(nativeServicePort)(JNIEnv *env, jclass clazz, jint which) {
    (void) env;
    (void) clazz;
    jint result = 0;
    pthread_mutex_lock(&server_lock);
    dnssd_t *dnssd = airplay_server_dnssd();
    if (dnssd && dnssd_android_is_registered(dnssd, which)) {
        result = dnssd_android_port(dnssd, which);
    }
    pthread_mutex_unlock(&server_lock);
    return result;
}

/* The TXT entries as key, value, key, value...; null when not registered.
 * Every key and value is ASCII (dnssdint.h and hex), so plain strings. */
JNIEXPORT jobjectArray JNICALL
NATIVE(nativeTxt)(JNIEnv *env, jclass clazz, jint which) {
    (void) clazz;
    jobjectArray result = NULL;
    pthread_mutex_lock(&server_lock);
    dnssd_t *dnssd = airplay_server_dnssd();
    if (dnssd && dnssd_android_is_registered(dnssd, which)) {
        int count = dnssd_android_txt_count(dnssd, which);
        jclass string_class = (*env)->FindClass(env, "java/lang/String");
        if (string_class) {
            result = (*env)->NewObjectArray(env, 2 * count, string_class, NULL);
        }
        for (int i = 0; result && i < count; i++) {
            jstring key = (*env)->NewStringUTF(env, dnssd_android_txt_key(dnssd, which, i));
            jstring value = (*env)->NewStringUTF(env, dnssd_android_txt_value(dnssd, which, i));
            if (!key || !value) {
                (*env)->ExceptionClear(env);
                result = NULL;
                break;
            }
            (*env)->SetObjectArrayElement(env, result, 2 * i, key);
            (*env)->SetObjectArrayElement(env, result, 2 * i + 1, value);
            (*env)->DeleteLocalRef(env, key);
            (*env)->DeleteLocalRef(env, value);
        }
    }
    pthread_mutex_unlock(&server_lock);
    return result;
}

#define FIND_STATIC(var, name, sig)                                          \
    do {                                                                     \
        var = (*env)->GetStaticMethodID(env, native_class, name, sig);       \
        if (!var) {                                                          \
            LOGE("AirPlayNative.%s%s missing", name, sig);                  \
            return JNI_ERR;                                                  \
        }                                                                    \
    } while (0)

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *jvm, void *reserved) {
    (void) reserved;
    JNIEnv *env = NULL;
    if ((*jvm)->GetEnv(jvm, (void **) &env, JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }
    if (pthread_key_create(&detach_key, detach_thread)) {
        return JNI_ERR;
    }
    jclass local = (*env)->FindClass(env, "com/limelight/local/airplay/AirPlayNative");
    if (!local) {
        LOGE("AirPlayNative class missing");
        return JNI_ERR;
    }
    native_class = (jclass) (*env)->NewGlobalRef(env, local);
    (*env)->DeleteLocalRef(env, local);

    FIND_STATIC(m_conn_init, "onConnInit", "()V");
    FIND_STATIC(m_conn_destroy, "onConnDestroy", "()V");
    FIND_STATIC(m_conn_reset, "onConnReset", "(I)V");
    FIND_STATIC(m_conn_feedback, "onConnFeedback", "()V");
    FIND_STATIC(m_client_request, "onClientRequest", "([B[B[B)Z");
    FIND_STATIC(m_mirror_running, "onMirrorRunning", "(Z)V");
    FIND_STATIC(m_video_codec, "onVideoCodec", "(Z)I");
    FIND_STATIC(m_video_size, "onVideoSize", "(FFFF)V");
    FIND_STATIC(m_video_frame, "onVideoFrame", "(Ljava/nio/ByteBuffer;ZJJ)V");
    FIND_STATIC(m_video_reset, "onVideoReset", "(I)V");
    FIND_STATIC(m_video_pause, "onVideoPause", "()V");
    FIND_STATIC(m_video_resume, "onVideoResume", "()V");
    FIND_STATIC(m_video_flush, "onVideoFlush", "()V");
    FIND_STATIC(m_audio_format, "onAudioFormat", "(IIZZ)V");
    FIND_STATIC(m_audio_frame, "onAudioFrame", "(Ljava/nio/ByteBuffer;IJJI)V");
    FIND_STATIC(m_audio_flush, "onAudioFlush", "()V");
    FIND_STATIC(m_audio_volume, "onAudioVolume", "(F)V");
    FIND_STATIC(m_log, "onLog", "(I[B)V");

    /* Only once the method ids are in, so a callback never sees a half
     * filled table */
    vm = jvm;
    return JNI_VERSION_1_6;
}
