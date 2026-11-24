package com.limelight.local.airplay;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Build;
import android.view.Surface;

import com.limelight.LimeLog;

import java.nio.ByteBuffer;

/**
 * The mirrored picture: Annex-B frames from UxPlay into a MediaCodec
 * decoder, which draws into the compositor. One decoder per stream
 * configuration: when the parameter sets change (the iPad turned, or a new
 * stream began) the decoder is started over with the new ones, which every
 * decoder handles, where a size change inside one decoder is up to the
 * vendor.
 *
 * feed() runs on UxPlay's mirror thread and waits for a free input buffer
 * a while, which holds the TCP stream back rather than queueing frames up.
 * The decoder's output is drained on a thread of its own.
 */
final class MirrorVideo {
    // An iPad key frame at 1080p is a few hundred kilobytes; a 4K H.265 one
    // can pass a megabyte
    private static final int MAX_INPUT = 2 * 1024 * 1024;
    private static final long INPUT_WAIT_US = 20_000;
    private static final int INPUT_TRIES = 25;
    private static final long OUTPUT_WAIT_US = 100_000;

    private final MirrorCompositor compositor;
    private final Object lock = new Object();

    // Under lock
    private MediaCodec codec;
    private NalUnits.Config config;
    private boolean codecH265;
    private Drain drain;
    private long queued, dropped;
    // Restarts after a failure, this configuration
    private int restarts;
    private static final int MAX_RESTARTS = 3;

    // The stream size UxPlay last reported, for the decoder's format
    private volatile int streamW, streamH;
    // Set while another thread wants the lock, so a waiting feed lets go
    private volatile boolean letGo;
    private volatile boolean closed;

    private MirrorVideo(MirrorCompositor compositor) {
        this.compositor = compositor;
    }

    static MirrorVideo create() {
        MirrorCompositor c = MirrorCompositor.create();
        return c == null ? null : new MirrorVideo(c);
    }

