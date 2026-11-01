package de.sdmxdw.microdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class FieldAttributesTest {

  @Test
  void keepsOrderBetweenLinesAndJson() {
    Map<String, String> parsed =
        FieldAttributes.parseLines("Feld-ID: RA-03\n\nXSD: RAH/rah_lim: komm\nEinführung: Paket 1");
    assertThat(parsed.keySet()).containsExactly("Feld-ID", "XSD", "Einführung");
    assertThat(parsed.get("XSD")).isEqualTo("RAH/rah_lim: komm");

    String json = FieldAttributes.toJson(parsed);
    assertThat(FieldAttributes.fromJson(json).keySet())
        .containsExactly("Feld-ID", "XSD", "Einführung");
    assertThat(FieldAttributes.toLines(FieldAttributes.fromJson(json)))
        .isEqualTo("Feld-ID: RA-03\nXSD: RAH/rah_lim: komm\nEinführung: Paket 1\n");
  }

  @Test
  void handlesEmptyAndInvalidInput() {
    assertThat(FieldAttributes.toJson(Map.of())).isNull();
    assertThat(FieldAttributes.fromJson(null)).isEmpty();
    assertThat(FieldAttributes.fromJson("not json")).isEmpty();
    assertThatThrownBy(() -> FieldAttributes.parseLines("without colon"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
