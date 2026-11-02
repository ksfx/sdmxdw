package de.sdmxdw.web;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.commonmark.Extension;
import org.commonmark.ext.autolink.AutolinkExtension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.springframework.stereotype.Component;

/**
 * Renders user-maintained Markdown (category scheme descriptions) to HTML. Raw HTML in the source
 * is escaped and javascript:/data: link targets are dropped, so the output can be inserted with
 * th:utext.
 */
@Component
public class MarkdownRenderer {

  private static final List<Extension> EXTENSIONS =
      List.of(AutolinkExtension.create(), TablesExtension.create());

  private final Parser parser = Parser.builder().extensions(EXTENSIONS).build();
  private final HtmlRenderer renderer =
      HtmlRenderer.builder()
          .extensions(EXTENSIONS)
          .escapeHtml(true)
          .sanitizeUrls(true)
          .softbreak("<br>")
          .build();

  public String render(String markdown) {
    if (markdown == null || markdown.isBlank()) {
      return "";
    }
    return renderer.render(parser.parse(markdown));
  }

  /** First paragraph of a Markdown text (the summary shown in the page header). */
  public static String firstParagraph(String text) {
    if (text == null || text.isBlank()) {
      return null;
    }
    String trimmed = text.strip();
    int end = trimmed.indexOf("\n\n");
    return end < 0 ? trimmed : trimmed.substring(0, end);
  }

  /**
   * A full stop before a capital letter, but not after abbreviations like "Art.", "z. B." or "d.
   * h.".
   */
  private static final Pattern SENTENCE_END =
      Pattern.compile(
          "(?<!\\b\\p{L})(?<!\\b(?:Art|Abs|Ziff|Nr|Kap|bzw|vgl|ca|Bst|lit|al|bspw|inkl))"
              + "\\.\\s+(?=[A-ZÄÖÜ])");

  /** First sentence of a Markdown definition as plain text, for tooltips. */
  public static String summary(String description) {
    String paragraph = firstParagraph(description);
    if (paragraph == null) {
      return null;
    }
    String plain = paragraph.replaceAll("[*_`#>|]", "").replaceAll("\\s+", " ").strip();
    Matcher end = SENTENCE_END.matcher(plain);
    String sentence = end.find() ? plain.substring(0, end.start() + 1) : plain;
    return sentence.length() > 240 ? sentence.substring(0, 237) + "..." : sentence;
  }
}
