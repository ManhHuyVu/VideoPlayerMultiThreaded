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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameGrabber;
import org.bytedeco.javacv.JavaFXFrameConverter;

import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.scene.image.Image;

/**
 * Multi-threaded frame processing pipeline.
 *
 * Three stages run on separate threads:
 *   Decoder: Grabs frames from FFmpeg, puts raw frames into decodeQueue
 *   Processor: Applies FrameProcessor effects, puts processed frames into processQueue
 *   Audio: Writes audio samples to sound card
 * The UI thread (JavaFX) pulls ready frames from processQueue via AnimationTimer.
 */
public class FramePipeline implements AutoCloseable {
    private static final int QUEUE_CAPACITY = 8;
    private static final long AUDIO_LEAD_NS = 30_000_000L;
    private static final int PROCESSOR_THREAD_COUNT = Math.max(2, Runtime.getRuntime().availableProcessors() - 1);

    private final BlockingQueue<PipelineFrame> decodeQueue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
    private final BlockingQueue<PipelineFrame> processQueue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
    private final Queue<PendingAudio> pendingAudio = new ArrayDeque<>();

    private final List<FrameProcessor> frameProcessors;
    private final BiConsumer<Integer, Integer> videoSizeChangedHandler;
    private final Consumer<Image> frameReadyHandler;
    private final Consumer<String> statusChangedHandler;
    private final BiConsumer<Boolean, Boolean> playbackStateChangedHandler;
    private final Consumer<Boolean> audioOutputStateChangedHandler;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private final AtomicBoolean seekRequested = new AtomicBoolean(false);
    private long seekTimestampUs = -1;

    private File videoFile;
    private FFmpegFrameGrabber grabber;
    private JavaFXFrameConverter converter;
    private AudioPlayer audioPlayer;
    private boolean audioAvailable;
    private boolean frameProcessorsInitialised;

    private double frameRate;
    private int intFrameRate;
    private int videoFrameDurationUs;
    private int totalVideoFrames;
    private long videoDurationUs = -1;
    private long firstTimestampUs = -1;
    private long logicalPlaybackBaseUs;
    private long playbackStartNs;
    private long pauseStartedNs;
    private long currentTimestampUs;

    private Thread decoderThread;
    private final List<Thread> processorThreads = new ArrayList<>();
    private Thread audioThread;
    private AnimationTimer uiTimer;

    public FramePipeline(
            List<FrameProcessor> frameProcessors,
            boolean audioEnabled,
            BiConsumer<Integer, Integer> videoSizeChangedHandler,
            Consumer<Image> frameReadyHandler,
            Consumer<String> statusChangedHandler,
            BiConsumer<Boolean, Boolean> playbackStateChangedHandler,
            Consumer<Boolean> audioOutputStateChangedHandler) {
        this.frameProcessors = frameProcessors;
        this.videoSizeChangedHandler = videoSizeChangedHandler;
        this.frameReadyHandler = frameReadyHandler;
        this.statusChangedHandler = statusChangedHandler;
        this.playbackStateChangedHandler = playbackStateChangedHandler;
        this.audioOutputStateChangedHandler = audioOutputStateChangedHandler;
    }

    public void play(File file) { /* TODO */ }
    public void startOver() { /* TODO */ }
    public void backFiveSeconds() { /* TODO */ }
    public void forwardFiveSeconds() { /* TODO */ }
    public void togglePause() { /* TODO */ }
    public void toggleAudioOutput() { /* TODO */ }
    public void stop() { /* TODO */ }
    public void shutdown() { /* TODO */ }

    @Override
    public void close() {
        shutdown();
    }

    // Inner classes
    static class PipelineFrame {
        private final Frame frame;
        private final Image image;
        private final InfoFrame info;
        private final int frameNumber;
        private final long timestampUs;
        private final long logicalTimestampUs;
        private final long targetTimeNs;
        private final long preparedAtNs;

        PipelineFrame(Frame frame, int frameNumber, long timestampUs, InfoFrame info) {
            this.frame = frame;
            this.image = null;
            this.info = info;
            this.frameNumber = frameNumber;
            this.timestampUs = timestampUs;
            this.logicalTimestampUs = 0;
            this.targetTimeNs = 0;
            this.preparedAtNs = 0;
        }

        PipelineFrame(Image image, int frameNumber, long timestampUs, long logicalTimestampUs, long targetTimeNs, long preparedAtNs) {
            this.frame = null;
            this.image = image;
            this.info = null;
            this.frameNumber = frameNumber;
            this.timestampUs = timestampUs;
            this.logicalTimestampUs = logicalTimestampUs;
            this.targetTimeNs = targetTimeNs;
            this.preparedAtNs = preparedAtNs;
        }

        Frame frame() { return frame; }
        Image image() { return image; }
        InfoFrame info() { return info; }
        int frameNumber() { return frameNumber; }
        long timestampUs() { return timestampUs; }
        long logicalTimestampUs() { return logicalTimestampUs; }
        long targetTimeNs() { return targetTimeNs; }
        long preparedAtNs() { return preparedAtNs; }
    }

    static class PendingAudio {
        private final long timestampUs;
        private final byte[] samples;
        private int offset;

        PendingAudio(long timestampUs, byte[] samples) {
            this.timestampUs = timestampUs;
            this.samples = samples;
        }

        long timestampUs() { return timestampUs; }
        byte[] samples() { return samples; }
        int offset() { return offset; }
        int remaining() { return samples.length - offset; }
        void advance(int byteCount) { offset += byteCount; }
        boolean finished() { return offset >= samples.length; }
    }
}
