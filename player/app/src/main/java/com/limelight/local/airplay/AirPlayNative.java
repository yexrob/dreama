package com.limelight.local.airplay;

import androidx.annotation.Keep;

import com.limelight.LimeLog;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * The bridge to libairplay (jni/airplay): UxPlay's receiver with the
 * GStreamer half left out. The library looks up every on* method below by
 * name and signature when it loads, so they stay exactly as they are and
 * R8 keeps them (@Keep, and the rule in proguard-rules.pro).
 *
 * The callbacks come on UxPlay's own threads: the HTTP thread for the
 * connection and the format, the mirror thread for video, the audio thread
 * for sound. None of them may call nativeStart, nativeReset or nativeStop,
 * which join those threads; the service hands those to its own thread.
 * The ByteBuffers point into native memory that is only valid during the
 * call.
 */
@Keep
final class AirPlayNative {
    private AirPlayNative() {
    }

    // The records nativeServiceName/Port/Txt describe
    static final int RAOP = 0;
    static final int AIRPLAY = 1;

    // UxPlay's reset_type_t
    static final int RESET_NOHOLD = 0;
    static final int RESET_RTP_SHUTDOWN = 1;

    // The audio ct values UxPlay hands over
    static final int CT_ALAC = 2;
    static final int CT_AAC_LC = 4;
    static final int CT_AAC_ELD = 8;

    interface Listener {
        void onConnInit();

        void onConnDestroy();

        void onConnReset(int reason);

        void onConnFeedback();

        boolean onClientRequest(String deviceId, String model, String name);

        void onMirrorRunning(boolean running);

        int onVideoCodec(boolean h265);

        void onVideoSize(float srcW, float srcH, float w, float h);

        void onVideoFrame(ByteBuffer frame, boolean h265);

        void onVideoReset(int type);

        void onVideoFlush();

        void onAudioFormat(int ct, int spf, boolean usingScreen, boolean isMedia);

        void onAudioFrame(ByteBuffer frame, int ct);

        void onAudioFlush();

        void onAudioVolume(float db);
    }

    private static final boolean LOADED;
    private static volatile Listener listener;

    static {
        boolean ok;
        try {
            System.loadLibrary("airplay");
            ok = true;
        } catch (Throwable t) {
            LimeLog.severe("AirPlay: native library did not load: " + t);
            ok = false;
        }
        LOADED = ok;
    }

    static boolean available() {
        return LOADED;
    }

    static synchronized void setListener(Listener l) {
        listener = l;
    }

    /** Clears the listener if it is still l, not one set since. */
    static synchronized void clearListener(Listener l) {
        if (listener == l) {
            listener = null;
        }
    }

    /**
     * Starts the receiver and builds its records. The TCP port it listens
     * on (port if that was free), or a negative code. name is UTF-8, hw
     * the six byte device id.
     */
    static native int nativeStart(byte[] name, byte[] hw, int width, int height, int fps,
                                  boolean h265, String keyFile, int port, int logLevel);

    /** Drops every client and listens again; the port, or a negative code. */
    static native int nativeReset();

    static native void nativeStop();

    /** UTF-8 instance name of a record, null when it is not built. */
    static native byte[] nativeServiceName(int which);

    static native int nativeServicePort(int which);

    /** The record's TXT entries as key, value, key, value... */
    static native String[] nativeTxt(int which);

    private static String text(byte[] b) {
        return b == null ? null : new String(b, StandardCharsets.UTF_8);
    }

    // ---- called from native ----

    static void onConnInit() {
        Listener l = listener;
        if (l != null) l.onConnInit();
    }

    static void onConnDestroy() {
        Listener l = listener;
        if (l != null) l.onConnDestroy();
    }

    static void onConnReset(int reason) {
        Listener l = listener;
        if (l != null) l.onConnReset(reason);
    }

    static void onConnFeedback() {
        Listener l = listener;
        if (l != null) l.onConnFeedback();
    }

    static boolean onClientRequest(byte[] deviceId, byte[] model, byte[] name) {
        Listener l = listener;
        return l == null || l.onClientRequest(text(deviceId), text(model), text(name));
    }

    static void onMirrorRunning(boolean running) {
        Listener l = listener;
        if (l != null) l.onMirrorRunning(running);
    }

    static int onVideoCodec(boolean h265) {
        Listener l = listener;
        return l == null ? -1 : l.onVideoCodec(h265);
    }

    static void onVideoSize(float srcW, float srcH, float w, float h) {
        Listener l = listener;
        if (l != null) l.onVideoSize(srcW, srcH, w, h);
    }

    static void onVideoFrame(ByteBuffer frame, boolean h265, long ntpLocal, long ntpRemote) {
        Listener l = listener;
        if (l != null) l.onVideoFrame(frame, h265);
    }

    static void onVideoReset(int type) {
        Listener l = listener;
        if (l != null) l.onVideoReset(type);
    }

    // Pause and resume only concern HLS, which is not offered
    static void onVideoPause() {
    }

    static void onVideoResume() {
    }

    static void onVideoFlush() {
        Listener l = listener;
        if (l != null) l.onVideoFlush();
    }

    static void onAudioFormat(int ct, int spf, boolean usingScreen, boolean isMedia) {
        Listener l = listener;
        if (l != null) l.onAudioFormat(ct, spf, usingScreen, isMedia);
    }

    static void onAudioFrame(ByteBuffer frame, int ct, long ntpLocal, long ntpRemote, int seq) {
        Listener l = listener;
        if (l != null) l.onAudioFrame(frame, ct);
    }

    static void onAudioFlush() {
        Listener l = listener;
        if (l != null) l.onAudioFlush();
    }

    static void onAudioVolume(float db) {
        Listener l = listener;
        if (l != null) l.onAudioVolume(db);
    }

    static void onLog(int level, byte[] message) {
        String m = text(message);
        if (m == null) {
            return;
        }
        // syslog levels, as UxPlay's logger.h
        if (level <= 3) {
            LimeLog.severe("AirPlay: " + m);
        } else if (level == 4) {
            LimeLog.warning("AirPlay: " + m);
        } else {
            LimeLog.info("AirPlay: " + m);
        }
    }
}
