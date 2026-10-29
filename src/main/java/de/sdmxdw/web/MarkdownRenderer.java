package de.sdmxdw.web;

import java.util.List;
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
}
