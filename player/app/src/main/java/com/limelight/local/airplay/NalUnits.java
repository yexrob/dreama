package com.limelight.local.airplay;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Annex-B helpers for the mirror frames UxPlay hands over. After a codec
 * packet (a new stream, or the iPad turning) UxPlay puts the parameter sets
 * in front of the next picture: SPS and PPS for H.264, VPS, SPS and PPS for
 * H.265, each behind a 00 00 00 01 start code. MediaCodec wants those as
 * csd buffers at configure time, and a change in them is a new resolution.
 *
 * Plain java.nio, so the host tests reach all of it.
 */
final class NalUnits {
    private NalUnits() {
    }

    static final int H264_SPS = 7;
    static final int H264_PPS = 8;
    static final int H265_VPS = 32;
    static final int H265_SPS = 33;
    static final int H265_PPS = 34;

    /** The parameter sets at the head of a frame, as MediaCodec takes them. */
    static final class Config {
        // Each with its start code. csd1 is the PPS for H.264 and null for
        // H.265, whose csd-0 carries all three.
        final byte[] csd0;
        final byte[] csd1;
        // Where the picture starts in the buffer, at its start code; the
        // buffer's limit when the frame held nothing else
        final int payloadStart;

        Config(byte[] csd0, byte[] csd1, int payloadStart) {
            this.csd0 = csd0;
            this.csd1 = csd1;
            this.payloadStart = payloadStart;
        }

        boolean sameAs(Config other) {
            return other != null && Arrays.equals(csd0, other.csd0) && Arrays.equals(csd1, other.csd1);
        }
    }

    static int type(byte first, boolean h265) {
        return h265 ? (first >> 1) & 0x3F : first & 0x1F;
    }

    static boolean isParameterSet(int type, boolean h265) {
        return h265 ? type == H265_VPS || type == H265_SPS || type == H265_PPS
                : type == H264_SPS || type == H264_PPS;
    }

    /**
     * The first start code at or after from, before limit: {where it starts,
     * where its NAL unit starts}. A four byte code starts at its extra zero.
     * Null when there is none.
     */
    static int[] nextStartCode(ByteBuffer b, int from, int limit) {
        for (int i = from; i + 2 < limit; i++) {
            if (b.get(i) == 0 && b.get(i + 1) == 0 && b.get(i + 2) == 1) {
                int start = (i > from && b.get(i - 1) == 0) ? i - 1 : i;
                return new int[]{start, i + 3};
            }
        }
        return null;
    }

    /**
     * The parameter sets leading the frame between b's position and limit,
     * or null when it does not start with a complete set. b is not moved.
     */
    static Config leadingConfig(ByteBuffer b, boolean h265) {
        int pos = b.position();
        int limit = b.limit();
        int[] code = nextStartCode(b, pos, limit);
        if (code == null || code[0] != pos) {
            return null;
        }
        byte[] vps = null, sps = null, pps = null;
        int payloadStart = limit;
        while (code != null) {
            int nal = code[1];
            if (nal >= limit) {
                break;
            }
            int[] next = nextStartCode(b, nal, limit);
            int end = next != null ? next[0] : limit;
            int t = type(b.get(nal), h265);
            if (!isParameterSet(t, h265)) {
                payloadStart = code[0];
                break;
            }
            // A parameter set never ends in a zero byte, so any there are
            // the next start code's or padding
            while (end > nal && b.get(end - 1) == 0) {
                end--;
            }
            byte[] unit = withStartCode(b, nal, end);
            if (t == (h265 ? H265_VPS : -1) && vps == null) {
                vps = unit;
            } else if (t == (h265 ? H265_SPS : H264_SPS) && sps == null) {
                sps = unit;
            } else if (t == (h265 ? H265_PPS : H264_PPS) && pps == null) {
                pps = unit;
            }
            code = next;
        }
        if (sps == null || pps == null || (h265 && vps == null)) {
            return null;
        }
        if (h265) {
            ByteArrayOutputStream all = new ByteArrayOutputStream(vps.length + sps.length + pps.length);
            all.write(vps, 0, vps.length);
            all.write(sps, 0, sps.length);
            all.write(pps, 0, pps.length);
            return new Config(all.toByteArray(), null, payloadStart);
        }
        return new Config(sps, pps, payloadStart);
    }

    private static byte[] withStartCode(ByteBuffer b, int from, int to) {
        byte[] out = new byte[4 + (to - from)];
        out[3] = 1;
        for (int i = from; i < to; i++) {
            out[4 + i - from] = b.get(i);
        }
        return out;
    }
}
