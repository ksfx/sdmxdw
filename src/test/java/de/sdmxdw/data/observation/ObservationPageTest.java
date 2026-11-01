package de.sdmxdw.data.observation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.sdmxdw.AbstractIntegrationTest;
import de.sdmxdw.structure.codelist.Codelist;
import de.sdmxdw.structure.codelist.CodelistService;
import de.sdmxdw.structure.conceptscheme.ConceptScheme;
import de.sdmxdw.structure.conceptscheme.ConceptSchemeService;
import de.sdmxdw.structure.dataflow.Dataflow;
import de.sdmxdw.structure.dataflow.DataflowService;
import de.sdmxdw.structure.dsd.DataStructureDefinition;
import de.sdmxdw.structure.dsd.DataStructureDefinitionService;
import de.sdmxdw.structure.dsd.DataType;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;

/** Filtered, paged listing behind the Datenmanager data page (issue #12). */
class ObservationPageTest extends AbstractIntegrationTest {

  @Autowired private ObservationService observationService;
  @Autowired private DataflowService dataflowService;
  @Autowired private DataStructureDefinitionService dsdService;
  @Autowired private ConceptSchemeService conceptSchemeService;
  @Autowired private CodelistService codelistService;

  /** Two key dimensions (COUNTRY, SECTOR) plus time; 2 x 3 series x 4 periods = 24 obs. */
  private Dataflow setUp(String suffix) {
    ConceptScheme cs =
        conceptSchemeService.createScheme("SDMXDW", "CS_PG_" + suffix, "1.0.0", "Page test");
    for (String c : List.of("COUNTRY", "SECTOR", "TIME_PERIOD", "OBS_VALUE")) {
      cs = conceptSchemeService.addConcept(cs.id(), c, c);
    }
    Codelist countries =
        codelistService.createCodelist("SDMXDW", "CL_PG_CTY_" + suffix, "1.0.0", "Country");
    countries = codelistService.addCode(countries.id(), "CH", "Switzerland");
    countries = codelistService.addCode(countries.id(), "DE", "Germany");
    Codelist sectors =
        codelistService.createCodelist("SDMXDW", "CL_PG_SEC_" + suffix, "1.0.0", "Sector");
    for (String s : List.of("A", "B", "C")) {
      sectors = codelistService.addCode(sectors.id(), s, "Sector " + s);
    }
    DataStructureDefinition dsd =
        dsdService.createDsd("SDMXDW", "DSD_PG_" + suffix, "1.0.0", "Page DSD");
    dsd = dsdService.addDimension(dsd.id(), id(cs, "COUNTRY"), countries.id(), DataType.STRING);
    dsd = dsdService.addDimension(dsd.id(), id(cs, "SECTOR"), sectors.id(), DataType.STRING);
    dsd = dsdService.addDimension(dsd.id(), id(cs, "TIME_PERIOD"), null, DataType.DATE);
    dsd = dsdService.addMeasure(dsd.id(), id(cs, "OBS_VALUE"), DataType.NUMBER);
    Dataflow df =
        dataflowService.createDataflow("SDMXDW", "DF_PG_" + suffix, "1.0.0", "Page", dsd.id());
    for (String country : List.of("CH", "DE")) {
      for (String sector : List.of("A", "B", "C")) {
        for (String period : List.of("2025-Q3", "2025-Q4", "2026-Q1", "2026-Q2")) {
          observationService.recordObservationByConceptCode(
              df.id(),
              Map.of("COUNTRY", country, "SECTOR", sector, "TIME_PERIOD", period),
              Map.of("OBS_VALUE", "1"),
              "test");
        }
      }
    }
    return df;
  }

  private static Long id(ConceptScheme cs, String code) {
    return cs.concepts().stream().filter(c -> c.code().equals(code)).findFirst().orElseThrow().id();
  }

