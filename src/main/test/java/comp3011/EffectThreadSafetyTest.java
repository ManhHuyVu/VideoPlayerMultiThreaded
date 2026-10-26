package comp3011;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.bytedeco.javacv.Frame;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The pipeline shares one processor instance across every pool thread, so each
 * effect must produce exactly the same output when run concurrently as when run
 * serially.
 *
 * <p>{@link FrameJitterer} and {@link FrameMottler} are deliberately excluded:
 * the README documents an open data race in their per-call scratch state.</p>
 */
class EffectThreadSafetyTest {

    record SafeEffect(String name, Supplier<FrameProcessor> factory, int channels) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<SafeEffect> safeEffects() {
        return Stream.of(
                new SafeEffect("FrameBlackAndWhiter", FrameBlackAndWhiter::new, 3),
                new SafeEffect("FrameFlickerer", FrameFlickerer::new, 3),
                new SafeEffect("FrameYellower", FrameYellower::new, 3),
                new SafeEffect("FrameVignetter", FrameVignetter::new, 3),
                new SafeEffect("FrameNumberer", FrameNumberer::new, 3),
                new SafeEffect("FrameScratcher", FrameScratcher::new, 3),
                new SafeEffect("FrameDuster", FrameDuster::new, 3),
                new SafeEffect("FrameBleeder", FrameBleeder::new, 3),
                new SafeEffect("FramePepperer", FramePepperer::new, 3));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("safeEffects")
    void concurrentProcessingMatchesSerialProcessing(SafeEffect effect) throws Exception {
        InfoVideo video = TestFrames.video(
                "concurrency-" + effect.name() + ".mp4", 500, 64, 64, effect.channels());
        FrameProcessor processor = effect.factory().get();
        processor.initialise(video);

        int taskCount = 64;
        byte[][] expected = new byte[taskCount][];
        for (int index = 0; index < taskCount; index++) {
            Frame frame = TestFrames.patterned(video, index);
            processor.process(frame, TestFrames.info(index));
            expected[index] = TestFrames.snapshot(frame);
        }

        ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<byte[]>> results = new ArrayList<>(taskCount);
            for (int index = 0; index < taskCount; index++) {
                int frameNumber = index;
                results.add(executor.submit(() -> {
                    start.await();
                    Frame frame = TestFrames.patterned(video, frameNumber);
                    processor.process(frame, TestFrames.info(frameNumber));
                    return TestFrames.snapshot(frame);
                }));
            }

            start.countDown();

            for (int index = 0; index < taskCount; index++) {
                assertArrayEquals(expected[index], results.get(index).get(),
                        effect.name() + " frame " + index + " differed under concurrency");
            }
        } finally {
            executor.shutdownNow();
        }
    }
}
