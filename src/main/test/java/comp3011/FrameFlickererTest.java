package comp3011;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.bytedeco.javacv.Frame;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FrameFlickererTest {
    private InfoVideo video;
    private FrameFlickerer processor;

    @BeforeEach
    void setUp() throws Exception {
        video = TestFrames.video("flicker.mp4", 300, 16, 16, 3);
        processor = new FrameFlickerer();
        processor.initialise(video);
    }

    @Test
    void sameFrameNumberProducesIdenticalOutput() throws Exception {
        Frame first = TestFrames.patterned(video, 11);
        Frame second = TestFrames.patterned(video, 11);

        processor.process(first, TestFrames.info(7));
        processor.process(second, TestFrames.info(7));

        assertArrayEquals(TestFrames.snapshot(first), TestFrames.snapshot(second));
    }

    @Test
    void flickerOnlyEverDarkensPixels() throws Exception {
        Frame frame = TestFrames.patterned(video, 5);
        byte[] before = TestFrames.snapshot(frame);

        processor.process(frame, TestFrames.info(42));

        byte[] after = TestFrames.snapshot(frame);
        for (int i = 0; i < before.length; i++) {
            assertTrue(Byte.toUnsignedInt(after[i]) <= Byte.toUnsignedInt(before[i]),
                    "pixel byte " + i + " brightened from "
                            + Byte.toUnsignedInt(before[i]) + " to " + Byte.toUnsignedInt(after[i]));
        }
    }

    @Test
    void differentFrameNumbersGenerallyProduceDifferentOutput() throws Exception {
        Set<String> outputs = new HashSet<>();
        for (int frameNumber = 0; frameNumber < 10; frameNumber++) {
            Frame frame = TestFrames.patterned(video, 0);
            processor.process(frame, TestFrames.info(frameNumber));
            outputs.add(java.util.Arrays.toString(TestFrames.snapshot(frame)));
        }

        assertTrue(outputs.size() > 1, "all ten frames were flickered identically");
    }

    @Test
    void blackFramesStayBlack() throws Exception {
        Frame frame = TestFrames.filled(video, 0);

        processor.process(frame, TestFrames.info(3));

        for (byte value : TestFrames.snapshot(frame)) {
            assertEquals(0, Byte.toUnsignedInt(value));
        }
    }

    @Test
    void rowPaddingIsLeftUntouched() throws Exception {
        InfoVideo stridedVideo = TestFrames.videoWithStride("flicker.mp4", 300, 5, 5, 3, 20);
        FrameFlickerer flickerer = new FrameFlickerer();
        flickerer.initialise(stridedVideo);
        Frame frame = TestFrames.filled(stridedVideo, 200);
        TestFrames.fillPadding(frame, stridedVideo, 0x33);
        byte[] before = TestFrames.padding(frame, stridedVideo);

        flickerer.process(frame, TestFrames.info(10));

        assertArrayEquals(before, TestFrames.padding(frame, stridedVideo));
    }
}
