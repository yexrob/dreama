package com.limelight.local.airplay;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Build;

import com.limelight.LimeLog;

import java.nio.ByteBuffer;

/**
 * The mirrored sound: AAC-ELD (what screen mirroring sends) or AAC-LC from
 * UxPlay, decoded with MediaCodec and played as soon as it is decoded. The
 * track's buffer is short and written without blocking, so a burst that
 * does not fit is dropped rather than piling up behind the picture; that
 * keeps sound and picture within a frame or so of each other without
 * reading the timestamps. ALAC (AirPlay audio on its own, not mirroring)
 * is not decoded.
 *
 * Every method may come on another of UxPlay's threads, hence the lock;
 * nothing under it waits for long.
 */
final class MirrorAudio {
    private static final int RATE = 44100;
    private static final int CHANNELS = 2;
    private static final int BUFFER_MS = 120;
    private static final long INPUT_WAIT_US = 10_000;

    private final Object lock = new Object();

    // Under lock
    private int formatCt, formatSpf;
    private MediaCodec codec;
    private int codecCt, codecSpf;
    private AudioTrack track;
    private int trackRate, trackChannels;
    private float gain = 1f;
    private boolean toldAlac;
    // A track that would not build is not tried again every frame
    private boolean trackFailed;
    private boolean closed;

    /** What the client will send, from its SETUP. */
    void format(int ct, int spf) {
        synchronized (lock) {
            formatCt = ct;
            formatSpf = spf;
        }
    }

    /** One compressed frame; frame is only valid during the call. */
    void feed(ByteBuffer frame, int ct) {
        synchronized (lock) {
            if (closed) {
                return;
            }
            if (ct != AirPlayNative.CT_AAC_ELD && ct != AirPlayNative.CT_AAC_LC) {
                if (!toldAlac) {
                    toldAlac = true;
                    LimeLog.warning("AirPlay: audio type " + ct + " is not decoded (ALAC); no sound");
                }
                return;
            }
            int spf = ct == formatCt && formatSpf > 0 ? formatSpf : (ct == AirPlayNative.CT_AAC_ELD ? 480 : 1024);
            if (codec == null || codecCt != ct || codecSpf != spf) {
                stopCodecLocked();
                startCodecLocked(ct, spf);
                if (codec == null) {
                    return;
                }
            }
            try {
                int index = codec.dequeueInputBuffer(INPUT_WAIT_US);
                if (index >= 0) {
                    ByteBuffer in = codec.getInputBuffer(index);
                    int len = frame.remaining();
                    if (in != null && len <= in.capacity()) {
                        in.clear();
                        in.put(frame.duplicate());
                        codec.queueInputBuffer(index, 0, len, System.nanoTime() / 1000, 0);
                    } else {
                        codec.queueInputBuffer(index, 0, 0, 0, 0);
                    }
                }
                drainLocked();
            } catch (IllegalStateException e) {
                LimeLog.warning("AirPlay: audio decoder failed: " + e);
                stopCodecLocked();
            }
        }
    }

    /** The sender skipped or paused: drop what is queued. */
    void flush() {
        synchronized (lock) {
            if (codec != null) {
                try {
                    codec.flush();
                } catch (IllegalStateException e) {
                    stopCodecLocked();
                }
            }
            if (track != null) {
                try {
                    track.pause();
                    track.flush();
                    track.play();
                } catch (IllegalStateException ignored) {
                }
            }
        }
    }

    /** The sender's volume: 0 dB down to -30, -144 for muted. */
    void setVolume(float db) {
        synchronized (lock) {
            gain = db <= -30f ? 0f : (float) Math.pow(10.0, Math.min(0f, db) / 20.0);
            if (track != null) {
                track.setVolume(gain);
            }
        }
    }

    /** Silent and empty: the session ended. */
    void reset() {
        synchronized (lock) {
            stopCodecLocked();
            stopTrackLocked();
            formatCt = 0;
            formatSpf = 0;
            gain = 1f;
            toldAlac = false;
            trackFailed = false;
        }
    }

