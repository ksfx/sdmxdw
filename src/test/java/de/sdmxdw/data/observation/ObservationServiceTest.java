package de.sdmxdw.data.observation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.sdmxdw.AbstractIntegrationTest;
import de.sdmxdw.data.audit.AuditLogService;
import de.sdmxdw.structure.codelist.Codelist;
import de.sdmxdw.structure.codelist.CodelistService;
import de.sdmxdw.structure.conceptscheme.ConceptScheme;
import de.sdmxdw.structure.conceptscheme.ConceptSchemeService;
import de.sdmxdw.structure.dataflow.Dataflow;
import de.sdmxdw.structure.dataflow.DataflowService;
import de.sdmxdw.structure.dsd.DataStructureDefinition;
import de.sdmxdw.structure.dsd.DataStructureDefinitionService;
import de.sdmxdw.structure.dsd.DataType;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;

class ObservationServiceTest extends AbstractIntegrationTest {

  @Autowired private ObservationService observationService;

  @Autowired private DataflowService dataflowService;

  @Autowired private DataStructureDefinitionService dsdService;

  @Autowired private ConceptSchemeService conceptSchemeService;

  @Autowired private CodelistService codelistService;

  @Autowired private AuditLogService auditLogService;

  private Dataflow setUpDataflowWithFreqAndObsValue(String suffix) {
    ConceptScheme concepts =
        conceptSchemeService.createScheme("SDMXDW", "CS_OBS_" + suffix, "1.0.0", "Obs concepts");
    concepts = conceptSchemeService.addConcept(concepts.id(), "FREQ", "Frequency");
    concepts = conceptSchemeService.addConcept(concepts.id(), "TIME_PERIOD", "Time period");
    concepts = conceptSchemeService.addConcept(concepts.id(), "OBS_VALUE", "Observation value");
    Long freqConceptId = idByCode(concepts, "FREQ");
    Long timeConceptId = idByCode(concepts, "TIME_PERIOD");
    Long obsValueConceptId = idByCode(concepts, "OBS_VALUE");

    Codelist freqCodelist =
        codelistService.createCodelist("SDMXDW", "CL_FREQ_" + suffix, "1.0.0", "Frequency");
    freqCodelist = codelistService.addCode(freqCodelist.id(), "A", "Annual");

    DataStructureDefinition dsd =
        dsdService.createDsd("SDMXDW", "DSD_OBS_" + suffix, "1.0.0", "Obs DSD");
    dsd = dsdService.addDimension(dsd.id(), freqConceptId, freqCodelist.id(), DataType.STRING);
    dsd = dsdService.addDimension(dsd.id(), timeConceptId, null, DataType.DATE);
    dsd = dsdService.addMeasure(dsd.id(), obsValueConceptId, DataType.NUMBER);

    return dataflowService.createDataflow(
        "SDMXDW", "DF_OBS_" + suffix, "1.0.0", "Obs dataflow", dsd.id());
  }

  private static Long idByCode(ConceptScheme scheme, String code) {
    return scheme.concepts().stream()
        .filter(c -> c.code().equals(code))
        .findFirst()
        .orElseThrow()
        .id();
  }

  private Map<Long, String> dimensionValues(
      DataStructureDefinition dsd, String freqCode, String timePeriod) {
    Map<Long, String> values = new HashMap<>();
    dsd.dimensions()
        .forEach(
            d -> {
              if (d.dataType() == DataType.DATE) {
                values.put(d.id(), timePeriod);
              } else {
                values.put(d.id(), freqCode);
              }
            });
    return values;
  }

  private Map<Long, String> measureValues(DataStructureDefinition dsd, String value) {
    Map<Long, String> values = new HashMap<>();
    dsd.measures().forEach(m -> values.put(m.id(), value));
    return values;
  }

  @Test
  @WithMockUser(roles = "DATENMANAGER")
  void recordingNewVintageClosesThePreviousOneButKeepsHistory() {
    Dataflow dataflow = setUpDataflowWithFreqAndObsValue("A");
    DataStructureDefinition dsd = dsdService.findById(dataflow.dsdId());

    Observation first =
        observationService.recordObservation(
            dataflow.id(),
            dimensionValues(dsd, "A", "2024"),
            measureValues(dsd, "100"),
            "dm@sdmxdw.local");
    Observation second =
        observationService.recordObservation(
            dataflow.id(),
            dimensionValues(dsd, "A", "2024"),
            measureValues(dsd, "101"),
            "dm@sdmxdw.local");

    var current = observationService.findCurrent(dataflow.id(), ObservationStage.ALPHA);
    assertThat(current).hasSize(1);
    assertThat(current.get(0).id()).isEqualTo(second.id());

    var history = observationService.findHistory(first.seriesId(), "2024", ObservationStage.ALPHA);
    assertThat(history).hasSize(2);
    assertThat(history.get(0).validTo()).isNotNull();
    assertThat(history.get(1).validTo()).isNull();
  }

  @Test
  @WithMockUser(roles = "DATENMANAGER")
  void rejectsUnknownCode() {
    Dataflow dataflow = setUpDataflowWithFreqAndObsValue("B");
    DataStructureDefinition dsd = dsdService.findById(dataflow.dsdId());

    assertThatThrownBy(
            () ->
                observationService.recordObservation(
                    dataflow.id(),
                    dimensionValues(dsd, "UNKNOWN", "2024"),
                    measureValues(dsd, "1"),
                    "dm@x"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @WithMockUser(roles = "DATENMANAGER")
  void promotionCopiesCurrentAlphaToBetaAndWritesAuditLog() {
    Dataflow dataflow = setUpDataflowWithFreqAndObsValue("C");
    DataStructureDefinition dsd = dsdService.findById(dataflow.dsdId());
    observationService.recordObservation(
        dataflow.id(),
        dimensionValues(dsd, "A", "2024"),
        measureValues(dsd, "100"),
        "dm@sdmxdw.local");

    ObservationService.PromotionResult result =
        observationService.promoteToBeta(dataflow.id(), "dm@sdmxdw.local");

    assertThat(result.promoted()).isEqualTo(1);
    assertThat(observationService.findCurrent(dataflow.id(), ObservationStage.BETA)).hasSize(1);
    assertThat(auditLogService.findByDataflow(dataflow.id())).hasSize(1);
    assertThat(auditLogService.findByDataflow(dataflow.id()).get(0).action())
        .isEqualTo("PROMOTE_ALPHA_TO_BETA");

    ObservationService.PromotionResult secondPromotion =
        observationService.promoteToBeta(dataflow.id(), "dm@sdmxdw.local");
    assertThat(secondPromotion.promoted()).isZero();
    assertThat(secondPromotion.unchanged()).isEqualTo(1);
  }

  @Test
  @WithMockUser(roles = "OEKONOM")
  void oekonomCannotSeeAlphaOrRecordObservations() {
    assertThatThrownBy(() -> observationService.findCurrent(1L, ObservationStage.ALPHA))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> observationService.recordObservation(1L, Map.of(), Map.of(), "oek@x"))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  @WithMockUser(roles = "OEKONOM")
  void oekonomCanSeeBeta() {
    assertThat(observationService.findCurrent(1L, ObservationStage.BETA)).isNotNull();
  }
}
