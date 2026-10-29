package de.sdmxdw.microdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.sdmxdw.AbstractIntegrationTest;
import de.sdmxdw.data.observation.ObservationService;
import de.sdmxdw.data.observation.ObservationStage;
import de.sdmxdw.structure.codelist.Codelist;
import de.sdmxdw.structure.codelist.CodelistService;
import de.sdmxdw.structure.conceptscheme.ConceptScheme;
import de.sdmxdw.structure.conceptscheme.ConceptSchemeService;
import de.sdmxdw.structure.dataflow.Dataflow;
import de.sdmxdw.structure.dataflow.DataflowService;
import de.sdmxdw.structure.dsd.DataStructureDefinition;
import de.sdmxdw.structure.dsd.DataStructureDefinitionService;
import de.sdmxdw.structure.dsd.DataType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;

class MicrodataRecordServiceTest extends AbstractIntegrationTest {

  @Autowired private MicrodataFlowService microdataFlowService;

  @Autowired private MicrodataRecordService microdataRecordService;

  @Autowired private CodelistService codelistService;

  @Autowired private ConceptSchemeService conceptSchemeService;

  @Autowired private DataStructureDefinitionService dsdService;

  @Autowired private DataflowService dataflowService;

  @Autowired private ObservationService observationService;

  private MicrodataFlow setUpFlowWithIndustryField(String suffix) {
    MicrodataFlow flow =
        microdataFlowService.createFlow(
            "DEMO", "MDF_REC_" + suffix, "1.0.0", "Record test " + suffix, null);
    Codelist industryCodelist =
        codelistService.createCodelist("DEMO", "CL_REC_" + suffix, "1.0.0", "Industry");
    industryCodelist = codelistService.addCode(industryCodelist.id(), "SERVICES", "Services");
    microdataFlowService.addField(
        flow.id(),
        "INDUSTRY",
        "Branche",
        FieldDataType.STRING,
        industryCodelist.id(),
        true,
        FacetSlot.facet1);
    microdataFlowService.addField(
        flow.id(), "AMOUNT", "Betrag", FieldDataType.NUMBER, null, true, null);
    return flow;
  }

  private Dataflow setUpAggregationTargetDataflow(String suffix) {
    ConceptScheme concepts =
        conceptSchemeService.createScheme("DEMO", "CS_AGG_" + suffix, "1.0.0", "Agg concepts");
    concepts = conceptSchemeService.addConcept(concepts.id(), "INDUSTRY", "Branche");
    concepts = conceptSchemeService.addConcept(concepts.id(), "TIME_PERIOD", "Period");
    concepts = conceptSchemeService.addConcept(concepts.id(), "COUNT", "Count");
    Long industryConceptId = idByCode(concepts, "INDUSTRY");
    Long timeConceptId = idByCode(concepts, "TIME_PERIOD");
    Long countConceptId = idByCode(concepts, "COUNT");

    Codelist industryCodelist =
        codelistService.createCodelist("DEMO", "CL_AGG_" + suffix, "1.0.0", "Industry");
    industryCodelist = codelistService.addCode(industryCodelist.id(), "SERVICES", "Services");

    DataStructureDefinition dsd =
        dsdService.createDsd("DEMO", "DSD_AGG_" + suffix, "1.0.0", "Agg DSD");
    dsd =
        dsdService.addDimension(
            dsd.id(), industryConceptId, industryCodelist.id(), DataType.STRING);
    dsd = dsdService.addDimension(dsd.id(), timeConceptId, null, DataType.DATE);
    dsd = dsdService.addMeasure(dsd.id(), countConceptId, DataType.NUMBER);

    return dataflowService.createDataflow(
        "DEMO", "DF_AGG_" + suffix, "1.0.0", "Agg dataflow", dsd.id());
  }

  private static Long idByCode(ConceptScheme scheme, String code) {
    return scheme.concepts().stream()
        .filter(c -> c.code().equals(code))
        .findFirst()
        .orElseThrow()
        .id();
  }