  @Test
  @WithMockUser(roles = "DATENMANAGER")
  void filtersByKeyDimensionsAndPeriodInSqlAndPages() {
    Dataflow df = setUp("A");
    EnumSet<ObservationStage> alpha = EnumSet.of(ObservationStage.ALPHA);

    var all = observationService.findCurrentPage(df.id(), alpha, Map.of(), null, null, 0, 10);
    assertThat(all.total()).isEqualTo(24);
    assertThat(all.rows()).hasSize(10);
    assertThat(all.totalPages()).isEqualTo(3);
    var lastPage = observationService.findCurrentPage(df.id(), alpha, Map.of(), null, null, 2, 10);
    assertThat(lastPage.rows()).hasSize(4);

    var chSectorAB =
        observationService.findCurrentPage(
            df.id(), alpha, Map.of(1, List.of("CH"), 2, List.of("A", "B")), null, null, 0, 50);
    assertThat(chSectorAB.total()).isEqualTo(8);
    assertThat(chSectorAB.rows()).allSatisfy(r -> assertThat(r.seriesKey()).matches("CH\\.(A|B)"));

    var periodRange =
        observationService.findCurrentPage(
            df.id(), alpha, Map.of(2, List.of("C")), "2026-Q1", "2026-Q2", 0, 50);
    assertThat(periodRange.total()).isEqualTo(4);

    var beta =
        observationService.findCurrentPage(
            df.id(), EnumSet.of(ObservationStage.BETA), Map.of(), null, null, 0, 50);
    assertThat(beta.total()).isZero();
  }

  @Test
  @WithMockUser(roles = "DATENMANAGER")
  void seriesSummariesAreAggregatedFilteredAndPagedInSql() {
    Dataflow df = setUp("S");
    observationService.recordObservationByConceptCode(
        df.id(),
        Map.of("COUNTRY", "CH", "SECTOR", "A", "TIME_PERIOD", "2026-Q2"),
        Map.of("OBS_VALUE", "7"),
        "test");
    observationService.promoteToBeta(df.id(), "test");
    ObservationStage beta = ObservationStage.BETA;

    var all = observationService.findSeriesPage(df.id(), beta, Map.of(), null, 0, 4);
    assertThat(all.total()).isEqualTo(6);
    assertThat(all.totalPages()).isEqualTo(2);
    assertThat(all.rows())
        .extracting(r -> r.seriesKey())
        .containsExactly("CH.A", "CH.B", "CH.C", "DE.A");
    var chA = all.rows().getFirst();
    assertThat(chA.firstPeriod()).isEqualTo("2025-Q3");
    assertThat(chA.lastPeriod()).isEqualTo("2026-Q2");
    assertThat(chA.count()).isEqualTo(4);
    assertThat(chA.latestMeasureValuesJson()).contains("7");
    assertThat(observationService.findSeriesPage(df.id(), beta, Map.of(), null, 1, 4).rows())
        .hasSize(2);

    var deBC =
        observationService.findSeriesPage(
            df.id(), beta, Map.of(1, List.of("DE"), 2, List.of("B", "C")), null, 0, 50);
    assertThat(deBC.rows()).extracting(r -> r.seriesKey()).containsExactly("DE.B", "DE.C");

    // substring search on the series key; LIKE wildcards in the input are taken literally
    assertThat(observationService.findSeriesPage(df.id(), beta, Map.of(), "e.c", 0, 50).rows())
        .extracting(r -> r.seriesKey())
        .containsExactly("DE.C");
    assertThat(observationService.findSeriesPage(df.id(), beta, Map.of(), "%", 0, 50).total())
        .isZero();
  }

  @Test
  @WithMockUser(roles = "DATENMANAGER")
  void keyFacetsCountSeriesUnderTheOtherSelections() {
    Dataflow df = setUp("F");
    observationService.promoteToBeta(df.id(), "test");
    ObservationStage beta = ObservationStage.BETA;

    var none = observationService.findKeyFacets(df.id(), beta, 2, Map.of(), null);
    assertThat(none.get(1)).containsExactlyInAnyOrderEntriesOf(Map.of("CH", 3L, "DE", 3L));
    assertThat(none.get(2)).containsExactlyInAnyOrderEntriesOf(Map.of("A", 2L, "B", 2L, "C", 2L));

    // selecting CH narrows the sectors to CH series, but keeps DE visible as alternative
    var ch = observationService.findKeyFacets(df.id(), beta, 2, Map.of(1, List.of("CH")), null);
    assertThat(ch.get(1)).containsExactlyInAnyOrderEntriesOf(Map.of("CH", 3L, "DE", 3L));
    assertThat(ch.get(2)).containsExactlyInAnyOrderEntriesOf(Map.of("A", 1L, "B", 1L, "C", 1L));

    var chA =
        observationService.findKeyFacets(
            df.id(), beta, 2, Map.of(1, List.of("CH"), 2, List.of("A")), null);
    assertThat(chA.get(1)).containsExactlyInAnyOrderEntriesOf(Map.of("CH", 1L, "DE", 1L));

    // Alpha-only dataflows have no published series yet
    Dataflow alphaOnly = setUp("G");
    assertThat(observationService.findKeyFacets(alphaOnly.id(), beta, 2, Map.of(), null).get(1))
        .isEmpty();
  }

