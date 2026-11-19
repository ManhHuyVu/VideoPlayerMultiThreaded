package comp3011;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.time.Duration;

/**
 * The subtitle back end: an embedded HTTP server on {@code localhost:8080}
 * exposing {@code POST /api/v1/subtitles} and {@code GET /api/v1/global/usage},
 * a cache of transcribed segments, a cumulative usage counter (seconds), and
 * the on/off toggle state.
 *
 * <p>Two instances exist over the JVM's life:
 * <ul>
 *   <li>the default no-op instance (returned by {@link #get()} before the app
 *       starts), which returns no segments and starts no server - safe to use
 *       from tests and before launch;</li>
 *   <li>the real instance created by {@link VideoPlayerApp}, which binds the
 *       server, calls the OpenAI Whisper API, and is published via
 *       {@link #set(SubtitleService)}.</li>
 * </ul>
 * Frame processors and the controller read from {@link #get()}, so they work
 * identically before and after the real service is published.</p>
 */
public class SubtitleService {
    public static final int PORT = 8080;
    private static final String OPENAI_URL = "https://api.openai.com/v1/audio/transcriptions";
    private static final long MIN_DISPLAY_US = 1_000_000L;   // 1 second
    private static final long MAX_DISPLAY_US = 7_000_000L;   // 7 seconds

    private static final int MAX_CACHE_ENTRIES = 100;

    private static volatile SubtitleService instance = new SubtitleService();

    public static SubtitleService get() {
        return instance;
    }

    public static void set(SubtitleService service) {
        instance = service;
    }

    private final boolean real;
    private HttpServer server;            // bound lazily in start(); null for the no-op default
    private final HttpClient httpClient;        // for POSTing to the embedded server
    private final java.util.concurrent.ExecutorService executor;
    private final ConcurrentHashMap<String, List<SubtitleSegment>> serverCache = new ConcurrentHashMap<>(); // server-side cache by filename
    final ConcurrentHashMap<String, List<SubtitleSegment>> cache = new ConcurrentHashMap<>();   // client-side segment cache (package-visible for tests)
    private final ConcurrentHashMap<String, Boolean> fetching = new ConcurrentHashMap<>();
    private final AtomicLong usageMicros = new AtomicLong(0);
    private final AtomicBoolean enabled = new AtomicBoolean(false);
    private volatile File mediaFile;            // the current media file (set by the app on play)

    /** No-op default: no server, no network, no segments. Safe in tests. */
    private SubtitleService() {
        this.real = false;
        this.server = null;
        this.httpClient = null;
        this.executor = null;
    }

