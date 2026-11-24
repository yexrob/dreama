#!/bin/sh
# depth3d: builds mac_check.c with the receiver's native sources against
# Homebrew's OpenSSL 3, with AddressSanitizer and UBSan, and runs it.
#   sh player/app/src/main/jni/airplay/mac/build.sh [-v] [out-dir]
# Needs: Xcode command line tools, brew install openssl@3.
set -e

VERBOSE=""
if [ "$1" = "-v" ]; then
    VERBOSE="-v"
    shift
fi
HERE=$(cd "$(dirname "$0")" && pwd)
AP=$(dirname "$HERE")
OUT=${1:-${TMPDIR:-/tmp}/depth3d-airplay-mac-check}
SSL=$(brew --prefix openssl@3)
mkdir -p "$OUT"

UX="$AP/uxplay/lib"
PL="$AP/libplist"

SRC="$UX/airplay_video.c $UX/byteutils.c $UX/compat.c $UX/crypto.c $UX/fairplay_playfair.c \
     $UX/http_request.c $UX/http_response.c $UX/httpd.c $UX/logger.c $UX/mirror_buffer.c \
     $UX/netutils.c $UX/pairing.c $UX/raop.c $UX/raop_buffer.c $UX/raop_ntp.c $UX/raop_rtp.c \
     $UX/raop_rtp_mirror.c $UX/srp.c $UX/utils.c \
     $UX/llhttp/api.c $UX/llhttp/http.c $UX/llhttp/llhttp.c \
     $UX/playfair/hand_garble.c $UX/playfair/modified_md5.c $UX/playfair/omg_hax.c \
     $UX/playfair/playfair.c $UX/playfair/sap_hash.c \
     $AP/android/dnssd_android.c $AP/android/airplay_server.c"
PLSRC="$PL/src/base64.c $PL/src/bplist.c $PL/src/bytearray.c $PL/src/hashtable.c $PL/src/jplist.c \
       $PL/src/jsmn.c $PL/src/oplist.c $PL/src/out-default.c $PL/src/out-limd.c $PL/src/out-plutil.c \
       $PL/src/plist.c $PL/src/ptrarray.c $PL/src/time64.c $PL/src/xplist.c \
       $PL/libcnary/node.c $PL/libcnary/node_list.c"

SAN="-fsanitize=address,undefined -fno-omit-frame-pointer -g -O1"

# libplist on its own, as on Android: with HAVE_MEMMEM and HAVE_STRNDUP,
# which macOS has too
cd "$OUT"
rm -f ./*.o
cc $SAN -c -w -DPACKAGE_VERSION=\"2.6.0\" -DHAVE_STRNDUP -DHAVE_MEMMEM \
   -DHAVE_TM_TM_GMTOFF -DHAVE_TM_TM_ZONE \
   -I"$PL/include" -I"$PL/src" -I"$PL/libcnary/include" $PLSRC

cc $SAN -w -DNOHOLD -DPLIST_210 -DPLIST_230 -DSTANDALONE -D__STDC_CONSTANT_MACROS \
   -D__STDC_LIMIT_MACROS -DTARGET_POSIX \
   -I"$UX" -I"$UX/llhttp" -I"$UX/playfair" -I"$AP/android" -I"$PL/include" -I"$SSL/include" \
   $SRC "$HERE/mac_check.c" "$OUT"/*.o "$SSL/lib/libcrypto.a" -lpthread \
   -o "$OUT/mac_check"

ASAN_OPTIONS=detect_leaks=0 "$OUT/mac_check" $VERBOSE
