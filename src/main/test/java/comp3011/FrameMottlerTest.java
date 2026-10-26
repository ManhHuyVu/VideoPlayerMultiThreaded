package comp3011;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.bytedeco.javacv.Frame;
import org.junit.jupiter.api.Test;

class FrameMottlerTest {

    private FrameMottler create(InfoVideo video) throws Exception {
        FrameMottler mottler = new FrameMottler();
        mottler.initialise(video);
        return mottler;
    }

    @Test
    void sameFrameProducesIdenticalOutput() throws Exception {
        InfoVideo video = TestFrames.video("mottle.mp4", 300, 64, 64, 3);
        FrameMottler mottler = create(video);
        Frame first = TestFrames.patterned(video, 15);
        Frame second = TestFrames.patterned(video, 15);

        mottler.process(first, TestFrames.info(19));
        mottler.process(second, TestFrames.info(19));

        assertArrayEquals(TestFrames.snapshot(first), TestFrames.snapshot(second));
    }

    @Test
    void someFramesAreMottled() throws Exception {
        InfoVideo video = TestFrames.video("mottle.mp4", 300, 64, 64, 3);
        FrameMottler mottler = create(video);
        int changedFrames = 0;

        for (int frameNumber = 0; frameNumber < 120; frameNumber++) {
            Frame frame = TestFrames.patterned(video, frameNumber);
            byte[] before = TestFrames.snapshot(frame);

            mottler.process(frame, TestFrames.info(frameNumber));

            if (!Arrays.equals(before, TestFrames.snapshot(frame))) {
                changedFrames++;
            }
        }

        assertTrue(changedFrames > 0, "mottling never changed any of 120 frames");
    }

    @Test
    void alphaIsLeftUntouched() throws Exception {
        InfoVideo video = TestFrames.video("mottle-rgba.mp4", 300, 32, 32, 4);
        FrameMottler mottler = create(video);
        Frame frame = TestFrames.bgr(video, 120, 120, 120, 200);

        mottler.process(frame, TestFrames.info(0));

        for (int y = 0; y < video.imageHeight(); y++) {
            for (int x = 0; x < video.imageWidth(); x++) {
                assertEquals(200, TestFrames.get(frame, video, x, y, 3));
            }
        }
    }

    @Test
    void zeroDimensionsAreIgnored() throws Exception {
        InfoVideo empty = TestFrames.video("mottle-empty.mp4", 300, 0, 0, 3);
        FrameMottler mottler = create(empty);
        Frame frame = TestFrames.frame(empty);

        assertDoesNotThrow(() -> mottler.process(frame, TestFrames.info(0)));
    }

    @Test
    void rowPaddingIsLeftUntouched() throws Exception {
        InfoVideo video = TestFrames.videoWithStride("mottle.mp4", 300, 48, 48, 3, 200);
        FrameMottler mottler = create(video);

        for (int frameNumber = 0; frameNumber < 30; frameNumber++) {
            Frame frame = TestFrames.patterned(video, frameNumber);
            TestFrames.fillPadding(frame, video, 0x99);
            byte[] before = TestFrames.padding(frame, video);

            mottler.process(frame, TestFrames.info(frameNumber));

            assertArrayEquals(before, TestFrames.padding(frame, video), "frame " + frameNumber);
        }
    }
}
