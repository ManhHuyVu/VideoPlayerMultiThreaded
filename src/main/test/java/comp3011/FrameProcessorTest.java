package comp3011;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

import org.bytedeco.javacv.Frame;
import org.junit.jupiter.api.Test;

class FrameProcessorTest {

    private static final class StubProcessor extends FrameProcessor {
        int seed(String mediaName, int instanceIndex) {
            return saltedMediaSeed(mediaName, instanceIndex);
        }

        InfoVideo storedVideoInfo() {
            return videoInfo;
        }

        @Override
        public void process(Frame frame, InfoFrame info) {
        }
    }

    @Test
    void saltedSeedIsDeterministic() {
        StubProcessor processor = new StubProcessor();
        assertEquals(processor.seed("movie.mp4", 4), processor.seed("movie.mp4", 4));
    }

    @Test
    void instanceIndexIsAddedToTheMediaSeed() {
        StubProcessor processor = new StubProcessor();
        assertEquals(processor.seed("movie.mp4", 0) + 7, processor.seed("movie.mp4", 7));
    }

    @Test
    void differentMediaNamesProduceDifferentSeeds() {
        StubProcessor processor = new StubProcessor();
        assertNotEquals(processor.seed("first.mp4", 0), processor.seed("second.mp4", 0));
    }

    @Test
    void emptyMediaNameUsesZeroAsTheBaseSeed() {
        StubProcessor processor = new StubProcessor();
        assertEquals(3, processor.seed("", 3));
    }

    @Test
    void seedMatchesCrc32OfTheUtf8MediaName() {
        StubProcessor processor = new StubProcessor();
        CRC32 crc = new CRC32();
        byte[] bytes = "movie.mp4".getBytes(StandardCharsets.UTF_8);
        crc.update(bytes, 0, bytes.length);

        assertEquals((int) crc.getValue(), processor.seed("movie.mp4", 0));
    }

    @Test
    void unicodeMediaNamesAreAccepted() {
        StubProcessor processor = new StubProcessor();
        assertEquals(
                processor.seed("\u7535\u5f71.mp4", 2),
                processor.seed("\u7535\u5f71.mp4", 2));
    }

    @Test
    void initialiseStoresTheVideoMetadata() throws Exception {
        StubProcessor processor = new StubProcessor();
        InfoVideo video = TestFrames.video(16, 9, 3);

        processor.initialise(video);

        assertSame(video, processor.storedVideoInfo());
    }
}
