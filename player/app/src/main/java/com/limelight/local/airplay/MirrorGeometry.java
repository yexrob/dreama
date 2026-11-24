package com.limelight.local.airplay;

/**
 * Where the mirrored picture goes. The renderer's screen keeps the size it
 * was started at, and an iPad turning changes the picture's shape mid
 * session, so the picture is fitted into a fixed canvas rather than the
 * screen following it. Plain arithmetic for the host tests.
 */
final class MirrorGeometry {
    private MirrorGeometry() {
    }

    /**
     * The picture's rectangle on the canvas, {x, y, width, height}: as large
     * as fits with its shape kept, centred. The whole canvas when the
     * picture's size is not known.
     */
    static int[] fit(int srcW, int srcH, int dstW, int dstH) {
        if (srcW <= 0 || srcH <= 0 || dstW <= 0 || dstH <= 0) {
            return new int[]{0, 0, Math.max(dstW, 0), Math.max(dstH, 0)};
        }
        double scale = Math.min(dstW / (double) srcW, dstH / (double) srcH);
        int w = Math.min(dstW, Math.max(1, (int) Math.round(srcW * scale)));
        int h = Math.min(dstH, Math.max(1, (int) Math.round(srcH * scale)));
        return new int[]{(dstW - w) / 2, (dstH - h) / 2, w, h};
    }

    /**
     * The canvas a session starts with: the shape of the first picture the
     * client reported, turned landscape, as large as fits in maxW x maxH,
     * even sided. A portrait picture later shows pillarboxed on it, which
     * suits the landscape video this is mostly for. maxW x maxH without a
     * report.
     */
    static int[] canvasFor(float w, float h, int maxW, int maxH) {
        if (!(w > 0) || !(h > 0)) {
            return new int[]{maxW, maxH};
        }
        double aspect = Math.max(w, h) / (double) Math.min(w, h);
        int cw, ch;
        if (aspect >= maxW / (double) maxH) {
            cw = maxW;
            ch = (int) Math.round(maxW / aspect);
        } else {
            ch = maxH;
            cw = (int) Math.round(maxH * aspect);
        }
        return new int[]{Math.max(2, cw & ~1), Math.max(2, ch & ~1)};
    }
}
