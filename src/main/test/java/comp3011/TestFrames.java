package comp3011;

import java.nio.Buffer;
import java.nio.ByteBuffer;

import org.bytedeco.javacv.Frame;

/**
 * Shared builders for synthetic, JavaCV-free test frames.
 *
 * <p>All effects operate on the pixel buffer of a {@link Frame} and read the
 * geometry from an {@link InfoVideo}. Keeping both under explicit control lets
 * the tests use tiny images, known colours and non-tight strides.</p>
 */
final class TestFrames {
    static final double FPS = 24.0;
    static final int FRAME_DURATION_US = 41_666;

    private TestFrames() {
    }

    static InfoVideo video(int width, int height, int channels) {
        return video("test.mp4", 300, width, height, channels);
    }

    static InfoVideo video(String mediaName, int totalFrames, int width, int height, int channels) {
        return new InfoVideo(
                mediaName,
                totalFrames,
                width,
                height,
                Frame.DEPTH_UBYTE,
                channels,
                width * channels,
                FPS,
                (int) FPS,
                FRAME_DURATION_US,
                0);
    }

    static InfoVideo videoWithStride(int width, int height, int channels, int stride) {
        return videoWithStride("test.mp4", 300, width, height, channels, stride, Frame.DEPTH_UBYTE);
    }

    static InfoVideo videoWithStride(
            String mediaName, int totalFrames, int width, int height, int channels, int stride) {
        return videoWithStride(mediaName, totalFrames, width, height, channels, stride, Frame.DEPTH_UBYTE);
    }

    static InfoVideo videoWithStride(
            String mediaName,
            int totalFrames,
            int width,
            int height,
            int channels,
            int stride,
            int depth) {
        return new InfoVideo(
                mediaName,
                totalFrames,
                width,
                height,
                depth,
                channels,
                stride,
                FPS,
                (int) FPS,
                FRAME_DURATION_US,
                0);
    }

    static InfoFrame info(int frameNumber) {
        return new InfoFrame(frameNumber, frameNumber * (long) FRAME_DURATION_US);
    }

    static Frame frame(InfoVideo video) {
        Frame frame = new Frame();
        frame.imageWidth = video.imageWidth();
        frame.imageHeight = video.imageHeight();
        frame.imageDepth = video.imageDepth();
        frame.imageChannels = video.imageChannels();
        frame.imageStride = video.imageStride();
        frame.image = new Buffer[] {
                ByteBuffer.allocate(Math.max(0, video.imageStride() * video.imageHeight()))
        };
        return frame;
    }

    static ByteBuffer buffer(Frame frame) {
        return (ByteBuffer) frame.image[0];
    }

    /** Every byte (including row padding) set to {@code value}. */
    static Frame filled(InfoVideo video, int value) {
        Frame frame = frame(video);
        ByteBuffer pixels = buffer(frame);
        for (int i = 0; i < pixels.capacity(); i++) {
            pixels.put(i, (byte) value);
        }
        return frame;
    }

    /** BGR(A) frame with the same colour in every pixel. */
    static Frame bgr(InfoVideo video, int blue, int green, int red, int alpha) {
        Frame frame = frame(video);
        ByteBuffer pixels = buffer(frame);
        for (int y = 0; y < video.imageHeight(); y++) {
            for (int x = 0; x < video.imageWidth(); x++) {
                int base = y * video.imageStride() + x * video.imageChannels();
                pixels.put(base, (byte) blue);
                if (video.imageChannels() > 1) {
                    pixels.put(base + 1, (byte) green);
                }
                if (video.imageChannels() > 2) {
                    pixels.put(base + 2, (byte) red);
                }
                for (int channel = 3; channel < video.imageChannels(); channel++) {
                    pixels.put(base + channel, (byte) alpha);
                }
            }
        }
        return frame;
    }

    /** Deterministic, high-variance pattern so races and accidental sharing show up. */
    static Frame patterned(InfoVideo video, int seed) {
        Frame frame = frame(video);
        ByteBuffer pixels = buffer(frame);
        for (int y = 0; y < video.imageHeight(); y++) {
            for (int x = 0; x < video.imageWidth(); x++) {
                int base = y * video.imageStride() + x * video.imageChannels();
                for (int channel = 0; channel < video.imageChannels(); channel++) {
                    int value = (seed * 31 + y * 17 + x * 7 + channel * 53) & 0xFF;
                    pixels.put(base + channel, (byte) value);
                }
            }
        }
        return frame;
    }

    static int get(Frame frame, InfoVideo video, int x, int y, int channel) {
        int index = y * video.imageStride() + x * video.imageChannels() + channel;
        return Byte.toUnsignedInt(buffer(frame).get(index));
    }

    static void set(Frame frame, InfoVideo video, int x, int y, int channel, int value) {
        int index = y * video.imageStride() + x * video.imageChannels() + channel;
        buffer(frame).put(index, (byte) value);
    }

    static byte[] snapshot(Frame frame) {
        ByteBuffer source = buffer(frame).duplicate();
        source.clear();
        byte[] copy = new byte[source.remaining()];
        source.get(copy);
        return copy;
    }

    static Frame copyOf(Frame source, InfoVideo video) {
        Frame copy = frame(video);
        ByteBuffer from = buffer(source).duplicate();
        from.clear();
        ByteBuffer to = buffer(copy).duplicate();
        to.clear();
        to.put(from);
        return copy;
    }

    static void fillPadding(Frame frame, InfoVideo video, int value) {
        ByteBuffer pixels = buffer(frame);
        int used = video.imageWidth() * video.imageChannels();
        for (int y = 0; y < video.imageHeight(); y++) {
            for (int offset = used; offset < video.imageStride(); offset++) {
                pixels.put(y * video.imageStride() + offset, (byte) value);
            }
        }
    }

    static byte[] padding(Frame frame, InfoVideo video) {
        ByteBuffer pixels = buffer(frame);
        int used = video.imageWidth() * video.imageChannels();
        int perRow = Math.max(0, video.imageStride() - used);
        byte[] result = new byte[perRow * video.imageHeight()];
        int index = 0;
        for (int y = 0; y < video.imageHeight(); y++) {
            for (int offset = used; offset < video.imageStride(); offset++) {
                result[index++] = pixels.get(y * video.imageStride() + offset);
            }
        }
        return result;
    }
}
