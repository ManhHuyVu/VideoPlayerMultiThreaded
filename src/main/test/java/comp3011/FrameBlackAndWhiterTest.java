package comp3011;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.bytedeco.javacv.Frame;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FrameBlackAndWhiterTest {
    private InfoVideo video;
    private FrameBlackAndWhiter processor;

    @BeforeEach
    void setUp() throws Exception {
        video = TestFrames.video(4, 4, 3);
        processor = new FrameBlackAndWhiter();
        processor.initialise(video);
    }

    private void assertPixel(Frame frame, int x, int y, int expected) {
        for (int channel = 0; channel < video.imageChannels(); channel++) {
            assertEquals(expected, TestFrames.get(frame, video, x, y, channel),
                    "channel " + channel + " at " + x + "," + y);
        }
    }

    @Test
    void pureRedConvertsToItsLuminance() throws Exception {
        Frame frame = TestFrames.bgr(video, 0, 0, 255, 0);

        processor.process(frame, TestFrames.info(0));

        assertPixel(frame, 0, 0, 53);
        assertPixel(frame, 3, 3, 53);
    }

    @Test
    void pureGreenConvertsToItsLuminance() throws Exception {
        Frame frame = TestFrames.bgr(video, 0, 255, 0, 0);

        processor.process(frame, TestFrames.info(0));

        assertPixel(frame, 1, 2, 182);
    }

    @Test
    void pureBlueConvertsToItsLuminance() throws Exception {
        Frame frame = TestFrames.bgr(video, 255, 0, 0, 0);

        processor.process(frame, TestFrames.info(0));

        assertPixel(frame, 2, 1, 18);
    }

    @Test
    void whiteAndBlackAreUnchanged() throws Exception {
        Frame white = TestFrames.filled(video, 255);
        Frame black = TestFrames.filled(video, 0);

        processor.process(white, TestFrames.info(0));
        processor.process(black, TestFrames.info(0));

        assertPixel(white, 0, 0, 255);
        assertPixel(black, 3, 3, 0);
    }

    @Test
    void neutralGreyIsUnchanged() throws Exception {
        Frame frame = TestFrames.bgr(video, 100, 100, 100, 0);

        processor.process(frame, TestFrames.info(0));

        assertPixel(frame, 0, 0, 100);
    }

    @Test
    void mixedColourIsWrittenToEveryChannel() throws Exception {
        Frame frame = TestFrames.bgr(video, 10, 20, 30, 0);

        processor.process(frame, TestFrames.info(0));

        int expected = (54 * 30 + 183 * 20 + 19 * 10) / 256;
        assertPixel(frame, 2, 2, expected);
    }

    @Test
    void runningTwiceIsIdempotent() throws Exception {
        Frame frame = TestFrames.patterned(video, 3);

        processor.process(frame, TestFrames.info(0));
        byte[] afterFirst = TestFrames.snapshot(frame);
        processor.process(frame, TestFrames.info(0));

        assertArrayEquals(afterFirst, TestFrames.snapshot(frame));
    }

    @Test
    void fourChannelFramesAlsoDesaturateAlpha() throws Exception {
        InfoVideo fourChannel = TestFrames.video(4, 4, 4);
        FrameBlackAndWhiter greyscale = new FrameBlackAndWhiter();
        greyscale.initialise(fourChannel);
        Frame frame = TestFrames.bgr(fourChannel, 0, 0, 255, 17);

        greyscale.process(frame, TestFrames.info(0));

        for (int channel = 0; channel < 4; channel++) {
            assertEquals(53, TestFrames.get(frame, fourChannel, 0, 0, channel));
        }
    }

    @Test
    void rowPaddingIsLeftUntouched() throws Exception {
        InfoVideo strided = TestFrames.videoWithStride(4, 4, 3, 16);
        FrameBlackAndWhiter greyscale = new FrameBlackAndWhiter();
        greyscale.initialise(strided);
        Frame frame = TestFrames.filled(strided, 255);
        TestFrames.fillPadding(frame, strided, 0x5A);
        byte[] before = TestFrames.padding(frame, strided);

        greyscale.process(frame, TestFrames.info(0));

        assertArrayEquals(before, TestFrames.padding(frame, strided));
    }
}
