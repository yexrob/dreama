package com.limelight.local.airplay;

import static org.junit.Assert.assertArrayEquals;

import org.junit.Test;

public class MirrorGeometryTest {
    @Test
    public void sameShapeFillsTheCanvas() {
        assertArrayEquals(new int[]{0, 0, 1920, 1080}, MirrorGeometry.fit(1280, 720, 1920, 1080));
    }

    @Test
    public void portraitIsPillarboxed() {
        // An iPad held upright on a 4:3 landscape canvas
        assertArrayEquals(new int[]{450, 0, 540, 720}, MirrorGeometry.fit(1536, 2048, 1440, 720));
    }

    @Test
    public void widerIsLetterboxed() {
        assertArrayEquals(new int[]{0, 135, 1440, 810}, MirrorGeometry.fit(1920, 1080, 1440, 1080));
    }

    @Test
    public void unknownSizeTakesTheCanvas() {
        assertArrayEquals(new int[]{0, 0, 1440, 1080}, MirrorGeometry.fit(0, 0, 1440, 1080));
    }

    @Test
    public void canvasFollowsTheFirstReportTurnedLandscape() {
        // iPad, 4:3 either way up
        assertArrayEquals(new int[]{1440, 1080}, MirrorGeometry.canvasFor(1440, 1080, 1920, 1080));
        assertArrayEquals(new int[]{1440, 1080}, MirrorGeometry.canvasFor(1080, 1440, 1920, 1080));
        // iPhone, wider than 16:9: full width, even height
        assertArrayEquals(new int[]{1920, 886}, MirrorGeometry.canvasFor(886, 1920, 1920, 1080));
        // 16:9
        assertArrayEquals(new int[]{1920, 1080}, MirrorGeometry.canvasFor(1280, 720, 1920, 1080));
    }

    @Test
    public void noReportIsTheOfferedDisplay() {
        assertArrayEquals(new int[]{1920, 1080}, MirrorGeometry.canvasFor(0, 0, 1920, 1080));
        assertArrayEquals(new int[]{1920, 1080}, MirrorGeometry.canvasFor(Float.NaN, 720, 1920, 1080));
    }
}
