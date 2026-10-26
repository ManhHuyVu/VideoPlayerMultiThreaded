package comp3011;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.bytedeco.javacv.Frame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FramePipelineTest {
    private final List<Boolean> audioStates = new ArrayList<>();
    private FramePipeline pipeline;

    @BeforeEach
    void setUp() {
        audioStates.clear();
        pipeline = newPipeline();
    }

    @AfterEach
    void tearDown() {
        pipeline.shutdown();
    }

    private FramePipeline newPipeline() {
        return new FramePipeline(
                List.of(),
                false,
                (width, height) -> { },
                image -> { },
                status -> { },
                (running, paused) -> { },
                audioStates::add);
    }

    @Test
    void startsIdleWithNoMetrics() {
        assertEquals(0, pipeline.getFramesProcessed());
        assertEquals(0, pipeline.getFramesDropped());
        assertEquals(0.0, pipeline.getDropRate());
        assertTrue(pipeline.isAudioOutputEnabled());
    }

    @Test
    void navigationWithoutAVideoIsANoOp() {
        assertDoesNotThrow(() -> pipeline.play());
        assertDoesNotThrow(() -> pipeline.startOver());
        assertDoesNotThrow(() -> pipeline.backFiveSeconds());
        assertDoesNotThrow(() -> pipeline.forwardFiveSeconds());
        assertDoesNotThrow(() -> pipeline.togglePause());
        assertEquals(0, pipeline.getFramesProcessed());
    }

    @Test
    void stopIsIdempotent() {
        assertDoesNotThrow(() -> {
            pipeline.stop();
            pipeline.stop();
            pipeline.shutdown();
        });
    }

    @Test
    void closeShutsThePipelineDown() {
        FramePipeline other = newPipeline();

        assertDoesNotThrow(() -> {
            other.close();
            other.close();
        });
    }

    @Test
    void decoderPipelineFrameCarriesSubmissionMetadata() {
        InfoVideo video = TestFrames.video(4, 4, 3);
        Frame raw = TestFrames.frame(video);
        InfoFrame info = TestFrames.info(11);

        FramePipeline.PipelineFrame frame = new FramePipeline.PipelineFrame(raw, 11, 500L, info, 3L);

        assertSame(raw, frame.frame());
        assertSame(info, frame.info());
        assertEquals(11, frame.frameNumber());
        assertEquals(500L, frame.timestampUs());
        assertEquals(3L, frame.seekGeneration());
        assertNull(frame.image());
        assertEquals(0L, frame.logicalTimestampUs());
        assertEquals(0L, frame.targetTimeNs());
        assertEquals(0L, frame.preparedAtNs());
    }

    @Test
    void processorPipelineFrameCarriesDisplayMetadata() {
        FramePipeline.PipelineFrame frame =
                new FramePipeline.PipelineFrame(null, 7, 100L, 250L, 1_000L, 900L);

        assertNull(frame.frame());
        assertNull(frame.image());
        assertNull(frame.info());
        assertEquals(7, frame.frameNumber());
        assertEquals(100L, frame.timestampUs());
        assertEquals(250L, frame.logicalTimestampUs());
        assertEquals(1_000L, frame.targetTimeNs());
        assertEquals(900L, frame.preparedAtNs());
        assertEquals(0L, frame.seekGeneration());
    }

    @Test
    void pendingAudioTracksPartialWrites() {
        byte[] samples = { 1, 2, 3, 4 };
        FramePipeline.PendingAudio audio = new FramePipeline.PendingAudio(1_234L, samples);

        assertEquals(1_234L, audio.timestampUs());
        assertSame(samples, audio.samples());
        assertEquals(0, audio.offset());
        assertEquals(4, audio.remaining());
        assertFalse(audio.finished());

        audio.advance(3);

        assertEquals(3, audio.offset());
        assertEquals(1, audio.remaining());
        assertFalse(audio.finished());

        audio.advance(1);

        assertEquals(4, audio.offset());
        assertEquals(0, audio.remaining());
        assertTrue(audio.finished());
    }

    @Test
    void pendingAudioReportsFinishedWhenAdvancedPastTheEnd() {
        FramePipeline.PendingAudio audio = new FramePipeline.PendingAudio(0L, new byte[] { 1, 2 });

        audio.advance(10);

        assertTrue(audio.finished());
        assertEquals(-8, audio.remaining());
    }

    @Test
    void pendingAudioWithNoSamplesIsAlreadyFinished() {
        FramePipeline.PendingAudio audio = new FramePipeline.PendingAudio(0L, new byte[0]);

        assertTrue(audio.finished());
        assertEquals(0, audio.remaining());
    }
}
