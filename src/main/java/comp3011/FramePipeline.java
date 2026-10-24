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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
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
    // Pipeline configuration constants
    private static final int QUEUE_CAPACITY = 16;  // Max frames buffered between stages
    private static final long AUDIO_LEAD_NS = 30_000_000L;  // 30ms audio lead time for buffer latency
    private static final int PROCESSOR_THREAD_COUNT = Math.min(8, Math.max(2, Runtime.getRuntime().availableProcessors() - 1));
    private static final long LATE_FRAME_THRESHOLD_NS = 33_000_000L; // Drop frames > 33ms late (~1 frame)

    // Performance metrics (atomic, read from UI thread)
    private final AtomicLong framesProcessed = new AtomicLong(0);
    private final AtomicLong framesDropped = new AtomicLong(0);

    // Inter-stage queues (thread-safe)
    private final BlockingQueue<PipelineFrame> decodeQueue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);  // Backpressure on decoder
    private final BlockingQueue<PipelineFrame> processQueue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
    private final BlockingQueue<PendingAudio> pendingAudio = new LinkedBlockingQueue<>(50); // Bounded - backpressure on decoder

    // Pipeline configuration (injected at construction)
    private final List<FrameProcessor> frameProcessors;              // Effect chain to apply per frame
    private final BiConsumer<Integer, Integer> videoSizeChangedHandler;  // Callback: video dimensions known
    private final Consumer<Image> frameReadyHandler;                 // Callback: processed frame ready for UI
    private final Consumer<String> statusChangedHandler;             // Callback: status text update
    private final BiConsumer<Boolean, Boolean> playbackStateChangedHandler; // Callback: playing/paused state
    private final Consumer<Boolean> audioOutputStateChangedHandler;  // Callback: audio enabled/disabled

    // Pipeline state (atomic for thread-safe access)
    private final AtomicBoolean running = new AtomicBoolean(false);   // Pipeline active
    private final AtomicBoolean paused = new AtomicBoolean(false);    // User pause requested
    private final AtomicBoolean seekRequested = new AtomicBoolean(false); // Seek in progress
    private final AtomicBoolean audioOutputEnabled = new AtomicBoolean(true); // Audio mute state
    private long seekTimestampUs = -1;                                // Target seek position (microseconds)
    private final AtomicLong seekGeneration = new AtomicLong(0); // Incremented on each seek to invalidate stale frames

    // Media resources (initialized in openResources())
    private File videoFile;                      // Source video file
    private FFmpegFrameGrabber grabber;          // FFmpeg decoder
    private JavaFXFrameConverter converter;      // Frame -> JavaFX Image
    private AudioPlayer audioPlayer;             // Audio output
    private boolean audioAvailable;              // True if media has audio track
    private boolean frameProcessorsInitialised;  // True after first frame initializes effects

    // Video metadata (populated after grabber starts)
    private double frameRate;                    // Frames per second (e.g., 23.98)
    private int intFrameRate;                    // Rounded frame rate (for headroom logging interval)
    private int videoFrameDurationUs;            // Frame duration in microseconds (1_000_000 / frameRate)
    private int totalVideoFrames;                // Total frame count from container
    private long videoDurationUs = -1;           // Total duration in microseconds (-1 if unknown)

    // Playback clock (managed by decoder thread, read by processor/audio/UI)
    private volatile long firstTimestampUs = -1;          // Media timestamp of first decoded frame
    private volatile long logicalPlaybackBaseUs;          // Logical time base (for seeks: 0 = start, or seek offset)
    private volatile long playbackStartNs;                // System.nanoTime() when playback started/resumed
    private volatile long pauseStartedNs;                 // System.nanoTime() when pause began
    private volatile long currentTimestampUs;             // Last displayed frame's logical timestamp

    // Expected frame number for in-order display (handles out-of-order processor completion)
    private int expectedFrameNumber = 0;

    // Worker threads
    private Thread decoderThread;                // Runs runDecoder()
    private ExecutorService processorExecutor;   // Runs processFrame() on PROCESSOR_THREAD_COUNT threads
    private Thread audioThread;                  // Runs runAudio()
    private AnimationTimer uiTimer;              // Runs pumpUI() on JavaFX thread
    private ScheduledExecutorService uiPumpScheduler; // High-frequency UI pump

    public FramePipeline(
            List<FrameProcessor> frameProcessors,
            boolean audioEnabled,
            BiConsumer<Integer, Integer> videoSizeChangedHandler,
            Consumer<Image> frameReadyHandler,
            Consumer<String> statusChangedHandler,
            BiConsumer<Boolean, Boolean> playbackStateChangedHandler,
            Consumer<Boolean> audioOutputStateChangedHandler) {
        this.frameProcessors = List.copyOf(frameProcessors); // Defensive copy, immutable after construction
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

    public void play() {
        if (videoFile == null) return;
        if (!running.get()) {
            startPipeline(0, false, currentTimestampUs);
        }
    }

    public void startOver() {
        if (videoFile == null) return;
        seekTo(0);
    }

    public void backFiveSeconds() {
        if (videoFile == null) return;
        seekRelative(-5_000_000L);
    }

    public void forwardFiveSeconds() {
        if (videoFile == null) return;
        seekRelative(5_000_000L);
    }

    public void togglePause() {
        if (!running.get()) {
            if (videoFile != null) {
                // At EOF or stopped: restart from beginning instead of full pipeline restart
                if (paused.get() && currentTimestampUs >= videoDurationUs && videoDurationUs > 0) {
                    seekTo(0);
                    paused.set(false);
                    notifyPlaybackStateChanged();
                } else {
                    startPipeline(0, false, 0);
                }
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
        boolean newState = !audioOutputEnabled.getAndSet(!audioOutputEnabled.get());
        if (!newState) {
            flushAudioOutput(); // Clear buffer when muting
        }
        notifyAudioOutputStateChanged(); // Notify UI to update button
    }

    public boolean isAudioOutputEnabled() {
        return audioOutputEnabled.get();
    }

    public void stop() {
        stopPipeline();
        // Reset playback position tracking
        currentTimestampUs = 0;
        logicalPlaybackBaseUs = 0;
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
        playbackStartNs = System.nanoTime(); // Set clock start immediately
        pauseStartedNs = initiallyPaused ? System.nanoTime() : 0;
        frameProcessorsInitialised = false;
        audioOutputEnabled.set(true); // Default to enabled

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

        processorExecutor = Executors.newFixedThreadPool(
                PROCESSOR_THREAD_COUNT,
                r -> new Thread(r, "VideoProcessor"));

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

        // High-frequency UI pump to supplement AnimationTimer (~500Hz).
        uiPumpScheduler = new ScheduledThreadPoolExecutor(1,
                r -> new Thread(r, "UIPump"));
        uiPumpScheduler.scheduleAtFixedRate(() -> {
            if (running.get()) {
                Platform.runLater(() -> pumpUI(System.nanoTime()));
            }
        }, 0, 2, TimeUnit.MILLISECONDS);
    }

    private void runDecoder() {
        try {
            int frameCount = 0;
            while (running.get() && !Thread.currentThread().isInterrupted()) {
                if (seekRequested.get()) {
                    // Perform the actual seek on the grabber
                    if (grabber != null && seekTimestampUs >= 0) {
                        try {
                            grabber.setTimestamp(seekTimestampUs);
                        } catch (Exception e) {
                            // Seek failed - notify UI
                            runOnFxThread(() -> notifyStatusChanged("Seek failed: " + e.getMessage()));
                        }
                    }
                    // Drain all queues - frames in flight are now stale
                    decodeQueue.clear();
                    processQueue.clear();
                    pendingAudio.clear();
                    flushAudioOutput();
                    seekRequested.set(false);
                    seekGeneration.incrementAndGet(); // Invalidate all in-flight frames
                    // Reset clock for new position - keep audio clock continuity
                    firstTimestampUs = seekTimestampUs;
                    logicalPlaybackBaseUs = seekTimestampUs;
                    pauseStartedNs = 0;
                    expectedFrameNumber = 0;
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
                frameCount++;

                long timestampUs = grabber.getTimestamp();

                if (audioAvailable && frame.samples != null && audioOutputEnabled.get()) {
                    byte[] samples = audioPlayer.copySamples(frame);
                    if (samples.length > 0) {
                        try {
                            pendingAudio.put(new PendingAudio(timestampUs, samples)); // Blocks if queue full
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
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

                PipelineFrame pf = new PipelineFrame(clonedFrame, frameNumber, timestampUs, info, seekGeneration.get());

                // Submit to processor executor; processed frame goes directly into processQueue
                processorExecutor.submit(() -> processFrame(pf));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            if (running.get()) {
                runOnFxThread(() -> handleError(e));
            }
        }
    }

    private PipelineFrame processFrame(PipelineFrame pf) throws Exception {
        if (pf.seekGeneration() != seekGeneration.get()) return null;

        Frame frame = pf.frame();
        InfoFrame info = pf.info();

        if (frame.image == null || frame.imageWidth <= 0 || frame.imageHeight <= 0 || frame.imageChannels != 3) {
            return null;
        }

        // Apply all frame processors in sequence
        for (FrameProcessor processor : frameProcessors) {
            processor.process(frame, info);
        }

        Image image = converter.convert(frame);
        if (image == null) return null;
        // Calculate presentation timestamp:
        // - relativeTimestampUs: frame's offset from first frame (media time)
        // - logicalTimestampUs: media time + seek offset (for resume/seek)
        // - targetTimeNs: absolute nanoseconds when frame should display
        long relativeTimestampUs = Math.max(0, pf.timestampUs() - firstTimestampUs);
        long logicalTimestampUs = logicalPlaybackBaseUs + relativeTimestampUs;
        long targetTimeNs = playbackStartNs + relativeTimestampUs * 1_000L;

        return new PipelineFrame(
                image,
                pf.frameNumber(),
                pf.timestampUs(),
                logicalTimestampUs,
                targetTimeNs,
                System.nanoTime());
    }

    private void runAudio() {
        try {
            while (running.get() && !Thread.currentThread().isInterrupted()) {
                if (paused.get() || firstTimestampUs == -1 || playbackStartNs <= 0 || !audioOutputEnabled.get()) {
                    Thread.sleep(5); // Wait for clock init or audio enabled
                    continue;
                }

                long now = System.nanoTime();
                // Calculate which audio timestamp should be playing now (+30ms lead)
                // AUDIO_LEAD_NS = 30ms ahead to account for audio buffer latency
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
                runOnFxThread(() -> handleError(e));
            }
        }
    }

    private void pumpUI(long now) {
        if (!running.get()) {
            return;
        }

        if (paused.get()) {
            return; // Don't display frames when paused
        }

        PipelineFrame ready = processQueue.peek(); // Non-blocking, don't remove
        while (ready != null) {
            // Stale frame from before a seek - drop it
            if (ready.frameNumber() < expectedFrameNumber) {
                processQueue.poll();
                framesDropped.incrementAndGet();
                ready = processQueue.peek();
                continue;
            }
            // Frame from the future - wait for expected frame to arrive
            if (ready.frameNumber() > expectedFrameNumber) {
                break;
            }
            long latenessNs = now - ready.targetTimeNs();
            if (latenessNs > LATE_FRAME_THRESHOLD_NS) {
                // Frame is too late - drop it and try next, to catch up
                processQueue.poll();
                framesDropped.incrementAndGet();
                expectedFrameNumber = ready.frameNumber() + 1;
                ready = processQueue.peek();
                continue;
            }
            if (ready.targetTimeNs() <= now) {
                // Frame is due - display it
                processQueue.poll();
                framesProcessed.incrementAndGet();
                expectedFrameNumber = ready.frameNumber() + 1;
                long proc = framesProcessed.get();
                if (proc % 24 == 0) { // ~once per second at 24fps
                    long dr = framesDropped.get();
                    long tot = proc + dr;
                    System.out.printf("[metrics] processed=%d dropped=%d dropRate=%.1f%%%n",
                            proc, dr, tot > 0 ? (double) dr / tot * 100.0 : 0.0);
                }
                currentTimestampUs = ready.logicalTimestampUs();
                seekRequested.set(false);

                // Log headroom once per second (at frame rate intervals)
                // Headroom = time between frame preparation and its deadline
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
                ready = processQueue.peek(); // Check next frame immediately
                continue;
            }
            break; // Frame not yet due - stop, wait for next pumpUI call
        }
        // Frames with targetTimeNs > now stay in queue for next pumpUI call
    }

    // Metrics for benchmarking
    public long getFramesProcessed() {
        return framesProcessed.get();
    }

    public long getFramesDropped() {
        return framesDropped.get();
    }

    public double getDropRate() {
        long total = framesProcessed.get() + framesDropped.get();
        return total > 0 ? (double) framesDropped.get() / total * 100.0 : 0.0;
    }

    private void finishPlayback() {
        long endTimestampUs = videoDurationUs != -1 ? videoDurationUs : currentTimestampUs;
        // Don't call stopPipeline() here - it would self-join the decoder thread
        // Just signal EOF state; the UI/controller thread will call stopPipeline() separately
        // Don't set running=false - let audio thread drain remaining samples
        paused.set(true);
        currentTimestampUs = endTimestampUs;
        // Report frame metrics
        long processed = framesProcessed.get();
        long dropped = framesDropped.get();
        long total = processed + dropped;
        System.out.printf("=== Playback metrics: %d frames processed, %d dropped (%.1f%%) ===%n",
                processed, dropped, total > 0 ? (double) dropped / total * 100.0 : 0.0);
        runOnFxThread(() -> {
            notifyStatusChanged("Playback finished");
            notifyPlaybackStateChanged();
        });
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
            Thread current = Thread.currentThread();
            if (decoderThread != current) {
                decoderThread.interrupt();
                try { decoderThread.join(1000); } catch (InterruptedException ignored) {}
            }
            decoderThread = null;
        }
        if (processorExecutor != null) {
            processorExecutor.shutdownNow();
            try { processorExecutor.awaitTermination(1, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
            processorExecutor = null;
        }
        if (audioThread != null) {
            audioThread.interrupt();
            try { audioThread.join(1000); } catch (InterruptedException ignored) {}
            audioThread = null;
        }

        closeResources();
    }

    private void notifyFrameReady(Image image) { runOnFxThread(() -> frameReadyHandler.accept(image)); }

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

    // Safely runs a Runnable on the JavaFX Application Thread.
    // If the JavaFX toolkit is not initialized (e.g., in tests without FX initialization),
    // runs the task immediately on the calling thread to avoid IllegalStateException.
    private static void runOnFxThread(Runnable task) {
        if (Platform.isFxApplicationThread()) {
            task.run();
        } else {
            try {
                Platform.runLater(task);
            } catch (IllegalStateException e) {
                // JavaFX toolkit not initialized - run directly to avoid IllegalStateException
                task.run();
            }
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
        runOnFxThread(() -> playbackStateChangedHandler.accept(running.get(), paused.get()));
    }
    private void notifyStatusChanged(String status) { runOnFxThread(() -> statusChangedHandler.accept(status)); }
    private void notifyVideoSizeChanged(int width, int height) { runOnFxThread(() -> videoSizeChangedHandler.accept(width, height)); }
    private void notifyAudioOutputStateChanged() {
        runOnFxThread(() -> audioOutputStateChangedHandler.accept(audioOutputEnabled.get()));
    }
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
        private final Frame frame;              // Raw frame from decoder (before processing)
        private final Image image;              // JavaFX Image after processing + conversion
        private final InfoFrame info;           // Frame metadata (frame number, media timestamp)
        private final int frameNumber;          // Sequential frame index
        private final long timestampUs;         // Original media timestamp (microseconds)
        private final long logicalTimestampUs;  // Media timestamp adjusted for seeks/resume
        private final long targetTimeNs;        // Absolute nanoseconds when frame should display
        private final long preparedAtNs;        // When this PipelineFrame was created (for headroom)
        private final long seekGeneration;      // Seek generation when frame was produced

        // Constructor for decoder output (raw frame, not yet processed)
        PipelineFrame(Frame frame, int frameNumber, long timestampUs, InfoFrame info, long seekGeneration) {
            this.frame = frame;
            this.image = null;
            this.info = info;
            this.frameNumber = frameNumber;
            this.timestampUs = timestampUs;
            this.logicalTimestampUs = 0;
            this.targetTimeNs = 0;
            this.preparedAtNs = 0;
            this.seekGeneration = seekGeneration;
        }

        // Constructor for processor output (processed Image, ready for display)
        PipelineFrame(Image image, int frameNumber, long timestampUs, long logicalTimestampUs, long targetTimeNs, long preparedAtNs) {
            this.frame = null;
            this.image = image;
            this.info = null;
            this.frameNumber = frameNumber;
            this.timestampUs = timestampUs;
            this.logicalTimestampUs = logicalTimestampUs;
            this.targetTimeNs = targetTimeNs;
            this.preparedAtNs = preparedAtNs;
            this.seekGeneration = 0;
        }

        Frame frame() { return frame; }
        Image image() { return image; }
        InfoFrame info() { return info; }
        int frameNumber() { return frameNumber; }
        long timestampUs() { return timestampUs; }
        long logicalTimestampUs() { return logicalTimestampUs; }
        long targetTimeNs() { return targetTimeNs; }
        long preparedAtNs() { return preparedAtNs; }
        long seekGeneration() { return seekGeneration; }
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
