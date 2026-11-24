/*
 * depth3d: libairplay for the ABIs the receiver is not built for (see
 * jni/airplay/Android.mk). Refusing to load makes System.loadLibrary throw,
 * so AirPlayNative.available() is false and AirPlayService says the
 * receiver did not start.
 */
#include <jni.h>

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void) vm;
    (void) reserved;
    return JNI_ERR;
}