  @Test
  @WithMockUser(roles = "DATENMANAGER")
  void ingestingNewVintageClosesThePreviousOneButKeepsHistory() {
    MicrodataFlow flow = setUpFlowWithIndustryField("A");

    MicrodataRecord first =
        microdataRecordService.ingestRecord(
            flow.id(),
            "LOAN-1",
            "2026-Q1",
            Map.of("INDUSTRY", "SERVICES", "AMOUNT", "1000"),
            "dm@x");
    MicrodataRecord second =
        microdataRecordService.ingestRecord(
            flow.id(),
            "LOAN-1",
            "2026-Q1",
            Map.of("INDUSTRY", "SERVICES", "AMOUNT", "2000"),
            "dm@x");

    MicrodataRecordService.PageResult current =
        microdataRecordService.findCurrentFiltered(
            flow.id(), MicrodataStage.ALPHA, null, null, null, null, null, null, 0, 10);
    assertThat(current.totalCount()).isEqualTo(1);
    assertThat(current.records().get(0).id()).isEqualTo(second.id());
    assertThat(current.records().get(0).facet1Value()).isEqualTo("SERVICES");

    var history =
        microdataRecordService.findHistory(flow.id(), "LOAN-1", "2026-Q1", MicrodataStage.ALPHA);
    assertThat(history).hasSize(2);
    assertThat(history.get(0).id()).isEqualTo(first.id());
    assertThat(history.get(0).validTo()).isNotNull();
    assertThat(history.get(1).validTo()).isNull();
  }

