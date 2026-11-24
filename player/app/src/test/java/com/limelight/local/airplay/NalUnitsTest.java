package com.limelight.local.airplay;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

public class NalUnitsTest {
    private static final byte[] SC4 = {0, 0, 0, 1};
    private static final byte[] SC3 = {0, 0, 1};

    private static byte[] cat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            out.write(p, 0, p.length);
        }
        return out.toByteArray();
    }

    private static byte[] b(int... v) {
        byte[] out = new byte[v.length];
        for (int i = 0; i < v.length; i++) {
            out[i] = (byte) v[i];
        }
        return out;
    }

    // H.264: SPS (0x67), PPS (0x68), IDR slice (0x65)
    private static final byte[] SPS = b(0x67, 0x64, 0x00, 0x28, 0xAC, 0x2B);
    private static final byte[] PPS = b(0x68, 0xEE, 0x3C, 0xB0);
    private static final byte[] IDR = b(0x65, 0x88, 0x84, 0x00, 0x33, 0x00, 0x00, 0x03, 0x01);

    @Test
    public void h264ConfigIsSplitOffThePicture() {
        byte[] frame = cat(SC4, SPS, SC4, PPS, SC4, IDR);
        NalUnits.Config c = NalUnits.leadingConfig(ByteBuffer.wrap(frame), false);
        assertNotNull(c);
        assertArrayEquals(cat(SC4, SPS), c.csd0);
        assertArrayEquals(cat(SC4, PPS), c.csd1);
        assertEquals(4 + SPS.length + 4 + PPS.length, c.payloadStart);
    }

    @Test
    public void respectsTheBufferPosition() {
        byte[] junk = b(9, 9, 9);
        byte[] frame = cat(junk, SC4, SPS, SC4, PPS, SC4, IDR);
        ByteBuffer buf = ByteBuffer.wrap(frame);
        buf.position(junk.length);
        NalUnits.Config c = NalUnits.leadingConfig(buf, false);
        assertNotNull(c);
        assertEquals(junk.length + 4 + SPS.length + 4 + PPS.length, c.payloadStart);
        // and leaves the buffer where it was
        assertEquals(junk.length, buf.position());
    }

    @Test
    public void aPictureWithoutParameterSetsHasNoConfig() {
        byte[] frame = cat(SC4, b(0x41, 0x9A, 0x20), SC4, b(0x41, 0x01));
        assertNull(NalUnits.leadingConfig(ByteBuffer.wrap(frame), false));
    }

    @Test
    public void halfAConfigIsNoConfig() {
        assertNull(NalUnits.leadingConfig(ByteBuffer.wrap(cat(SC4, SPS, SC4, IDR)), false));
    }

    @Test
    public void configAloneEndsAtTheLimit() {
        byte[] frame = cat(SC4, SPS, SC4, PPS);
        NalUnits.Config c = NalUnits.leadingConfig(ByteBuffer.wrap(frame), false);
        assertNotNull(c);
        assertEquals(frame.length, c.payloadStart);
    }

    @Test
    public void threeByteStartCodesAndTrailingZeros() {
        byte[] frame = cat(SC3, SPS, b(0, 0), SC3, PPS, SC3, IDR);
        NalUnits.Config c = NalUnits.leadingConfig(ByteBuffer.wrap(frame), false);
        assertNotNull(c);
        // The zeros after the SPS are not part of it
        assertArrayEquals(cat(SC4, SPS), c.csd0);
        assertArrayEquals(cat(SC4, PPS), c.csd1);
        assertEquals(3 + SPS.length + 2 + 3 + PPS.length, c.payloadStart);
    }

    @Test
    public void notStartingWithAStartCodeIsNoConfig() {
        assertNull(NalUnits.leadingConfig(ByteBuffer.wrap(cat(b(0x12), SC4, SPS, SC4, PPS)), false));
    }

    // H.265: VPS (type 32 -> 0x40 0x01), SPS (33 -> 0x42 0x01), PPS (34 -> 0x44 0x01), IDR_W_RADL (19 -> 0x26 0x01)
    private static final byte[] VPS5 = b(0x40, 0x01, 0x0C, 0x01, 0xFF);
    private static final byte[] SPS5 = b(0x42, 0x01, 0x01, 0x01, 0x60);
    private static final byte[] PPS5 = b(0x44, 0x01, 0xC1, 0x72);
    private static final byte[] IDR5 = b(0x26, 0x01, 0xAF, 0x06);

    @Test
    public void h265ConfigGoesInOneBuffer() {
        byte[] frame = cat(SC4, VPS5, SC4, SPS5, SC4, PPS5, SC4, IDR5);
        NalUnits.Config c = NalUnits.leadingConfig(ByteBuffer.wrap(frame), true);
        assertNotNull(c);
        assertArrayEquals(cat(SC4, VPS5, SC4, SPS5, SC4, PPS5), c.csd0);
        assertNull(c.csd1);
        assertEquals(frame.length - 4 - IDR5.length, c.payloadStart);
    }

    @Test
    public void h265NeedsTheVps() {
        assertNull(NalUnits.leadingConfig(ByteBuffer.wrap(cat(SC4, SPS5, SC4, PPS5, SC4, IDR5)), true));
    }

    @Test
    public void nalTypes() {
        assertEquals(7, NalUnits.type((byte) 0x67, false));
        assertEquals(5, NalUnits.type((byte) 0x65, false));
        assertEquals(32, NalUnits.type((byte) 0x40, true));
        assertEquals(19, NalUnits.type((byte) 0x26, true));
        assertTrue(NalUnits.isParameterSet(8, false));
        assertFalse(NalUnits.isParameterSet(5, false));
        assertTrue(NalUnits.isParameterSet(34, true));
        assertFalse(NalUnits.isParameterSet(7, true));
    }

    @Test
    public void sameConfigComparesBytes() {
        byte[] frame = cat(SC4, SPS, SC4, PPS, SC4, IDR);
        NalUnits.Config a = NalUnits.leadingConfig(ByteBuffer.wrap(frame), false);
        NalUnits.Config c = NalUnits.leadingConfig(ByteBuffer.wrap(frame.clone()), false);
        assertTrue(a.sameAs(c));
        byte[] turned = frame.clone();
        turned[8] ^= 1;     // a byte inside the SPS: another resolution
        assertFalse(a.sameAs(NalUnits.leadingConfig(ByteBuffer.wrap(turned), false)));
        assertFalse(a.sameAs(null));
    }
}
