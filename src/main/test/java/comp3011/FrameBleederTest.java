package comp3011;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bytedeco.javacv.Frame;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FrameBleederTest {
    private InfoVideo video;
    private FrameBleeder processor;

    @BeforeEach
    void setUp() throws Exception {
        video = TestFrames.video(32, 32, 3);
        processor = new FrameBleeder();
        processor.initialise(video);
    }

    @Test
    void blackFramesAreUnchanged() throws Exception {
        Frame frame = TestFrames.filled(video, 0);

        processor.process(frame, TestFrames.info(0));

        for (byte value : TestFrames.snapshot(frame)) {
            assertEquals(0, Byte.toUnsignedInt(value));
        }
    }

    @Test
    void pixelsBelowTheBrightnessThresholdAreUnchanged() throws Exception {
        Frame frame = TestFrames.filled(video, 100);

        processor.process(frame, TestFrames.info(0));

        for (byte value : TestFrames.snapshot(frame)) {
            assertEquals(100, Byte.toUnsignedInt(value));
        }
    }

    @Test
    void brightAreasGetAWarmBleedWithRedStrongest() throws Exception {
        Frame frame = TestFrames.filled(video, 160);
        int before = 160;

        processor.process(frame, TestFrames.info(0));

        int blue = TestFrames.get(frame, video, 10, 10, 0);
        int green = TestFrames.get(frame, video, 10, 10, 1);
        int red = TestFrames.get(frame, video, 10, 10, 2);

        int blueBoost = blue - before;
        int greenBoost = green - before;
        int redBoost = red - before;

        assertTrue(redBoost > greenBoost, "red bleed should be strongest");
        assertTrue(greenBoost > blueBoost, "green bleed should be stronger than blue");
        assertTrue(blueBoost > 0, "blue should still receive a small bleed");
    }

    @Test
    void brightRegionsBleedIntoTheirNeighbours() throws Exception {
        InfoVideo large = TestFrames.video(64, 64, 3);
        FrameBleeder bleeder = new FrameBleeder();
        bleeder.initialise(large);
        Frame frame = TestFrames.filled(large, 0);
        for (int y = 16; y < 32; y++) {
            for (int x = 16; x < 32; x++) {
                TestFrames.set(frame, large, x, y, 0, 255);
                TestFrames.set(frame, large, x, y, 1, 255);
                TestFrames.set(frame, large, x, y, 2, 255);
            }
        }

        bleeder.process(frame, TestFrames.info(0));

        boolean bled = false;
        for (int y = 0; y < large.imageHeight() && !bled; y++) {
            for (int x = 0; x < large.imageWidth(); x++) {
                boolean insideBrightBlock = x >= 16 && x < 32 && y >= 16 && y < 32;
                if (!insideBrightBlock && TestFrames.get(frame, large, x, y, 2) > 0) {
                    bled = true;
                    break;
                }
            }
        }

        assertTrue(bled, "bright block did not bleed into any neighbouring pixel");
        assertEquals(0, TestFrames.get(frame, large, 0, 0, 2), "far corner should stay dark");
    }

    @Test
    void sameFrameProducesIdenticalOutput() throws Exception {
        Frame first = TestFrames.patterned(video, 8);
        Frame second = TestFrames.patterned(video, 8);

        processor.process(first, TestFrames.info(2));
        processor.process(second, TestFrames.info(2));

        assertArrayEquals(TestFrames.snapshot(first), TestFrames.snapshot(second));
    }

    @Test
    void rowPaddingIsLeftUntouched() throws Exception {
        InfoVideo strided = TestFrames.videoWithStride(24, 24, 3, 96);
        FrameBleeder bleeder = new FrameBleeder();
        bleeder.initialise(strided);
        Frame frame = TestFrames.filled(strided, 200);
        TestFrames.fillPadding(frame, strided, 0x19);
        byte[] before = TestFrames.padding(frame, strided);

        bleeder.process(frame, TestFrames.info(0));

        assertArrayEquals(before, TestFrames.padding(frame, strided));
    }
}
