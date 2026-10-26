package comp3011;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bytedeco.javacv.Frame;
import org.junit.jupiter.api.Test;

class FrameScratcherTest {

    private InfoVideo video(int totalFrames, int width, int height) {
        return TestFrames.video("scratches.mp4", totalFrames, width, height, 3);
    }

    private FrameScratcher create(InfoVideo video) throws Exception {
        FrameScratcher scratcher = new FrameScratcher();
        scratcher.initialise(video);
        return scratcher;
    }

    @Test
    void noVideoFramesMeansNoScratches() throws Exception {
        InfoVideo video = video(0, 100, 100);
        FrameScratcher scratcher = create(video);
        Frame frame = TestFrames.filled(video, 255);

        scratcher.process(frame, TestFrames.info(0));

        for (int y = 0; y < video.imageHeight(); y++) {
            for (int x = 0; x < video.imageWidth(); x++) {
                assertEquals(255, TestFrames.get(frame, video, x, y, 0));
            }
        }
    }

    @Test
    void scratchesAreAlwaysBlackAndSomeFramesAreAffected() throws Exception {
        InfoVideo video = video(5_000, 64, 64);
        FrameScratcher scratcher = create(video);
        int changedFrames = 0;

        for (int frameNumber = 0; frameNumber < 200; frameNumber++) {
            Frame frame = TestFrames.filled(video, 255);
            scratcher.process(frame, TestFrames.info(frameNumber));

            boolean changed = false;
            for (byte value : TestFrames.snapshot(frame)) {
                int unsigned = Byte.toUnsignedInt(value);
                assertTrue(unsigned == 0 || unsigned == 255,
                        "scratch drawing produced a non-black value " + unsigned);
                if (unsigned == 0) {
                    changed = true;
                }
            }
            if (changed) {
                changedFrames++;
            }
        }

        assertTrue(changedFrames > 0, "no scratch was drawn in 200 frames");
    }

    @Test
    void sameFrameProducesIdenticalOutput() throws Exception {
        InfoVideo video = video(2_000, 80, 60);
        FrameScratcher scratcher = create(video);
        Frame first = TestFrames.filled(video, 255);
        Frame second = TestFrames.filled(video, 255);

        scratcher.process(first, TestFrames.info(123));
        scratcher.process(second, TestFrames.info(123));

        assertArrayEquals(TestFrames.snapshot(first), TestFrames.snapshot(second));
    }

    @Test
    void rowPaddingIsLeftUntouched() throws Exception {
        InfoVideo video = TestFrames.videoWithStride("scratches.mp4", 5_000, 60, 50, 3, 200);
        FrameScratcher scratcher = create(video);

        for (int frameNumber = 0; frameNumber < 100; frameNumber++) {
            Frame frame = TestFrames.filled(video, 255);
            TestFrames.fillPadding(frame, video, 0x66);
            byte[] before = TestFrames.padding(frame, video);

            scratcher.process(frame, TestFrames.info(frameNumber));

            assertArrayEquals(before, TestFrames.padding(frame, video), "frame " + frameNumber);
        }
    }
}
