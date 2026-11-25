package com.limelight.binding.video;

import static com.limelight.binding.video.XrShared.*;

/**
 * Pure screen-adjustment readout formatter, change-gated upload cache, screen
 * geometry helpers, and §6.5 layer-budget verifier matching native
 * xr_screen_ctrl.c verbatim so both sides agree and JVM unit tests exercise
 * every boundary without Android dependencies.
 */
public final class ScreenReadout {

    public static final float FOV_MIN_DEG = 30.0f;
    public static final float FOV_MAX_DEG = 90.0f;
    public static final float FOV_DEFAULT_DEG = 50.0f;
    public static final float[] FOV_DETENTS = { 40.0f, 50.0f, 62.0f, 80.0f };

    public static final float DIST_MIN_M = 2.0f;
    public static final float DIST_MAX_M = 8.0f;
    public static final float DIST_DEFAULT_M = 3.0f;
    public static final float[] DIST_DETENTS = { 2.0f, 3.0f, 5.0f };

    // Unified global width preference bounds in decimetres:
    // max 160 dm (16.0 m) = 2 * 8.0 m * tan(45 deg), matching native SCREEN_MAX_WIDTH
    public static final int MIN_SCREEN_SIZE_DM = 8;
    public static final int MAX_SCREEN_SIZE_DM = 160;
    public static final int MIN_SCREEN_DIST_DM = 20;
    public static final int MAX_SCREEN_DIST_DM = 80;

    public static final int CURVATURE_STEP = 5;
    public static final float DIAG_WIDTH_RATIO = 0.8716f;
    public static final float METRES_PER_INCH = 0.0254f;

    // Coarse slider quantization for readout texture upload gating (1/64 steps)
    public static final int PROGRESS_BUCKETS = 64;

    public static final int LAYER_BUDGET_HARDWARE_MAX = 16;
    public static final int LAYER_BUDGET_DESIGN_MAX = 13;

    public static final String[] DIST_TICK_LABELS = { "2", "3", "5" };
    public static final String[] SIZE_TICK_LABELS = { "40", "50", "62", "80" };
    public static final float[] DIST_TICK_POSITIONS = {
            distNorm(2.0f), distNorm(3.0f), distNorm(5.0f)
    };
    public static final float[] SIZE_TICK_POSITIONS = {
            sizeNorm(40.0f), sizeNorm(50.0f), sizeNorm(62.0f), sizeNorm(80.0f)
    };

    private ScreenReadout() {
    }

    public static float clampFov(float fovDeg) {
        return Math.max(FOV_MIN_DEG, Math.min(FOV_MAX_DEG, fovDeg));
    }

    public static float clampDist(float distM) {
        return Math.max(DIST_MIN_M, Math.min(DIST_MAX_M, distM));
    }

    public static int clampCurvature(int percent) {
        return Math.max(0, Math.min(100, percent));
    }

    public static int quantizeCurvature(int percent) {
        int clamped = clampCurvature(percent);
        return Math.round(clamped / (float)CURVATURE_STEP) * CURVATURE_STEP;
    }

    /** Flat physical width in metres: 2 * distM * tan(fovDeg / 2). */
    public static float flatWidthMeters(float fovDeg, float distM) {
        float fov = Math.max(1.0f, Math.min(170.0f, fovDeg));
        float d = Math.max(0.05f, distM);
        return (float)(2.0 * d * Math.tan(Math.toRadians(fov * 0.5)));
    }

    /** Horizontal angular width in degrees from flat physical width and viewing distance. */
    public static float fovDegFromFlatWidth(float flatWidthM, float distM) {
        if (flatWidthM <= 1e-4f || distM <= 1e-4f) {
            return FOV_DEFAULT_DEG;
        }
        return (float)Math.toDegrees(2.0 * Math.atan2(flatWidthM * 0.5, distM));
    }

