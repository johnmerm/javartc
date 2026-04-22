package org.igor.javartc;

/**
 * Hook for per-frame video processing on the server side.
 * <p>
 * Implementations receive raw BGR frames from the receive pipeline and may
 * return a modified frame (same dimensions, same BGR format) that is then
 * encoded and sent back to the browser.  The Swing "Raw Input" panel always
 * shows the unprocessed decoded video; the "Processed Output" panel shows
 * whatever this processor returns.
 * <p>
 * Register a custom processor by declaring a {@code @Primary @Component} (or
 * {@code @Bean}) in any Spring configuration class.  The default bean is
 * {@link PassthroughVideoProcessor}.
 */
public interface VideoProcessor {

    /**
     * Process a single video frame.
     *
     * @param bgr    raw frame bytes in BGR format (3 bytes per pixel, row-major)
     * @param width  frame width in pixels
     * @param height frame height in pixels
     * @return processed frame in the same BGR format and dimensions,
     *         or {@code null} to pass the original frame through unchanged
     */
    byte[] process(byte[] bgr, int width, int height);
}
