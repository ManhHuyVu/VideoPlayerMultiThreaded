package comp3011;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.bytedeco.javacv.Frame;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FramePeppererTest {
    private InfoVideo video;
    private FramePepperer processor;

    @BeforeEach
    void setUp() throws Exception {
        video = TestFrames.video("pepper.mp4", 300, 64, 64, 3);
        processor = new FramePepperer();
        processor.initialise(video);
    }

    @Test
    void sameFrameProducesIdenticalOutput() throws Exception {
        Frame first = TestFrames.patterned(video, 31);
        Frame second = TestFrames.patterned(video, 31);

        processor.process(first, TestFrames.info(23));
        processor.process(second, TestFrames.info(23));

        assertArrayEquals(TestFrames.snapshot(first), TestFrames.snapshot(second));
    }

    @Test
    void pepperOnlyDarkensAndAppearsOnSomeFrames() throws Exception {
        int changedFrames = 0;

        for (int frameNumber = 0; frameNumber < 200; frameNumber++) {
            Frame frame = TestFrames.patterned(video, frameNumber);
            byte[] before = TestFrames.snapshot(frame);

            processor.process(frame, TestFrames.info(frameNumber));

            byte[] after = TestFrames.snapshot(frame);
            for (int i = 0; i < before.length; i++) {
                assertTrue(Byte.toUnsignedInt(after[i]) <= Byte.toUnsignedInt(before[i]),
                        "pixel byte " + i + " brightened");
            }
            if (!Arrays.equals(before, after)) {
                changedFrames++;
            }
        }

        assertTrue(changedFrames > 0, "pepper never changed any of 200 frames");
    }

    @Test
    void zeroFrameRateFallsBackToTheIntegerRate() throws Exception {
        InfoVideo silent = new InfoVideo(
                "pepper-silent.mp4", 300, 48, 48, Frame.DEPTH_UBYTE, 3, 144, 0.0, 0, 0, 0);
        FramePepperer fallback = new FramePepperer();
        fallback.initialise(silent);
        Frame frame = TestFrames.patterned(silent, 4);

        assertDoesNotThrow(() -> fallback.process(frame, TestFrames.info(3)));
    }

    @Test
    void zeroDimensionsAreIgnored() throws Exception {
        InfoVideo empty = TestFrames.video("pepper-empty.mp4", 300, 0, 0, 3);
        FramePepperer fallback = new FramePepperer();
        fallback.initialise(empty);
        Frame frame = TestFrames.frame(empty);

        assertDoesNotThrow(() -> fallback.process(frame, TestFrames.info(0)));
    }

    @Test
    void singleChannelFramesAreSupported() throws Exception {
        InfoVideo oneChannel = TestFrames.video("pepper-mono.mp4", 300, 48, 48, 1);
        FramePepperer pepperer = new FramePepperer();
        pepperer.initialise(oneChannel);
        Frame frame = TestFrames.filled(oneChannel, 255);

        pepperer.process(frame, TestFrames.info(0));

        assertEquals(48 * 48, TestFrames.snapshot(frame).length);
    }

    @Test
    void rowPaddingIsLeftUntouched() throws Exception {
        InfoVideo strided = TestFrames.videoWithStride("pepper.mp4", 300, 48, 48, 3, 200);
        FramePepperer pepperer = new FramePepperer();
        pepperer.initialise(strided);

        for (int frameNumber = 0; frameNumber < 50; frameNumber++) {
            Frame frame = TestFrames.patterned(strided, frameNumber);
            TestFrames.fillPadding(frame, strided, 0x88);
            byte[] before = TestFrames.padding(frame, strided);

            pepperer.process(frame, TestFrames.info(frameNumber));

            assertArrayEquals(before, TestFrames.padding(frame, strided), "frame " + frameNumber);
        }
    }
}