    /** Equivalent 16:9 diagonal inches per ux-spec.md §6.12. */
    public static int diagonalInches(float fovDeg, float distM) {
        float widthM = flatWidthMeters(fovDeg, distM);
        float diagM = widthM / DIAG_WIDTH_RATIO;
        return Math.round(diagM / METRES_PER_INCH);
    }

    /** Continuous curvature radius R = D / c, or 0 when flat (<= 0%). */
    public static float curvatureRadiusMeters(float distM, int curvaturePercent) {
        int clamped = clampCurvature(curvaturePercent);
        if (clamped <= 0) {
            return 0.0f;
        }
        float c = clamped / 100.0f;
        return Math.max(0.05f, distM) / c;
    }

    /** Normalized [0..1] scale bar position for size (linear 30°..90°). */
    public static float sizeNorm(float fovDeg) {
        float clamped = clampFov(fovDeg);
        return (clamped - FOV_MIN_DEG) / (FOV_MAX_DEG - FOV_MIN_DEG);
    }

    /** Normalized [0..1] scale bar position for distance (logarithmic 2 m..8 m). */
    public static float distNorm(float distM) {
        float clamped = clampDist(distM);
        return (float)(Math.log(clamped / DIST_MIN_M) / Math.log(DIST_MAX_M / DIST_MIN_M));
    }

    /** Quantizes normalized slider progress [0..1] into [0..PROGRESS_BUCKETS]. */
    public static int quantizeProgressBucket(float norm) {
        float clamped = Math.max(0.0f, Math.min(1.0f, norm));
        return Math.round(clamped * PROGRESS_BUCKETS);
    }

    /** Converts a quantized progress bucket back to [0..1]. */
    public static float quantizedProgress(int bucket) {
        int clamped = Math.max(0, Math.min(PROGRESS_BUCKETS, bucket));
        return clamped / (float)PROGRESS_BUCKETS;
    }

    /** Preset index 0..3 if within 0.55° of a detent, else SCREEN_PRESET_NONE (-1). */
    public static int sizePresetIndex(float fovDeg) {
        for (int i = 0; i < FOV_DETENTS.length; i++) {
            if (Math.abs(fovDeg - FOV_DETENTS[i]) <= 0.55f) {
                return i;
            }
        }
        return SCREEN_PRESET_NONE;
    }

    /**
     * Formats the screen-adjust readout line from already-rounded displayed units
     * without allocating String.format Formatter instances:
     *   Distance: "↕ 3.4 m · 中 50° · 143 寸"
     *   Size:     "↔ 大 62° · 3.0 m · 163 寸"
     */
    public static String formatReadoutDisplay(int axis, int fovDegRounded, int distTenths,
                                              int inches, int presetIdx,
                                              String[] presetLabels, String inchUnit) {
        int safeDist = Math.max(0, distTenths);
        StringBuilder sb = new StringBuilder(32);
        String preset = (presetLabels != null && presetIdx >= 0 && presetIdx < presetLabels.length)
                ? presetLabels[presetIdx] : null;
        if (axis == ADJUST_AXIS_DIST) {
            sb.append("↕ ")
              .append(safeDist / 10).append('.').append(safeDist % 10).append(" m · ");
            if (preset != null && !preset.isEmpty()) {
                sb.append(preset).append(' ');
            }
            sb.append(fovDegRounded).append("° · ")
              .append(inches).append(' ').append(inchUnit != null ? inchUnit : "");
        } else {
            sb.append("↔ ");
            if (preset != null && !preset.isEmpty()) {
                sb.append(preset).append(' ');
            }
            sb.append(fovDegRounded).append("° · ")
              .append(safeDist / 10).append('.').append(safeDist % 10).append(" m · ")
              .append(inches).append(' ').append(inchUnit != null ? inchUnit : "");
        }
        return sb.toString();
    }

