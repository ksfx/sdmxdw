package de.sdmxdw.web.explore;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Tooltips and page header use the first sentence / paragraph of a definition (#23). */
class FieldSummaryTest {

  @Test
  void firstSentenceSkipsAbbreviations() {
    assertThat(
            ExploreMicrodataFlowController.summary(
                "Dieses Feld erfasst die Limite gemäss Art. 53 Abs. 3 ERV. Weitere Details."))
        .isEqualTo("Dieses Feld erfasst die Limite gemäss Art. 53 Abs. 3 ERV.");
    assertThat(ExploreMicrodataFlowController.summary("z.B. Aktien, **Anleihen**.\n\nMehr."))
        .isEqualTo("z.B. Aktien, Anleihen.");
    assertThat(
            ExploreMicrodataFlowController.summary(
                "Einzelnes Kreditprodukt, z. B. Festhypothek. Weiter."))
        .isEqualTo("Einzelnes Kreditprodukt, z. B. Festhypothek.");
    assertThat(ExploreMicrodataFlowController.summary(null)).isNull();
  }

  @Test
  void firstParagraph() {
    assertThat(ExploreMicrodataFlowController.firstParagraph("  A b.\n\nC d.")).isEqualTo("A b.");
    assertThat(ExploreMicrodataFlowController.firstParagraph("")).isNull();
  }
}
