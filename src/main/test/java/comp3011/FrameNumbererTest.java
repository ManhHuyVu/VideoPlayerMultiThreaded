package comp3011;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.bytedeco.javacv.Frame;
import org.junit.jupiter.api.Test;

class FrameNumbererTest {

    private FrameNumberer create(InfoVideo video) throws Exception {
        FrameNumberer numberer = new FrameNumberer();
        numberer.initialise(video);
        return numberer;
    }

    @Test
    void rendersZeroInTheBottomRightTile() throws Exception {
        InfoVideo video = TestFrames.video(20, 20, 3);
        FrameNumberer numberer = create(video);
        Frame frame = TestFrames.filled(video, 77);

        numberer.process(frame, TestFrames.info(0));

        // Tile origin: width - 1 - 5 = 14, height - 1 - 7 = 12.
        assertEquals(0, TestFrames.get(frame, video, 14, 12, 0), "tile background cleared");
        assertEquals(255, TestFrames.get(frame, video, 15, 13, 0), "top segment");
        assertEquals(255, TestFrames.get(frame, video, 16, 13, 1));
        assertEquals(255, TestFrames.get(frame, video, 17, 13, 2));
        assertEquals(255, TestFrames.get(frame, video, 15, 17, 0), "bottom segment");
        assertEquals(0, TestFrames.get(frame, video, 18, 18, 0), "tile bottom-right corner");
    }

    @Test
    void negativeFrameNumbersAreClampedToZero() throws Exception {
        InfoVideo video = TestFrames.video(20, 20, 3);
        FrameNumberer numberer = create(video);
        Frame negative = TestFrames.filled(video, 40);
        Frame zero = TestFrames.filled(video, 40);

        numberer.process(negative, TestFrames.info(-5));
        numberer.process(zero, TestFrames.info(0));

        assertArrayEquals(TestFrames.snapshot(zero), TestFrames.snapshot(negative));
    }

    @Test
    void framesTooSmallForTheGlyphAreUnchanged() throws Exception {
        InfoVideo video = TestFrames.video(4, 4, 3);
        FrameNumberer numberer = create(video);
        Frame frame = TestFrames.filled(video, 77);

        numberer.process(frame, TestFrames.info(0));

        for (byte value : TestFrames.snapshot(frame)) {
            assertEquals(77, Byte.toUnsignedInt(value));
        }
    }

    @Test
    void multiDigitFrameNumbersRenderMoreThanOneTile() throws Exception {
        InfoVideo video = TestFrames.video(30, 20, 3);
        FrameNumberer numberer = create(video);
        Frame one = TestFrames.filled(video, 0);
        Frame twelve = TestFrames.filled(video, 0);

        numberer.process(one, TestFrames.info(1));
        numberer.process(twelve, TestFrames.info(12));

        assertNotEquals(
                java.util.Arrays.toString(TestFrames.snapshot(one)),
                java.util.Arrays.toString(TestFrames.snapshot(twelve)));
    }

    @Test
    void singleChannelFramesAreSupported() throws Exception {
        InfoVideo video = TestFrames.video(8, 8, 1);
        FrameNumberer numberer = create(video);
        Frame frame = TestFrames.filled(video, 0);

        numberer.process(frame, TestFrames.info(0));

        assertEquals(255, TestFrames.get(frame, video, 3, 1, 0));
    }

    @Test
    void sameFrameProducesIdenticalOutput() throws Exception {
        InfoVideo video = TestFrames.video(30, 20, 3);
        FrameNumberer numberer = create(video);
        Frame first = TestFrames.patterned(video, 2);
        Frame second = TestFrames.patterned(video, 2);

        numberer.process(first, TestFrames.info(9));
        numberer.process(second, TestFrames.info(9));

        assertArrayEquals(TestFrames.snapshot(first), TestFrames.snapshot(second));
    }

    @Test
    void rowPaddingIsLeftUntouched() throws Exception {
        InfoVideo video = TestFrames.videoWithStride(20, 20, 3, 80);
        FrameNumberer numberer = create(video);
        Frame frame = TestFrames.filled(video, 200);
        TestFrames.fillPadding(frame, video, 0x22);
        byte[] before = TestFrames.padding(frame, video);

        numberer.process(frame, TestFrames.info(5));

        assertArrayEquals(before, TestFrames.padding(frame, video));
    }

    @Test
    void drawingIsConfinedToTheGlyphRegion() throws Exception {
        InfoVideo video = TestFrames.video(40, 30, 3);
        FrameNumberer numberer = create(video);
        Frame frame = TestFrames.filled(video, 10);

        numberer.process(frame, TestFrames.info(7));

        assertEquals(10, TestFrames.get(frame, video, 0, 0, 0), "top-left untouched");
        assertEquals(10, TestFrames.get(frame, video, 39, 0, 0), "top-right untouched");
        assertEquals(10, TestFrames.get(frame, video, 0, 29, 0), "bottom-left untouched");
    }
}