  @Test
  @WithMockUser(roles = "OEKONOM")
  void oekonomMayOnlyListBeta() {
    assertThatThrownBy(
            () ->
                observationService.findCurrentPage(
                    1L, EnumSet.of(ObservationStage.ALPHA), Map.of(), null, null, 0, 10))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(
            () ->
                observationService.findCurrentPage(
                    1L, EnumSet.allOf(ObservationStage.class), Map.of(), null, null, 0, 10))
        .isInstanceOf(AccessDeniedException.class);
    assertThat(
            observationService.findCurrentPage(
                1L, EnumSet.of(ObservationStage.BETA), Map.of(), null, null, 0, 10))
        .isNotNull();
  }

  @Autowired private de.sdmxdw.data.audit.AuditLogService auditLogService;

  @Test
  @WithMockUser(roles = "DATENMANAGER")
  void correctionCreatesNewAlphaVintageWithReasonThatTravelsToBeta() {
    Dataflow df = setUp("C");
    observationService.promoteToBeta(df.id(), "test");
    var row =
        observationService
            .findCurrentPage(
                df.id(),
                EnumSet.of(ObservationStage.BETA),
                Map.of(1, List.of("CH"), 2, List.of("A")),
                "2026-Q2",
                "2026-Q2",
                0,
                1)
            .rows()
            .get(0);
    Long measureId =
        observationService.parseMeasureValues(row.measureValuesJson()).keySet().iterator().next();

    observationService.correctObservation(
        df.id(), row.seriesId(), "2026-Q2", Map.of(measureId, "42"), " revised by bank ", "dm@x");

    Observation alpha =
        observationService
            .findCurrentOne(df.id(), row.seriesId(), "2026-Q2", ObservationStage.ALPHA)
            .orElseThrow();
    assertThat(observationService.parseMeasureValues(alpha.measureValuesJson()).get(measureId))
        .isEqualTo("42");
    assertThat(alpha.comment()).isEqualTo("revised by bank");
    assertThat(observationService.findHistory(row.seriesId(), "2026-Q2", ObservationStage.ALPHA))
        .hasSize(2);
    Observation betaBefore =
        observationService
            .findCurrentOne(df.id(), row.seriesId(), "2026-Q2", ObservationStage.BETA)
            .orElseThrow();
    assertThat(observationService.parseMeasureValues(betaBefore.measureValuesJson()).get(measureId))
        .isEqualTo("1");

    observationService.promoteToBeta(df.id(), "test");
    Observation betaAfter =
        observationService
            .findCurrentOne(df.id(), row.seriesId(), "2026-Q2", ObservationStage.BETA)
            .orElseThrow();
    assertThat(betaAfter.comment()).isEqualTo("revised by bank");
    assertThat(auditLogService.findByDataflow(df.id()))
        .anySatisfy(
            e -> {
              assertThat(e.action()).isEqualTo("CORRECT_OBSERVATION");
              assertThat(e.details()).contains("CH.A 2026-Q2").contains("revised by bank");
            });
  }

  @Test
  @WithMockUser(roles = "OEKONOM")
  void oekonomCannotCorrect() {
    assertThatThrownBy(
            () -> observationService.correctObservation(1L, 1L, "2026-Q2", Map.of(), null, "x"))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  @WithMockUser(roles = "DATENMANAGER")
  void redeliveringAnUnchangedValueDoesNotCreateANewVintage() {
    Dataflow df = setUp("D");
    Map<String, String> dims = Map.of("COUNTRY", "CH", "SECTOR", "A", "TIME_PERIOD", "2026-Q2");
    Observation first =
        observationService.recordObservationByConceptCode(
            df.id(), dims, Map.of("OBS_VALUE", "1"), "import");
    Observation again =
        observationService.recordObservationByConceptCode(
            df.id(), dims, Map.of("OBS_VALUE", "1"), "import");
    assertThat(again.id()).isEqualTo(first.id());
    assertThat(observationService.findHistory(first.seriesId(), "2026-Q2", ObservationStage.ALPHA))
        .hasSize(1);

    observationService.recordObservationByConceptCode(
        df.id(), dims, Map.of("OBS_VALUE", "2"), "import");
    assertThat(observationService.findHistory(first.seriesId(), "2026-Q2", ObservationStage.ALPHA))
        .hasSize(2);
  }
}
