package comp3011;

/**
 * A single subtitle entry: the text to show and the media-time window (in
 * microseconds) during which it should be visible.
 */
public record SubtitleSegment(
        long startUs,
        long endUs,
        String text) {
}
