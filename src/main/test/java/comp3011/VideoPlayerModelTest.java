package comp3011;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class VideoPlayerModelTest {
    private VideoPlayerModel model;

    @BeforeEach
    void setUp() {
        model = new VideoPlayerModel(
                false,
                List.of(),
                (width, height) -> { },
                image -> { },
                status -> { },
                (running, paused) -> { },
                audioEnabled -> { });
    }

    @Test
    void transportControlsAreSafeWithoutMedia() {
        assertDoesNotThrow(() -> {
            model.play();
            model.startOver();
            model.backFiveSeconds();
            model.forwardFiveSeconds();
            model.togglePause();
        });
    }

    @Test
    void stoppingAndShuttingDownAreSafeWithoutMedia() {
        assertDoesNotThrow(() -> {
            model.stopPlayback();
            model.shutdown();
        });
    }
}
