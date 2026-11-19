package comp3011;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.Arrays;
import java.util.List;

import org.bytedeco.javacv.Frame;
import org.junit.jupiter.api.Test;

class SubtitleFrameProcessorTest {

    @Test
    void subtitlesRenderWhenEnabledAndCoverFrame() throws Exception {
        InfoVideo video = TestFrames.video(100, 100, 3);
        SubtitleFrameProcessor processor = new SubtitleFrameProcessor();
        processor.initialise(video);

        SubtitleService original = SubtitleService.get();
        try {
            SubtitleService service = new SubtitleService(19876); // unstarted: no server bound, no network
            service.setEnabled(true);
            service.cache.put("test.mp4", List.of(new SubtitleSegment(0, 1_000_000, "Hello world")));
            SubtitleService.set(service);

            Frame frame = TestFrames.filled(video, 0);
            byte[] before = TestFrames.snapshot(frame);
            processor.process(frame, TestFrames.info(0)); // timestampUs == 0, inside the segment
            assertNotEquals(Arrays.toString(before), Arrays.toString(TestFrames.snapshot(frame)),
                    "enabled subtitles covering the frame should modify it");
        } finally {
            SubtitleService.set(original);
        }
    }

    @Test
    void subtitlesDoNotRenderWhenDisabled() throws Exception {
        InfoVideo video = TestFrames.video(100, 100, 3);
        SubtitleFrameProcessor processor = new SubtitleFrameProcessor();
        processor.initialise(video);

        SubtitleService original = SubtitleService.get();
        try {
            SubtitleService service = new SubtitleService(19876);
            service.setEnabled(false);
            service.cache.put("test.mp4", List.of(new SubtitleSegment(0, 1_000_000, "Hello world")));
            SubtitleService.set(service);

            Frame frame = TestFrames.filled(video, 0);
            byte[] before = TestFrames.snapshot(frame);
            processor.process(frame, TestFrames.info(0));
            assertEquals(Arrays.toString(before), Arrays.toString(TestFrames.snapshot(frame)),
                    "disabled subtitles should not modify the frame");
        } finally {
            SubtitleService.set(original);
        }
    }

    @Test
    void subtitlesDoNotRenderOutsideSegmentWindow() throws Exception {
        InfoVideo video = TestFrames.video(100, 100, 3);
        SubtitleFrameProcessor processor = new SubtitleFrameProcessor();
        processor.initialise(video);

        SubtitleService original = SubtitleService.get();
        try {
            SubtitleService service = new SubtitleService(19876);
            service.setEnabled(true);
            // Segment covers media time 0..1s only; frame at 5s is outside.
            service.cache.put("test.mp4", List.of(new SubtitleSegment(0, 1_000_000, "Hello world")));
            SubtitleService.set(service);

            Frame frame = TestFrames.filled(video, 0);
            byte[] before = TestFrames.snapshot(frame);
            processor.process(frame, TestFrames.info(120)); // timestampUs = 120 * 41666 = 5s, outside
            assertEquals(Arrays.toString(before), Arrays.toString(TestFrames.snapshot(frame)),
                    "subtitles outside their time window should not modify the frame");
        } finally {
            SubtitleService.set(original);
        }
    }

    @Test
    void defaultServiceRendersNothing() throws Exception {
        InfoVideo video = TestFrames.video(100, 100, 3);
        SubtitleFrameProcessor processor = new SubtitleFrameProcessor();
        processor.initialise(video);

        // No subtitle server configured: get() returns the no-op default.
        Frame frame = TestFrames.filled(video, 0);
        byte[] before = TestFrames.snapshot(frame);
        processor.process(frame, TestFrames.info(0));
        assertEquals(Arrays.toString(before), Arrays.toString(TestFrames.snapshot(frame)),
                "no subtitle service should leave the frame untouched");
    }
}
