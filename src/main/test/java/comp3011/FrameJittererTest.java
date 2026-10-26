package comp3011;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.bytedeco.javacv.Frame;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FrameJittererTest {
    private InfoVideo video;
    private FrameJitterer processor;

    @BeforeEach
    void setUp() throws Exception {
        video = TestFrames.video("jitter.mp4", 300, 40, 30, 3);
        processor = new FrameJitterer();
        processor.initialise(video);
    }

    @Test
    void sameFrameProducesIdenticalOutput() throws Exception {
        Frame first = TestFrames.patterned(video, 13);
        Frame second = TestFrames.patterned(video, 13);

        processor.process(first, TestFrames.info(37));
        processor.process(second, TestFrames.info(37));

        assertArrayEquals(TestFrames.snapshot(first), TestFrames.snapshot(second));
    }

    @Test
    void whiteFramesOnlyProduceBlackOrWhiteAfterJitter() throws Exception {
        for (int frameNumber = 0; frameNumber < 200; frameNumber++) {
            Frame frame = TestFrames.filled(video, 255);
            processor.process(frame, TestFrames.info(frameNumber));

            for (byte value : TestFrames.snapshot(frame)) {
                int unsigned = Byte.toUnsignedInt(value);
                assertTrue(unsigned == 0 || unsigned == 255,
                        "jitter produced an unexpected value " + unsigned + " on frame " + frameNumber);
            }
        }
    }

    @Test
    void someFramesAreShiftedAndOthersAreUntouched() throws Exception {
        int changedFrames = 0;
        int unchangedFrames = 0;

        for (int frameNumber = 0; frameNumber < 100; frameNumber++) {
            Frame frame = TestFrames.filled(video, 200);
            byte[] before = TestFrames.snapshot(frame);

            processor.process(frame, TestFrames.info(frameNumber));

            if (Arrays.equals(before, TestFrames.snapshot(frame))) {
                unchangedFrames++;
            } else {
                changedFrames++;
            }
        }

        assertTrue(changedFrames > 0, "jitter never displaced a frame");
        assertTrue(unchangedFrames > 0, "jitter displaced every single frame");
    }

    @Test
    void aShiftedFrameLeavesABlackRowAtTheExposedEdge() throws Exception {
        for (int frameNumber = 0; frameNumber < 100; frameNumber++) {
            Frame frame = TestFrames.filled(video, 255);
            byte[] before = TestFrames.snapshot(frame);
            processor.process(frame, TestFrames.info(frameNumber));

            if (Arrays.equals(before, TestFrames.snapshot(frame))) {
                continue;
            }

            boolean hasBlack = false;
            for (byte value : TestFrames.snapshot(frame)) {
                if (Byte.toUnsignedInt(value) == 0) {
                    hasBlack = true;
                    break;
                }
            }
            assertTrue(hasBlack, "displaced frame had no exposed background");
            return;
        }

        throw new AssertionError("no frame was displaced in 100 attempts");
    }

    @Test
    void singleChannelFramesAreSupported() throws Exception {
        InfoVideo oneChannel = TestFrames.video("jitter-mono.mp4", 300, 20, 20, 1);
        FrameJitterer jitterer = new FrameJitterer();
        jitterer.initialise(oneChannel);
        Frame frame = TestFrames.filled(oneChannel, 255);

        jitterer.process(frame, TestFrames.info(0));

        assertEquals(20 * 20, TestFrames.snapshot(frame).length);
    }

    @Test
    void rowPaddingIsLeftUntouched() throws Exception {
        InfoVideo strided = TestFrames.videoWithStride("jitter.mp4", 300, 20, 20, 3, 80);
        FrameJitterer jitterer = new FrameJitterer();
        jitterer.initialise(strided);

        for (int frameNumber = 0; frameNumber < 100; frameNumber++) {
            Frame frame = TestFrames.patterned(strided, frameNumber);
            TestFrames.fillPadding(frame, strided, 0x55);
            byte[] before = TestFrames.padding(frame, strided);

            jitterer.process(frame, TestFrames.info(frameNumber));

            assertArrayEquals(before, TestFrames.padding(frame, strided), "frame " + frameNumber);
        }
    }
}
