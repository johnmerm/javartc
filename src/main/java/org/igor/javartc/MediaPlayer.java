package org.igor.javartc;

import org.freedesktop.gstreamer.*;
import org.freedesktop.gstreamer.elements.AppSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.nio.ByteOrder;
import java.nio.IntBuffer;

/**
 * Displays decoded video frames (from a GStreamer appsink) in a Swing JFrame.
 */
public class MediaPlayer implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(MediaPlayer.class);

    private final JFrame frame;
    private final VideoPanel panel;

    public MediaPlayer(String title) {
        panel = new VideoPanel();
        frame = new JFrame(title);
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.setSize(640, 480);
        frame.add(panel);
        SwingUtilities.invokeLater(() -> frame.setVisible(true));
    }

    /**
     * Creates an AppSink configured for BGRA frames that feeds this display.
     * Wire it into a GStreamer pipeline after a videoconvert element.
     */
    public AppSink createSink() {
        AppSink sink = (AppSink) ElementFactory.make("appsink", "display-sink");
        sink.set("emit-signals", true);
        sink.set("sync", false);
        Caps caps = Caps.fromString("video/x-raw,format=BGRx");
        sink.setCaps(caps);

        sink.connect((AppSink.NEW_SAMPLE) s -> {
            Sample sample = s.pullSample();
            if (sample == null) return FlowReturn.OK;
            try {
                Structure capStruct = sample.getCaps().getStructure(0);
                int width  = capStruct.getInteger("width");
                int height = capStruct.getInteger("height");

                Buffer buf = sample.getBuffer();
                ByteOrder order = ByteOrder.nativeOrder();
                IntBuffer pixels = buf.map(false).order(order).asIntBuffer();

                BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
                int[] imgData = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
                pixels.get(imgData, 0, Math.min(pixels.remaining(), imgData.length));
                buf.unmap();

                SwingUtilities.invokeLater(() -> panel.setFrame(image));
            } catch (Exception e) {
                LOG.warn("Error processing video frame", e);
            } finally {
                sample.dispose();
            }
            return FlowReturn.OK;
        });
        return sink;
    }

    @Override
    public void close() {
        SwingUtilities.invokeLater(() -> {
            frame.setVisible(false);
            frame.dispose();
        });
    }

    private static class VideoPanel extends JPanel {
        private volatile BufferedImage current;

        void setFrame(BufferedImage img) {
            current = img;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            BufferedImage img = current;
            if (img != null) g.drawImage(img, 0, 0, getWidth(), getHeight(), null);
        }
    }
}
