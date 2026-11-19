package comp3011;

import java.awt.Color;
import java.awt.Font;
import java.awt.font.FontRenderContext;
import java.awt.font.LineMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import org.bytedeco.javacv.Frame;

/**
 * Renders speech-to-text subtitles onto each frame while subtitles are enabled.
 *
 * <p>Segments are fetched asynchronously from the embedded subtitle server
 * ({@link SubtitleService}) and cached by media name; rendering itself is
 * synchronous and deterministic once the segments for a media are known, so it
 * honours the {@link FrameProcessor} contract (no shared mutable state in
 * {@link #process}).</p>
 *
 * <p>Style: white sans-serif text with a black outline, bottom centre, at most
 * 42 characters per line and at most two lines (one preferred).</p>
 */
public class SubtitleFrameProcessor extends FrameProcessor {
    private static final int MAX_CHARS_PER_LINE = 42;
    private static final int MAX_LINES = 2;
    private static final int OUTLINE_ALPHA_MIN = 64;

    public SubtitleFrameProcessor() {
    }

    @Override
    public void process(Frame frame, InfoFrame info) throws Exception {
        if (videoInfo == null) {
            return;
        }
        if (frame.image == null || frame.image.length == 0 || frame.image[0] == null) {
            return;
        }
        if (!(frame.image[0] instanceof ByteBuffer pixels)) {
            throw new Exception("SubtitleFrameProcessor requires byte-backed image frames");
        }
        if (videoInfo.imageDepth() != Frame.DEPTH_UBYTE && videoInfo.imageDepth() != Frame.DEPTH_BYTE) {
            throw new Exception("SubtitleFrameProcessor requires 8-bit image frames");
        }

        int channels = videoInfo.imageChannels();
        if (channels < 1 || channels > 4) {
            throw new Exception("SubtitleFrameProcessor requires at least one image channel");
        }

        SubtitleService service = SubtitleService.get();
        if (!service.isEnabled()) {
            return;
        }

        List<SubtitleSegment> segments = service.ensureSegments(videoInfo.mediaName());
        if (segments.isEmpty()) {
            return;
        }

        long nowUs = info.timestampUs();
        SubtitleSegment active = null;
        for (SubtitleSegment segment : segments) {
          if (nowUs < segment.startUs() || nowUs > segment.endUs()) continue;
          active = segment;
          break;
        }
        if (active == null || active.text() == null || active.text().isBlank()) {
          return;
        }
        try {
          List<String> allLines = this.wrapLines(active.text());
          List<String> lines = this.pageFor(allLines, nowUs, active.startUs(), active.endUs());
          this.renderSubtitle(pixels, lines);
        } catch (Throwable throwable) {
        }
    }

    private void renderSubtitle(ByteBuffer pixels, List<String> lines) {
        if (lines.isEmpty()) {
            return;
        }

        // Font size scales with video height for readability on large screens.
        int fontSize = Math.max(24, Math.min(72, this.videoInfo.imageHeight() / 18));
        Font font = new Font("SansSerif", Font.BOLD, fontSize);
        FontRenderContext frc = new FontRenderContext(null, true, true);
        LineMetrics lm = font.getLineMetrics("Ag", frc);
        int ascent = (int) Math.ceil(lm.getAscent());
        int descent = (int) Math.ceil(lm.getDescent());
        int lineHeight = ascent + descent;
        int outlineWidth = 1;
        int padX = 1;
        int padY = 20;

        int stride = videoInfo.imageStride();
        int channels = videoInfo.imageChannels();
        int imageWidth = videoInfo.imageWidth();
        int imageHeight = videoInfo.imageHeight();

        // Render each line into its own correctly-sized image so short lines
        // Dont get padded to the width of the longest line (which looked like
        // a giant empty outline around small text).
        for (int lineIdx = 0; lineIdx < lines.size(); lineIdx++) {
            String line = lines.get(lineIdx);
            int lineWidth = (int) Math.ceil(font.getStringBounds(line, frc).getWidth());
            int width = Math.min(lineWidth + padX * 2, imageWidth * 3 / 4);
            int height = lineHeight + padY * 2;

            BufferedImage block = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = block.createGraphics();
            g.setFont(font);
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);

                // Semi-transparent dark background box hugging this line's text.
                g.setColor(new Color(0, 0, 0, 160));
                g.fillRoundRect(0, 0, width, height, 8, 8);

                // Thin black outline: draw glyphs at the 4 cardinal offsets.
            int baseline = padY + ascent;
            int textX = (width - lineWidth) / 2;

            g.setColor(Color.BLACK);
            int[] offsets = new int[]{-outlineWidth, 0, outlineWidth};
            for (int dx : offsets) {
              for (int dy : offsets) {
                if (dx == 0 && dy == 0) continue;
                g.drawString(line, textX + dx, baseline + dy);
              }
            }

            g.setColor(Color.WHITE);
            g.drawString(line, textX, baseline);
            } finally {
                g.dispose();
            }