    void release() {
        synchronized (lock) {
            closed = true;
            stopCodecLocked();
            stopTrackLocked();
        }
    }

    // ---- under lock ----

    // AudioSpecificConfig for 44.1 kHz stereo. ELD (object type 39) with
    // its frame length flag for 480 samples; LC (2) likewise for 960.
    static byte[] csd(int ct, int spf) {
        if (ct == AirPlayNative.CT_AAC_ELD) {
            return new byte[]{(byte) 0xF8, (byte) 0xE8, (byte) (spf == 512 ? 0x40 : 0x50), 0x00};
        }
        return new byte[]{0x12, (byte) (spf == 960 ? 0x14 : 0x10)};
    }

    private void startCodecLocked(int ct, int spf) {
        MediaFormat f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, RATE, CHANNELS);
        f.setByteBuffer("csd-0", ByteBuffer.wrap(csd(ct, spf)));
        f.setInteger(MediaFormat.KEY_IS_ADTS, 0);
        MediaCodec mc = null;
        try {
            mc = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            mc.configure(f, null, null, 0);
            mc.start();
        } catch (Exception e) {
            LimeLog.severe("AirPlay: AAC decoder (type " + ct + ", " + spf + " samples) did not start: " + e);
            if (mc != null) {
                mc.release();
            }
            return;
        }
        LimeLog.info("AirPlay: audio " + (ct == AirPlayNative.CT_AAC_ELD ? "AAC-ELD" : "AAC-LC")
                + " " + spf + " samples a frame, " + mc.getName());
        codec = mc;
        codecCt = ct;
        codecSpf = spf;
    }

    private void stopCodecLocked() {
        if (codec == null) {
            return;
        }
        try {
            codec.stop();
        } catch (RuntimeException ignored) {
        }
        codec.release();
        codec = null;
    }

    private void drainLocked() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (true) {
            int index = codec.dequeueOutputBuffer(info, 0);
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat out = codec.getOutputFormat();
                ensureTrackLocked(out.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                        out.getInteger(MediaFormat.KEY_CHANNEL_COUNT));
                continue;
            }
            if (index < 0) {
                return;
            }
            ByteBuffer pcm = codec.getOutputBuffer(index);
            if (pcm != null && info.size > 0) {
                ensureTrackLocked(trackRate > 0 ? trackRate : RATE, trackChannels > 0 ? trackChannels : CHANNELS);
                if (track != null) {
                    pcm.position(info.offset);
                    pcm.limit(info.offset + info.size);
                    // What does not fit now is dropped: that bounds the delay
                    track.write(pcm, info.size, AudioTrack.WRITE_NON_BLOCKING);
                }
            }
            codec.releaseOutputBuffer(index, false);
        }
    }

    private void ensureTrackLocked(int rate, int channels) {
        if ((track != null || trackFailed) && rate == trackRate && channels == trackChannels) {
            return;
        }
        stopTrackLocked();
        int mask = channels == 1 ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
        int min = AudioTrack.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT);
        int want = rate * channels * 2 * BUFFER_MS / 1000;
        try {
            AudioTrack.Builder b = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(rate)
                            .setChannelMask(mask)
                            .build())
                    .setBufferSizeInBytes(Math.max(min, want))
                    .setTransferMode(AudioTrack.MODE_STREAM);
            if (Build.VERSION.SDK_INT >= 26) {
                b.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY);
            }
            AudioTrack t = b.build();
            t.setVolume(gain);
            t.play();
            track = t;
            trackRate = rate;
            trackChannels = channels;
        } catch (RuntimeException e) {
            LimeLog.severe("AirPlay: no audio track " + rate + " Hz x" + channels + ": " + e);
            track = null;
            trackFailed = true;
            trackRate = rate;
            trackChannels = channels;
        }
    }

    private void stopTrackLocked() {
        if (track == null) {
            return;
        }
        try {
            track.pause();
            track.flush();
        } catch (IllegalStateException ignored) {
        }
        track.release();
        track = null;
        trackFailed = false;
        trackRate = 0;
        trackChannels = 0;
    }
}
