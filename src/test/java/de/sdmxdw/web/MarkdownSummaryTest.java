package de.sdmxdw.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Tooltips and page header use the first sentence / paragraph of a definition (#23). */
class MarkdownSummaryTest {

  @Test
  void firstSentenceSkipsAbbreviations() {
    assertThat(
            MarkdownRenderer.summary(
                "Dieses Feld erfasst die Limite gemäss Art. 53 Abs. 3 ERV. Weitere Details."))
        .isEqualTo("Dieses Feld erfasst die Limite gemäss Art. 53 Abs. 3 ERV.");
    assertThat(MarkdownRenderer.summary("z.B. Aktien, **Anleihen**.\n\nMehr."))
        .isEqualTo("z.B. Aktien, Anleihen.");
    assertThat(MarkdownRenderer.summary("Einzelnes Kreditprodukt, z. B. Festhypothek. Weiter."))
        .isEqualTo("Einzelnes Kreditprodukt, z. B. Festhypothek.");
    assertThat(MarkdownRenderer.summary(null)).isNull();
  }

  @Test
  void firstParagraph() {
    assertThat(MarkdownRenderer.firstParagraph("  A b.\n\nC d.")).isEqualTo("A b.");
    assertThat(MarkdownRenderer.firstParagraph("")).isNull();
  }
}