            int[] rgb = new int[width * height];
            block.getRGB(0, 0, width, height, rgb, 0, width);

            int startX = Math.max(100, (imageWidth - width) / 2);
            int startY = Math.max(50, imageHeight - imageHeight / 3) + lineIdx * (height + 4);

            for (int py = 0; py < height; py++) {
                int fy = startY + py;
                if (fy >= imageHeight) continue;
                int rowBase = fy * stride;
                for (int px = 0; px < width; px++) {
                    int fx = startX + px;
                    if (fx >= imageWidth) continue;
                    int argb = rgb[py * width + px];
                    int alpha = ((argb >> 24) & 0xff);
                    if (alpha < OUTLINE_ALPHA_MIN) continue;
                    int idx = rowBase + fx * channels;
                    pixels.put(idx, (byte) ((argb >> 16) & 0xff));
                    if (channels > 1) pixels.put(idx + 1, (byte) ((argb >> 8) & 0xff));
                    if (channels > 2) pixels.put(idx + 2, (byte) (argb & 0xff));
                    if (channels > 3) pixels.put(idx + 3, (byte) alpha);
                }
            }
        }
    }

    /**
     * Wraps {@code text} into at most {@link #MAX_LINES} lines of at most
     * {@link #MAX_CHARS_PER_LINE} characters, preferring a single line. Breaks
     * at word boundaries; a single over-long word is truncated.
     *
     * <p>shortcut: breaks at spaces rather than after punctuation; upgrade to a
     * punctuation-aware break when the design is refined.</p>
     */
    private List<String> wrapLines(String text) {
      String[] words = text.split(" ");
      ArrayList<String> lines = new ArrayList<>();
      StringBuilder cur = new StringBuilder();
      for (String word : words) {
        String add = cur.length() == 0 ? word : " " + word;
          if (cur.length() + add.length() <= MAX_CHARS_PER_LINE) {
              cur.append(add);
              continue;
          }
        lines.add(cur.length() == 0 ? truncate(word, MAX_CHARS_PER_LINE) : cur.toString());
        cur = new StringBuilder(truncate(word, MAX_CHARS_PER_LINE));
      }
      if (cur.length() > 0) {
          lines.add(cur.toString());
      }
      if (lines.isEmpty()) {
          lines.add(text);
      }
      return lines;
    }

    private static String truncate(String s, int n) {
        return s.length() > n ? s.substring(0, n) : s;
    }

    private List<String> pageFor(List<String> allLines, long nowUs, long startUs, long endUs) {
      int totalPages = (int) Math.ceil(allLines.size() / (double) MAX_LINES);
      if (totalPages <= 1) {
        return allLines;
      }
      long duration = Math.max(1, endUs - startUs);
      long elapsed = Math.min(duration, Math.max(0, nowUs - startUs));
      int page = (int) ((elapsed * totalPages) / duration);
      page = Math.min(page, totalPages - 1);
      int from = page * MAX_LINES;
      int to = Math.min(from + MAX_LINES, allLines.size());
      return allLines.subList(from, to);
    }
}
