# depth3d: AirPlay screen mirroring receiver (libairplay.so).
#
#   uxplay/   UxPlay v1.73.7 lib/ (GPLv3 / LGPL-2.1+, playfair GPL,
#             llhttp MIT), without dnssd.c and the CMake files
#   libplist/ libplist 2.6.0 (LGPL-2.1+), the library part only
#   android/  depth3d's: dnssd.h for NsdManager, server setup, JNI
#
# OpenSSL is the libcrypto prebuilt moonlight-core already links.

MY_LOCAL_PATH := $(call my-dir)
LOCAL_PATH := $(MY_LOCAL_PATH)

# The receiver is arm64-v8a only: every headset this is for is arm64, and
# the libraries go into the APK uncompressed, so each other ABI would add 3
# to 5 MB (most of it a second static libcrypto). The others get a stub of
# the same name, since the Gradle build asks every ABI for the modules it
# found for one; the stub refuses to load. The real thing builds for all
# four: drop the test and the stub to have them.
ifeq ($(TARGET_ARCH_ABI),arm64-v8a)

# ---- libplist, static ----
include $(CLEAR_VARS)
LOCAL_MODULE := airplay-plist

LOCAL_SRC_FILES := libplist/src/base64.c \
                   libplist/src/bplist.c \
                   libplist/src/bytearray.c \
                   libplist/src/hashtable.c \
                   libplist/src/jplist.c \
                   libplist/src/jsmn.c \
                   libplist/src/oplist.c \
                   libplist/src/out-default.c \
                   libplist/src/out-limd.c \
                   libplist/src/out-plutil.c \
                   libplist/src/plist.c \
                   libplist/src/ptrarray.c \
                   libplist/src/time64.c \
                   libplist/src/xplist.c \
                   libplist/libcnary/node.c \
                   libplist/libcnary/node_list.c

LOCAL_C_INCLUDES := $(LOCAL_PATH)/libplist/include \
                    $(LOCAL_PATH)/libplist/src \
                    $(LOCAL_PATH)/libplist/libcnary/include

# What libplist's configure finds on bionic: strndup, memmem and the
# tm_gmtoff / tm_zone fields are all there
LOCAL_CFLAGS := -DPACKAGE_VERSION=\"2.6.0\" -DHAVE_STRNDUP -DHAVE_MEMMEM \
                -DHAVE_TM_TM_GMTOFF -DHAVE_TM_TM_ZONE -D_GNU_SOURCE \
                -fvisibility=hidden -O2 -Wno-unused-parameter
LOCAL_EXPORT_C_INCLUDES := $(LOCAL_PATH)/libplist/include

include $(BUILD_STATIC_LIBRARY)

# ---- UxPlay lib + depth3d's bridge, shared ----
include $(CLEAR_VARS)
LOCAL_PATH := $(MY_LOCAL_PATH)
LOCAL_MODULE := airplay

UXPLAY := uxplay/lib
LOCAL_SRC_FILES := $(UXPLAY)/airplay_video.c \
                   $(UXPLAY)/byteutils.c \
                   $(UXPLAY)/compat.c \
                   $(UXPLAY)/crypto.c \
                   $(UXPLAY)/fairplay_playfair.c \
                   $(UXPLAY)/http_request.c \
                   $(UXPLAY)/http_response.c \
                   $(UXPLAY)/httpd.c \
                   $(UXPLAY)/logger.c \
                   $(UXPLAY)/mirror_buffer.c \
                   $(UXPLAY)/netutils.c \
                   $(UXPLAY)/pairing.c \
                   $(UXPLAY)/raop.c \
                   $(UXPLAY)/raop_buffer.c \
                   $(UXPLAY)/raop_ntp.c \
                   $(UXPLAY)/raop_rtp.c \
                   $(UXPLAY)/raop_rtp_mirror.c \
                   $(UXPLAY)/srp.c \
                   $(UXPLAY)/utils.c \
                   $(UXPLAY)/llhttp/api.c \
                   $(UXPLAY)/llhttp/http.c \
                   $(UXPLAY)/llhttp/llhttp.c \
                   $(UXPLAY)/playfair/hand_garble.c \
                   $(UXPLAY)/playfair/modified_md5.c \
                   $(UXPLAY)/playfair/omg_hax.c \
                   $(UXPLAY)/playfair/playfair.c \
                   $(UXPLAY)/playfair/sap_hash.c \
                   android/dnssd_android.c \
                   android/airplay_server.c \
                   android/airplay_jni.c

LOCAL_C_INCLUDES := $(LOCAL_PATH)/$(UXPLAY) \
                    $(LOCAL_PATH)/$(UXPLAY)/llhttp \
                    $(LOCAL_PATH)/$(UXPLAY)/playfair \
                    $(LOCAL_PATH)/android

# As UxPlay's lib/CMakeLists.txt builds it on Linux, with libplist >= 2.3.
# Only the JNI entry points are exported.
LOCAL_CFLAGS := -DNOHOLD -DPLIST_210 -DPLIST_230 -DSTANDALONE \
                -D__STDC_CONSTANT_MACROS -D__STDC_LIMIT_MACROS -DTARGET_POSIX \
                -D_REENTRANT -fvisibility=hidden -O2 \
                -Wno-unused-parameter -Wno-unused-variable -Wno-unused-function \
                -Wno-unused-but-set-variable -Wno-pointer-sign -Wno-sign-compare \
                -Wno-deprecated-declarations

LOCAL_LDLIBS := -llog
LOCAL_STATIC_LIBRARIES := airplay-plist libcrypto
LOCAL_LDFLAGS += -Wl,--exclude-libs,ALL -Wl,--gc-sections
LOCAL_CFLAGS += -ffunction-sections -fdata-sections

LOCAL_BRANCH_PROTECTION := standard

include $(BUILD_SHARED_LIBRARY)

else # not arm64-v8a

include $(CLEAR_VARS)
LOCAL_PATH := $(MY_LOCAL_PATH)
LOCAL_MODULE := airplay
LOCAL_SRC_FILES := android/airplay_stub.c
include $(BUILD_SHARED_LIBRARY)

endif # arm64-v8a
