package org.igor.javartc;

import org.opencv.core.*;
import org.opencv.imgproc.Imgproc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * {@link VideoProcessor} that performs Canny edge detection on each frame.
 *
 * <p>The output is the detected edges (white) overlaid on a darkened version of the original
 * colour frame, giving a stylised "sketch" effect while preserving colour context.
 *
 * <p><b>Compilation</b>: this class lives in {@code src/opencv/java/} and is only compiled
 * when the {@code opencv} Maven profile is active (i.e. inside the Docker multi-stage build).
 * It will not appear in JARs built without that profile.
 *
 * <p><b>Activation at runtime</b>: {@code @ConditionalOnClass} ensures this bean is only
 * registered when the OpenCV JAR is on the classpath. The native library
 * ({@code libopencv_java454d.so}) is loaded in the static initialiser; it is provided by the
 * {@code libopencv4.5d-jni} apt package installed in the Docker image.
 * The library path {@code /usr/lib/jni} is added to {@code java.library.path} via the
 * Docker {@code ENTRYPOINT} flag {@code -Djava.library.path=/usr/lib/jni}.
 */
@Order(3)
@Component
@ConditionalOnClass(name = "org.opencv.core.Core")
public class EdgeDetectionVideoProcessor implements VideoProcessor {

    private static final Logger LOG = LoggerFactory.getLogger(EdgeDetectionVideoProcessor.class);

    // Canny thresholds — lower = more edges, higher = fewer/cleaner edges
    private static final double CANNY_THRESHOLD_LOW  = 50.0;
    private static final double CANNY_THRESHOLD_HIGH = 150.0;

    // Weight of the original (darkened) frame in the overlay. 0 = pure edges, 1 = original only.
    private static final double ORIGINAL_WEIGHT = 0.4;

    static {
        // Ubuntu 22.04 builds OpenCV with a 'd' (debug) suffix: libopencv_java454d.so.
        // Core.NATIVE_LIBRARY_NAME returns "opencv_java454" (no 'd'), so we load explicitly.
        // The .so lives in /usr/lib/jni which is on java.library.path via the Docker ENTRYPOINT.
        final String LIB_NAME = "opencv_java454d";
        try {
            System.loadLibrary(LIB_NAME);
            LOG.info("OpenCV native library loaded: {}", Core.VERSION);
        } catch (UnsatisfiedLinkError e) {
            LOG.error("Failed to load OpenCV native library '{}'. "
                    + "Ensure libopencv4.5d-jni is installed and /usr/lib/jni is on java.library.path.",
                    LIB_NAME, e);
            throw e;
        }
    }

    @Override
    public String getName() {
        return "EdgeDetection";
    }

    @Override
    public byte[] process(byte[] bgr, int width, int height) {
        // Wrap input bytes in an OpenCV Mat (no copy — Mat views the array)
        Mat src = new Mat(height, width, CvType.CV_8UC3);
        src.put(0, 0, bgr);

        // ── Canny edge detection ─────────────────────────────────────────────
        Mat gray  = new Mat();
        Mat edges = new Mat();
        Imgproc.cvtColor(src, gray, Imgproc.COLOR_BGR2GRAY);
        Imgproc.Canny(gray, edges, CANNY_THRESHOLD_LOW, CANNY_THRESHOLD_HIGH);
        gray.release();

        // Convert single-channel edge mask to 3-channel BGR so we can blend it
        Mat edgesBgr = new Mat();
        Imgproc.cvtColor(edges, edgesBgr, Imgproc.COLOR_GRAY2BGR);
        edges.release();

        // ── Overlay: edges (white) on darkened original ──────────────────────
        // result = ORIGINAL_WEIGHT * src + 1.0 * edges  (edges win where they exist)
        Mat result = new Mat();
        Core.addWeighted(src, ORIGINAL_WEIGHT, edgesBgr, 1.0, 0.0, result);
        edgesBgr.release();
        src.release();

        // Read result back to byte array
        byte[] out = new byte[(int) (result.total() * result.channels())];
        result.get(0, 0, out);
        result.release();

        return out;
    }
}
