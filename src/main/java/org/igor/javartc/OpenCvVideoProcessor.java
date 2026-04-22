package org.igor.javartc;

// Uncomment @Component and add opencv-java to the classpath to activate.
// See pom.xml for dependency instructions.
//
// @org.springframework.stereotype.Component
// @org.springframework.context.annotation.Primary
public class OpenCvVideoProcessor implements VideoProcessor {

    // import org.opencv.core.*;
    // import org.opencv.imgproc.Imgproc;
    //
    // static {
    //     // Load the native OpenCV library once at class initialisation.
    //     // The exact path depends on your OS and opencv-java version.
    //     System.loadLibrary(Core.NATIVE_LIBRARY_NAME);
    // }

    @Override
    public byte[] process(byte[] bgr, int width, int height) {
        // ── Convert raw bytes → OpenCV Mat ───────────────────────────────
        // Mat mat = new Mat(height, width, CvType.CV_8UC3);
        // mat.put(0, 0, bgr);

        // ── Your processing here ─────────────────────────────────────────
        // detectAndDraw(mat);

        // ── Convert Mat → raw bytes ───────────────────────────────────────
        // byte[] out = new byte[(int) (mat.total() * mat.channels())];
        // mat.get(0, 0, out);
        // return out;

        return null; // passthrough until uncommented
    }

    /**
     * Override this method to add detection / annotation logic.
     * The {@code mat} is BGR, same size as the input frame.
     * Draw directly onto it — the result is what gets encoded and sent
     * to the browser and displayed in the "Processed Output" Swing panel.
     */
    @SuppressWarnings("unused")
    protected void detectAndDraw(Object mat /* Mat */) {
        // Example (requires OpenCV imports):
        // Imgproc.putText(mat, "Hello WebRTC",
        //     new Point(20, 40), Imgproc.FONT_HERSHEY_SIMPLEX,
        //     1.0, new Scalar(0, 255, 0), 2);
    }
}
