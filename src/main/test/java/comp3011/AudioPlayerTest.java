package comp3011;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;

import org.bytedeco.javacv.Frame;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AudioPlayerTest {
    private AudioPlayer player;

    @BeforeEach
    void setUp() {
        player = new AudioPlayer();
    }

    private static Frame frameWith(int channels, Buffer... samples) {
        Frame frame = new Frame();
        frame.audioChannels = channels;
        frame.samples = samples;
        return frame;
    }

    private static byte[] pcm(short... samples) {
        byte[] bytes = new byte[samples.length * 2];
        for (int i = 0; i < samples.length; i++) {
            bytes[i * 2] = (byte) (samples[i] & 0xFF);
            bytes[i * 2 + 1] = (byte) ((samples[i] >> 8) & 0xFF);
        }
        return bytes;
    }

    @Test
    void missingSamplesReturnAnEmptyArray() {
        Frame frame = frameWith(2);
        frame.samples = null;

        assertArrayEquals(new byte[0], player.copySamples(frame));
    }

    @Test
    void emptySampleArrayReturnsAnEmptyArray() {
        assertArrayEquals(new byte[0], player.copySamples(frameWith(2, new Buffer[0])));
    }

    @Test
    void packedShortSamplesAreWrittenLittleEndian() {
        Frame frame = frameWith(2, ShortBuffer.wrap(new short[] { 0x0102, 0x0304 }));

        assertArrayEquals(pcm((short) 0x0102, (short) 0x0304), player.copySamples(frame));
    }

    @Test
    void packedShortRespectsTheBufferPosition() {
        ShortBuffer buffer = ShortBuffer.wrap(new short[] { 1, 2, 3 });
        buffer.get();

        assertArrayEquals(pcm((short) 2, (short) 3), player.copySamples(frameWith(2, buffer)));
    }

    @Test
    void planarShortSamplesAreInterleaved() {
        Frame frame = frameWith(
                2,
                ShortBuffer.wrap(new short[] { 1, 3 }),
                ShortBuffer.wrap(new short[] { 2, 4 }));

        assertArrayEquals(pcm((short) 1, (short) 2, (short) 3, (short) 4), player.copySamples(frame));
    }

    @Test
    void planarShortUsesTheShortestChannel() {
        Frame frame = frameWith(
                2,
                ShortBuffer.wrap(new short[] { 1, 3, 5 }),
                ShortBuffer.wrap(new short[] { 2, 4 }));

        assertArrayEquals(pcm((short) 1, (short) 2, (short) 3, (short) 4), player.copySamples(frame));
    }

    @Test
    void mixedPlanarBufferTypesAreRejected() {
        Frame frame = frameWith(
                2,
                ShortBuffer.wrap(new short[] { 1 }),
                ByteBuffer.allocate(2));

        assertArrayEquals(new byte[0], player.copySamples(frame));
    }

    @Test
    void packedFloatSamplesAreConvertedAndClamped() {
        Frame frame = frameWith(
                1,
                FloatBuffer.wrap(new float[] { 0.0f, 1.0f, -1.0f, 0.5f, 2.0f, -2.0f }));

        assertArrayEquals(
                pcm((short) 0, (short) 32767, (short) -32767, (short) 16384, (short) 32767, (short) -32768),
                player.copySamples(frame));
    }

    @Test
    void unsupportedSampleTypesReturnAnEmptyArray() {
        assertArrayEquals(new byte[0], player.copySamples(frameWith(1, IntBuffer.wrap(new int[] { 1, 2 }))));
    }

    @Test
    void writingBeforeOpeningReturnsZero() {
        assertEquals(0, player.write(new byte[] { 1, 2 }, 0, 2));
    }

    @Test
    void flushingAndClosingBeforeOpeningAreSafe() {
        assertDoesNotThrow(() -> player.flush());
        assertDoesNotThrow(() -> player.close());
        assertDoesNotThrow(() -> player.close());
    }

    @Test
    void openingWithAnInvalidFormatDoesNothing() {
        assertDoesNotThrow(() -> player.open(0, 2));
        assertDoesNotThrow(() -> player.open(-44100, -1));
        assertEquals(0, player.write(new byte[] { 1, 2 }, 0, 2));
        assertDoesNotThrow(() -> player.close());
    }

    @Test
    void monoChannelCountWithPackedBufferStillWorks() {
        Frame frame = frameWith(1, ShortBuffer.wrap(new short[] { 7, -7 }));

        assertArrayEquals(pcm((short) 7, (short) -7), player.copySamples(frame));
    }
}
