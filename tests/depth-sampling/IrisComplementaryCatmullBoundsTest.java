package net.irisshaders.iris.vulkan;

import java.util.Random;

/** Bounds the exact five-tap Catmull-Rom and ClipAABB formulas in Complementary composite6. */
public final class IrisComplementaryCatmullBoundsTest {
    public static void main(String[] args) {
        float minimumDenominator = 1, minimumRawColor = 0, maximumRawColor = 1;
        int samples = 0;
        Random random = new Random(262);
        for (int x = 0; x <= 256; x++) {
            for (int y = 0; y <= 256; y++) {
                float[] wx = weights(x / 256f), wy = weights(y / 256f);
                float[] taps = {wx[1] * wy[0], wx[0] * wy[1], wx[1] * wy[1], wx[2] * wy[1], wx[1] * wy[2]};
                float denominator = 0, negative = 0, positive = 0;
                for (float tap : taps) { denominator += tap; negative += Math.min(tap, 0); positive += Math.max(tap, 0); }
                minimumDenominator = Math.min(minimumDenominator, denominator);
                if (!(denominator > 0.96f)) throw new AssertionError("Catmull normalization can divide by zero");
                minimumRawColor = Math.min(minimumRawColor, negative / denominator);
                maximumRawColor = Math.max(maximumRawColor, positive / denominator);
                float[] reconstructed = new float[3];
                for (int channel = 0; channel < 3; channel++) {
                    for (float tap : taps) reconstructed[channel] += tap * random.nextFloat();
                    reconstructed[channel] /= denominator;
                }
                // Even a negative Catmull lobe cannot make a finite bright neighborhood black.
                float[] minimum = {0.25f, 0.4f, 0.6f};
                float[] maximum = {0.7f, 0.85f, 1.0f};
                float[] clipped = clip(reconstructed, minimum, maximum);
                for (int channel = 0; channel < 3; channel++) {
                    if (!Float.isFinite(clipped[channel]) || clipped[channel] < minimum[channel] - 1e-6f || clipped[channel] > maximum[channel] + 1e-6f) {
                        throw new AssertionError("Finite Catmull result escaped ClipAABB");
                    }
                }
                samples++;
            }
        }
        float[] corrupt = clip(new float[]{Float.POSITIVE_INFINITY, 1, 1}, new float[]{0.25f,0.4f,0.6f}, new float[]{0.7f,0.85f,1});
        if (!Float.isNaN(corrupt[0])) throw new AssertionError("Expected the pack's infinity weakness");
        System.out.println("CATMULL_BOUNDS_PASS: " + samples + " fractional positions; min denominator=" + minimumDenominator
                + ", finite input [0,1] raw output bounds=[" + minimumRawColor + "," + maximumRawColor + "]");
        System.out.println("ClipAABB always preserves the bright finite neighborhood bounds; infinity in history can generate NaN, which the pack's pre-clamp isnan check does not catch.");
    }

    private static float[] weights(float f) {
        float f2 = f * f, f3 = f * f2, c = 0.7f;
        float w0 = -c * f3 + 2 * c * f2 - c * f;
        float w1 = (2 - c) * f3 - (3 - c) * f2 + 1;
        float w2 = -(2 - c) * f3 + (3 - 2 * c) * f2 + c * f;
        float w3 = c * f3 - c * f2;
        return new float[]{w0, w1 + w2, w3};
    }

    private static float[] clip(float[] q, float[] minimum, float[] maximum) {
        float[] p = new float[3], v = new float[3];
        float ma = 0;
        for (int i = 0; i < 3; i++) {
            p[i] = 0.5f * (maximum[i] + minimum[i]);
            float e = 0.5f * (maximum[i] - minimum[i]) + 1e-8f;
            v[i] = q[i] - p[i];
            ma = Math.max(ma, Math.abs(v[i] / e));
        }
        if (ma <= 1) return q;
        return new float[]{p[0] + v[0] / ma, p[1] + v[1] / ma, p[2] + v[2] / ma};
    }
}
