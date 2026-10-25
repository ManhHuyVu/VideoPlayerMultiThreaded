# Video Player Film Effects Pipeline

A JavaFX desktop video player that applies a configurable chain of deterministic
film-degradation effects (scratches, dust, flicker, mottling, light bleed, and
more) to every frame in real time, built on a multithreaded decode/process/display
pipeline. COMP3011 Assignment 2.

## Architecture

### Component layout

| Component | Responsibility |
|---|---|
| `VideoPlayerApp` | JavaFX `Application` entry point. Owns the `Stage`/`Scene`, screen placement, resize handling. |
| `VideoPlayerAppLauncher` | Plain `main()` that delegates to `VideoPlayerApp`, so the runnable jar's manifest doesn't point directly at a class extending `Application`. |
| `VideoPlayerController` | Wires view buttons and keyboard shortcuts to model calls, forwards model callbacks back to the view. |
| `VideoPlayerModel` | Thin facade over `FramePipeline`, keeps the controller's interface stable if the pipeline implementation changes. |
| `VideoPlayerView` | JavaFX scene graph only: video surface, transport controls, tooltips. No playback logic. |
| `FramePipeline` | The engine. Owns every worker thread and queue described below. |
| `FrameProcessor` + subclasses | The effect chain: one class per visual effect. |
| `CommandLineController` | Parses launch arguments into a video file, launch options, and an ordered list of `FrameProcessor` instances. |
| `AudioPlayer` | Converts decoded audio samples to PCM and streams them to the system `SourceDataLine`. |

### Why a multithreaded pipeline

Decoding a frame, running it through a chain of pixel-level effects, converting
it for display, and writing audio to the sound card are four different kinds of
work with four different costs and timing constraints. Doing all four on one
thread ties playback speed to the slowest stage, the effect chain, which scales
directly with how many effects are active. Splitting the work into independently
running stages lets decode, processing, audio, and display each proceed at their
own pace, connected by queues instead of by a single call stack.

### Pipeline stages

1. **Decoder thread** (`runDecoder`) drives `FFmpegFrameGrabber`, pulls one frame
   at a time, clones it (the grabber reuses its internal `Frame` object), and
   submits it for processing. Audio samples are split off into a pending-audio
   queue in the same pass.
2. **Processor pool** — a fixed thread pool, sized to available cores minus one
   (capped at 8, minimum 2), runs the full effect chain against a frame inside
   `processFrame()`. Each submission returns a `Future<PipelineFrame>`.
3. **Collector thread** (`runCollector`) drains those `Future`s in the exact
   order the decoder submitted them, blocking on each one's completion before
   moving to the next. This re-serializes the pool's naturally out-of-order
   completions back into correct frame order before anything reaches the
   display queue.
4. **Audio thread** (`runAudio`) paces PCM writes to the sound card against the
   playback clock, independent of how fast video frames are processing.
5. **JavaFX Application Thread** — an `AnimationTimer` drives `pumpUI()`, which
   pulls due frames off the display queue and pushes them to the `ImageView`.

### Reasoning behind the key design choices

**A pool for processing, a single thread for ordering.** Running the effect
chain is the most expensive stage, so it's the one that benefits from running
across multiple threads at once. But the display needs frames in chronological
order. Letting pool threads push straight to the display queue themselves
produces out-of-order output whenever one frame's chain takes longer than a
neighbouring frame's, which happens constantly since several effects draw a
Poisson-distributed number of marks per frame. A single collector thread
blocking on futures in submission order restores correct ordering without
serializing the expensive step itself.

**Bounded queues throughout.** `orderedResults`, `processQueue`, and
`pendingAudio` are all capacity-limited. A blocking `put()` on a full queue
applies backpressure all the way back to the decoder. Without that bound, a
decoder reading frames faster than the chain can process and display them would
let cloned frames pile up in memory without limit.

**`ThreadLocal` scratch state inside effects.** Every `FrameProcessor` instance
is shared across every thread in the processor pool by design, one instance per
effect, applied to every frame. The contract is that `process()` must not
mutate shared state. Where an effect genuinely needs a scratch buffer spanning
one frame's width (`FrameBleeder`'s bright-mask arrays, `FrameMottler`'s
noise-field cache), that buffer lives behind a `ThreadLocal`, so each pool
thread gets its own copy instead of two threads racing on the same array
mid-frame.

**Deterministic-but-random effects.** Every stochastic effect seeds its
`java.util.Random` from a hash of the media file name plus the effect's
position in the chain (`saltedMediaSeed()`). Re-running the same video with the
same flags in the same order reproduces identical scratches, dust, and jitter,
frame by frame, which keeps output checkable against a reference run.

**Seek handling via a generation counter.** Every frame carries the
`seekGeneration` value active when it was submitted. A seek increments that
counter and drains every in-flight queue. A frame still mid-processing when a
seek lands gets discarded by the collector, since its presentation timestamp
was computed against a playback clock that no longer applies.

**FX-thread marshalling.** JavaFX only allows scene-graph mutation from its own
Application Thread. Every callback from the decoder, processor, audio, and
collector threads back into the view, status text, button state, a new frame
image, is routed through `Platform.runLater` via a small `runOnFxThread()`
helper, so background threads never touch UI nodes directly.

### Known limitation

`FrameJitterer` and part of `FrameMottler` still hold their per-call scratch
state (`sourcePixels`/`blackRow`, and the block-level colour scale indices) in
plain instance fields rather than behind a `ThreadLocal`. Since pool threads
share these effect instances across concurrently processed frames, those two
spots have an open data race: concurrent frames can corrupt each other's jitter
offsets or mottling colours. Every other effect in the chain is already
thread-safe. This is the next item to fix, not yet resolved in this build.

## Run Guide

### Requirements

- JDK 25 or newer (`pom.xml` targets release 25)
- Maven 3.8+
- No separate FFmpeg install needed, `javacv-platform` bundles native FFmpeg
  binaries for the major platforms.

### Build

```
mvn clean package
```

### Run

```
java -jar target/VideoPlayerSingleThread-0.1.jar <video-file> [options] [effect flags]
```

Launch options:

- `-h`, `--help` — print usage and exit
- `-a`, `--audio` — play the video's audio track
- `-x`, `--maximise` — open maximised
- `-1`, `--monitor-1` / `-2`, `--monitor-2` — open on a specific display

Effect flags (long or short form, short flags stack together, repeating an
effect applies it again, effects run in the order given):

- `-n`, `--number-frames` — overlay the frame number
- `-s`, `--scratch-frames` — vertical film scratches
- `-f`, `--flicker-frames` — random per-frame dimming
- `-w`, `--black-and-white` — desaturate
- `-y`, `--yellow-frames` — warmer colour temperature
- `-v`, `--vignette-frames` — darken the edges
- `-d`, `--dust-frames` — dust and hair marks
- `-j`, `--jitter-frames` — random frame-to-frame displacement
- `-m`, `--mottle-frames` — cloudy emulsion mottling
- `-b`, `--bleed-frames` — light bleed around bright areas
- `-p`, `--pepper-frames` — dark spots and blotches

Example, playing with audio, maximised, and a mixed effect chain: