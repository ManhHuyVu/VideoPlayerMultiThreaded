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

    public void play(File file) {
        stop();
        videoFile = file;
        videoDurationUs = -1;
        startPipeline(0, false, -1);
    }

    public void startOver() {
        if (videoFile == null) return;
        seekTo(0);
    }

    public void backFiveSeconds() {
        seekRelative(-5_000_000L);
    }

    public void forwardFiveSeconds() {
        seekRelative(5_000_000L);
    }

    public void togglePause() {
        if (!running.get()) {
            if (videoFile != null) {
                startPipeline(0, false, 0);
            }
            return;
        }
        boolean newPause = !paused.getAndSet(!paused.get());
        if (newPause) {
            pauseStartedNs = System.nanoTime();
            flushAudioOutput();
        } else {
            resumePlaybackClock(System.nanoTime());
        }
        notifyPlaybackStateChanged();
    }

    public void toggleAudioOutput() {
        // Audio output toggle handled by model level
    }

    public void stop() {
        stopPipeline();
    }

    public void shutdown() {
        stopPipeline();
    }

    private void seekRelative(long offsetUs) {
        if (videoFile == null) return;
        long base = currentTimestampUs;
        seekTo(base + offsetUs);
    }

    private void seekTo(long timestampUs) {
        seekTimestampUs = Math.max(0, timestampUs);
        seekRequested.set(true);
    }

    @Override
    public void close() {
        shutdown();
    }

    private void startPipeline(long startTimestampUs, boolean initiallyPaused, long initialSeekBaseUs) {
        paused.set(initiallyPaused);
        logicalPlaybackBaseUs = initialSeekBaseUs != -1 ? initialSeekBaseUs : startTimestampUs;
        firstTimestampUs = -1;
        playbackStartNs = 0;
        pauseStartedNs = initiallyPaused ? System.nanoTime() : 0;
        frameProcessorsInitialised = false;

        notifyStatusChanged(videoFile.getName());

        try {
            openResources(startTimestampUs);
            running.set(true);
            startThreads();
            notifyPlaybackStateChanged();
        } catch (Exception e) {
            handleError(e);
        }
    }

    private void openResources(long startTimestampUs) throws Exception {
        converter = new JavaFXFrameConverter();
        grabber = new FFmpegFrameGrabber(videoFile);
        grabber.setImageMode(FrameGrabber.ImageMode.COLOR);
        grabber.setSampleMode(FrameGrabber.SampleMode.SHORT);
        grabber.start();

        notifyVideoSizeChanged(grabber.getImageWidth(), grabber.getImageHeight());

        frameRate = grabber.getFrameRate();
        intFrameRate = (int) Math.round(frameRate);
        videoFrameDurationUs = frameRate > 0 ? (int) Math.round(1_000_000.0 / frameRate) : 0;
        totalVideoFrames = grabber.getLengthInVideoFrames();

        long durationUs = grabber.getLengthInTime();
        videoDurationUs = durationUs > 0 ? durationUs : -1;

        audioAvailable = grabber.hasAudio();
        audioPlayer = new AudioPlayer();
        if (audioAvailable) {
            audioPlayer.open(grabber.getSampleRate(), grabber.getAudioChannels());
        }

        if (startTimestampUs > 0) {
            grabber.setTimestamp(startTimestampUs);
        }
    }

    private void startThreads() {
        decoderThread = new Thread(this::runDecoder, "VideoDecoder");
        
        for (int i = 0; i < PROCESSOR_THREAD_COUNT; i++) {
            Thread t = new Thread(this::runProcessor, "VideoProcessor-" + i);
            processorThreads.add(t);
            t.start();
        }
        
        audioThread = new Thread(this::runAudio, "AudioWriter");
        uiTimer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                pumpUI(now);
            }
        };

        decoderThread.start();
        if (audioAvailable) {
            audioThread.start();
        }
        uiTimer.start();
    }

    private void runDecoder() {
        try {
            while (running.get() && !Thread.currentThread().isInterrupted()) {
                if (seekRequested.get()) {
                    // Perform the actual seek on the grabber
                    if (grabber != null && seekTimestampUs >= 0) {
                        try {
                            grabber.setTimestamp(seekTimestampUs);
                        } catch (Exception e) {
                            // Ignore seek errors
                        }
                    }
                    // Clear queues and reset state
                    decodeQueue.clear();
                    processQueue.clear();
                    pendingAudio.clear();
                    flushAudioOutput();
                    seekRequested.set(false);
                    firstTimestampUs = -1;
                    continue;
                }

                if (paused.get()) {
                    Thread.sleep(10); // Yield while paused
                    continue;
                }

                Frame frame = audioAvailable ? grabber.grab() : grabber.grabImage();
                if (frame == null) {
                    finishPlayback(); // EOF
                    break;
                }

                long timestampUs = grabber.getTimestamp();

                if (audioAvailable && frame.samples != null) {
                    byte[] samples = audioPlayer.copySamples(frame);
                    if (samples.length > 0) {
                        pendingAudio.add(new PendingAudio(timestampUs, samples));
                    }
                }

                if (frame.image == null) {
                    continue; // Audio-only frame
                }

                // Clone frame because grabber reuses the same Frame object
                Frame clonedFrame = frame.clone();

                if (!frameProcessorsInitialised) {
                    initialiseFrameProcessors(new InfoVideo(
                            mediaName(videoFile),
                            totalVideoFrames,
                            clonedFrame.imageWidth,
                            clonedFrame.imageHeight,
                            clonedFrame.imageDepth,
                            clonedFrame.imageChannels,
                            clonedFrame.imageStride,
                            frameRate,
                            intFrameRate,
                            videoFrameDurationUs,
                            grabber.getPixelFormat()));
                    frameProcessorsInitialised = true;
                }

                if (firstTimestampUs == -1) {
                    firstTimestampUs = timestampUs;
                    playbackStartNs = System.nanoTime();
                    if (paused.get()) {
                        pauseStartedNs = playbackStartNs;
                    }
                }

                int frameNumber = grabber.getFrameNumber();
                InfoFrame info = new InfoFrame(frameNumber, timestampUs);

                PipelineFrame pf = new PipelineFrame(clonedFrame, frameNumber, timestampUs, info);
                decodeQueue.put(pf);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            if (running.get()) {
                Platform.runLater(() -> handleError(e));
            }
        }
    }

    private void runProcessor() {
        try {
            while (running.get() && !Thread.currentThread().isInterrupted()) {
                PipelineFrame pf = decodeQueue.take(); // Blocks until frame available
                if (!running.get()) break;

                Frame frame = pf.frame();
                InfoFrame info = pf.info();

                if (frame.image == null || frame.imageWidth <= 0 || frame.imageHeight <= 0 || frame.imageChannels != 3) {
                    continue; // Skip invalid frames
                }

                // Apply all frame processors in sequence
                for (FrameProcessor processor : frameProcessors) {
                    processor.process(frame, info);
                }

                Image image = converter.convert(frame);
                if (image == null) {
                    continue;
                }
                // Calculate presentation timestamp
                long relativeTimestampUs = Math.max(0, pf.timestampUs() - firstTimestampUs);
                long logicalTimestampUs = logicalPlaybackBaseUs + relativeTimestampUs;
                long targetTimeNs = playbackStartNs + relativeTimestampUs * 1_000L;

                PipelineFrame ready = new PipelineFrame(
                        image,
                        pf.frameNumber(),
                        pf.timestampUs(),
                        logicalTimestampUs,
                        targetTimeNs,
                        System.nanoTime());
                processQueue.put(ready); // Blocks if queue full (backpressure)
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            if (running.get()) {
                Platform.runLater(() -> handleError(e));
            }
        }
    }
    private void runAudio() {
        try {
            while (running.get() && !Thread.currentThread().isInterrupted()) {
                if (paused.get() || firstTimestampUs == -1 || playbackStartNs <= 0) {
                    Thread.sleep(5); // Wait for clock init
                    continue;
                }

                long now = System.nanoTime();
                // Calculate which audio timestamp should be playing now (+30ms lead)
                long dueTimestampUs = firstTimestampUs + (now + AUDIO_LEAD_NS - playbackStartNs) / 1_000L;

                while (!pendingAudio.isEmpty()) {
                    PendingAudio audio = pendingAudio.peek();
                    if (audio.timestampUs() > dueTimestampUs) {
                        break; // Not yet due
                    }

                    int written = audioPlayer.write(audio.samples(), audio.offset(), audio.remaining());
                    if (written <= 0) {
                        break; // Buffer full
                    }

                    audio.advance(written);
                    if (!audio.finished()) {
                        break; // Partial write
                    }
                    pendingAudio.remove(); // Fully written
                }

                Thread.sleep(2); // Yield
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            if (running.get()) {
                Platform.runLater(() -> handleError(e));
            }
        }
    }

    private void pumpUI(long now) {
        if (!running.get()) {
            return;
        }

        PipelineFrame ready = processQueue.poll(); // Non-blocking
        if (ready != null && ready.targetTimeNs() <= now) {
            currentTimestampUs = ready.logicalTimestampUs();
            seekRequested.set(false);

            // Log headroom once per second (at frame rate intervals)
            if (intFrameRate > 0 && ready.frameNumber() % intFrameRate == 0) {
                long remainingUs = (ready.targetTimeNs() - ready.preparedAtNs()) / 1_000L;
                if (remainingUs < 0) {
                    System.out.printf("Frame headroom is \u001B[31m%5dus\u001B[0m out of %dus spare.%n",
                            remainingUs, videoFrameDurationUs);
                } else {
                    System.out.printf("Frame headroom is \u001B[32m%5dus\u001B[0m out of %dus spare.%n",
                            remainingUs, videoFrameDurationUs);
                }
            }

            notifyFrameReady(ready.image()); // Push to JavaFX ImageView
        }

        if (processQueue.isEmpty() && !paused.get() && !seekRequested.get()) {
            // Decoder will naturally produce more frames
        }
    }

    private void finishPlayback() {
        long endTimestampUs = videoDurationUs != -1 ? videoDurationUs : currentTimestampUs;
        stopPipeline();
        paused.set(true);
        currentTimestampUs = endTimestampUs;
        notifyStatusChanged("Playback finished");
        notifyPlaybackStateChanged();
    }

    private void handleError(Exception e) {
        e.printStackTrace();
        stopPipeline();
        paused.set(false);
        notifyStatusChanged("Error: " + e.getMessage());
        notifyPlaybackStateChanged();
    }

    private void stopPipeline() {
        running.set(false);
        seekRequested.set(true); // Unblock any waiting threads

        if (uiTimer != null) {
            uiTimer.stop();
            uiTimer = null;
        }

        if (decoderThread != null) {
            decoderThread.interrupt();
            try { decoderThread.join(1000); } catch (InterruptedException ignored) {}
            decoderThread = null;
        }
        for (Thread t : processorThreads) {
            t.interrupt();
            try { t.join(1000); } catch (InterruptedException ignored) {}
        }
        processorThreads.clear();
        if (audioThread != null) {
            audioThread.interrupt();
            try { audioThread.join(1000); } catch (InterruptedException ignored) {}
            audioThread = null;
        }

        closeResources();
    }

    private void notifyFrameReady(Image image) { frameReadyHandler.accept(image); }

    private void closeResources() {
        decodeQueue.clear();
        processQueue.clear();
        pendingAudio.clear();
        firstTimestampUs = -1;
        playbackStartNs = 0;
        pauseStartedNs = 0;
        audioAvailable = false;
        frameProcessorsInitialised = false;

        if (audioPlayer != null) {
            audioPlayer.close();
            audioPlayer = null;
        }
        if (grabber != null) {
            try { grabber.stop(); } catch (Exception ignored) {}
            try { grabber.close(); } catch (Exception ignored) {}
            grabber = null;
        }
        if (converter != null) {
            converter.close();
            converter = null;
        }
    }

    private void flushAudioOutput() { if (audioPlayer != null) audioPlayer.flush(); }
    private void resumePlaybackClock(long now) {
        if (pauseStartedNs > 0 && playbackStartNs > 0) {
            playbackStartNs += now - pauseStartedNs;
        }
        pauseStartedNs = 0;
    }
    private void notifyPlaybackStateChanged() {
        playbackStateChangedHandler.accept(running.get(), paused.get());
    }
    private void notifyStatusChanged(String status) { statusChangedHandler.accept(status); }
    private void notifyVideoSizeChanged(int width, int height) { videoSizeChangedHandler.accept(width, height); }
    private void initialiseFrameProcessors(InfoVideo videoInfo) throws Exception {
        for (FrameProcessor processor : frameProcessors) {
            processor.initialise(videoInfo);
        }
    }
    private String mediaName(File file) {
        String fileName = file.getName();
        int extensionStart = fileName.lastIndexOf('.');
        if (extensionStart <= 0) return fileName;
        return fileName.substring(0, extensionStart);
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
