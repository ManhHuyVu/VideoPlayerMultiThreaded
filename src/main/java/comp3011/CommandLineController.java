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
import java.util.ArrayList;
import java.util.List;

/**
 * Created in the VideoPlayerApp's main function to receive and parse the video player's command-line arguments.
 *
 * <p>
 * Extracts the video file and supported launch options, reports invalid input
 * or help text, and exposes the resulting launch configuration via methods to the
 * {@link VideoPlayerApp}.
 * </p>
 */
public class CommandLineController {
    private final String[] args;

    private boolean helpRequested;
    private boolean audioRequested;
    private boolean maximiseRequested;
    private Integer displayId;
    private File videoFile;
    private String errorMessage;
    private final List<FrameProcessor> frameProcessors = new ArrayList<>();

    public CommandLineController(String[] args) {
        this.args = args.clone();
        parse();
        if (errorMessage != null) {
            System.out.println(errorMessage);
        }
        if (helpRequested) {
            printHelp();
        }
    }

    public File getVideoFile() {
        return videoFile;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public int getExitCode() {
        return errorMessage == null ? 0 : 1;
    }

    public Integer getDisplayId() {
        return displayId;
    }

    public boolean isAudioRequested() {
        return audioRequested;
    }

    public boolean isMaximiseRequested() {
        return maximiseRequested;
    }

    public boolean shouldLaunchApplication() {
        return errorMessage == null && videoFile != null;
    }

    public List<FrameProcessor> getFrameProcessors() {
        return frameProcessors;
    }

    private void parse() {
        List<String> videoFiles = new ArrayList<>();
        for (String arg : args) {
            if ("-h".equals(arg) || "--help".equals(arg)) {
                helpRequested = true;
            } else if ("-a".equals(arg) || "--audio".equals(arg)) {
                audioRequested = true;
            } else if ("-x".equals(arg) || "--maximise".equals(arg)) {
                maximiseRequested = true;
            } else if ("-1".equals(arg) || "--monitor-1".equals(arg)) {
                setDisplayId(1);
            } else if ("-2".equals(arg) || "--monitor-2".equals(arg)) {
                setDisplayId(2);
            } else if (arg.startsWith("--")) {
                parseLongOption(arg); // --long-option
            } else if (arg.startsWith("-")) {
                parseShortOptions(arg); // -abc (stacked)
            } else {
                videoFiles.add(arg); // Positional: video file
            }
        }

        if (videoFiles.size() > 1) {
            errorMessage = "Usage: VideoPlayer [options] [video-file]";
        } else if (videoFiles.size() == 1) {
            videoFile = new File(videoFiles.get(0));
            if (!videoFile.isFile()) {
                errorMessage = "File not found: " + videoFile.getPath();
                videoFile = null;
            }
        } else {
            if (!helpRequested) {
                errorMessage = "No video file specified.";
            }
        }
    }

    private void parseLongOption(String arg) {
        switch (arg) {
            case "--number-frames":
                frameProcessors.add(new FrameNumberer());
                break;
            case "--scratch-frames":
                frameProcessors.add(new FrameScratcher());
                break;
            case "--flicker-frames":
                frameProcessors.add(new FrameFlickerer());
                break;
            case "--black-and-white":
                frameProcessors.add(new FrameBlackAndWhiter());
                break;
            case "--yellow-frames":
                frameProcessors.add(new FrameYellower());
                break;
            case "--vignette-frames":
                frameProcessors.add(new FrameVignetter());
                break;
            default:
                errorMessage = "Unknown option: " + arg;
        }
    }

    private void parseShortOptions(String arg) {
        for (int i = 1; i < arg.length(); i++) {
            char c = arg.charAt(i);
            switch (c) {
                case 'n':
                    frameProcessors.add(new FrameNumberer());
                    break;
                case 's':
                    frameProcessors.add(new FrameScratcher());
                    break;
                case 'f':
                    frameProcessors.add(new FrameFlickerer());
                    break;
                case 'w':
                    frameProcessors.add(new FrameBlackAndWhiter());
                    break;
                case 'y':
                    frameProcessors.add(new FrameYellower());
                    break;
                case 'v':
                    frameProcessors.add(new FrameVignetter());
                    break;
                default:
                    errorMessage = "Unknown option: -" + c;
            }
            if (errorMessage != null) {
                break;
            }
        }
        // Supports stacked: -nssnfwyvdjmbp
    }

    private void setDisplayId(int displayId) {
        if (this.displayId != null && this.displayId != displayId) {
            errorMessage = "Only one display option can be used";
            return;
        }
        this.displayId = displayId;
    }

    private void printHelp() {
        System.out.println("Usage: VideoPlayer [options] [video-file]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  -h, --help         Show this help message");
        System.out.println("  -a, --audio        Play audio");
        System.out.println("  -x, --maximise     Open the player maximised");
        System.out.println("  -1, --monitor-1    Open the player on display 1");
        System.out.println("  -2, --monitor-2    Open the player on display 2");
        System.out.println();
        System.out.println("Frame processors:");
        System.out.println("  -n, --number-frames    Render the frame number onto each frame");
        System.out.println("  -s, --scratch-frames   Render vertical film scratches");
        System.out.println("  -f, --flicker-frames   Randomly dim frames");
        System.out.println("  -w, --black-and-white  Convert frames to black and white");
        System.out.println("  -y, --yellow-frames    Apply a warmer colour temperature");
        System.out.println("  -v, --vignette-frames  Darken the frame edges");
        System.out.println("Frame processors are applied in command-line order and may be repeated.");
        System.out.println("Example: -nssnfwyvdjmbp numbers, scratches twice, numbers again, flickers,");
        System.out.println("converts, warms, vignettes, dusts, jitters, mottles, bleeds, then peppers.");
    }
}