    /** Whether this headset has a hardware H.265 decoder. */
    static boolean hasHevcDecoder() {
        try {
            for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
                if (info.isEncoder()) {
                    continue;
                }
                String name = info.getName().toLowerCase(java.util.Locale.ROOT);
                if (name.startsWith("omx.google.") || name.startsWith("c2.android.")) {
                    continue;
                }
                for (String type : info.getSupportedTypes()) {
                    if (type.equalsIgnoreCase(MediaFormat.MIMETYPE_VIDEO_HEVC)) {
                        return true;
                    }
                }
            }
        } catch (RuntimeException e) {
            LimeLog.warning("AirPlay: codec list: " + e);
        }
        return false;
    }

    void setStreamSize(int w, int h) {
        streamW = w;
        streamH = h;
    }

    void attach(Surface out, int w, int h) {
        compositor.attach(out, w, h);
    }

    void detach() {
        compositor.detach();
    }

    /** One frame as UxPlay hands it over; frame is only valid during the call. */
    void feed(ByteBuffer frame, boolean h265) {
        if (closed) {
            return;
        }
        synchronized (lock) {
            if (closed) {
                return;
            }
            NalUnits.Config c = NalUnits.leadingConfig(frame, h265);
            int start = frame.position();
            if (c != null) {
                if (codec == null || codecH265 != h265 || !c.sameAs(config)) {
                    stopCodecLocked();
                    restarts = 0;
                    startCodecLocked(c, h265);
                }
                start = c.payloadStart;
            }
            // Before the first parameter sets there is nothing to decode with
            if (codec == null || start >= frame.limit()) {
                return;
            }
            queueLocked(frame, start);
        }
    }

    /** Drops the decoder: the stream ended, or another device took over. */
    void reset() {
        letGo = true;
        synchronized (lock) {
            letGo = false;
            stopCodecLocked();
        }
        compositor.clearPicture();
    }

    void release() {
        closed = true;
        letGo = true;
        synchronized (lock) {
            stopCodecLocked();
        }
        compositor.release();
    }

    // ---- under lock ----

    private void startCodecLocked(NalUnits.Config c, boolean h265) {
        String mime = h265 ? MediaFormat.MIMETYPE_VIDEO_HEVC : MediaFormat.MIMETYPE_VIDEO_AVC;
        int w = streamW > 0 ? streamW : 1920;
        int h = streamH > 0 ? streamH : 1080;
        MediaFormat f = MediaFormat.createVideoFormat(mime, w, h);
        f.setByteBuffer("csd-0", ByteBuffer.wrap(c.csd0));
        if (c.csd1 != null) {
            f.setByteBuffer("csd-1", ByteBuffer.wrap(c.csd1));
        }
        f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT);
        // Real time rather than as fast as it can
        f.setInteger(MediaFormat.KEY_PRIORITY, 0);
        MediaCodec mc = null;
        try {
            mc = MediaCodec.createDecoderByType(mime);
            if (Build.VERSION.SDK_INT >= 30 && lowLatency(mc, mime)) {
                f.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
            }
            Surface out = compositor.decoderSurface();
            if (out == null) {
                throw new IllegalStateException("no decoder surface");
            }
            mc.configure(f, out, null, 0);
            mc.start();
        } catch (Exception e) {
            LimeLog.severe("AirPlay: decoder " + mime + " " + w + "x" + h + " did not start: " + e);
            if (mc != null) {
                mc.release();
            }
            return;
        }
        LimeLog.info("AirPlay: decoder " + mc.getName() + " for " + mime + " " + w + "x" + h);
        codec = mc;
        config = c;
        codecH265 = h265;
        drain = new Drain(mc);
        drain.start();
    }

    private static boolean lowLatency(MediaCodec mc, String mime) {
        try {
            return mc.getCodecInfo().getCapabilitiesForType(mime)
                    .isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void stopCodecLocked() {
        if (codec == null) {
            return;
        }
        if (drain != null) {
            drain.finish();
            drain = null;
        }
        try {
            codec.stop();
        } catch (RuntimeException ignored) {
        }
        codec.release();
        LimeLog.info("AirPlay: decoder stopped after " + queued + " frames, " + dropped + " dropped");
        codec = null;
        config = null;
        queued = 0;
        dropped = 0;
    }

    private void queueLocked(ByteBuffer frame, int start) {
        int len = frame.limit() - start;
        try {
            for (int i = 0; i < INPUT_TRIES && !letGo && !closed; i++) {
                int index = codec.dequeueInputBuffer(INPUT_WAIT_US);
                if (index < 0) {
                    continue;
                }
                long pts = System.nanoTime() / 1000;
                ByteBuffer in = codec.getInputBuffer(index);
                if (in == null || len > in.capacity()) {
                    // Too big for the decoder: give the buffer back empty
                    codec.queueInputBuffer(index, 0, 0, pts, 0);
                    dropped++;
                    return;
                }
                in.clear();
                ByteBuffer src = frame.duplicate();
                src.position(start);
                in.put(src);
                codec.queueInputBuffer(index, 0, len, pts, 0);
                queued++;
                return;
            }
            dropped++;
        } catch (IllegalStateException e) {
            // A decoder that failed mid stream is started again with the
            // same parameter sets: iOS only sends them again when the
            // picture changes size
            LimeLog.warning("AirPlay: decoder failed: " + e);
            NalUnits.Config c = config;
            boolean h265 = codecH265;
            stopCodecLocked();
            if (c != null && restarts++ < MAX_RESTARTS) {
                startCodecLocked(c, h265);
            }
        }
    }

    /** Hands each decoded picture to the compositor as it comes. */
    private final class Drain extends Thread {
        private final MediaCodec mc;
        private volatile boolean running = true;

        Drain(MediaCodec mc) {
            super("airplay-decode");
            this.mc = mc;
        }

        void finish() {
            running = false;
            try {
                join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void run() {
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            while (running) {
                int index;
                try {
                    index = mc.dequeueOutputBuffer(info, OUTPUT_WAIT_US);
                    if (index >= 0) {
                        mc.releaseOutputBuffer(index, true);
                    } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        int[] size = pictureSize(mc.getOutputFormat());
                        LimeLog.info("AirPlay: picture " + size[0] + "x" + size[1]);
                        compositor.setPictureSize(size[0], size[1]);
                    }
                } catch (IllegalStateException e) {
                    if (running) {
                        LimeLog.warning("AirPlay: decoder output: " + e);
                    }
                    return;
                }
            }
        }
    }

    // The visible part, where the decoder says it crops
    static int[] pictureSize(MediaFormat f) {
        int w = f.getInteger(MediaFormat.KEY_WIDTH);
        int h = f.getInteger(MediaFormat.KEY_HEIGHT);
        if (f.containsKey("crop-left") && f.containsKey("crop-right")) {
            w = f.getInteger("crop-right") - f.getInteger("crop-left") + 1;
        }
        if (f.containsKey("crop-top") && f.containsKey("crop-bottom")) {
            h = f.getInteger("crop-bottom") - f.getInteger("crop-top") + 1;
        }
        return new int[]{w, h};
    }
}
