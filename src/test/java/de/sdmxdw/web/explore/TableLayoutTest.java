package de.sdmxdw.web.explore;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Layout of the table view from .Stat annotations, the URL or the heuristic (#15). */
class TableLayoutTest {

  private static final List<String> KRED =
      List.of("FREQ", "MELDEINSTITUT", "KREDITNEHMERGRUPPE", "KREDITNEHMER", "KOLONNE");
  private static final TableLayout.Request NONE = new TableLayout.Request(null, null, null);

  @Test
  void annotationsPlaceRowsColumnsAndDefaults() {
    TableLayout l =
        TableLayout.resolve(
            KRED,
            "TIME_PERIOD",
            Map.of(
                "LAYOUT_ROW", "KREDITNEHMER",
                "LAYOUT_COLUMN", " KOLONNE ,UNKNOWN",
                "DEFAULT", "MELDEINSTITUT=DEMO01+DEMO02,TIME_PERIOD_END=2026-Q1",
                "NOT_DISPLAYED", "FREQ,KOLONNE=99"),
            NONE,
            Map.of());
    assertThat(l.source()).isEqualTo(TableLayout.Source.ANNOTATIONS);
    assertThat(l.rows()).containsExactly("KREDITNEHMER");
    assertThat(l.columns()).containsExactly("KOLONNE");
    assertThat(l.sections()).isEmpty();
    assertThat(l.filters())
        .containsExactly("FREQ", "MELDEINSTITUT", "KREDITNEHMERGRUPPE", "TIME_PERIOD");
    assertThat(l.defaults()).containsEntry("MELDEINSTITUT", "DEMO01");
    assertThat(l.defaultTime()).isEqualTo("2026-Q1");
    assertThat(l.hiddenFilters()).containsExactly("FREQ");
    assertThat(l.hiddenCodes()).containsEntry("KOLONNE", java.util.Set.of("99"));
  }

  @Test
  void onlyRowsAnnotatedPutsTimeIntoTheColumns() {
    TableLayout l =
        TableLayout.resolve(
            KRED,
            "TIME_PERIOD",
            Map.of("LAYOUT_ROW", "KREDITNEHMER", "LAYOUT_TIME_PERIOD_DESC", ""),
            NONE,
            Map.of());
    assertThat(l.columns()).containsExactly("TIME_PERIOD");
    assertThat(l.filters()).doesNotContain("TIME_PERIOD");
    assertThat(l.timeDescending()).isTrue();
  }

  @Test
  void theUrlWinsAndDimensionsAppearOnce() {
    TableLayout l =
        TableLayout.resolve(
            KRED,
            "TIME_PERIOD",
            Map.of("LAYOUT_ROW", "KREDITNEHMER", "LAYOUT_COLUMN", "KOLONNE"),
            TableLayout.Request.parse(
                "KREDITNEHMERGRUPPE", "KOLONNE,KREDITNEHMERGRUPPE", "TIME_PERIOD"),
            Map.of());
    assertThat(l.source()).isEqualTo(TableLayout.Source.URL);
    assertThat(l.sections()).containsExactly("KREDITNEHMERGRUPPE");
    assertThat(l.rows()).containsExactly("KOLONNE");
    assertThat(l.columns()).containsExactly("TIME_PERIOD");
    assertThat(l.filters()).containsExactly("FREQ", "MELDEINSTITUT", "KREDITNEHMER");
  }

  @Test
  void withoutAnnotationsTheLargestDimensionsGoIntoRowsAndColumns() {
    TableLayout l =
        TableLayout.resolve(
            KRED,
            "TIME_PERIOD",
            Map.of(),
            NONE,
            Map.of(
                "FREQ", 1,
                "MELDEINSTITUT", 2,
                "KREDITNEHMERGRUPPE", 6,
                "KREDITNEHMER", 56,
                "KOLONNE", 30));
    assertThat(l.source()).isEqualTo(TableLayout.Source.HEURISTIC);
    assertThat(l.rows()).containsExactly("KREDITNEHMER");
    assertThat(l.columns()).containsExactly("KOLONNE");
    assertThat(l.filters()).contains("TIME_PERIOD", "MELDEINSTITUT");

    // a single varying dimension: time goes into the columns
    TableLayout one =
        TableLayout.resolve(
            List.of("FREQ", "CTY"), "TIME_PERIOD", Map.of(), NONE, Map.of("FREQ", 1, "CTY", 3));
    assertThat(one.rows()).containsExactly("CTY");
    assertThat(one.columns()).containsExactly("TIME_PERIOD");
  }

  @Test
  void parsesTheStatSyntax() {
    assertThat(TableLayout.assignments("FREQ=A+Q, REF_AREA ,X="))
        .containsEntry("FREQ", List.of("A", "Q"))
        .containsEntry("REF_AREA", List.of())
        .containsEntry("X", List.of());
    assertThat(TableLayout.ids(" ")).isNull();
  }
}