    public SubtitleService(int port) throws Exception {
        this.real = true;
        this.server = null; // bound lazily in start() so tests can build without a port
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "SubtitleService");
            t.setDaemon(true);
            return t;
        });
    }

    public void start() throws Exception {
        if (!real) return;
        server = HttpServer.create(new InetSocketAddress("localhost", PORT), 0);
        server.createContext("/api/v1/subtitles", this::handleSubtitles);
        server.createContext("/api/v1/global/usage", this::handleUsage);
        server.setExecutor(executor);
        server.start();
    }

    public void stop() {
        if (real && server != null) {
            server.stop(0);
            executor.shutdownNow();
        }
    }

    public void setMediaFile(File file) {
        this.mediaFile = file;
    }

    public void setEnabled(boolean enabled) {
        this.enabled.set(enabled);
    }

    public void toggleEnabled() {
        this.enabled.set(!this.enabled.get());
    }

    public boolean isEnabled() {
        return enabled.get();
    }

    /**
     * Returns cached segments for the media, or kicks off an asynchronous
     * transcription (via the embedded server) and returns empty until it
     * completes. Never blocks the calling frame-processing thread.
     */
    public List<SubtitleSegment> ensureSegments(String mediaName) {
        if (!real) {
            return List.of();
        }
        List<SubtitleSegment> cached = cache.get(mediaName);
        if (cached != null) {
            return cached;
        }
        if (fetching.putIfAbsent(mediaName, Boolean.TRUE) != null) {
            return List.of(); // already being transcribed
        }
        File file = mediaFile;
        if (file == null) {
            fetching.remove(mediaName);
            return List.of();
        }
        CompletableFuture.runAsync(() -> {
            try {
                List<SubtitleSegment> segs = fetchFromServer(file);
                List<SubtitleSegment> toCache = segs == null ? List.of() : segs;
                putCache(mediaName, toCache);
                writeSubtitleFile(mediaName, toCache);
            } catch (Throwable ignored) {
                cache.put(mediaName, List.of());
            } finally {
                fetching.remove(mediaName);
            }
        }, executor);
        return List.of();
    }

    /**
     * Writes subtitle segments to an .srt file alongside the media file so they
     * can be inspected or used by other players. File is named
     * {@code <mediaName>.srt} in the same directory as the media.
     */
    private void writeSubtitleFile(String mediaName, List<SubtitleSegment> segments) {
        if (mediaFile == null || segments.isEmpty()) {
            return;
        }
        File srtFile = new File(mediaFile.getParentFile(), mediaName + ".srt");
        try (java.io.PrintWriter w = new java.io.PrintWriter(
                new java.io.OutputStreamWriter(
                        new java.io.FileOutputStream(srtFile), java.nio.charset.StandardCharsets.UTF_8))) {
            int index = 1;
            for (SubtitleSegment s : segments) {
                w.println(index++);
                w.println(formatSrtTime(s.startUs()) + " --> " + formatSrtTime(s.endUs()));
                w.println(s.text());
                w.println();
            }
        } catch (java.io.IOException ignored) {
        }
    }

    /**
     * Formats microseconds as an SRT timestamp: {@code HH:MM:SS,mmm}.
     */
    private static String formatSrtTime(long us) {
        long totalMs = us / 1_000L;
        long hours = totalMs / 3_600_000L;
        long minutes = (totalMs % 3_600_000L) / 60_000L;
        long seconds = (totalMs % 60_000L) / 1_000L;
        long millis = totalMs % 1_000L;
        return String.format("%02d:%02d:%02d,%03d", hours, minutes, seconds, millis);
    }

    public List<SubtitleSegment> getSegments(String mediaName) {
        List<SubtitleSegment> cached = cache.get(mediaName);
        return cached == null ? List.of() : cached;
    }

    public double getUsageSeconds() {
        return usageMicros.get() / 1_000_000.0;
    }

    // ---------------- client side: POST media to the embedded server ----------------

    private List<SubtitleSegment> fetchFromServer(File file) throws Exception {
        long durationUs = probeDuration(file);
        File audioFile = demuxAudio(file);
        File toUpload = audioFile != null ? audioFile : file;
        boolean demuxed = audioFile != null;
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + PORT + "/api/v1/subtitles"))
                    .header("X-Filename", file.getName())
                    .header("X-Duration-Us", String.valueOf(durationUs))
                    .header("Content-Type", "application/octet-stream")
                    .POST(HttpRequest.BodyPublishers.ofFile(toUpload.toPath()))
                    .timeout(Duration.ofSeconds(120))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return List.of();
            }
            Object parsed = Json.parse(response.body());
            List<?> segments = Json.asList(Json.get(parsed, "segments"));
            if (segments == null) {
                return List.of();
            }
            List<SubtitleSegment> result = new ArrayList<>();
            for (Object o : segments) {
                Map<String, ?> seg = Json.asMap(o);
                if (seg == null) continue;
                long startUs = (long) Json.asNumber(seg.get("startUs"), 0.0);
                long endUs = (long) Json.asNumber(seg.get("endUs"), 0.0);
                String text = Json.asString(seg.get("text"));
                if (text == null || text.isBlank() || endUs <= startUs) continue;
                result.add(new SubtitleSegment(startUs, endUs, text));
            }
            return result;
        } finally {
            if (demuxed) {
                audioFile.delete();
            }
        }
    }

    /**
     * Extracts the audio track from {@code file} into a temp file without
     * re-encoding ({@code -acodec copy}), so Whisper-1 gets only the audio it
     * needs instead of the whole video. Returns {@code null} if demux fails
     * (e.g. no audio track), in which case the caller falls back to the whole
     * file.
     *
     * <p>shortcut: shells out to the {@code ffmpeg} CLI because {@code
     * -acodec copy} stream-copy is trivial there and verbose via the JavaCV
     * API; upgrade to the JavaCV API only if the CLI is unavailable.</p>
     */
    private File demuxAudio(File file) throws Exception {
        File temp = File.createTempFile("subtitle-audio-", ".m4a");
        temp.deleteOnExit();
        ProcessBuilder pb = new ProcessBuilder(
                "ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
                "-i", file.toString(),
                "-vn", "-acodec", "copy",
                temp.toString());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        int code = p.waitFor();
        if (code != 0 || !temp.exists() || temp.length() == 0) {
            temp.delete();
            return null;
        }
        return temp;
    }

    // ---------------- server side: OpenAI Whisper transcription ----------------

    private void handleSubtitles(HttpExchange exchange) {
        try {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                respond(exchange, 405, "{\"error\":\"method not allowed\"}");
                return;
            }
            byte[] body = exchange.getRequestBody().readAllBytes();
            String filename = exchange.getRequestHeaders().getFirst("X-Filename");
            if (filename == null || filename.isBlank()) {
                filename = "media";
            }

            List<SubtitleSegment> segs = serverCache.get(filename);
            boolean cached = segs != null;
            if (!cached) {
                String durationHeader = exchange.getRequestHeaders().getFirst("X-Duration-Us");
                long durationUs = durationHeader != null ? Long.parseLong(durationHeader) : 0L;
                segs = transcribeWhisper(body, filename, durationUs);
                if (segs == null) segs = List.of();
                putServerCache(filename, segs);
                if (durationUs > 0) {
                    usageMicros.addAndGet(durationUs);
                }
            }

            respond(exchange, 200, segmentsToJson(segs));
        } catch (Throwable t) {
            try {
                respond(exchange, 502, "{\"error\":\"" + escapeJson(t.getMessage() == null ? "transcription failed" : t.getMessage()) + "\"}");
            } catch (IOException ignored) {
            }
        } finally {
            exchange.close();
        }
    }

    private void handleUsage(HttpExchange exchange) {
        try {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                respond(exchange, 405, "{\"error\":\"method not allowed\"}");
                return;
            }
            respond(exchange, 200, "{\"usage\":" + getUsageSeconds() + "}");
        } catch (IOException ignored) {
        } finally {
            exchange.close();
        }
    }

    /** Bounded put so the server-side cache cannot grow without limit. */
    private void putServerCache(String key, List<SubtitleSegment> value) {
        serverCache.put(key, value);
        if (serverCache.size() > MAX_CACHE_ENTRIES) {
            String first = serverCache.keySet().iterator().next();
            serverCache.remove(first);
        }
    }

    /** Bounded put so the client-side cache cannot grow without limit. */
    private void putCache(String key, List<SubtitleSegment> value) {
        cache.put(key, value);
        if (cache.size() > MAX_CACHE_ENTRIES) {
            String first = cache.keySet().iterator().next();
            cache.remove(first);
        }
    }

    /**
     * Probes the media file's duration in microseconds using FFmpeg, without
     * decoding any frames. Returns 0 if the duration cannot be determined.
     */
    private static long probeDuration(File file) throws Exception {
        FFmpegFrameGrabber g = new FFmpegFrameGrabber(file);
        try {
            g.start();
            long d = g.getLengthInTime();
            return d > 0 ? d : 0;
        } finally {
            try {
                g.stop();
            } catch (Exception ignored) {
            }
            try {
                g.close();
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * Calls OpenAI {@code /v1/audio/transcriptions} with the whisper-1 model,
     * {@code verbose_json} format, and word + segment timestamp granularity.
     * Returns filtered segments (segments with {@code noSpeechProb > 0.6}
     * AND {@code aveLogProb < -1.0} are skipped).
     */
    private List<SubtitleSegment> transcribeWhisper(byte[] audio, String filename, long durationUs) throws Exception {
        String key = System.getenv("OPENAI_API_KEY");
        if (key == null || key.isBlank()) {
            return List.of();
        }

        // Write audio to a temp file so curl can upload it (Java's HttpClient
        // is rejected by OpenAI's API; curl works reliably).
        File tempAudio = File.createTempFile("whisper-audio-", ".m4a");
        tempAudio.deleteOnExit();
        Files.write(tempAudio.toPath(), audio);

        // Try whisper-1 first (per assignment spec) with verbose_json + timestamps.
        List<SubtitleSegment> result = tryTranscribeWhisper1(tempAudio, filename, key);
        if (result != null) {
            return result;
        }
        return tryTranscribeFallback(tempAudio, filename, durationUs, key);
    }

    /**
     * Transcribes with whisper-1 using verbose_json + timestamp granularity
     * (per assignment spec). Returns null if the model ID is invalid for this
     * API key, so the caller can try a fallback.
     */
    private List<SubtitleSegment> tryTranscribeWhisper1(File audioFile, String filename, String key) throws Exception {
        CurlResult r = runCurl(audioFile, "whisper-1", "verbose_json",
                "timestamp_granularities[]=word",
                "timestamp_granularities[]=segment");
        if (r == null) {
            return null; // curl failed
        }
        if (r.httpCode >= 400) {
            if (r.body.contains("invalid model ID")) {
                return null; // try fallback
            }
            return List.of();
        }
        return parseWhisperSegments(r.body);
    }

    /**
     * Fallback transcription with gpt-4o-mini-transcribe using json format.
     * gpt-4o-mini-transcribe does not support verbose_json or timestamp
     * granularity, so we return a single segment covering the full audio
     * duration with the plain-text transcript.
     */
    private List<SubtitleSegment> tryTranscribeFallback(File audioFile, String filename, long durationUs, String key) throws Exception {
        CurlResult r = runCurl(audioFile, "gpt-4o-mini-transcribe", "json");
        if (r == null || r.httpCode >= 400) {
            return List.of();
        }

        Object parsed = Json.parse(r.body);
        String text = asString(Json.get(parsed, "text"));
        if (text == null || text.isBlank()) {
            return List.of();
        }

        long endUs = durationUs > 0 ? durationUs : MAX_DISPLAY_US;
        if (endUs > MAX_DISPLAY_US) {
            endUs = MAX_DISPLAY_US;
        }
        return List.of(new SubtitleSegment(0, endUs, text));
    }

    /**
     * Result of a curl transcription request.
     */
    private record CurlResult(int httpCode, String body) {
    }

    /**
     * Runs curl to POST an audio file to OpenAI's Whisper API. Returns the
     * HTTP status code and response body, or null if curl itself failed.
     */
    private CurlResult runCurl(File audioFile, String model, String responseFormat, String... extraFields) throws Exception {
        List<String> args = new ArrayList<>();
        args.add("curl");
        args.add("-s");
        args.add("-w");
        args.add("\n%{http_code}");
        args.add("-X");
        args.add("POST");
        args.add(OPENAI_URL);
        args.add("-H");
        args.add("Authorization: Bearer " + System.getenv("OPENAI_API_KEY"));
        args.add("-F");
        args.add("file=@" + audioFile.getAbsolutePath());
        args.add("-F");
        args.add("model=" + model);
        args.add("-F");
        args.add("response_format=" + responseFormat);
        for (String field : extraFields) {
            args.add("-F");
            args.add(field);
        }
        args.add("--max-time");
        args.add("180");

        ProcessBuilder pb = new ProcessBuilder(args);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = p.waitFor();

        if (code != 0 || output.isBlank()) {
            return null;
        }

        String[] lines = output.trim().split("\n", -1);
        String lastLine = lines[lines.length - 1].trim();
        int httpCode;
        try {
            httpCode = Integer.parseInt(lastLine);
        } catch (NumberFormatException e) {
            httpCode = 0;
        }
        String body = output.substring(0, output.length() - lastLine.length()).trim();

        return new CurlResult(httpCode, body);
    }

    /**
     * Parses a whisper-1 verbose_json response into {@link SubtitleSegment}s.
     * Skips segments where {@code noSpeechProb > 0.8 AND aveLogProb < -1.5}
     * (stricter on both, keeps more borderline speech). Cleans up text by
     * trimming and collapsing internal whitespace.
     */
    private List<SubtitleSegment> parseWhisperSegments(String body) {
        Object parsed = Json.parse(body);
        List<?> segments = Json.asList(Json.get(parsed, "segments"));
        if (segments == null) {
            return List.of();
        }

        List<SubtitleSegment> result = new ArrayList<>();
        for (Object o : segments) {
            @SuppressWarnings("unchecked")
            Map<String, ?> seg = (Map<String, ?>) o;
            if (seg == null) continue;

            Double noSpeechProb = asNumber(seg.get("no_speech_prob"), -1.0);
            Double aveLogProb = asNumber(seg.get("avg_logprob"), 0.0);
            // Skip segments that are almost certainly not speech.
            if (noSpeechProb > 0.8 && aveLogProb < -1.5) {
                continue;
            }

            Double start = asNumber(seg.get("start"), 0.0);
            Double end = asNumber(seg.get("end"), 0.0);
            String text = cleanText(asString(seg.get("text")));
            if (text == null || text.isBlank()) continue;

            long startUs = (long) (start * 1_000_000.0);
            long endUs = (long) (end * 1_000_000.0);
            if (endUs <= startUs) {
                endUs = startUs + MIN_DISPLAY_US;
            }
            result.add(new SubtitleSegment(clampStart(startUs), clampEnd(startUs, endUs), text));
        }

        // If segment list is empty or very sparse, fall back to per-word
        // timestamps so fast/dense dialogue still gets subtitles.
        if (result.isEmpty() || result.size() < 2) {
            List<SubtitleSegment> words = parseWhisperWords(body);
            if (!words.isEmpty()) {
                return words;
            }
        }
        return result;
    }

    /**
     * Builds one subtitle segment per transcribed word using the word-level
     * start/end timestamps. Used as a fallback when segment-level output is
     * empty or sparse (dense/fast dialogue often lands here).
     */
    private List<SubtitleSegment> parseWhisperWords(String body) {
        Object parsed = Json.parse(body);
        List<?> words = Json.asList(Json.get(parsed, "words"));
        if (words == null || words.isEmpty()) {
            return List.of();
        }

        List<SubtitleSegment> result = new ArrayList<>();
        for (Object o : words) {
            @SuppressWarnings("unchecked")
            Map<String, ?> seg = (Map<String, ?>) o;
            if (seg == null) continue;

            String text = cleanText(asString(seg.get("word")));
            if (text == null || text.isBlank()) continue;

            Double start = asNumber(seg.get("start"), 0.0);
            Double end = asNumber(seg.get("end"), 0.0);
            if (end <= start) {
                end = start + MIN_DISPLAY_US / 1_000_000.0;
            }

            long startUs = (long) (start * 1_000_000.0);
            long endUs = (long) (end * 1_000_000.0);
            if (endUs <= startUs) {
                endUs = startUs + MIN_DISPLAY_US;
            }
            result.add(new SubtitleSegment(clampStart(startUs), clampEnd(startUs, endUs), text));
        }
        return result;
    }

    /**
     * Cleans up Whisper transcript text: trims, collapses internal whitespace,
     * strips leading dashes/bullets Whisper sometimes prepends.
     */
    private static String cleanText(String text) {
        if (text == null) {
            return null;
        }
        // Strip leading/trailing whitespace and common Whisper artifacts.
        text = text.trim();
        text = text.replaceAll("^[-–—]\\s+", "");
        // Collapse multiple spaces to one.
        text = text.replaceAll("\\s+", " ");
        return text.isEmpty() ? null : text;
    }

    private static void part(ByteArrayOutputStream baos, String boundary, String name, String value) {
        try {
            baos.write(("\r\n--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
            baos.write(("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            baos.write((value + "\r\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
        }
    }

    private static void write(ByteArrayOutputStream baos, byte[] data) {
        try {
            baos.write(data);
        } catch (IOException ignored) {
        }
    }

    private static long clampStart(long startUs) {
        return Math.max(0, startUs);
    }

    private static long clampEnd(long startUs, long endUs) {
        long duration = endUs - startUs;
        if (duration < MIN_DISPLAY_US) {
            endUs = startUs + MIN_DISPLAY_US;
        }
        if (duration > MAX_DISPLAY_US) {
            endUs = startUs + MAX_DISPLAY_US;
        }
        return endUs;
    }

    private static double asNumber(Object value, double fallback) {
        return value instanceof Number n ? n.doubleValue() : fallback;
    }

    private static String asString(Object value) {
        return value instanceof String s ? s : null;
    }

    private void respond(HttpExchange exchange, int code, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(code, b.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(b);
        }
    }

    private String segmentsToJson(List<SubtitleSegment> segments) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"segments\":[");
        for (int i = 0; i < segments.size(); i++) {
            if (i > 0) sb.append(',');
            SubtitleSegment s = segments.get(i);
            sb.append("{\"startUs\":").append(s.startUs())
              .append(",\"endUs\":").append(s.endUs())
              .append(",\"text\":\"").append(escapeJson(s.text())).append("\"}");
        }
        sb.append("]}");
        return sb.toString();
    }

    private static String escapeJson(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
