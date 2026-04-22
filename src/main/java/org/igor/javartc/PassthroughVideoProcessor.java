package org.igor.javartc;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Default no-op {@link VideoProcessor}: passes frames through unchanged.
 * Replace with a {@code @Primary @Component} implementation to activate
 * custom processing.
 */
@Order(1)
@Component
public class PassthroughVideoProcessor implements VideoProcessor {

    @Override
    public byte[] process(byte[] bgr, int width, int height) {
        return null; // null = use original bytes unchanged
    }
}
