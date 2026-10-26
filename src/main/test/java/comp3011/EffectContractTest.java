package comp3011;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ShortBuffer;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.bytedeco.javacv.Frame;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Common contract every {@link FrameProcessor} must honour: ignore absent
 * images, refuse unsupported buffers, depth and channel counts with a clear
 * message, and accept a normal 8-bit frame.
 */
class EffectContractTest {

    record ProcessorCase(String name, Supplier<FrameProcessor> factory, int minimumChannels) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<ProcessorCase> processors() {
        return Stream.of(
                new ProcessorCase("FrameBlackAndWhiter", FrameBlackAndWhiter::new, 3),
                new ProcessorCase("FrameFlickerer", FrameFlickerer::new, 1),
                new ProcessorCase("FrameYellower", FrameYellower::new, 3),
                new ProcessorCase("FrameVignetter", FrameVignetter::new, 1),
                new ProcessorCase("FrameNumberer", FrameNumberer::new, 1),
                new ProcessorCase("FrameScratcher", FrameScratcher::new, 1),
                new ProcessorCase("FrameDuster", FrameDuster::new, 1),
                new ProcessorCase("FrameJitterer", FrameJitterer::new, 1),
                new ProcessorCase("FrameBleeder", FrameBleeder::new, 3),
                new ProcessorCase("FramePepperer", FramePepperer::new, 1),
                new ProcessorCase("FrameMottler", FrameMottler::new, 3));
    }

    @ParameterizedTest(name = "{0} ignores a null image")
    @MethodSource("processors")
    void nullImageIsIgnored(ProcessorCase testCase) throws Exception {
        InfoVideo video = TestFrames.video(8, 8, testCase.minimumChannels());
        FrameProcessor processor = testCase.factory().get();
        processor.initialise(video);

        Frame frame = TestFrames.frame(video);
        frame.image = null;

        assertDoesNotThrow(() -> processor.process(frame, TestFrames.info(0)));
    }

    @ParameterizedTest(name = "{0} ignores an empty image array")
    @MethodSource("processors")
    void emptyImageArrayIsIgnored(ProcessorCase testCase) throws Exception {
        InfoVideo video = TestFrames.video(8, 8, testCase.minimumChannels());
        FrameProcessor processor = testCase.factory().get();
        processor.initialise(video);

        Frame frame = TestFrames.frame(video);
        frame.image = new Buffer[0];

        assertDoesNotThrow(() -> processor.process(frame, TestFrames.info(0)));
    }

    @ParameterizedTest(name = "{0} ignores a null image element")
    @MethodSource("processors")
    void nullImageElementIsIgnored(ProcessorCase testCase) throws Exception {
        InfoVideo video = TestFrames.video(8, 8, testCase.minimumChannels());
        FrameProcessor processor = testCase.factory().get();
        processor.initialise(video);

        Frame frame = TestFrames.frame(video);
        frame.image = new Buffer[] { null };

        assertDoesNotThrow(() -> processor.process(frame, TestFrames.info(0)));
    }

    @ParameterizedTest(name = "{0} rejects non-byte-backed images")
    @MethodSource("processors")
    void nonByteBackedImageIsRejected(ProcessorCase testCase) throws Exception {
        InfoVideo video = TestFrames.video(8, 8, testCase.minimumChannels());
        FrameProcessor processor = testCase.factory().get();
        processor.initialise(video);

        Frame frame = TestFrames.frame(video);
        frame.image = new Buffer[] { ShortBuffer.allocate(64) };

        Exception error = assertThrows(Exception.class,
                () -> processor.process(frame, TestFrames.info(0)));
        assertTrue(error.getMessage().contains("byte-backed image frames"),
                () -> "Unexpected message: " + error.getMessage());
    }

    @ParameterizedTest(name = "{0} rejects non-8-bit video metadata")
    @MethodSource("processors")
    void nonByteDepthIsRejected(ProcessorCase testCase) throws Exception {
        InfoVideo video = TestFrames.videoWithStride(
                "test.mp4", 300, 8, 8, testCase.minimumChannels(), 8 * testCase.minimumChannels(), Frame.DEPTH_SHORT);
        FrameProcessor processor = testCase.factory().get();
        processor.initialise(video);

        Frame frame = TestFrames.frame(video);

        Exception error = assertThrows(Exception.class,
                () -> processor.process(frame, TestFrames.info(0)));
        assertTrue(error.getMessage().contains("8-bit image frames"),
                () -> "Unexpected message: " + error.getMessage());
    }

    @ParameterizedTest(name = "{0} rejects too few channels")
    @MethodSource("processors")
    void tooFewChannelsIsRejected(ProcessorCase testCase) throws Exception {
        int channels = Math.max(0, testCase.minimumChannels() - 1);
        InfoVideo video = TestFrames.video(8, 8, channels);
        FrameProcessor processor = testCase.factory().get();
        processor.initialise(video);

        Frame frame = TestFrames.frame(video);

        Exception error = assertThrows(Exception.class,
                () -> processor.process(frame, TestFrames.info(0)));
        assertTrue(error.getMessage().contains("at least"),
                () -> "Unexpected message: " + error.getMessage());
    }

    @ParameterizedTest(name = "{0} accepts a valid frame")
    @MethodSource("processors")
    void validFrameIsAccepted(ProcessorCase testCase) throws Exception {
        InfoVideo video = TestFrames.video(8, 8, testCase.minimumChannels());
        FrameProcessor processor = testCase.factory().get();
        processor.initialise(video);

        Frame frame = TestFrames.frame(video);

        assertDoesNotThrow(() -> processor.process(frame, TestFrames.info(0)));
    }

    @ParameterizedTest(name = "{0} rejects a zero-channel frame")
    @MethodSource("processors")
    void zeroChannelsIsAlwaysRejected(ProcessorCase testCase) throws Exception {
        InfoVideo video = TestFrames.video(8, 8, 0);
        FrameProcessor processor = testCase.factory().get();
        processor.initialise(video);

        Frame frame = TestFrames.frame(video);
        frame.image = new Buffer[] { ByteBuffer.allocate(64) };

        assertThrows(Exception.class, () -> processor.process(frame, TestFrames.info(0)));
    }
}