  @Test
  @WithMockUser(roles = "DATENMANAGER")
  void rejectsUnknownCodeAndMissingRequiredField() {
    MicrodataFlow flow = setUpFlowWithIndustryField("B");

    assertThatThrownBy(
            () ->
                microdataRecordService.ingestRecord(
                    flow.id(),
                    "LOAN-2",
                    "2026-Q1",
                    Map.of("INDUSTRY", "UNKNOWN_CODE", "AMOUNT", "1"),
                    "dm@x"))
        .isInstanceOf(IllegalArgumentException.class);

    assertThatThrownBy(
            () ->
                microdataRecordService.ingestRecord(
                    flow.id(), "LOAN-3", "2026-Q1", Map.of("INDUSTRY", "SERVICES"), "dm@x"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @WithMockUser(roles = "DATENMANAGER")
  void filtersPushDownIntoSqlByFacetAndFreeText() {
    MicrodataFlow flow = setUpFlowWithIndustryField("C");
    microdataRecordService.ingestRecord(
        flow.id(), "LOAN-4", "2026-Q1", Map.of("INDUSTRY", "SERVICES", "AMOUNT", "123456"), "dm@x");

    MicrodataRecordService.PageResult byFacet =
        microdataRecordService.findCurrentFiltered(
            flow.id(), MicrodataStage.ALPHA, null, "SERVICES", null, null, null, null, 0, 10);
    assertThat(byFacet.totalCount()).isEqualTo(1);

    MicrodataRecordService.PageResult byWrongFacet =
        microdataRecordService.findCurrentFiltered(
            flow.id(), MicrodataStage.ALPHA, null, "MANUFACTURING", null, null, null, null, 0, 10);
    assertThat(byWrongFacet.totalCount()).isZero();

    MicrodataRecordService.PageResult byFreeText =
        microdataRecordService.findCurrentFiltered(
            flow.id(), MicrodataStage.ALPHA, null, null, null, null, null, "123456", 0, 10);
    assertThat(byFreeText.totalCount()).isEqualTo(1);
  }

  @Test
  @WithMockUser(roles = "DATENMANAGER")
  void promotionCopiesCurrentAlphaToBeta() {
    MicrodataFlow flow = setUpFlowWithIndustryField("D");
    microdataRecordService.ingestRecord(
        flow.id(), "LOAN-5", "2026-Q1", Map.of("INDUSTRY", "SERVICES", "AMOUNT", "1"), "dm@x");

    MicrodataRecordService.PromotionResult result =
        microdataRecordService.promoteToBeta(flow.id(), "dm@x");
    assertThat(result.promoted()).isEqualTo(1);

    MicrodataRecordService.PageResult beta =
        microdataRecordService.findCurrentFiltered(
            flow.id(), MicrodataStage.BETA, null, null, null, null, null, null, 0, 10);
    assertThat(beta.totalCount()).isEqualTo(1);

    MicrodataRecordService.PromotionResult secondPromotion =
        microdataRecordService.promoteToBeta(flow.id(), "dm@x");
    assertThat(secondPromotion.promoted()).isZero();
    assertThat(secondPromotion.unchanged()).isEqualTo(1);
  }

  @Test
  @WithMockUser(roles = "DATENMANAGER")
  void aggregatesBetaRecordsIntoLinkedDataflowAsObservations() {
    MicrodataFlow flow = setUpFlowWithIndustryField("E");
    Dataflow target = setUpAggregationTargetDataflow("E");
    microdataFlowService.linkDataflow(flow.id(), target.id());

    microdataRecordService.ingestRecord(
        flow.id(), "LOAN-6", "2026-Q1", Map.of("INDUSTRY", "SERVICES", "AMOUNT", "1"), "dm@x");
    microdataRecordService.ingestRecord(
        flow.id(), "LOAN-7", "2026-Q1", Map.of("INDUSTRY", "SERVICES", "AMOUNT", "2"), "dm@x");
    microdataRecordService.promoteToBeta(flow.id(), "dm@x");

    MicrodataRecordService.AggregationResult result =
        microdataRecordService.aggregateAndPublish(flow.id(), "dm@x");
    assertThat(result.groupsWritten()).isEqualTo(1);

    var betaObservations = observationService.findCurrent(target.id(), ObservationStage.BETA);
    assertThat(betaObservations).hasSize(1);
    Map<Long, String> measureValues =
        observationService.parseMeasureValues(betaObservations.get(0).measureValuesJson());
    assertThat(measureValues.values()).containsExactly("2");
  }

  @Test
  @WithMockUser(roles = "DATENMANAGER")
  void aggregationFailsWithoutLinkedDataflow() {
    MicrodataFlow flow = setUpFlowWithIndustryField("F");

    assertThatThrownBy(() -> microdataRecordService.aggregateAndPublish(flow.id(), "dm@x"))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  @WithMockUser(roles = "DATENMANAGER")
  void supportsMultipleInstancesOfARepeatingFieldGroup() {
    MicrodataFlow flow = setUpFlowWithIndustryField("G");
    Codelist collateralCodelist =
        codelistService.createCodelist("DEMO", "CL_COLLATERAL_G", "1.0.0", "Collateral type");
    collateralCodelist = codelistService.addCode(collateralCodelist.id(), "MORTGAGE", "Mortgage");
    collateralCodelist = codelistService.addCode(collateralCodelist.id(), "GUARANTEE", "Guarantee");
    microdataFlowService.addGroupField(
        flow.id(),
        "COLLATERAL",
        "COLLATERAL_ITEM_TYPE",
        "Collateral type",
        FieldDataType.STRING,
        collateralCodelist.id(),
        true);
    microdataFlowService.addGroupField(
        flow.id(),
        "COLLATERAL",
        "COLLATERAL_ITEM_VALUE",
        "Collateral value",
        FieldDataType.NUMBER,
        null,
        true);

    microdataRecordService.ingestRecord(
        flow.id(),
        "LOAN-8",
        "2026-Q1",
        Map.of("INDUSTRY", "SERVICES", "AMOUNT", "1"),
        Map.of(
            "COLLATERAL",
            List.of(
                Map.of("COLLATERAL_ITEM_TYPE", "MORTGAGE", "COLLATERAL_ITEM_VALUE", "500000"),
                Map.of("COLLATERAL_ITEM_TYPE", "GUARANTEE", "COLLATERAL_ITEM_VALUE", "100000"))),
        "dm@x");

    MicrodataRecord record =
        microdataRecordService
            .findCurrentFiltered(
                flow.id(), MicrodataStage.ALPHA, null, null, null, null, null, null, 0, 10)
            .records()
            .get(0);
    MicrodataPayload payload = microdataRecordService.parsePayload(record.payloadJson());

    assertThat(payload.scalars()).containsEntry("INDUSTRY", "SERVICES");
    assertThat(payload.groups().get("COLLATERAL")).hasSize(2);
    assertThat(payload.groups().get("COLLATERAL").get(0))
        .containsEntry("COLLATERAL_ITEM_TYPE", "MORTGAGE");
    assertThat(payload.groups().get("COLLATERAL").get(1))
        .containsEntry("COLLATERAL_ITEM_TYPE", "GUARANTEE");
    assertThat(record.facet1Value()).isEqualTo("SERVICES");
  }

  @Test
  @WithMockUser(roles = "DATENMANAGER")
  void rejectsMissingRequiredFieldWithinAGroupInstance() {
    MicrodataFlow flow = setUpFlowWithIndustryField("H");
    microdataFlowService.addGroupField(
        flow.id(),
        "COLLATERAL",
        "COLLATERAL_ITEM_TYPE",
        "Collateral type",
        FieldDataType.STRING,
        null,
        true);

    assertThatThrownBy(
            () ->
                microdataRecordService.ingestRecord(
                    flow.id(),
                    "LOAN-9",
                    "2026-Q1",
                    Map.of("INDUSTRY", "SERVICES", "AMOUNT", "1"),
                    Map.of("COLLATERAL", List.of(Map.of())),
                    "dm@x"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @WithMockUser(roles = "DATENMANAGER")
  void referenceFieldLinksToAnotherFlowAndValidatesTheTargetExists() {
    MicrodataFlow counterparty =
        microdataFlowService.createFlow("DEMO", "MDF_CPTY_I", "1.0.0", "Counterparty", null);
    microdataFlowService.addField(
        counterparty.id(), "RATING", "Rating", FieldDataType.STRING, null, true, null);
    MicrodataFlow loan =
        microdataFlowService.createFlow("DEMO", "MDF_LOAN_I", "1.0.0", "Loan", null);
    microdataFlowService.addReferenceField(
        loan.id(), "COUNTERPARTY", "Gegenpartei", counterparty.id(), true, FacetSlot.facet1);

    assertThatThrownBy(
            () ->
                microdataRecordService.ingestRecord(
                    loan.id(), "LOAN-10", "2026-Q1", Map.of("COUNTERPARTY", "GP-1"), "dm@x"))
        .isInstanceOf(IllegalArgumentException.class);

    microdataRecordService.ingestRecord(
        counterparty.id(), "GP-1", "2026-Q1", Map.of("RATING", "AA"), "dm@x");
    MicrodataRecord loanRecord =
        microdataRecordService.ingestRecord(
            loan.id(), "LOAN-10", "2026-Q1", Map.of("COUNTERPARTY", "GP-1"), "dm@x");

    assertThat(loanRecord.facet1Value()).isEqualTo("GP-1");
    var resolved =
        microdataRecordService.findCurrentOne(
            counterparty.id(), "GP-1", "2026-Q1", MicrodataStage.ALPHA);
    assertThat(resolved).isPresent();
  }

  @Test
  @WithMockUser(roles = "DATENMANAGER")
  void addReferenceFieldRejectsUnknownTargetFlow() {
    MicrodataFlow flow =
        microdataFlowService.createFlow("DEMO", "MDF_REFTEST_I", "1.0.0", "Ref test", null);

    assertThatThrownBy(
            () ->
                microdataFlowService.addReferenceField(flow.id(), "X", "X", 999_999L, false, null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @WithMockUser(roles = "OEKONOM")
  void oekonomCannotIngestButCanReadBeta() {
    assertThatThrownBy(
            () -> microdataRecordService.ingestRecord(1L, "x", "2026-Q1", Map.of(), "oek@x"))
        .isInstanceOf(AccessDeniedException.class);
    assertThat(
            microdataRecordService.findCurrentFiltered(
                1L, MicrodataStage.BETA, null, null, null, null, null, null, 0, 10))
        .isNotNull();
  }

  @Test
  @WithMockUser(roles = "DATENMANAGER")
  void reportingPeriodFilterPushesDownIntoSql() {
    MicrodataFlow flow = setUpFlowWithIndustryField("I");
    microdataRecordService.ingestRecord(
        flow.id(), "LOAN-11", "2026-Q1", Map.of("INDUSTRY", "SERVICES", "AMOUNT", "1"), "dm@x");
    microdataRecordService.ingestRecord(
        flow.id(), "LOAN-12", "2026-Q2", Map.of("INDUSTRY", "SERVICES", "AMOUNT", "2"), "dm@x");

    MicrodataRecordService.PageResult q1 =
        microdataRecordService.findCurrentFiltered(
            flow.id(), MicrodataStage.ALPHA, "2026-Q1", null, null, null, null, null, 0, 10);
    assertThat(q1.totalCount()).isEqualTo(1);
    assertThat(q1.records().get(0).entityId()).isEqualTo("LOAN-11");

    MicrodataRecordService.PageResult both =
        microdataRecordService.findCurrentFiltered(
            flow.id(), MicrodataStage.ALPHA, null, null, null, null, null, null, 0, 10);
    assertThat(both.totalCount()).isEqualTo(2);

    assertThat(microdataRecordService.findDistinctReportingPeriods(MicrodataStage.ALPHA))
        .contains("2026-Q1", "2026-Q2");
  }
}
