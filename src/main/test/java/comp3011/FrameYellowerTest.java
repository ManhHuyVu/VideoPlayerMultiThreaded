package comp3011;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bytedeco.javacv.Frame;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FrameYellowerTest {
    private InfoVideo video;
    private FrameYellower processor;

    @BeforeEach
    void setUp() throws Exception {
        video = TestFrames.video(8, 8, 3);
        processor = new FrameYellower();
        processor.initialise(video);
    }

    @Test
    void neutralGreyGetsAWarmCast() throws Exception {
        Frame frame = TestFrames.bgr(video, 100, 100, 100, 0);

        processor.process(frame, TestFrames.info(0));

        int blue = TestFrames.get(frame, video, 0, 0, 0);
        int green = TestFrames.get(frame, video, 0, 0, 1);
        int red = TestFrames.get(frame, video, 0, 0, 2);

        assertEquals(100, red, "red is already at the destination white point");
        assertTrue(green < 100, "green should be slightly cooled");
        assertTrue(blue < green, "blue should be cooled the most");
    }

    @Test
    void whiteGetsProgressivelyWarmerAcrossTheChannels() throws Exception {
        Frame frame = TestFrames.filled(video, 255);

        processor.process(frame, TestFrames.info(0));

        int blue = TestFrames.get(frame, video, 0, 0, 0);
        int green = TestFrames.get(frame, video, 0, 0, 1);
        int red = TestFrames.get(frame, video, 0, 0, 2);

        assertEquals(255, red);
        assertTrue(red > green);
        assertTrue(green > blue);
    }

    @Test
    void blackFramesStayBlack() throws Exception {
        Frame frame = TestFrames.filled(video, 0);

        processor.process(frame, TestFrames.info(0));

        for (byte value : TestFrames.snapshot(frame)) {
            assertEquals(0, Byte.toUnsignedInt(value));
        }
    }

    @Test
    void alphaIsLeftUntouched() throws Exception {
        InfoVideo fourChannel = TestFrames.video(8, 8, 4);
        FrameYellower yellower = new FrameYellower();
        yellower.initialise(fourChannel);
        Frame frame = TestFrames.bgr(fourChannel, 100, 100, 100, 200);

        yellower.process(frame, TestFrames.info(0));

        assertEquals(200, TestFrames.get(frame, fourChannel, 0, 0, 3));
        assertTrue(TestFrames.get(frame, fourChannel, 0, 0, 0) < 100);
    }

    @Test
    void sameFrameProducesIdenticalOutput() throws Exception {
        Frame first = TestFrames.patterned(video, 9);
        Frame second = TestFrames.patterned(video, 9);

        processor.process(first, TestFrames.info(4));
        processor.process(second, TestFrames.info(4));

        assertArrayEquals(TestFrames.snapshot(first), TestFrames.snapshot(second));
    }

    @Test
    void rowPaddingIsLeftUntouched() throws Exception {
        InfoVideo strided = TestFrames.videoWithStride(8, 8, 3, 32);
        FrameYellower yellower = new FrameYellower();
        yellower.initialise(strided);
        Frame frame = TestFrames.filled(strided, 180);
        TestFrames.fillPadding(frame, strided, 0x44);
        byte[] before = TestFrames.padding(frame, strided);

        yellower.process(frame, TestFrames.info(0));

        assertArrayEquals(before, TestFrames.padding(frame, strided));
    }
}
