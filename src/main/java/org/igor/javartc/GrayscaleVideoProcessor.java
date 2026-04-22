package org.igor.javartc;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Proof-of-concept {@link VideoProcessor} that converts every frame to grayscale.
 *
 * <p>The input/output format is packed BGR (3 bytes per pixel), matching
 * {@link java.awt.image.BufferedImage#TYPE_3BYTE_BGR} and OpenCV's default Mat format.
 * Luminance is computed with standard BT.601 coefficients:
 * <pre>  Y = 0.114·B + 0.587·G + 0.299·R</pre>
 * and all three channels are set to Y, producing a grey pixel in the BGR colourspace.
 *
 * <p>Activate by ensuring this class has the highest {@code @Primary} priority. To switch
 * back to passthrough, remove {@code @Primary} from here and add it to
 * {@link PassthroughVideoProcessor} (or just delete this class).
 */
@Order(2)
@Component
public class GrayscaleVideoProcessor implements VideoProcessor {

    // BT.601 luma coefficients scaled to fixed-point (×1024) to avoid per-pixel float arithmetic
    private static final int W_B = 117;   // round(0.114 * 1024)
    private static final int W_G = 601;   // round(0.587 * 1024)
    private static final int W_R = 306;   // round(0.299 * 1024)

    @Override
    public byte[] process(byte[] bgr, int width, int height) {
        byte[] out = new byte[bgr.length];
        for (int i = 0; i < bgr.length; i += 3) {
            int b = bgr[i]     & 0xFF;
            int g = bgr[i + 1] & 0xFF;
            int r = bgr[i + 2] & 0xFF;
            byte y = (byte) ((W_B * b + W_G * g + W_R * r) >> 10);
            out[i]     = y;
            out[i + 1] = y;
            out[i + 2] = y;
        }
        return out;
    }
}
