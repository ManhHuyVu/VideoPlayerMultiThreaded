package comp3011;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bytedeco.javacv.Frame;
import org.junit.jupiter.api.Test;

class FrameVignetterTest {

    private FrameVignetter create(InfoVideo video) throws Exception {
        FrameVignetter vignetter = new FrameVignetter();
        vignetter.initialise(video);
        return vignetter;
    }

    @Test
    void centreStaysBrightWhileCornersFade() throws Exception {
        InfoVideo video = TestFrames.video(101, 101, 3);
        FrameVignetter vignetter = create(video);
        Frame frame = TestFrames.filled(video, 255);

        vignetter.process(frame, TestFrames.info(0));

        assertEquals(255, TestFrames.get(frame, video, 50, 50, 0));
        assertEquals(255, TestFrames.get(frame, video, 50, 50, 1));
        assertEquals(255, TestFrames.get(frame, video, 50, 50, 2));

        int topLeft = TestFrames.get(frame, video, 0, 0, 1);
        assertTrue(topLeft < 255);
        assertEquals(topLeft, TestFrames.get(frame, video, 100, 0, 1));
        assertEquals(topLeft, TestFrames.get(frame, video, 0, 100, 1));
        assertEquals(topLeft, TestFrames.get(frame, video, 100, 100, 1));
    }

    @Test
    void edgeFadeIsSymmetricInBothAxes() throws Exception {
        InfoVideo video = TestFrames.video(64, 64, 3);
        FrameVignetter vignetter = create(video);
        Frame frame = TestFrames.filled(video, 255);

        vignetter.process(frame, TestFrames.info(0));

        for (int y = 0; y < video.imageHeight(); y++) {
            for (int x = 0; x < video.imageWidth() / 2; x++) {
                for (int channel = 0; channel < 3; channel++) {
                    assertEquals(
                            TestFrames.get(frame, video, x, y, channel),
                            TestFrames.get(frame, video, video.imageWidth() - 1 - x, y, channel));
                }
            }
        }

        for (int y = 0; y < video.imageHeight() / 2; y++) {
            for (int x = 0; x < video.imageWidth(); x++) {
                for (int channel = 0; channel < 3; channel++) {
                    assertEquals(
                            TestFrames.get(frame, video, x, y, channel),
                            TestFrames.get(frame, video, x, video.imageHeight() - 1 - y, channel));
                }
            }
        }
    }

    @Test
    void singlePixelFrameIsUnchanged() throws Exception {
        InfoVideo video = TestFrames.video(1, 1, 3);
        FrameVignetter vignetter = create(video);
        Frame frame = TestFrames.filled(video, 200);

        vignetter.process(frame, TestFrames.info(0));

        assertEquals(200, TestFrames.get(frame, video, 0, 0, 0));
    }

    @Test
    void sameFrameProducesIdenticalOutput() throws Exception {
        InfoVideo video = TestFrames.video(32, 24, 3);
        FrameVignetter vignetter = create(video);
        Frame first = TestFrames.patterned(video, 6);
        Frame second = TestFrames.patterned(video, 6);

        vignetter.process(first, TestFrames.info(1));
        vignetter.process(second, TestFrames.info(1));

        assertArrayEquals(TestFrames.snapshot(first), TestFrames.snapshot(second));
    }

    @Test
    void rowPaddingIsLeftUntouched() throws Exception {
        InfoVideo video = TestFrames.videoWithStride(16, 16, 3, 56);
        FrameVignetter vignetter = create(video);
        Frame frame = TestFrames.filled(video, 220);
        TestFrames.fillPadding(frame, video, 0x11);
        byte[] before = TestFrames.padding(frame, video);

        vignetter.process(frame, TestFrames.info(0));

        assertArrayEquals(before, TestFrames.padding(frame, video));
    }
}
