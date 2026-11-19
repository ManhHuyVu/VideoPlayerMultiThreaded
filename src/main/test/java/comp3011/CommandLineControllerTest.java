package comp3011;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CommandLineControllerTest {

    @TempDir
    Path tempDir;

    private String lastOutput;

    private CommandLineController parse(String... args) {
        PrintStream original = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
            return new CommandLineController(args);
        } finally {
            System.setOut(original);
            lastOutput = captured.toString(StandardCharsets.UTF_8);
        }
    }

    private Path videoFile(String name) throws IOException {
        return Files.createFile(tempDir.resolve(name));
    }

    private static List<Class<?>> processorTypes(CommandLineController controller) {
        List<Class<?>> types = new ArrayList<>();
        for (FrameProcessor processor : controller.getFrameProcessors()) {
            types.add(processor.getClass());
        }
        return types;
    }

    @Test
    void noArgumentsReportsAMissingVideoFile() {
        CommandLineController controller = parse();

        assertEquals("No video file specified.", controller.getErrorMessage());
        assertEquals(1, controller.getExitCode());
        assertFalse(controller.shouldLaunchApplication());
        assertNull(controller.getVideoFile());
        assertTrue(controller.getFrameProcessors().isEmpty());
        assertTrue(lastOutput.contains("No video file specified."));
    }

    @Test
    void helpAloneIsNotAnError() {
        CommandLineController controller = parse("-h");

        assertNull(controller.getErrorMessage());
        assertEquals(0, controller.getExitCode());
        assertFalse(controller.shouldLaunchApplication());
        assertTrue(lastOutput.contains("Usage: VideoPlayer"));
    }

    @Test
    void longHelpAloneIsNotAnError() {
        CommandLineController controller = parse("--help");

        assertNull(controller.getErrorMessage());
        assertTrue(lastOutput.contains("Usage: VideoPlayer"));
    }

    @Test
    void helpWithAValidFilePrintsUsageAndKeepsTheFile() throws IOException {
        Path file = videoFile("movie.mp4");

        CommandLineController controller = parse("--help", file.toString());

        assertNull(controller.getErrorMessage());
        assertEquals(file.toFile(), controller.getVideoFile());
        assertTrue(lastOutput.contains("Usage: VideoPlayer"));
    }

    @Test
    void validVideoFileIsAccepted() throws IOException {
        Path file = videoFile("movie.mp4");

        CommandLineController controller = parse(file.toString());

        assertNull(controller.getErrorMessage());
        assertEquals(0, controller.getExitCode());
        assertTrue(controller.shouldLaunchApplication());
        assertEquals(file.toFile(), controller.getVideoFile());
    }

    @Test
    void missingFileIsRejected() {
        Path missing = tempDir.resolve("missing.mp4");

        CommandLineController controller = parse(missing.toString());

        assertTrue(controller.getErrorMessage().startsWith("File not found:"));
        assertTrue(controller.getErrorMessage().contains(missing.toString()));
        assertNull(controller.getVideoFile());
        assertFalse(controller.shouldLaunchApplication());
        assertEquals(1, controller.getExitCode());
    }

    @Test
    void directoryIsNotAcceptedAsAVideoFile() {
        CommandLineController controller = parse(tempDir.toString());

        assertTrue(controller.getErrorMessage().startsWith("File not found:"));
        assertNull(controller.getVideoFile());
    }

    @Test
    void multipleVideoFilesAreRejected() throws IOException {
        Path first = videoFile("first.mp4");
        Path second = videoFile("second.mp4");

        CommandLineController controller = parse(first.toString(), second.toString());

        assertEquals("Usage: VideoPlayer [options] [video-file]", controller.getErrorMessage());
        assertNull(controller.getVideoFile());
        assertFalse(controller.shouldLaunchApplication());
    }

    @Test
    void audioFlagsAreRecognised() {
        assertTrue(parse("-a").isAudioRequested());
        assertTrue(parse("--audio").isAudioRequested());
        assertFalse(parse().isAudioRequested());
    }

    @Test
    void maximiseFlagsAreRecognised() {
        assertTrue(parse("-x").isMaximiseRequested());
        assertTrue(parse("--maximise").isMaximiseRequested());
        assertFalse(parse().isMaximiseRequested());
    }

    @Test
    void displayFlagsAreRecognised() {
        assertEquals(1, parse("-1").getDisplayId());
        assertEquals(1, parse("--monitor-1").getDisplayId());
        assertEquals(2, parse("-2").getDisplayId());
        assertEquals(2, parse("--monitor-2").getDisplayId());
        assertNull(parse().getDisplayId());
    }

    @Test
    void conflictingDisplayFlagsAreRejected() throws IOException {
        Path file = videoFile("movie.mp4");

        CommandLineController controller = parse("-1", "-2", file.toString());

        assertEquals("Only one display option can be used", controller.getErrorMessage());
        assertFalse(controller.shouldLaunchApplication());
    }

    @Test
    void repeatingTheSameDisplayFlagIsAllowed() throws IOException {
        Path file = videoFile("movie.mp4");

        CommandLineController controller = parse("-1", "--monitor-1", file.toString());

        assertNull(controller.getErrorMessage());
        assertEquals(1, controller.getDisplayId());
    }

    @Test
    void stackedShortEffectFlagsKeepTheirOrder() throws IOException {
        Path file = videoFile("movie.mp4");

        CommandLineController controller = parse("-nsfwyvdjmbp", file.toString());

        assertNull(controller.getErrorMessage());
        assertEquals(List.of(
                FrameNumberer.class,
                FrameScratcher.class,
                FrameFlickerer.class,
                FrameBlackAndWhiter.class,
                FrameYellower.class,
                FrameVignetter.class,
                FrameDuster.class,
                FrameJitterer.class,
                FrameMottler.class,
                FrameBleeder.class,
                FramePepperer.class), processorTypes(controller));
    }

    @Test
    void longEffectFlagsKeepTheirOrder() throws IOException {
        Path file = videoFile("movie.mp4");

        CommandLineController controller = parse(
                "--pepper-frames",
                "--number-frames",
                "--bleed-frames",
                "--scratch-frames",
                file.toString());

        assertEquals(List.of(
                FramePepperer.class,
                FrameNumberer.class,
                FrameBleeder.class,
                FrameScratcher.class), processorTypes(controller));
    }

    @Test
    void mixedShortAndLongFlagsKeepTheirOrder() throws IOException {
        Path file = videoFile("movie.mp4");

        CommandLineController controller = parse(
                "-n",
                "--scratch-frames",
                "-fw",
                "--vignette-frames",
                file.toString());

        assertEquals(List.of(
                FrameNumberer.class,
                FrameScratcher.class,
                FrameFlickerer.class,
                FrameBlackAndWhiter.class,
                FrameVignetter.class), processorTypes(controller));
    }

    @Test
    void repeatedEffectsCreateSeparateInstances() throws IOException {
        Path file = videoFile("movie.mp4");

        CommandLineController controller = parse("-ss", file.toString());
        List<FrameProcessor> processors = controller.getFrameProcessors();

        assertEquals(2, processors.size());
        assertEquals(FrameScratcher.class, processors.get(0).getClass());
        assertEquals(FrameScratcher.class, processors.get(1).getClass());
        assertNotSame(processors.get(0), processors.get(1));
    }

    @Test
    void unknownLongOptionIsRejected() throws IOException {
        Path file = videoFile("movie.mp4");

        CommandLineController controller = parse("--bogus", file.toString());

        assertEquals("Unknown option: --bogus", controller.getErrorMessage());
        assertEquals(1, controller.getExitCode());
        assertFalse(controller.shouldLaunchApplication());
    }

    @Test
    void unknownShortOptionStopsParsingTheStack() throws IOException {
        Path file = videoFile("movie.mp4");

        CommandLineController controller = parse("-nq", file.toString());

        assertEquals("Unknown option: -q", controller.getErrorMessage());
        assertEquals(List.of(FrameNumberer.class), processorTypes(controller));
    }

    @Test
    void optionsAndTheVideoFileMayAppearInAnyOrder() throws IOException {
        Path file = videoFile("movie.mp4");

        CommandLineController controller = parse("-a", file.toString(), "-s");

        assertNull(controller.getErrorMessage());
        assertEquals(file.toFile(), controller.getVideoFile());
        assertTrue(controller.isAudioRequested());
        assertEquals(List.of(FrameScratcher.class), processorTypes(controller));
    }

    @Test
    void controllerUsesItsOwnCopyOfTheArguments() throws IOException {
        Path file = videoFile("movie.mp4");
        String[] args = {file.toString()};

        CommandLineController controller = new CommandLineController(args);
        args[0] = "changed.mp4";

        assertEquals(file.toFile(), controller.getVideoFile());
    }
}
