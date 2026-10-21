/*
 * Starter code supplied for Adelaide University COMP3011 Assignment 2.
 * Students are free to modify this file for assessment purposes.
 * 
 * Authors:
 *   1. Simon Ratcliffe, in collaboration with GPT-5.6 Terra
 *   2. <student name and student number insert here upon modification>
 *
 * Copyright 2026 Simon Ratcliffe
 */
package comp3011;

import java.io.File;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import javafx.scene.image.Image;

/**
 * The model part of the video player model-view-controller architecture.
 *
 * <p>
 * Delegates to {@link FramePipeline} for multi-threaded decoding, processing,
 * and audio playback. Keeps the same callback interface for the controller.
 * </p>
 */
public class VideoPlayerModel {
    private final FramePipeline pipeline;

    public VideoPlayerModel(
            boolean audioEnabled,
            List<FrameProcessor> frameProcessors,
            BiConsumer<Integer, Integer> videoSizeChangedHandler,
            Consumer<Image> frameReadyHandler,
            Consumer<String> statusChangedHandler,
            BiConsumer<Boolean, Boolean> playbackStateChangedHandler,
            Consumer<Boolean> audioOutputStateChangedHandler) {
        this.pipeline = new FramePipeline(
                frameProcessors,
                audioEnabled,
                videoSizeChangedHandler,
                frameReadyHandler,
                statusChangedHandler,
                playbackStateChangedHandler,
                audioOutputStateChangedHandler);
    }

    public void play(File file) {
        pipeline.play(file);
    }

    public void play() {
        pipeline.play();
    }

    public void startOver() {
        pipeline.startOver();
    }

    public void backFiveSeconds() {
        pipeline.backFiveSeconds();
    }

    public void forwardFiveSeconds() {
        pipeline.forwardFiveSeconds();
    }

    public void togglePause() {
        pipeline.togglePause();
    }

    public void toggleAudioOutput() {
        pipeline.toggleAudioOutput();
    }

    public boolean isAudioOutputEnabled() {
        return pipeline.isAudioOutputEnabled();
    }

    public void stopPlayback() {
        pipeline.stop();
    }

    public void shutdown() {
        pipeline.shutdown();
    }
}