    /** Convenience overload accepting 0.1° and cm inputs directly. */
    public static String formatReadoutText(int axis, int fovTenths, int distCm, int inches,
                                           int presetIdx, String[] presetLabels, String inchUnit) {
        int fovRounded = Math.round(fovTenths / 10.0f);
        int distTenths = Math.round(distCm / 10.0f);
        return formatReadoutDisplay(axis, fovRounded, distTenths, inches, presetIdx,
                presetLabels, inchUnit);
    }

    /**
     * Change-gated cache for the screen-adjust readout HUD:
     * - Rebuilds the formatted string ONLY when displayed integer units change
     * - Signals a texture redraw/upload ONLY when the formatted string OR the
     *   1/64-quantized slider bucket changes
     */
    public static final class ReadoutCache {
        private int textAxis = Integer.MIN_VALUE;
        private int textFovDeg = Integer.MIN_VALUE;
        private int textDistTenths = Integer.MIN_VALUE;
        private int textInches = Integer.MIN_VALUE;
        private int textPreset = Integer.MIN_VALUE;
        private String cachedText = "";
        private String uploadedText = null;
        private int uploadedBucket = Integer.MIN_VALUE;
        private int stringFormatCount = 0;

        public void reset() {
            textAxis = Integer.MIN_VALUE;
            textFovDeg = Integer.MIN_VALUE;
            textDistTenths = Integer.MIN_VALUE;
            textInches = Integer.MIN_VALUE;
            textPreset = Integer.MIN_VALUE;
            cachedText = "";
            uploadedText = null;
            uploadedBucket = Integer.MIN_VALUE;
        }

        public boolean update(int axis, int fovTenths, int distCm, int inches, int presetIdx,
                              String[] presetLabels, String inchUnit) {
            int fovDeg = Math.round(fovTenths / 10.0f);
            int distTenths = Math.round(distCm / 10.0f);
            if (axis != textAxis || fovDeg != textFovDeg || distTenths != textDistTenths
                    || inches != textInches || presetIdx != textPreset) {
                textAxis = axis;
                textFovDeg = fovDeg;
                textDistTenths = distTenths;
                textInches = inches;
                textPreset = presetIdx;
                cachedText = formatReadoutDisplay(axis, fovDeg, distTenths, inches, presetIdx,
                        presetLabels, inchUnit);
                stringFormatCount++;
            }
            float progress = axis == ADJUST_AXIS_DIST
                    ? distNorm(distCm / 100.0f)
                    : sizeNorm(fovTenths / 10.0f);
            int bucket = quantizeProgressBucket(progress);
            if (bucket != uploadedBucket || !cachedText.equals(uploadedText)) {
                uploadedBucket = bucket;
                uploadedText = cachedText;
                return true;
            }
            return false;
        }

        public String getText() {
            return cachedText;
        }

        public float getQuantizedProgress() {
            return quantizedProgress(uploadedBucket);
        }

        public int getProgressBucket() {
            return uploadedBucket;
        }

        public int getStringFormatCount() {
            return stringFormatCount;
        }
    }

    /**
     * Computes simultaneous OpenXR composition layers per ux-spec.md §6.5.
     * Matches native screenLayerBudgetCount in xr_screen_ctrl.c: the dimmer/room
     * slot is always counted (1 layer in every playback state).
     */
    public static int layerBudgetCount(boolean inRoom, boolean stereo, boolean hasDanmaku,
                                       boolean hasSubtitle, boolean hasControlBar,
                                       boolean hasPalette, boolean hasReadout, boolean hasToast,
                                       boolean hasCard, boolean hasPointer,
                                       boolean hasController, boolean hasGlow) {
        int count = 1; // §6.5: dimmer (passthrough/void) OR 3D room is always 1 slot
        if (hasGlow) count += 1;
        count += stereo ? 2 : 1;
        if (hasDanmaku) count += 2;
        if (hasSubtitle) count += 1;
        if (hasControlBar && !hasReadout) count += 1;
        if (hasPalette) count += 1;
        if (hasReadout || hasToast) count += 1;
        if (hasCard) count += 1;
        if (hasPointer) count += 2;
        if (hasController) count += 1;
        return count;
    }
}
