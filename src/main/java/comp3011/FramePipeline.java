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
    // Pipeline configuration constants
    private static final int QUEUE_CAPACITY = 16;  // Max frames buffered between stages
    private static final long AUDIO_LEAD_NS = 30_000_000L;  // 30ms audio lead time for buffer latency
    private static final int PROCESSOR_THREAD_COUNT = Math.min(8, Math.max(2, Runtime.getRuntime().availableProcessors() - 1));

    // Inter-stage queues (thread-safe)
    private final BlockingQueue<PipelineFrame> decodeQueue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
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
    private long firstTimestampUs = -1;          // Media timestamp of first decoded frame
    private long logicalPlaybackBaseUs;          // Logical time base (for seeks: 0 = start, or seek offset)
    private long playbackStartNs;                // System.nanoTime() when playback started/resumed
    private long pauseStartedNs;                 // System.nanoTime() when pause began
    private long currentTimestampUs;             // Last displayed frame's logical timestamp

    // Worker threads
    private Thread decoderThread;                // Runs runDecoder()
    private final List<Thread> processorThreads = new ArrayList<>();  // Run runProcessor() (multiple)
    private Thread audioThread;                  // Runs runAudio()
    private AnimationTimer uiTimer;              // Runs pumpUI() on JavaFX thread

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
        System.err.println("[Decoder] Thread started, audioAvailable=" + audioAvailable);
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
                            notifyStatusChanged("Seek failed: " + e.getMessage());
                        }
                    }
                    // Clear queues and reset state
                    // Must clear ALL queues because frames in flight are now stale
                    decodeQueue.clear();
                    processQueue.clear();
                    pendingAudio.clear();
                    flushAudioOutput();
                    // Drain any remaining audio to avoid blocking on put()
                    pendingAudio.clear();
                    seekRequested.set(false);
                    // Reset clock for new position
                    firstTimestampUs = -1;
                    playbackStartNs = System.nanoTime();
                    logicalPlaybackBaseUs = seekTimestampUs;
                    pauseStartedNs = 0;
                    // Give processor threads time to see seekRequested and exit
                    try { Thread.sleep(20); } catch (InterruptedException ignored) {}
                    continue;
                }

                if (paused.get()) {
                    Thread.sleep(10); // Yield while paused
                    continue;
                }

                Frame frame = audioAvailable ? grabber.grab() : grabber.grabImage();
                System.err.println("[Decoder] Got frame: " + (frame != null) + ", image=" + (frame != null && frame.image != null) + ", samples=" + (frame != null && frame.samples != null));
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

                PipelineFrame pf = new PipelineFrame(clonedFrame, frameNumber, timestampUs, info);
                decodeQueue.put(pf); // Blocks if queue full (backpressure on decoder)
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
                if (seekRequested.get()) {
                    // Clear any frames we're holding
                    return;
                }
                PipelineFrame pf = decodeQueue.take(); // Blocks until frame available
                if (!running.get()) break;

                if (seekRequested.get()) {
                    // Seek happened while we were waiting, discard this frame
                    continue;
                }

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
                // Calculate presentation timestamp:
                // - relativeTimestampUs: frame's offset from first frame (media time)
                // - logicalTimestampUs: media time + seek offset (for resume/seek)
                // - targetTimeNs: absolute nanosecond time when frame should display
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
                Platform.runLater(() -> handleError(e));
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
        if (ready != null && ready.targetTimeNs() <= now) {
            // Frame is due (or past due) - display it now
            processQueue.poll(); // Remove now that we're using it
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
        }
        // Frames with targetTimeNs > now stay in queue for next pumpUI call
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
    private void notifyAudioOutputStateChanged() {
        audioOutputStateChangedHandler.accept(audioOutputEnabled.get());
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

        // Constructor for decoder output (raw frame, not yet processed)
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
