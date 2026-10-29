package de.sdmxdw.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MarkdownRendererTest {

  private final MarkdownRenderer renderer = new MarkdownRenderer();

  @Test
  void rendersHeadingsTablesAndAutolinks() {
    String html =
        renderer.render("# Title\n\n| A | B |\n|---|---|\n| 1 | 2 |\n\nSee https://snb.ch");
    assertThat(html).contains("<h1>Title</h1>", "<table>", "href=\"https://snb.ch\"");
  }

  @Test
  void escapesRawHtmlAndDropsScriptLinks() {
    String html = renderer.render("<script>alert(1)</script>\n\n[x](javascript:alert(1))");
    assertThat(html).doesNotContain("<script>").doesNotContain("javascript:");
    assertThat(html).contains("&lt;script&gt;");
  }

  @Test
  void blankIsEmpty() {
    assertThat(renderer.render(null)).isEmpty();
    assertThat(renderer.render("  ")).isEmpty();
  }
}
