package comp3011;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.nio.FloatBuffer;
import java.util.List;

import org.bytedeco.javacv.Frame;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * Executable documentation for defects found while building the suite.
 *
 * <p>Each test is disabled because it fails against the current implementation.
 * They spell out the intended behaviour so the fixes can be verified by simply
 * removing {@link Disabled}.</p>
 */
class KnownIssuesTest {

    private static Frame stereoPlanarFloats() {
        Frame frame = new Frame();
        frame.audioChannels = 2;
        frame.samples = new FloatBuffer[] {
                FloatBuffer.wrap(new float[] { 0.0f, 0.5f }),
                FloatBuffer.wrap(new float[] { 1.0f, -1.0f })
        };
        return frame;
    }

    private static byte[] littleEndianPcm(short... samples) {
        byte[] bytes = new byte[samples.length * 2];
        for (int i = 0; i < samples.length; i++) {
            bytes[i * 2] = (byte) (samples[i] & 0xFF);
            bytes[i * 2 + 1] = (byte) ((samples[i] >> 8) & 0xFF);
        }
        return bytes;
    }

    @Disabled("BUG: FramePipeline.runOnFxThread catches IllegalStateException, but JavaFX 26's "
            + "Platform.isFxApplicationThread() throws RuntimeException('No toolkit found'), "
            + "so every callback crashes outside a running JavaFX toolkit.")
    @Test
    void audioOutputCanBeToggledWithoutAJavaFxToolkit() {
        FramePipeline pipeline = new FramePipeline(
                List.of(),
                false,
                (width, height) -> { },
                image -> { },
                status -> { },
                (running, paused) -> { },
                enabled -> { });

        assertDoesNotThrow(pipeline::toggleAudioOutput);
        pipeline.shutdown();
    }

    @Disabled("BUG: AudioPlayer.copySamplesToOutputBuffer tests the packed FloatBuffer branch "
            + "before the planar FloatBuffer branch, so a stereo planar buffer only converts "
            + "channel 0 and silently drops channel 1.")
    @Test
    void planarFloatSamplesAreInterleaved() {
        AudioPlayer player = new AudioPlayer();

        assertArrayEquals(
                littleEndianPcm((short) 0, (short) 32767, (short) 16384, (short) -32767),
                player.copySamples(stereoPlanarFloats()));
    }

    @Disabled("BUG: FrameYellower sizes its ThreadLocal scratch buffer on first process() and "
            + "never resets it in initialise(), so reusing an instance for a larger video "
            + "throws IndexOutOfBoundsException.")
    @Test
    void yellowerCanBeReusedForALargerVideo() throws Exception {
        FrameYellower yellower = new FrameYellower();
        InfoVideo small = TestFrames.video(4, 4, 3);
        yellower.initialise(small);
        yellower.process(TestFrames.frame(small), TestFrames.info(0));

        InfoVideo large = TestFrames.video(20, 20, 3);
        yellower.initialise(large);
        Frame largeFrame = TestFrames.frame(large);

        assertDoesNotThrow(() -> yellower.process(largeFrame, TestFrames.info(0)));
    }
}
